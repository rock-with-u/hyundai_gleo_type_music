package com.example.gleo_type.event.core

import com.example.gleo_type.event.location.LocationDto
import com.example.gleo_type.event.music.MusicDto
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.navigation.CongestionItemDto
import com.example.gleo_type.event.navigation.DestinationDto
import com.example.gleo_type.event.navigation.FavoriteDto
import com.example.gleo_type.event.navigation.WaypointDestinationDto
import com.example.gleo_type.event.navigation.WaypointDto
import com.example.gleo_type.event.vehicle.VehicleData

// ==========================================
// 공통 envelope
// eventId, domain, label, timestamp, driveSessionId + payload
// domain/label은 payload에서 파생되므로 따로 지정할 수 없다.
// ==========================================

data class EventDto(
    val eventId: String,
    val timestamp: String,
    val driveSessionId: String?,
    val payload: EventPayload
) {
    val label: EventLabel get() = payload.label
    val domain: EventDomain get() = payload.label.domain
}

// ==========================================
// domain별 payload
// 각 payload는 자기 domain의 label 타입만 받는다.
// ==========================================

sealed interface EventPayload {
    val label: EventLabel
}

/** MUSIC: 전체 음악 상태 */
data class MusicPayload(
    override val label: MusicLabel,
    val music: MusicDto
) : EventPayload

/** VEHICLE: label은 vehicle 데이터 종류로 결정된다. */
data class VehiclePayload(
    val music: MusicSummaryDto,
    val vehicle: VehicleData
) : EventPayload {
    override val label: VehicleLabel get() = vehicle.label
}

data class LocationPayload(
    val music: MusicSummaryDto,
    val location: LocationDto
) : EventPayload {
    override val label: LocationLabel get() = LocationLabel.LOCATION_CHANGED
}

data class DestinationPayload(
    override val label: DestinationLabel,
    val music: MusicSummaryDto,
    val destination: DestinationDto
) : EventPayload

data class TrafficPayload(
    val music: MusicSummaryDto,
    val congestionItemList: List<CongestionItemDto>
) : EventPayload {
    override val label: TrafficLabel get() = TrafficLabel.CONGESTION_SEGMENTS_UPDATED
}

data class WaypointPayload(
    val music: MusicSummaryDto,
    val destination: WaypointDestinationDto,
    val waypoints: List<WaypointDto>
) : EventPayload {
    override val label: WaypointLabel get() = WaypointLabel.LIST_RETRIEVED
}

data class FavoritesPayload(
    val music: MusicSummaryDto,
    val favorite: FavoriteDto
) : EventPayload {
    override val label: FavoritesLabel get() = FavoritesLabel.GET_FAVORITES
}
