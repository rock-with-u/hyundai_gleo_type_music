package com.example.gleo_type.event.location

import ai.pleos.playground.location.fused.model.LocationData
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.IntervalThrottle
import com.example.gleo_type.event.core.LocationPayload
import com.example.gleo_type.event.format.DecimalPlaces
import com.example.gleo_type.event.format.toEventString
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.send.EventPublisher

/** Pleos FusedLocation LocationData → LocationDto */
object LocationEventMapper {

    fun toDto(data: LocationData): LocationDto = LocationDto(
        latitude = data.latitude.toEventString(DecimalPlaces.COORDINATE),
        longitude = data.longitude.toEventString(DecimalPlaces.COORDINATE),
        altitude = data.altitude.toEventString(DecimalPlaces.ALTITUDE),
        heading = data.heading.toEventString(DecimalPlaces.HEADING),
        timestampMillis = data.timestampMillis.toEventString()
    )
}

/**
 * FusedLocationMonitor 콜백 → LOCATION_CHANGED.
 * - 최소 1초 간격
 * - 좌표·고도·방향이 직전 발행값과 같으면(정차 중) 발행하지 않음
 */
class LocationEventSource(
    private val eventFactory: EventFactory,
    private val musicState: MusicStateHolder,
    private val publisher: EventPublisher,
    private val throttle: IntervalThrottle = IntervalThrottle(minIntervalMs = 1_000)
) {
    private var lastPosition: LocationDto? = null

    @Synchronized
    fun onLocation(data: LocationData) {
        val dto = LocationEventMapper.toDto(data)
        val position = dto.copy(timestampMillis = null)
        if (position == lastPosition) return
        if (!throttle.shouldEmit()) return
        lastPosition = position

        val payload = LocationPayload(
            music = musicState.currentSummary(),
            location = dto
        )
        publisher.publish(eventFactory.create(payload))
    }
}
