package com.example.gleo_type.event

import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import com.example.gleo_type.event.core.LocationPayload
import com.example.gleo_type.event.core.VehicleLabel
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.location.LocationEventMapper
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.vehicle.DoorLockAggregator
import com.example.gleo_type.event.vehicle.VehicleData
import com.example.gleo_type.event.vehicle.VehicleEventMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 스펙 VEHICLE / LOCATION 예시(문법 오류 수정본)와 직렬화 결과 비교 */
class VehicleLocationSerializationTest {

    private val vehicleFixtures = Fixtures.load("vehicle.json")
    private val locationFixtures = Fixtures.load("location.json")

    private val music = MusicSummaryDto(musicId = "101", currentPositionMs = "65000", isPlaying = "true")

    private fun checkVehicle(expectedLabel: VehicleLabel, data: VehicleData?) {
        requireNotNull(data)
        assertEquals(expectedLabel, data.label)
        val fixture = Fixtures.byLabel(vehicleFixtures, expectedLabel.name)
        assertMatchesFixture(createEvent(fixture, VehiclePayload(music, data)), fixture)
    }

    @Test
    fun speed_convertsMetersPerSecondToKmh() =
        checkVehicle(VehicleLabel.SPEED_CHANGED, VehicleEventMapper.speedFromMetersPerSecond(18.194445f))

    @Test
    fun steeringAngle() =
        checkVehicle(VehicleLabel.STEERING_ANGLE_CHANGED, VehicleEventMapper.steeringAngle(233f))

    @Test
    fun cruiseControl() =
        checkVehicle(VehicleLabel.CRUISE_CONTROL_CHANGED, VehicleEventMapper.cruiseControl(CruiseControl.ENABLED))

    @Test
    fun cruiseTargetSpeed() =
        checkVehicle(VehicleLabel.CRUISE_TARGET_SPEED_CHANGED, VehicleEventMapper.cruiseTargetSpeed(83f))

    @Test
    fun gear() =
        checkVehicle(VehicleLabel.GEAR_CHANGED, VehicleEventMapper.gear(VehicleGear.GEAR_DRIVE))

    @Test
    fun brake() =
        checkVehicle(VehicleLabel.BRAKE_CHANGED, VehicleEventMapper.brake(true))

    @Test
    fun doorPosition() =
        checkVehicle(VehicleLabel.DOOR_POSITION_CHANGED, VehicleEventMapper.doorPosition(DoorValue.ROW_1_LEFT, 0))

    @Test
    fun doorLock() =
        checkVehicle(VehicleLabel.DOOR_LOCK_CHANGED, VehicleEventMapper.doorLock(true))

    @Test
    fun windowPosition() =
        checkVehicle(VehicleLabel.WINDOW_POSITION_CHANGED, VehicleEventMapper.windowPosition(WindowValue.ROW_1_LEFT, 0))

    @Test
    fun wiper() =
        checkVehicle(VehicleLabel.WIPER_CHANGED, VehicleEventMapper.wiper(WindowValue.FRONT_WINDSHIELD, WiperState.ON))

    @Test
    fun everyVehicleLabelHasFixture() {
        assertEquals(
            VehicleLabel.entries.map { it.name }.toSet(),
            vehicleFixtures.map { it["label"] }.toSet()
        )
    }

    @Test
    fun unsupportedSdkValues_produceNoEvent() {
        assertNull(VehicleEventMapper.gear(VehicleGear.GEAR_UNKNOWN))
        assertNull(VehicleEventMapper.gear(null))
        assertNull(VehicleEventMapper.doorPosition(DoorValue.HOOD, 1))
        assertNull(VehicleEventMapper.windowPosition(WindowValue.ROOF_TOP_1, 1))
        assertNull(VehicleEventMapper.wiper(WindowValue.FRONT_WINDSHIELD, WiperState.SERVICE))
        assertNull(VehicleEventMapper.cruiseControl(CruiseControl.OTHER).cruiseControlState)
        assertEquals("P", VehicleEventMapper.gear(VehicleGear.GEAR_PARK)!!.gear)
        assertEquals("D", VehicleEventMapper.gear(VehicleGear.GEAR_3)!!.gear)
        assertEquals("OPEN", VehicleEventMapper.doorPosition(DoorValue.ROW_2_RIGHT, 37)!!.doorPosition.state)
        assertEquals("REAR_RIGHT", VehicleEventMapper.doorPosition(DoorValue.ROW_2_RIGHT, 37)!!.doorPosition.area)
    }

    @Test
    fun doorLockAggregator_emitsOnlyWhenAllDoorsKnownAndChanged() {
        val aggregator = DoorLockAggregator(VehicleEventMapper.SUPPORTED_DOORS)
        assertNull(aggregator.update(DoorValue.ROW_1_LEFT, true))
        assertNull(aggregator.update(DoorValue.ROW_1_RIGHT, true))
        assertNull(aggregator.update(DoorValue.ROW_2_LEFT, true))
        assertEquals(true, aggregator.update(DoorValue.ROW_2_RIGHT, true))
        assertNull(aggregator.update(DoorValue.ROW_2_RIGHT, true))
        assertEquals(false, aggregator.update(DoorValue.ROW_1_LEFT, false))
        assertNull(aggregator.update(DoorValue.HOOD, false))
    }

    @Test
    fun locationChanged() {
        val fixture = locationFixtures.single()
        val data = LocationData(
            latitude = 37.3977,
            longitude = 127.11275,
            altitude = 45.0,
            heading = 308.21f,
            confidence = 1f,
            timestampMillis = 1_793_770_186_280L
        )
        val payload = LocationPayload(music, LocationEventMapper.toDto(data))
        assertMatchesFixture(createEvent(fixture, payload), fixture)
    }

    @Test
    fun noMusicPlaying_keepsMusicObjectWithNulls() {
        val fixture = vehicleFixtures.first()
        val event = createEvent(
            fixture,
            VehiclePayload(MusicSummaryDto.EMPTY, VehicleEventMapper.brake(false)!!)
        )
        val json = com.example.gleo_type.event.json.EventJsonSerializer().serialize(event)
        assertExplicitNull(json, "music", "musicId")
        assertExplicitNull(json, "music", "currentPositionMs")
        assertExplicitNull(json, "music", "isPlaying")
    }
}
