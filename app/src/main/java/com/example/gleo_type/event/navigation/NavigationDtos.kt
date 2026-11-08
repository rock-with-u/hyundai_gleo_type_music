package com.example.gleo_type.event.navigation

/** DESTINATION: navigation.destination */
data class DestinationDto(
    val name: String?,
    val latitude: String?,
    val longitude: String?,
    val remainingTimeSec: String?,
    val totalDistanceM: String?,
    val remainingDistanceM: String?,
    val progressPercent: String?,
    /** CANCEL_DESTINATION일 때만 "true" */
    val cancelStatus: String?
)

/** WAYPOINT: navigation.destination (DESTINATION과 필드 구성이 다름) */
data class WaypointDestinationDto(
    val name: String?,
    val remainingDistanceM: String?,
    val remainingTimeSec: String?,
    /** 목적지가 없는(삭제된) 상태면 "true" */
    val deleteStatus: String?
)

/** WAYPOINT: navigation.waypoints[] */
data class WaypointDto(
    /** 1부터 시작 */
    val order: String?,
    val name: String?,
    val remainingDistanceM: String?,
    val remainingTimeSec: String?
)

/** FAVORITES: navigation.destination */
data class FavoriteDto(
    val name: String?,
    val latitude: String?,
    val longitude: String?,
    val favoritePlaceId: String?,
    /** HOME / OFFICE / EV_CHARGING_STATION / DEFAULT */
    val placeType: String?
)

/** TRAFFIC: trafficStatus.congestionItemList[] */
data class CongestionItemDto(
    val offset: String?,
    val distance: String?,
    val trafficCongestionTime: String?,
    val trafficCongestionType: String?,
    val avrSpeed: String?,
    val roadName: String?,
    val startCrossName: String?,
    val endCrossName: String?
)
