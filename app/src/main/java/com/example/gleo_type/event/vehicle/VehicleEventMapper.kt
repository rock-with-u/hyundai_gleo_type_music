package com.example.gleo_type.event.vehicle

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import com.example.gleo_type.event.format.DecimalPlaces
import com.example.gleo_type.event.format.toEventString

/**
 * Pleos Vehicle SDK / AAOS 값 → VehicleData.
 * SDK 타입은 여기서만 다루고 DTO에는 문자열만 남긴다.
 * null 반환 = 스펙에서 표현할 수 없는 값이므로 이벤트를 만들지 않음.
 */
object VehicleEventMapper {

    private const val MPS_TO_KMH = 3.6

    /** AAOS PERF_VEHICLE_SPEED(m/s) → km/h */
    fun speedFromMetersPerSecond(metersPerSecond: Float?): VehicleData.Speed? {
        val mps = metersPerSecond ?: return null
        val kmh = (mps.toDouble() * MPS_TO_KMH).coerceAtLeast(0.0)
        return VehicleData.Speed(kmh.toEventString(DecimalPlaces.SPEED))
    }

    fun steeringAngle(angle: Float?): VehicleData.SteeringAngle? =
        angle?.let { VehicleData.SteeringAngle(it.toEventString(DecimalPlaces.ANGLE)) }

    /** OTHER → null(Not ready), 그 외는 SDK 이름 그대로 */
    fun cruiseControl(state: CruiseControl?): VehicleData.CruiseControl =
        VehicleData.CruiseControl(
            when (state) {
                null, CruiseControl.OTHER -> null
                else -> state.name
            }
        )

    fun cruiseTargetSpeed(speedKmh: Float?): VehicleData.CruiseTargetSpeed? =
        speedKmh?.let { VehicleData.CruiseTargetSpeed(it.toEventString(DecimalPlaces.SPEED)) }

    fun gear(gear: VehicleGear?): VehicleData.Gear? {
        val code = when (gear) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE,
            VehicleGear.GEAR_1, VehicleGear.GEAR_2, VehicleGear.GEAR_3,
            VehicleGear.GEAR_4, VehicleGear.GEAR_5, VehicleGear.GEAR_6,
            VehicleGear.GEAR_7, VehicleGear.GEAR_8, VehicleGear.GEAR_9 -> "D"
            VehicleGear.GEAR_UNKNOWN, null -> return null
        }
        return VehicleData.Gear(code)
    }

    fun brake(pressed: Boolean?): VehicleData.Brake? =
        pressed?.let { VehicleData.Brake(it.toEventString()) }

    fun doorPosition(door: DoorValue, position: Int?): VehicleData.DoorPosition? {
        val area = doorArea(door) ?: return null
        val state = openState(position) ?: return null
        return VehicleData.DoorPosition(AreaStateDto(area, state))
    }

    fun doorLock(allLocked: Boolean): VehicleData.DoorLock =
        VehicleData.DoorLock(allLocked.toEventString())

    fun windowPosition(window: WindowValue, position: Int?): VehicleData.WindowPosition? {
        val area = windowArea(window) ?: return null
        val state = openState(position) ?: return null
        return VehicleData.WindowPosition(AreaStateDto(area, state))
    }

    fun wiper(window: WindowValue, state: WiperState?): VehicleData.Wiper? {
        val area = when (window) {
            WindowValue.FRONT_WINDSHIELD -> "FRONT"
            WindowValue.REAR_WINDSHIELD -> "REAR"
            else -> return null
        }
        val value = when (state) {
            WiperState.ON -> "ON"
            WiperState.OFF -> "OFF"
            else -> return null
        }
        return VehicleData.Wiper(AreaStateDto(area, value))
    }

    /** 스펙 area로 표현 가능한 도어 (3열·후드·트렁크 제외) */
    val SUPPORTED_DOORS = listOf(
        DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
        DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT
    )

    val SUPPORTED_WINDOWS = listOf(
        WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT,
        WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT
    )

    val SUPPORTED_WIPERS = listOf(
        WindowValue.FRONT_WINDSHIELD, WindowValue.REAR_WINDSHIELD
    )

    private fun doorArea(door: DoorValue): String? = when (door) {
        DoorValue.ROW_1_LEFT -> "FRONT_LEFT"
        DoorValue.ROW_1_RIGHT -> "FRONT_RIGHT"
        DoorValue.ROW_2_LEFT -> "REAR_LEFT"
        DoorValue.ROW_2_RIGHT -> "REAR_RIGHT"
        else -> null
    }

    private fun windowArea(window: WindowValue): String? = when (window) {
        WindowValue.ROW_1_LEFT -> "FRONT_LEFT"
        WindowValue.ROW_1_RIGHT -> "FRONT_RIGHT"
        WindowValue.ROW_2_LEFT -> "REAR_LEFT"
        WindowValue.ROW_2_RIGHT -> "REAR_RIGHT"
        else -> null
    }

    /** 0 = 닫힘, 그 외 = 열림 (AAOS 관례 기반 가정, 실차 확인 필요) */
    private fun openState(position: Int?): String? = when (position) {
        null -> null
        0 -> "CLOSED"
        else -> "OPEN"
    }
}

/**
 * 도어별 잠금 콜백 → 전체 잠금 여부.
 * 지원되는 모든 도어 값이 들어온 뒤, 집계값이 바뀔 때만 결과를 돌려준다.
 * 차량이 지원하지 않는 도어(onFailed: area not supported)는 [exclude]로 집계에서 뺀다.
 */
class DoorLockAggregator(private val allDoors: Collection<DoorValue>) {
    private val doors = LinkedHashSet(allDoors)
    private val states = HashMap<DoorValue, Boolean>()
    private var lastAllLocked: Boolean? = null

    /** @return 집계값이 바뀌었으면 새 값, 아니면 null */
    @Synchronized
    fun update(door: DoorValue, locked: Boolean?): Boolean? {
        if (door !in doors || locked == null) return null
        states[door] = locked
        return evaluate()
    }

    /** 지원하지 않는 도어를 집계 대상에서 제외. 남은 도어로 집계가 완성되면 결과를 돌려준다. */
    @Synchronized
    fun exclude(door: DoorValue): Boolean? {
        if (!doors.remove(door)) return null
        states.remove(door)
        return evaluate()
    }

    private fun evaluate(): Boolean? {
        if (doors.isEmpty() || !states.keys.containsAll(doors)) return null
        val allLocked = doors.all { states[it] == true }
        if (allLocked == lastAllLocked) return null
        lastAllLocked = allLocked
        return allLocked
    }

    @Synchronized
    fun reset() {
        doors.clear()
        doors.addAll(allDoors)
        states.clear()
        lastAllLocked = null
    }
}

/**
 * 상태값 이벤트 중복 제거.
 * SDK는 등록 직후 초기값을 보내고 같은 값을 다시 보내기도 하므로,
 * 같은 대상(label + area)의 값이 직전과 같으면 발행하지 않는다.
 */
class VehicleStateDeduplicator {
    private val last = HashMap<String, VehicleData>()

    @Synchronized
    fun isNew(data: VehicleData): Boolean {
        val key = when (data) {
            is VehicleData.DoorPosition -> "${data.label}:${data.doorPosition.area}"
            is VehicleData.WindowPosition -> "${data.label}:${data.windowPosition.area}"
            is VehicleData.Wiper -> "${data.label}:${data.wiper.area}"
            else -> data.label.name
        }
        if (last[key] == data) return false
        last[key] = data
        return true
    }

    @Synchronized
    fun reset() = last.clear()
}
