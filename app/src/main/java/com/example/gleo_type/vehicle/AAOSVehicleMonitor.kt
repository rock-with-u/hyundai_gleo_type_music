
package com.example.gleo_type.vehicle

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.Proxy

class AAOSVehicleMonitor(
    private val context: Context,
    private val onEvent: (VehicleEventDto) -> Unit
) {
    companion object {
        private const val TAG = "AAOSVehicle"

        private const val SPEED = 291504647
        private const val SPEED_DISPLAY = 291504648
        private const val ACCELERATOR = 291504911
        private const val BRAKE_PERCENT = 291504912

        private val names = mapOf(
            SPEED to "PERF_VEHICLE_SPEED",
            SPEED_DISPLAY to "PERF_VEHICLE_SPEED_DISPLAY",
            ACCELERATOR to
                    "ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE",
            BRAKE_PERCENT to
                    "BRAKE_PEDAL_COMPRESSION_PERCENTAGE"
        )
    }

    private val thread = HandlerThread(
        "AAOSVehicleWorker"
    ).apply {
        start()
    }

    private val worker = Handler(thread.looper)

    @Volatile
    private var active = false

    private var car: Any? = null
    private var manager: Any? = null
    private var listener: Any? = null

    private val lastValues = mutableMapOf<String, Any?>()

    fun startMonitoring() {
        if (active) {
            return
        }

        active = true

        worker.post {
            connectAndSubscribe()
        }
    }

    private fun connectAndSubscribe() {
        try {
            // Android Automotive Car API
            val carClass = Class.forName("android.car.Car")

            val createCar = carClass.getMethod(
                "createCar",
                Context::class.java
            )

            car = createCar.invoke(null, context)

            if (car == null) {
                Log.e(TAG, "Car 객체 생성 실패")
                return
            }

            val getCarManager = carClass.getMethod(
                "getCarManager",
                String::class.java
            )

            manager = getCarManager.invoke(
                car,
                "property"
            )

            val propertyManager = manager

            if (propertyManager == null) {
                Log.e(TAG, "CarPropertyManager 연결 실패")
                return
            }

            Log.i(TAG, "[AAOS] CarPropertyManager 연결 성공")

            inspectSupportedProperties(propertyManager)
            subscribeProperties(propertyManager)

        } catch (e: Exception) {
            Log.e(
                TAG,
                "[AAOS] 연결 실패: ${e.message}",
                e
            )
        }
    }

    private fun inspectSupportedProperties(
        propertyManager: Any
    ) {
        try {
            val getPropertyList =
                propertyManager.javaClass.getMethod(
                    "getPropertyList"
                )

            val configs =
                getPropertyList.invoke(propertyManager)
                        as? List<*> ?: emptyList<Any>()

            Log.i(
                TAG,
                "[AAOS] 접근 가능한 속성 ${configs.size}개"
            )

            for (config in configs) {
                if (config == null) {
                    continue
                }

                val getPropertyId =
                    config.javaClass.getMethod("getPropertyId")

                val id = getPropertyId.invoke(config) as Int

                val name = names[id] ?: "PROPERTY_$id"

                Log.d(
                    TAG,
                    "[속성목록] $name ($id)"
                )
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "[AAOS] 속성 목록 조회 실패",
                e
            )
        }
    }

    private fun subscribeProperties(
        propertyManager: Any
    ) {
        try {
            val callbackClass = Class.forName(
                "android.car.hardware.property." +
                        "CarPropertyManager" +
                        "\$CarPropertyEventCallback"
            )

            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->

                when (method.name) {
                    "onChangeEvent" -> {
                        val event = args?.firstOrNull()

                        if (event != null) {
                            handleChange(event)
                        }

                        null
                    }

                    "onErrorEvent" -> {
                        Log.w(
                            TAG,
                            "[속성오류] ${args?.contentToString()}"
                        )
                        null
                    }

                    "toString" -> "AAOSVehicleCallback"

                    "hashCode" ->
                        System.identityHashCode(proxy)

                    "equals" ->
                        proxy === args?.firstOrNull()

                    else -> null
                }
            }

            listener = callback

            val register = propertyManager.javaClass.getMethod(
                "registerCallback",
                callbackClass,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )

            for ((id, name) in names) {
                if (!active) {
                    break
                }

                try {
                    // 초기에는 부하를 줄이기 위해 1Hz 요청
                    val result = register.invoke(
                        propertyManager,
                        callback,
                        id,
                        1.0f
                    )

                    if (result == true) {
                        Log.i(TAG, "[구독성공] $name")
                    } else {
                        Log.w(
                            TAG,
                            "[구독불가] $name : 미지원 또는 권한 없음"
                        )
                    }

                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "[구독실패] $name : ${e.cause ?: e}"
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "[AAOS] 구독 설정 실패", e)
        }
    }

    private fun handleChange(event: Any) {
        if (!active) {
            return
        }

        try {
            val eventClass = event.javaClass

            val propertyId =
                eventClass.getMethod("getPropertyId")
                    .invoke(event) as Int

            val areaId =
                eventClass.getMethod("getAreaId")
                    .invoke(event) as Int

            val value =
                eventClass.getMethod("getValue")
                    .invoke(event)

            val name = names[propertyId] ?: return
            val key = "$propertyId:$areaId"

            val normalizedValue = when (value) {
                is Float -> {
                    if (value == 0f) 0.0 else value.toDouble()
                }
                is Double -> {
                    if (value == 0.0) 0.0 else value
                }
                else -> value
            }

            synchronized(lastValues) {
                if (
                    lastValues.containsKey(key) &&
                    lastValues[key] == normalizedValue
                ) {
                    return
                }

                lastValues[key] = normalizedValue
            }

            val dto = VehicleEventDto(
                domain = "AAOS",
                apiName = name,
                eventName = "onChangeEvent",
                eventType = "REALTIME",
                values = listOf(
                    VehicleValueConverter.convert(areaId),
                    VehicleValueConverter.convert(
                        normalizedValue
                    )
                )
            )

            Log.i(TAG, "[값변경] $name = $normalizedValue")
            Log.i(TAG, "[DTO전달] ${dto.toJson()}")

            onEvent(dto)

        } catch (e: Exception) {
            Log.e(TAG, "[AAOS] 이벤트 처리 실패", e)
        }
    }

    fun stopMonitoring() {
        active = false

        worker.post {
            try {
                val propertyManager = manager
                val callback = listener

                if (propertyManager != null && callback != null) {
                    val callbackClass = Class.forName(
                        "android.car.hardware.property." +
                                "CarPropertyManager" +
                                "\$CarPropertyEventCallback"
                    )

                    val unregister =
                        propertyManager.javaClass.getMethod(
                            "unregisterCallback",
                            callbackClass
                        )

                    unregister.invoke(
                        propertyManager,
                        callback
                    )
                }

                listener = null

                synchronized(lastValues) {
                    lastValues.clear()
                }

                Log.i(TAG, "[AAOS] 구독 해제 완료")

            } catch (e: Exception) {
                Log.e(TAG, "[AAOS] 구독 해제 실패", e)
            }
        }
    }

    fun release() {
        active = false

        worker.post {
            try {
                val propertyManager = manager
                val callback = listener

                if (propertyManager != null && callback != null) {
                    val callbackClass = Class.forName(
                        "android.car.hardware.property." +
                                "CarPropertyManager" +
                                "\$CarPropertyEventCallback"
                    )

                    propertyManager.javaClass.getMethod(
                        "unregisterCallback",
                        callbackClass
                    ).invoke(propertyManager, callback)
                }

                car?.javaClass?.getMethod("disconnect")
                    ?.invoke(car)

                listener = null
                manager = null
                car = null

            } catch (e: Exception) {
                Log.e(TAG, "[AAOS] 종료 오류", e)
            } finally {
                thread.quitSafely()
            }
        }
    }
}
