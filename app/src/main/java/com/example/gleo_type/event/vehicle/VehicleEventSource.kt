package com.example.gleo_type.event.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.util.Log
import com.example.gleo_type.event.core.ChangeThrottle
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.send.EventPublisher

/**
 * [Pleos Vehicle SDK 콜백 / AAOS 속도] → VehicleEventMapper → VEHICLE 이벤트.
 *
 * 차량 속도는 Pleos Vehicle SDK에 리스너가 없어 AAOS PERF_VEHICLE_SPEED를
 * [onAaosSpeed]로 전달받는다.
 *
 * SDK 리스너는 등록만 담당하고, 값 처리는 on* 함수가 한다(단위 테스트에서 직접 호출).
 */
class VehicleEventSource(
    private val vehicleProvider: () -> Vehicle,
    private val eventFactory: EventFactory,
    private val musicState: MusicStateHolder,
    private val publisher: EventPublisher,
    private val speedThrottle: ChangeThrottle = ChangeThrottle(minDelta = 1.0, minIntervalMs = 1_000),
    private val steeringThrottle: ChangeThrottle = ChangeThrottle(minDelta = 5.0, minIntervalMs = 500)
) {
    companion object {
        private const val TAG = "GleoVehicleEvent"
    }

    private var vehicle: Vehicle? = null
    private val unregisters = mutableListOf<() -> Unit>()
    private val doorLocks = DoorLockAggregator(VehicleEventMapper.SUPPORTED_DOORS)
    private val deduplicator = VehicleStateDeduplicator()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        val sdk = vehicleProvider()
        sdk.initialize()
        vehicle = sdk
        started = true

        registerSimple(sdk)
        registerAreas(sdk)
        Log.i(TAG, "[STARTED] registrations=${unregisters.size}")
    }

    fun stop() {
        if (!started) return
        started = false
        unregisters.asReversed().forEach { unregister ->
            runCatching(unregister).onFailure { Log.w(TAG, "[UNREGISTER_FAILED]", it) }
        }
        unregisters.clear()
        runCatching { vehicle?.release() }
        vehicle = null
        speedThrottle.reset()
        steeringThrottle.reset()
        doorLocks.reset()
        deduplicator.reset()
        Log.i(TAG, "[STOPPED]")
    }

    // ==========================================
    // 값 처리 (SDK 콜백 → 이벤트)
    // ==========================================

    /** AAOS PERF_VEHICLE_SPEED (m/s) */
    fun onAaosSpeed(metersPerSecond: Float?) {
        val data = VehicleEventMapper.speedFromMetersPerSecond(metersPerSecond) ?: return
        val kmh = data.speedKmh?.toDoubleOrNull() ?: return
        if (speedThrottle.shouldEmit(kmh)) emit(data)
    }

    fun onSteeringAngle(angle: Float?) {
        val data = VehicleEventMapper.steeringAngle(angle) ?: return
        if (steeringThrottle.shouldEmit(angle!!.toDouble())) emit(data)
    }

    fun onCruiseControlState(state: CruiseControl?) = emit(VehicleEventMapper.cruiseControl(state))

    fun onCruiseTargetSpeed(speed: Float?) {
        VehicleEventMapper.cruiseTargetSpeed(speed)?.let(::emit)
    }

    fun onGear(gear: VehicleGear?) {
        VehicleEventMapper.gear(gear)?.let(::emit)
    }

    fun onBrake(pressed: Boolean?) {
        VehicleEventMapper.brake(pressed)?.let(::emit)
    }

    fun onDoorPosition(door: DoorValue, position: Int?) {
        VehicleEventMapper.doorPosition(door, position)?.let(::emit)
    }

    fun onDoorLock(door: DoorValue, locked: Boolean?) {
        doorLocks.update(door, locked)?.let { emit(VehicleEventMapper.doorLock(it)) }
    }

    /** 차량이 해당 도어의 잠금 상태를 지원하지 않을 때 (onFailed) */
    fun onDoorLockUnsupported(door: DoorValue) {
        doorLocks.exclude(door)?.let { emit(VehicleEventMapper.doorLock(it)) }
    }

    fun onWindowPosition(window: WindowValue, position: Int?) {
        VehicleEventMapper.windowPosition(window, position)?.let(::emit)
    }

    fun onWiper(window: WindowValue, state: WiperState?) {
        VehicleEventMapper.wiper(window, state)?.let(::emit)
    }

    private fun emit(data: VehicleData) {
        if (!deduplicator.isNew(data)) return
        val payload = VehiclePayload(music = musicState.currentSummary(), vehicle = data)
        publisher.publish(eventFactory.create(payload))
    }

    // ==========================================
    // area 없는 리스너 등록
    // ==========================================

    private fun registerSimple(sdk: Vehicle) {
        register("SteeringWheelAngle") {
            val listener = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = onSteeringAngle(angle)
                override fun onFailed(e: Exception) = logFailure("SteeringWheelAngle", e)
            }
            sdk.getSteeringWheel().registerSteeringWheelAngle(listener)
            return@register { sdk.getSteeringWheel().unregisterSteeringWheelAngle(listener) }
        }

        register("CruiseControlState") {
            val listener = object : CruiseControlStateListener {
                override fun onCruiseControlStateUpdated(state: CruiseControl?) = onCruiseControlState(state)
                override fun onFailed(e: Exception) = logFailure("CruiseControlState", e)
            }
            sdk.getDrivingMode().registerCruiseControlState(listener)
            return@register { sdk.getDrivingMode().unregisterCruiseControlState(listener) }
        }

        register("CruiseControlTargetSpeed") {
            val listener = object : CruiseControlTargetSpeedListener {
                override fun onCruiseControlTargetSpeedUpdated(speed: Float?) = onCruiseTargetSpeed(speed)
                override fun onFailed(e: Exception) = logFailure("CruiseControlTargetSpeed", e)
            }
            sdk.getDrivingMode().registerCruiseControlTargetSpeed(listener)
            return@register { sdk.getDrivingMode().unregisterCruiseControlTargetSpeed(listener) }
        }

        register("GearSelection") {
            val listener = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = onGear(gear)
                override fun onFailed(e: Exception) = logFailure("GearSelection", e)
            }
            sdk.getGear().registerGearSelection(listener)
            return@register { sdk.getGear().unregisterGearSelection(listener) }
        }

        register("BrakePedalStatus") {
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = onBrake(status)
                override fun onFailed(e: Exception) = logFailure("BrakePedalStatus", e)
            }
            sdk.getBrake().registerBrakePedalStatus(listener)
            return@register { sdk.getBrake().unregisterBrakePedalStatus(listener) }
        }
    }

    // ==========================================
    // area별 리스너 등록 (도어 / 창문 / 와이퍼)
    // ==========================================

    private fun registerAreas(sdk: Vehicle) {
        for (door in VehicleEventMapper.SUPPORTED_DOORS) {
            val area = DoorArea(door)

            register("DoorPosition.$door") {
                val listener = object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) = onDoorPosition(door, pos)
                    override fun onFailed(e: Exception) = logFailure("DoorPosition.$door", e)
                }
                sdk.getDoor().registerDoorPosition(area, listener)
                return@register { sdk.getDoor().unregisterDoorPosition(area, listener) }
            }

            register("DoorLockState.$door") {
                val listener = object : DoorLockStateListener {
                    override fun onDoorLockStateUpdated(state: Boolean?) = onDoorLock(door, state)
                    override fun onFailed(e: Exception) {
                        logFailure("DoorLockState.$door", e)
                        onDoorLockUnsupported(door)
                    }
                }
                sdk.getDoor().registerDoorLockState(area, listener)
                return@register { sdk.getDoor().unregisterDoorLockState(area, listener) }
            }
        }

        for (window in VehicleEventMapper.SUPPORTED_WINDOWS) {
            val area = WindowArea(window)
            register("WindowPosition.$window") {
                val listener = object : WindowPositionListener {
                    override fun onWindowPositionUpdated(position: Int?) = onWindowPosition(window, position)
                    override fun onFailed(e: Exception) = logFailure("WindowPosition.$window", e)
                }
                sdk.getWindow().registerWindowPosition(area, listener)
                return@register { sdk.getWindow().unregisterWindowPosition(area, listener) }
            }
        }

        for (window in VehicleEventMapper.SUPPORTED_WIPERS) {
            val area = WindowArea(window)
            register("WiperState.$window") {
                val listener = object : WiperStateListener {
                    override fun onWiperStateUpdated(state: WiperState?) = onWiper(window, state)
                    override fun onFailed(e: Exception) = logFailure("WiperState.$window", e)
                }
                sdk.getWiper().registerWiperState(area, listener)
                return@register { sdk.getWiper().unregisterWiperState(area, listener) }
            }
        }
    }

    /** 등록 실패(미지원 area 등)는 해당 항목만 건너뛴다. */
    private inline fun register(key: String, block: () -> (() -> Unit)) {
        try {
            unregisters += block()
            Log.i(TAG, "[REGISTERED] $key")
        } catch (e: Exception) {
            Log.w(TAG, "[REGISTER_FAILED] $key : ${e.message}")
        }
    }

    private fun logFailure(key: String, e: Exception) {
        Log.e(TAG, "[CALLBACK_FAILED] $key", e)
    }
}
