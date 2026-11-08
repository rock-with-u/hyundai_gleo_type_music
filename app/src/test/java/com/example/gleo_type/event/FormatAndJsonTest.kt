package com.example.gleo_type.event

import com.example.gleo_type.event.core.ChangeThrottle
import com.example.gleo_type.event.core.EventDomain
import com.example.gleo_type.event.core.EventLabel
import com.example.gleo_type.event.core.DestinationLabel
import com.example.gleo_type.event.core.FavoritesLabel
import com.example.gleo_type.event.core.LocationLabel
import com.example.gleo_type.event.core.MusicLabel
import com.example.gleo_type.event.core.TrafficLabel
import com.example.gleo_type.event.core.VehicleLabel
import com.example.gleo_type.event.core.WaypointLabel
import com.example.gleo_type.event.format.toEventString
import com.example.gleo_type.event.format.toEventTimestamp
import com.example.gleo_type.event.json.JsonWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FormatAndJsonTest {

    @Test
    fun decimal_roundsAndStripsTrailingZeros_keepingOneFractionDigit() {
        assertEquals("0.0", 0.0.toEventString(4))
        assertEquals("1.0", 1.0.toEventString(4))
        assertEquals("0.3926", (60_000.0 / 152_823).toEventString(4))
        assertEquals("0.0046", (706.0 / 152_823).toEventString(4))
        assertEquals("2000.0", 2000.0.toEventString(1))
        assertEquals("37.26610833", 37.26610833.toEventString(8))
        assertEquals("37.3977", 37.3977.toEventString(8))
        assertEquals("34.78", (8_000.0 / 23_000 * 100).toEventString(2))
        assertEquals("308.21", 308.21f.toEventString(2))
        assertEquals("65.5", 65.46.toEventString(1))
        assertEquals("-12.5", (-12.5f).toEventString(1))
        assertNull(Double.NaN.toEventString(2))
        assertNull((null as Double?).toEventString(2))
    }

    @Test
    fun boolean_andIntegers() {
        assertEquals("true", true.toEventString())
        assertEquals("false", false.toEventString())
        assertEquals("152823", 152_823L.toEventString())
        assertEquals("1", 1.toEventString())
    }

    @Test
    fun timestamp_isIso8601WithSeoulOffset() {
        assertEquals(
            "2026-11-03T22:45:30.123+09:00",
            Instant.parse("2026-11-03T13:45:30.123Z").toEventTimestamp()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonWriter_rejectsNonStringScalars() {
        JsonWriter.write(mapOf("durationMs" to 152823))
    }

    @Test
    fun jsonWriter_escapesAndKeepsNull() {
        val json = JsonWriter.write(mapOf("a" to "q\"\\\n한글", "b" to null, "c" to listOf("1")))
        assertEquals("{\"a\":\"q\\\"\\\\\\n한글\",\"b\":null,\"c\":[\"1\"]}", json)
        assertEquals(mapOf("a" to "q\"\\\n한글", "b" to null, "c" to listOf("1")), TestJson.parse(json))
    }

    @Test
    fun everyLabelBelongsToItsDomain_andEveryDomainHasLabels() {
        val labels: List<EventLabel> = MusicLabel.entries + VehicleLabel.entries + LocationLabel.entries +
                DestinationLabel.entries + TrafficLabel.entries + WaypointLabel.entries + FavoritesLabel.entries

        assertEquals(EventDomain.entries.toSet(), labels.map { it.domain }.toSet())
        assertTrue(MusicLabel.entries.all { it.domain == EventDomain.MUSIC })
        assertTrue(VehicleLabel.entries.all { it.domain == EventDomain.VEHICLE })
        assertTrue(DestinationLabel.entries.all { it.domain == EventDomain.DESTINATION })
    }

    @Test
    fun changeThrottle_passesOnDeltaOrInterval() {
        var now = 0L
        val throttle = ChangeThrottle(minDelta = 1.0, minIntervalMs = 1_000, now = { now })

        assertTrue(throttle.shouldEmit(60.0))
        assertFalse(throttle.shouldEmit(60.4))      // 작은 변화 + 시간 미경과
        assertTrue(throttle.shouldEmit(61.0))       // 1.0 이상 변화
        now += 1_000
        assertTrue(throttle.shouldEmit(61.2))       // 시간 경과 + 값 변화
        now += 1_000
        assertFalse(throttle.shouldEmit(61.2))      // 값 동일
    }
}
