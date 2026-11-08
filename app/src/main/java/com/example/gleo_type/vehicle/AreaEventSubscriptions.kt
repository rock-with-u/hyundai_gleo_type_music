package com.example.gleo_type.vehicle

import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * registerXxx(area, listener) 전용 확장 구독기.
 * SDK가 공개한 Area 객체/상수로만 구독을 시도한다.
 * 테스트 용도: 실차 적용 전 필요한 권한 및 capability 검증 필수.
 */
class AreaEventSubscriptions(
    private val vehicle: Vehicle,
    private val sender: VehicleEventSender
) {
    companion object {
        private const val TAG = "GleoArea"
    }

    private data class Registration(
        val service: Any,
        val unregister: Method,
        val args: Array<Any>,
        val name: String
    )

    private val registrations = CopyOnWriteArrayList<Registration>()
    private var active = false

    fun start() {
        if (active) return
        active = true

        val domains = listOf(
            "Door", "Wiper", "Window", "HVAC", "Seat", "Tire",
            "SideMirror", "Safety", "Light"
        )

        for (domain in domains) {
            val getter = vehicle.javaClass.methods.firstOrNull {
                it.name == "get$domain" && it.parameterCount == 0
            }

            if (getter == null) {
                Log.w(TAG, "[NO_DOMAIN] $domain")
                continue
            }

            val service = try {
                getter.invoke(vehicle)
            } catch (e: Exception) {
                Log.e(TAG, "[DOMAIN_FAILED] $domain", e)
                null
            } ?: continue

            subscribeDomain(domain, service)
        }

        Log.i(TAG, "[SUMMARY] area registrations=${registrations.size}")
    }

    private fun subscribeDomain(domain: String, service: Any) {
        val methods = service.javaClass.methods
            .filter {
                it.name.startsWith("register") &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0].simpleName.endsWith("Area") &&
                        it.parameterTypes[1].isInterface
            }
            .distinctBy {
                it.name + it.parameterTypes.joinToString { p -> p.name }
            }

        for (method in methods) {
            val areaType = method.parameterTypes[0]
            val listenerType = method.parameterTypes[1]
            val key = "$domain.${method.name}"

            val unregisterName =
                "unregister" + method.name.removePrefix("register")

            val unregister = service.javaClass.methods.firstOrNull {
                it.name == unregisterName &&
                        (
                                it.parameterTypes.contentEquals(method.parameterTypes) ||
                                        (
                                                it.parameterCount == 1 &&
                                                        it.parameterTypes[0] == listenerType
                                                )
                                )
            }

            if (unregister == null) {
                Log.w(TAG, "[NO_UNREGISTER] $key")
                continue
            }

            val areas = findAreas(areaType)

            if (areas.isEmpty()) {
                Log.w(
                    TAG,
                    "[AREA_NOT_RESOLVED] $key type=${areaType.name} " +
                            "constructors=${
                                areaType.declaredConstructors.joinToString { c ->
                                    c.parameterTypes.joinToString { p ->
                                        p.simpleName
                                    }
                                }
                            }"
                )
                continue
            }

            for ((areaName, area) in areas) {
                val listener = Proxy.newProxyInstance(
                    listenerType.classLoader,
                    arrayOf(listenerType)
                ) { proxy, called, args ->
                    when {
                        called.name == "toString" &&
                                called.parameterCount == 0 -> {
                            "AreaListener($key,$areaName)"
                        }

                        called.name == "hashCode" &&
                                called.parameterCount == 0 -> {
                            System.identityHashCode(proxy)
                        }

                        called.name == "equals" &&
                                called.parameterCount == 1 -> {
                            proxy === args?.getOrNull(0)
                        }

                        called.name == "onFailed" -> {
                            Log.e(
                                TAG,
                                "[CALLBACK_FAILED] $key " +
                                        "area=$areaName " +
                                        "error=${args?.contentToString()}"
                            )
                            null
                        }

                        else -> {
                            val value = args?.joinToString(",") {
                                it?.toString() ?: "null"
                            }

                            Log.i(
                                TAG,
                                "[CALLBACK] $key " +
                                        "area=$areaName " +
                                        "method=${called.name} " +
                                        "value=$value"
                            )

                            sender.emit(
                                VehicleEvent(
                                    domain = domain,
                                    eventType = called.name,
                                    area = areaName,
                                    value = value
                                )
                            )
                            null
                        }
                    }
                }

                try {
                    method.invoke(service, area, listener)

                    val undoArgs: Array<Any> =
                        if (unregister.parameterCount == 2) {
                            arrayOf(area, listener)
                        } else {
                            arrayOf(listener)
                        }

                    registrations.add(
                        Registration(
                            service,
                            unregister,
                            undoArgs,
                            "$key/$areaName"
                        )
                    )

                    Log.i(
                        TAG,
                        "[REGISTERED] $key area=$areaName"
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "[REGISTER_FAILED] $key " +
                                "area=$areaName : ${e.cause ?: e}"
                    )
                }
            }
        }
    }

    /**
     * Pleos SDK Area 생성자는
     * DoorArea(DoorValue[]),
     * WindowArea(WindowValue[]) 등의 배열 인자를 받는다.
     *
     * 배열 자체가 아닌 componentType을 이용해
     * 실제 enum 값을 찾는다.
     */
    private fun findAreas(
        areaType: Class<*>
    ): List<Pair<String, Any>> {

        val found = linkedMapOf<String, Any>()

        // SDK가 제공하는 Area 인스턴스가 있으면 우선 사용한다.
        for (field in areaType.fields) {
            try {
                if (
                    Modifier.isStatic(field.modifiers) &&
                    areaType.isAssignableFrom(field.type)
                ) {
                    val value = field.get(null)

                    if (value != null) {
                        found[field.name] = value
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "[AREA_FIELD_FAILED] " +
                            "${areaType.simpleName}.${field.name}: $e"
                )
            }
        }

        if (found.isNotEmpty()) {
            return found.entries.map { it.key to it.value }
        }

        val constructor =
            areaType.constructors.firstOrNull {
                it.parameterCount == 1
            } ?: areaType.declaredConstructors.firstOrNull {
                it.parameterCount == 1
            }

        if (constructor == null) {
            Log.w(
                TAG,
                "[AREA_NO_CONSTRUCTOR] ${areaType.name}"
            )
            return emptyList()
        }

        val parameterType = constructor.parameterTypes[0]

        // 핵심 수정: 배열의 실제 원소 타입을 가져온다.
        val valueType = if (parameterType.isArray) {
            parameterType.componentType
        } else {
            parameterType
        }

        val options = linkedMapOf<String, Any>()

        if (valueType.isEnum) {
            for (candidate in valueType.enumConstants.orEmpty()) {
                val enumValue = candidate as Enum<*>

                if (enumValue.name != "UNKNOWN") {
                    options[enumValue.name] = candidate
                }
            }
        }

        for (field in valueType.fields) {
            try {
                if (
                    Modifier.isStatic(field.modifiers) &&
                    valueType.isAssignableFrom(field.type) &&
                    field.name != "UNKNOWN"
                ) {
                    val value = field.get(null)

                    if (value != null) {
                        options.putIfAbsent(field.name, value)
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (options.isEmpty()) {
            Log.w(
                TAG,
                "[AREA_VALUE_EMPTY] ${areaType.simpleName} " +
                        "parameterType=${parameterType.name} " +
                        "valueType=${valueType.name} " +
                        "enum=${valueType.isEnum}"
            )
        }

        for ((name, value) in options) {
            try {
                constructor.isAccessible = true

                // 배열 생성자에는 실제 배열 객체를 전달한다.
                val areaArgument: Any =
                    if (parameterType.isArray) {
                        val values =
                            java.lang.reflect.Array.newInstance(
                                valueType,
                                1
                            )

                        java.lang.reflect.Array.set(
                            values,
                            0,
                            value
                        )

                        values
                    } else {
                        value
                    }

                val area = constructor.newInstance(areaArgument)

                found[name] = area

                Log.i(
                    TAG,
                    "[AREA_RESOLVED] " +
                            "${areaType.simpleName}.$name"
                )
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "[AREA_CREATE_FAILED] " +
                            "${areaType.simpleName}.$name: " +
                            "${e.cause ?: e}"
                )
            }
        }

        return found.entries.map { it.key to it.value }
    }

    fun stop() {
        for (registration in registrations.reversed()) {
            try {
                registration.unregister.invoke(
                    registration.service,
                    *registration.args
                )

                Log.i(
                    TAG,
                    "[UNREGISTERED] ${registration.name}"
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "[UNREGISTER_FAILED] " +
                            "${registration.name} : " +
                            "${e.cause ?: e}"
                )
            }
        }

        registrations.clear()
        active = false
    }
}
