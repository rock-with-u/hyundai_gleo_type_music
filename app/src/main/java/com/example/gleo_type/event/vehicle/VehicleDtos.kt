package com.example.gleo_type.event.vehicle

import com.example.gleo_type.event.core.VehicleLabel

/** area + state 쌍 (도어/창문/와이퍼) */
data class AreaStateDto(
    val area: String?,
    val state: String?
)

/**
 * "vehicle" 객체. 하위 타입이 label을 결정하므로
 * label과 맞지 않는 필드를 담은 이벤트는 만들 수 없다.
 */
sealed class VehicleData(val label: VehicleLabel) {

    data class Speed(val speedKmh: String?) :
        VehicleData(VehicleLabel.SPEED_CHANGED)

    data class SteeringAngle(val steeringAngle: String?) :
        VehicleData(VehicleLabel.STEERING_ANGLE_CHANGED)

    /** null = Not ready */
    data class CruiseControl(val cruiseControlState: String?) :
        VehicleData(VehicleLabel.CRUISE_CONTROL_CHANGED)

    data class CruiseTargetSpeed(val cruiseTargetSpeedKmh: String?) :
        VehicleData(VehicleLabel.CRUISE_TARGET_SPEED_CHANGED)

    data class Gear(val gear: String?) :
        VehicleData(VehicleLabel.GEAR_CHANGED)

    /** 스펙의 break_status 오타를 brakeStatus로 정정 */
    data class Brake(val brakeStatus: String?) :
        VehicleData(VehicleLabel.BRAKE_CHANGED)

    /** 스펙의 windowPosition 키(복붙 오류)를 doorPosition으로 정정 */
    data class DoorPosition(val doorPosition: AreaStateDto) :
        VehicleData(VehicleLabel.DOOR_POSITION_CHANGED)

    /** 앞·뒤 4개 도어가 모두 잠겼으면 "true" */
    data class DoorLock(val doorLocked: String?) :
        VehicleData(VehicleLabel.DOOR_LOCK_CHANGED)

    data class WindowPosition(val windowPosition: AreaStateDto) :
        VehicleData(VehicleLabel.WINDOW_POSITION_CHANGED)

    data class Wiper(val wiper: AreaStateDto) :
        VehicleData(VehicleLabel.WIPER_CHANGED)
}
