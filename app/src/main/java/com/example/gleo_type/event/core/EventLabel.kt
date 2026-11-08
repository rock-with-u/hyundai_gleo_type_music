package com.example.gleo_type.event.core

// ==========================================
// 이벤트 domain / label
// label은 domain별 enum으로 분리되어 있고, 각 label이 자신의 domain을 안다.
// payload 타입이 label 타입을 고정하므로 허용되지 않는 domain-label 조합은 만들 수 없다.
// ==========================================

enum class EventDomain {
    MUSIC,
    VEHICLE,
    LOCATION,
    DESTINATION,
    TRAFFIC,
    WAYPOINT,
    FAVORITES
}

sealed interface EventLabel {
    val domain: EventDomain

    /** JSON에 기록되는 label 문자열 */
    val code: String
}

enum class MusicLabel : EventLabel {
    STARTED,
    RESUMED,
    PAUSED,
    COMPLETED,
    SKIPPED,
    AUTO_TRANSITION,
    UNKNOWN_TRANSITION,
    SEEKED,
    BUFFERING,
    ERROR,
    REPEAT_MODE_CHANGED,
    SHUFFLE_MODE_CHANGED;

    override val domain: EventDomain get() = EventDomain.MUSIC
    override val code: String get() = name
}

enum class VehicleLabel : EventLabel {
    SPEED_CHANGED,
    STEERING_ANGLE_CHANGED,
    CRUISE_CONTROL_CHANGED,
    CRUISE_TARGET_SPEED_CHANGED,
    GEAR_CHANGED,
    BRAKE_CHANGED,
    DOOR_POSITION_CHANGED,
    DOOR_LOCK_CHANGED,
    WINDOW_POSITION_CHANGED,
    WIPER_CHANGED;

    override val domain: EventDomain get() = EventDomain.VEHICLE
    override val code: String get() = name
}

enum class LocationLabel : EventLabel {
    LOCATION_CHANGED;

    override val domain: EventDomain get() = EventDomain.LOCATION
    override val code: String get() = name
}

enum class DestinationLabel : EventLabel {
    SET_DESTINATION,
    CANCEL_DESTINATION,
    CHANGE_DESTINATION,
    REMAINING_DISTANCE_CHANGED;

    override val domain: EventDomain get() = EventDomain.DESTINATION
    override val code: String get() = name
}

enum class TrafficLabel : EventLabel {
    CONGESTION_SEGMENTS_UPDATED;

    override val domain: EventDomain get() = EventDomain.TRAFFIC
    override val code: String get() = name
}

enum class WaypointLabel : EventLabel {
    LIST_RETRIEVED;

    override val domain: EventDomain get() = EventDomain.WAYPOINT
    override val code: String get() = name
}

enum class FavoritesLabel : EventLabel {
    GET_FAVORITES;

    override val domain: EventDomain get() = EventDomain.FAVORITES
    override val code: String get() = name
}
