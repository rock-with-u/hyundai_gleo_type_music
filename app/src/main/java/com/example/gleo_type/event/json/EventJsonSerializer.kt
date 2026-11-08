package com.example.gleo_type.event.json

import com.example.gleo_type.event.core.DestinationPayload
import com.example.gleo_type.event.core.EventDto
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.core.FavoritesPayload
import com.example.gleo_type.event.core.LocationPayload
import com.example.gleo_type.event.core.MusicPayload
import com.example.gleo_type.event.core.TrafficPayload
import com.example.gleo_type.event.core.VehiclePayload
import com.example.gleo_type.event.core.WaypointPayload
import com.example.gleo_type.event.location.LocationDto
import com.example.gleo_type.event.music.MusicDto
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.navigation.CongestionItemDto
import com.example.gleo_type.event.navigation.DestinationDto
import com.example.gleo_type.event.navigation.FavoriteDto
import com.example.gleo_type.event.navigation.WaypointDestinationDto
import com.example.gleo_type.event.navigation.WaypointDto
import com.example.gleo_type.event.vehicle.AreaStateDto
import com.example.gleo_type.event.vehicle.VehicleData

/**
 * EventDto → JSON.
 * JSON 필드명은 이 파일에서만 정의한다.
 * payload 키(music, vehicle, navigation, trafficStatus, location)는 envelope 최상위에 펼친다.
 */
class EventJsonSerializer {

    fun serialize(event: EventDto, pretty: Boolean = false): String =
        JsonWriter.write(toJsonMap(event), pretty)

    fun toJsonMap(event: EventDto): Map<String, Any?> {
        val root = linkedMapOf<String, Any?>(
            "eventId" to event.eventId,
            "domain" to event.domain.name,
            "label" to event.label.code,
            "timestamp" to event.timestamp,
            "driveSessionId" to event.driveSessionId
        )
        root.putAll(payloadFields(event.payload))
        return root
    }

    private fun payloadFields(payload: EventPayload): Map<String, Any?> = when (payload) {
        is MusicPayload -> mapOf(
            "music" to music(payload.music)
        )

        is VehiclePayload -> linkedMapOf(
            "music" to summary(payload.music),
            "vehicle" to vehicle(payload.vehicle)
        )

        is LocationPayload -> linkedMapOf(
            "music" to summary(payload.music),
            "location" to location(payload.location)
        )

        is DestinationPayload -> linkedMapOf(
            "music" to summary(payload.music),
            "navigation" to mapOf("destination" to destination(payload.destination))
        )

        is TrafficPayload -> linkedMapOf(
            "music" to summary(payload.music),
            "trafficStatus" to mapOf(
                "congestionItemList" to payload.congestionItemList.map(::congestion)
            )
        )

        is WaypointPayload -> linkedMapOf(
            "music" to summary(payload.music),
            "navigation" to linkedMapOf(
                "destination" to waypointDestination(payload.destination),
                "waypoints" to payload.waypoints.map(::waypoint)
            )
        )

        is FavoritesPayload -> linkedMapOf(
            "music" to summary(payload.music),
            "navigation" to mapOf("destination" to favorite(payload.favorite))
        )
    }

    private fun music(dto: MusicDto) = linkedMapOf(
        "musicId" to dto.musicId,
        "durationMs" to dto.durationMs,
        "currentPositionMs" to dto.currentPositionMs,
        "remainingTimeMs" to dto.remainingTimeMs,
        "isPlaying" to dto.isPlaying,
        "bufferedPositionMs" to dto.bufferedPositionMs,
        "completionRate" to dto.completionRate,
        "repeatMode" to dto.repeatMode,
        "shuffleEnabled" to dto.shuffleEnabled,
        "fromPositionMs" to dto.fromPositionMs,
        "toPositionMs" to dto.toPositionMs,
        "errorCode" to dto.errorCode,
        "errorMessage" to dto.errorMessage
    )

    private fun summary(dto: MusicSummaryDto) = linkedMapOf(
        "musicId" to dto.musicId,
        "currentPositionMs" to dto.currentPositionMs,
        "isPlaying" to dto.isPlaying
    )

    private fun vehicle(data: VehicleData): Map<String, Any?> = when (data) {
        is VehicleData.Speed -> mapOf("speedKmh" to data.speedKmh)
        is VehicleData.SteeringAngle -> mapOf("steeringAngle" to data.steeringAngle)
        is VehicleData.CruiseControl -> mapOf("cruiseControlState" to data.cruiseControlState)
        is VehicleData.CruiseTargetSpeed -> mapOf("cruiseTargetSpeedKmh" to data.cruiseTargetSpeedKmh)
        is VehicleData.Gear -> mapOf("gear" to data.gear)
        is VehicleData.Brake -> mapOf("brakeStatus" to data.brakeStatus)
        is VehicleData.DoorPosition -> mapOf("doorPosition" to areaState(data.doorPosition))
        is VehicleData.DoorLock -> mapOf("doorLocked" to data.doorLocked)
        is VehicleData.WindowPosition -> mapOf("windowPosition" to areaState(data.windowPosition))
        is VehicleData.Wiper -> mapOf("wiper" to areaState(data.wiper))
    }

    private fun areaState(dto: AreaStateDto) = linkedMapOf(
        "area" to dto.area,
        "state" to dto.state
    )

    private fun location(dto: LocationDto) = linkedMapOf(
        "latitude" to dto.latitude,
        "longitude" to dto.longitude,
        "altitude" to dto.altitude,
        "heading" to dto.heading,
        "timestampMillis" to dto.timestampMillis
    )

    private fun destination(dto: DestinationDto) = linkedMapOf(
        "name" to dto.name,
        "latitude" to dto.latitude,
        "longitude" to dto.longitude,
        "remainingTimeSec" to dto.remainingTimeSec,
        "totalDistanceM" to dto.totalDistanceM,
        "remainingDistanceM" to dto.remainingDistanceM,
        "progressPercent" to dto.progressPercent,
        "cancelStatus" to dto.cancelStatus
    )

    private fun waypointDestination(dto: WaypointDestinationDto) = linkedMapOf(
        "name" to dto.name,
        "remainingDistanceM" to dto.remainingDistanceM,
        "remainingTimeSec" to dto.remainingTimeSec,
        "deleteStatus" to dto.deleteStatus
    )

    private fun waypoint(dto: WaypointDto) = linkedMapOf(
        "order" to dto.order,
        "name" to dto.name,
        "remainingDistanceM" to dto.remainingDistanceM,
        "remainingTimeSec" to dto.remainingTimeSec
    )

    private fun favorite(dto: FavoriteDto) = linkedMapOf(
        "name" to dto.name,
        "latitude" to dto.latitude,
        "longitude" to dto.longitude,
        "favoritePlaceId" to dto.favoritePlaceId,
        "placeType" to dto.placeType
    )

    private fun congestion(dto: CongestionItemDto) = linkedMapOf(
        "offset" to dto.offset,
        "distance" to dto.distance,
        "trafficCongestionTime" to dto.trafficCongestionTime,
        "trafficCongestionType" to dto.trafficCongestionType,
        "avrSpeed" to dto.avrSpeed,
        "roadName" to dto.roadName,
        "startCrossName" to dto.startCrossName,
        "endCrossName" to dto.endCrossName
    )
}
