package com.example.gleo_type.event

import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import com.example.gleo_type.event.core.ChangeThrottle
import com.example.gleo_type.event.core.EventDomain
import com.example.gleo_type.event.core.IntervalThrottle
import com.example.gleo_type.event.core.LocationPayload
import com.example.gleo_type.event.core.VehicleLabel
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.location.LocationEventSource
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.vehicle.VehicleData
import com.example.gleo_type.event.vehicle.VehicleEventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** VehicleEventSource / LocationEventSource: SDK 콜백 값 → 이벤트 발행 검증 */
class VehicleLocationSourceTest {

    private lateinit var h: SourceTestHarness
    private lateinit var vehicle: VehicleEventSource
    private lateinit var location: LocationEventSource

    @Before
    fun setUp() {
        h = SourceTestHarness()
        vehicle = VehicleEventSource(
            vehicleProvider = { error("SDK는 start()에서만 사용") },
            eventFactory = h.factory,
            musicState = h.musicState,
            publisher = h.publisher,
            speedThrottle = ChangeThrottle(minDelta = 1.0, minIntervalMs = 1_000, now = h.clock),
            steeringThrottle = ChangeThrottle(minDelta = 5.0, minIntervalMs = 500, now = h.clock)
        )
        location = LocationEventSource(
            eventFactory = h.factory,
            musicState = h.musicState,
            publisher = h.publisher,
            throttle = IntervalThrottle(minIntervalMs = 1_000, now = h.clock)
        )
        h.playMusic()
    }

    private fun vehicleData(): List<VehicleData> = h.payloadsOf<VehiclePayload>().map { it.vehicle }

    private fun labels(): List<VehicleLabel> = vehicleData().map { it.label }

    // ------------------------------------------
    // 속도 / 조향각 (고빈도 + 스로틀)
    // ------------------------------------------

    @Test
    fun speed_convertsToKmh_andThrottles() {
        vehicle.onAaosSpeed(18.194445f)          // 65.5 km/h → 발행
        vehicle.onAaosSpeed(18.2f)               // 65.5 → 변화 < 1, 시간 미경과
        vehicle.onAaosSpeed(18.5f)               // 66.6 → 1 km/h 이상 변화
        h.now += 1_000
        vehicle.onAaosSpeed(18.55f)              // 66.8 → 1초 경과 + 값 변화
        vehicle.onAaosSpeed(null)                // 값 없음 → 무시

        assertEquals(
            listOf("65.5", "66.6", "66.8"),
            vehicleData().map { (it as VehicleData.Speed).speedKmh }
        )
        val event = h.events.first()
        assertEquals(EventDomain.VEHICLE, event.domain)
        assertEquals(VehicleLabel.SPEED_CHANGED, event.label)
        assertEquals("11", event.driveSessionId)
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun steering_throttlesSmallChanges() {
        vehicle.onSteeringAngle(10f)
        vehicle.onSteeringAngle(12f)             // 2° 변화, 0.5초 미경과
        vehicle.onSteeringAngle(-233f)           // 큰 변화
        h.now += 500
        vehicle.onSteeringAngle(-232f)           // 시간 경과 + 변화
        vehicle.onSteeringAngle(null)

        assertEquals(
            listOf("10.0", "-233.0", "-232.0"),
            vehicleData().map { (it as VehicleData.SteeringAngle).steeringAngle }
        )
    }

    // ------------------------------------------
    // 상태값 (스로틀 없음)
    // ------------------------------------------

    @Test
    fun discreteSignals_emitMappedValues_andSkipUnsupported() {
        vehicle.onCruiseControlState(CruiseControl.ENABLED)
        vehicle.onCruiseControlState(CruiseControl.OTHER)
        vehicle.onCruiseTargetSpeed(83f)
        vehicle.onCruiseTargetSpeed(null)
        vehicle.onGear(VehicleGear.GEAR_PARK)
        vehicle.onGear(VehicleGear.GEAR_UNKNOWN)
        vehicle.onBrake(true)
        vehicle.onBrake(null)
        vehicle.onDoorPosition(DoorValue.ROW_1_LEFT, 0)
        vehicle.onDoorPosition(DoorValue.HOOD, 100)
        vehicle.onWindowPosition(WindowValue.ROW_2_RIGHT, 50)
        vehicle.onWindowPosition(WindowValue.ROOF_TOP_1, 50)
        vehicle.onWiper(WindowValue.REAR_WINDSHIELD, WiperState.OFF)
        vehicle.onWiper(WindowValue.FRONT_WINDSHIELD, WiperState.SERVICE)

        assertEquals(
            listOf(
                VehicleData.CruiseControl("ENABLED"),
                VehicleData.CruiseControl(null),
                VehicleData.CruiseTargetSpeed("83.0"),
                VehicleData.Gear("P"),
                VehicleData.Brake("true"),
                VehicleData.DoorPosition(com.example.gleo_type.event.vehicle.AreaStateDto("FRONT_LEFT", "CLOSED")),
                VehicleData.WindowPosition(com.example.gleo_type.event.vehicle.AreaStateDto("REAR_RIGHT", "OPEN")),
                VehicleData.Wiper(com.example.gleo_type.event.vehicle.AreaStateDto("REAR", "OFF"))
            ),
            vehicleData()
        )
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun doorLock_emitsAggregateOnlyAfterAllDoorsReported_andOnChange() {
        vehicle.onDoorLock(DoorValue.ROW_1_LEFT, true)
        vehicle.onDoorLock(DoorValue.ROW_1_RIGHT, true)
        vehicle.onDoorLock(DoorValue.ROW_2_LEFT, true)
        assertTrue("4개 도어가 다 들어오기 전엔 발행 안 함", h.events.isEmpty())

        vehicle.onDoorLock(DoorValue.ROW_2_RIGHT, true)   // 전체 잠김
        vehicle.onDoorLock(DoorValue.ROW_2_RIGHT, true)   // 동일
        vehicle.onDoorLock(DoorValue.ROW_1_LEFT, false)   // 하나 열림
        vehicle.onDoorLock(DoorValue.ROW_1_RIGHT, false)  // 집계값 동일(false)

        assertEquals(listOf(VehicleLabel.DOOR_LOCK_CHANGED, VehicleLabel.DOOR_LOCK_CHANGED), labels())
        assertEquals(
            listOf("true", "false"),
            vehicleData().map { (it as VehicleData.DoorLock).doorLocked }
        )
    }

    @Test
    fun doorLock_unsupportedDoorIsExcludedFromAggregate() {
        // 에뮬레이터에서 확인: DOOR_LOCK이 ROW_1_RIGHT(area 0x4)를 지원하지 않아 onFailed 발생
        vehicle.onDoorLock(DoorValue.ROW_1_LEFT, true)
        vehicle.onDoorLock(DoorValue.ROW_2_LEFT, true)
        vehicle.onDoorLock(DoorValue.ROW_2_RIGHT, true)
        assertTrue(h.events.isEmpty())

        vehicle.onDoorLockUnsupported(DoorValue.ROW_1_RIGHT)   // 남은 3개로 집계 완성
        vehicle.onDoorLock(DoorValue.ROW_1_RIGHT, false)       // 제외된 도어 값은 무시
        vehicle.onDoorLock(DoorValue.ROW_2_LEFT, false)

        assertEquals(
            listOf("true", "false"),
            vehicleData().map { (it as VehicleData.DoorLock).doorLocked }
        )
    }

    @Test
    fun repeatedSameState_isSentOnce_perArea() {
        // 에뮬레이터에서 확인: 등록 직후 초기값과 같은 값이 한 번 더 들어온다.
        vehicle.onDoorPosition(DoorValue.ROW_1_RIGHT, 0)
        vehicle.onDoorPosition(DoorValue.ROW_1_RIGHT, 0)
        vehicle.onDoorPosition(DoorValue.ROW_2_LEFT, 0)        // 다른 도어 → 발행
        vehicle.onDoorPosition(DoorValue.ROW_1_RIGHT, 30)      // 열림 → 발행
        vehicle.onDoorPosition(DoorValue.ROW_1_RIGHT, 60)      // 여전히 OPEN → 생략
        vehicle.onWiper(WindowValue.REAR_WINDSHIELD, WiperState.OFF)
        vehicle.onWiper(WindowValue.REAR_WINDSHIELD, WiperState.OFF)
        vehicle.onGear(VehicleGear.GEAR_PARK)
        vehicle.onGear(VehicleGear.GEAR_PARK)
        vehicle.onGear(VehicleGear.GEAR_DRIVE)

        assertEquals(
            listOf(
                VehicleLabel.DOOR_POSITION_CHANGED, VehicleLabel.DOOR_POSITION_CHANGED, VehicleLabel.DOOR_POSITION_CHANGED,
                VehicleLabel.WIPER_CHANGED,
                VehicleLabel.GEAR_CHANGED, VehicleLabel.GEAR_CHANGED
            ),
            labels()
        )
    }

    @Test
    fun vehicleEvents_carryMusicSummary() {
        h.playMusic(musicId = "101", positionMs = 60_000, playing = true)
        h.now += 5_000
        vehicle.onBrake(true)

        h.musicState.clear()
        vehicle.onBrake(false)

        val music = h.payloadsOf<VehiclePayload>().map { it.music }
        assertEquals(listOf(MusicSummaryDto("101", "65000", "true"), MusicSummaryDto.EMPTY), music)
    }

    // ------------------------------------------
    // LOCATION
    // ------------------------------------------

    private fun locationData(lat: Double, ts: Long) = LocationData(
        latitude = lat, longitude = 127.11275, altitude = 45.0,
        heading = 308.21f, confidence = 1f, timestampMillis = ts
    )

    @Test
    fun location_emitsAtMostOncePerSecond() {
        location.onLocation(locationData(37.3977, 1L))
        h.now += 300
        location.onLocation(locationData(37.3978, 2L))   // 1초 미만 → 무시
        h.now += 700
        location.onLocation(locationData(37.3979, 3L))   // 1초 경과

        val payloads = h.payloadsOf<LocationPayload>()
        assertEquals(listOf("37.3977", "37.3979"), payloads.map { it.location.latitude })
        assertEquals(listOf("1", "3"), payloads.map { it.location.timestampMillis })
        assertTrue(h.events.all { it.domain == EventDomain.LOCATION && it.label.code == "LOCATION_CHANGED" })
        assertEquals(MusicSummaryDto("101", "65000", "true"), payloads.first().music)
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun location_stationaryPositionIsNotRepeated() {
        // 에뮬레이터에서 확인: 정차 중에도 같은 좌표가 1초마다 들어온다.
        location.onLocation(locationData(37.3977, 1L))
        h.now += 1_000
        location.onLocation(locationData(37.3977, 2L))   // 좌표 동일 → 생략
        h.now += 1_000
        location.onLocation(locationData(37.3977, 3L))   // 좌표 동일 → 생략
        location.onLocation(locationData(37.3980, 4L))   // 이동 + 직전 발행 후 2초 → 발행

        assertEquals(
            listOf("1", "4"),
            h.payloadsOf<LocationPayload>().map { it.location.timestampMillis }
        )
    }
}
