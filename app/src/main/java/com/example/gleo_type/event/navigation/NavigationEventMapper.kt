package com.example.gleo_type.event.navigation

import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TrafficCongestionInfo
import ai.pleos.playground.navi.data.WaypointInfo
import com.example.gleo_type.event.core.DestinationLabel
import com.example.gleo_type.event.format.DecimalPlaces
import com.example.gleo_type.event.format.toEventString

/**
 * NaviHelper SDK 데이터 → navigation DTO.
 *
 * 계산 필드
 * - progressPercent = (totalDistanceM - remainingDistanceM) / totalDistanceM * 100
 *   totalDistanceM은 경로 시작 시점의 RouteStartInfo.distance(m)를 기억해 둔 값.
 */
object NavigationEventMapper {

    /** 경로가 없을 때 NaviHelper가 내려주는 빈 목적지(이름 없음 + 좌표 0,0) */
    fun isEmpty(info: DestinationInfo): Boolean =
        info.name.isBlank() && info.latitude == 0.0 && info.longitude == 0.0

    fun destination(
        label: DestinationLabel,
        info: DestinationInfo?,
        totalDistanceM: Int?
    ): DestinationDto {
        val remaining = info?.remainDistance
        val progress = if (totalDistanceM != null && totalDistanceM > 0 && remaining != null) {
            ((totalDistanceM - remaining).toDouble() / totalDistanceM * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }

        return DestinationDto(
            name = info?.name,
            latitude = info?.latitude.toEventString(DecimalPlaces.COORDINATE),
            longitude = info?.longitude.toEventString(DecimalPlaces.COORDINATE),
            remainingTimeSec = info?.remainTime.toEventString(),
            totalDistanceM = totalDistanceM.toEventString(),
            remainingDistanceM = remaining.toEventString(),
            progressPercent = progress.toEventString(DecimalPlaces.PERCENT),
            cancelStatus = (label == DestinationLabel.CANCEL_DESTINATION).toEventString()
        )
    }

    fun waypointDestination(info: DestinationInfo?): WaypointDestinationDto =
        WaypointDestinationDto(
            name = info?.name,
            remainingDistanceM = info?.remainDistance.toEventString(),
            remainingTimeSec = info?.remainTime.toEventString(),
            deleteStatus = (info == null).toEventString()
        )

    fun waypoints(list: List<WaypointInfo>): List<WaypointDto> =
        list.mapIndexed { index, info ->
            WaypointDto(
                order = (index + 1).toEventString(),
                name = info.name,
                remainingDistanceM = info.remainDistance.toEventString(),
                remainingTimeSec = info.remainTime.toEventString()
            )
        }

    /** 등록되지 않은 빈 슬롯(이름 없음 + 좌표 0,0)은 null → 이벤트를 만들지 않음 */
    fun favorite(info: BookmarkInfo): FavoriteDto? {
        if (info.name.isBlank() && info.latitude == 0.0 && info.longitude == 0.0) return null
        return favoriteDto(info)
    }

    private fun favoriteDto(info: BookmarkInfo): FavoriteDto = FavoriteDto(
        name = info.name,
        latitude = info.latitude.toEventString(DecimalPlaces.COORDINATE),
        longitude = info.longitude.toEventString(DecimalPlaces.COORDINATE),
        favoritePlaceId = info.poiId,
        placeType = info.category.name
    )

    fun congestionItems(list: List<TrafficCongestionInfo>): List<CongestionItemDto> =
        list.map { item ->
            CongestionItemDto(
                offset = item.offset.toEventString(DecimalPlaces.DISTANCE),
                distance = item.distance.toEventString(DecimalPlaces.DISTANCE),
                trafficCongestionTime = item.trafficCongestionTime.toEventString(),
                trafficCongestionType = item.trafficCongestionType.name,
                avrSpeed = item.avrSpeed.toEventString(DecimalPlaces.SPEED),
                roadName = item.roadName,
                startCrossName = item.startCrossName,
                endCrossName = item.endCrossName
            )
        }
}
