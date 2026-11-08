
package com.example.gleo_type.vehicle

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pleos SDK 공개 registerXxx(listener) 메서드 자동 탐색기.
 * area / 다중 인자가 필요한 API는 안전하게 SKIP하며 진단 로그를 남긴다.
 * 실제 차량에 적용할 때 area별 typed listener로 확장한다.
 */
class VehicleMonitor(context: Context, private val sender: VehicleEventSender) {
    private val vehicle = Vehicle(context.applicationContext)
    private val areaSubscriptions = AreaEventSubscriptions(vehicle, sender)
    private data class Subscription(val owner: Any, val unregister: Method, val listener: Any, val key: String)
    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    @Volatile private var started = false

    fun start() {
        if (started) return
        vehicle.initialize()
        started = true
        Log.i("GleoVehicle", "[INIT] Vehicle.initialize complete")
        val domains = listOf(
            "Brake", "CarInfo", "Display", "Door", "DrivingMode", "EvBattery",
            "Fuel", "HVAC", "Light", "Odometer", "Safety", "Seat",
            "SideMirror", "SteeringWheel", "Tire", "Toll", "TurnSignal", "Window", "Wiper"
        )
        for (domain in domains) {
            try {
                val getter = vehicle.javaClass.methods.firstOrNull { it.name == "get$domain" && it.parameterCount == 0 }
                if (getter == null) {
                    Log.w("GleoVehicle", "[NO_DOMAIN] $domain")
                    continue
                }
                val service = getter.invoke(vehicle) ?: continue
                inspectAndSubscribe(domain, service)
            } catch (e: Exception) {
                Log.e("GleoVehicle", "[DOMAIN_FAILED] $domain : ${e.message}", e)
            }
        }
        Log.i("GleoVehicle", "[SUMMARY] simple registered=${subscriptions.size}")
        areaSubscriptions.start()
    }

    private fun inspectAndSubscribe(domain: String, service: Any) {
        val methods = service.javaClass.methods
            .filter { it.name.startsWith("register") && !it.name.contains('$') }
            .distinctBy { it.name + it.parameterTypes.joinToString { type -> type.name } }

        for (method in methods) {
            val key = "$domain.${method.name}"
            if (method.parameterCount != 1 || !method.parameterTypes[0].isInterface) {
                Log.i("GleoVehicle", "[SKIP_AREA_OR_SIGNATURE] $key args=${method.parameterTypes.joinToString { it.simpleName }}")
                continue
            }
            val listenerType = method.parameterTypes[0]
            val unregisterName = "unregister" + method.name.removePrefix("register")
            val unregister = service.javaClass.methods.firstOrNull { it.name == unregisterName && it.parameterTypes.contentEquals(method.parameterTypes) }
            if (unregister == null) {
                Log.w("GleoVehicle", "[SKIP_NO_UNREGISTER] $key")
                continue
            }
            try {
                val listener = Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType),
                    InvocationHandler { proxy, called, args ->
                        if (called.declaringClass == Any::class.java) {
                            when (called.name) {
                                "toString" -> "MonitorListener($key)"
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.getOrNull(0)
                                else -> null
                            }
                        } else if (called.name == "onFailed") {
                            Log.e("GleoVehicle", "[CALLBACK_FAILED] $key : ${args?.joinToString()}")
                            null
                        } else {
                            val value = args?.joinToString(separator = ",") { it?.toString() ?: "null" }
                            sender.emit(VehicleEvent(domain = domain, eventType = called.name, value = value))
                            Log.i("GleoVehicle", "[CALLBACK] $key.${called.name} value=$value")
                            null
                        }
                    }
                )
                method.invoke(service, listener)
                subscriptions.add(Subscription(service, unregister, listener, key))
                Log.i("GleoVehicle", "[REGISTERED] $key")
            } catch (e: Exception) {
                Log.e("GleoVehicle", "[REGISTER_FAILED] $key : ${e.cause ?: e}")
            }
        }
    }

    fun stop() {
        if (!started) return
        areaSubscriptions.stop()
        for (subscription in subscriptions.reversed()) {
            try {
                subscription.unregister.invoke(subscription.owner, subscription.listener)
                Log.i("GleoVehicle", "[UNREGISTERED] ${subscription.key}")
            } catch (e: Exception) {
                Log.e("GleoVehicle", "[UNREGISTER_FAILED] ${subscription.key} : ${e.cause ?: e}")
            }
        }
        subscriptions.clear()
        vehicle.release()
        started = false
        Log.i("GleoVehicle", "[RELEASE] Vehicle.release complete")
    }
}
