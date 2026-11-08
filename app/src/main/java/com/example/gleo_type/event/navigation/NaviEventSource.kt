package com.example.gleo_type.event.navigation

import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.example.gleo_type.event.core.DestinationLabel
import com.example.gleo_type.event.core.DestinationPayload
import com.example.gleo_type.event.core.EventFactory
import com.example.gleo_type.event.core.EventPayload
import com.example.gleo_type.event.core.FavoritesPayload
import com.example.gleo_type.event.core.IntervalThrottle
import com.example.gleo_type.event.core.TrafficPayload
import com.example.gleo_type.event.core.WaypointPayload
import com.example.gleo_type.event.music.MusicStateHolder
import com.example.gleo_type.event.send.EventPublisher

/**
 * NaviHelper 콜백 → DESTINATION / WAYPOINT / FAVORITES / TRAFFIC 이벤트.
 *
 * NaviMonitor가 받은 콜백을 이 리스너로 전달하고(연결은 NaviMonitor가 관리),
 * 추가 조회가 필요하면 [requester]("getDestinationInfo" 등 NaviHelper 메서드 이름)를 호출한다.
 *
 * 흐름
 * - onRouteStarted → SET(경로 없던 상태) / CHANGE(경로 진행 중) 결정 → getDestinationInfo → onDestinationInfo에서 발행
 * - onRouteCancelled → 캐시된 목적지로 CANCEL 발행
 * - onDrivingInfo(주기) → 5초마다 getDestinationInfo → 남은 거리가 바뀌면 REMAINING_DISTANCE_CHANGED
 * - onWaypointChanged → getWaypointInfo → onWayPointInfo에서 LIST_RETRIEVED
 * - onBookMarkChanged → getBookmarkInfo → onBookmarkInfo에서 즐겨찾기 1개당 GET_FAVORITES
 * - onRouteStateInfo.trafficStatus → CONGESTION_SEGMENTS_UPDATED (목록이 바뀔 때만)
 */
class NaviEventSource(
    private val eventFactory: EventFactory,
    private val musicState: MusicStateHolder,
    private val publisher: EventPublisher,
    private val requester: (String) -> Unit,
    private val remainingRequestThrottle: IntervalThrottle = IntervalThrottle(minIntervalMs = 5_000)
) : NaviHelperEventListener {

    private var routeActive = false
    private var totalDistanceM: Int? = null
    private var pendingDestinationLabel: DestinationLabel? = null
    private var lastDestination: DestinationInfo? = null
    private var lastEmittedRemainingM: Int? = null
    private var lastCongestion: List<CongestionItemDto>? = null

    // ==========================================
    // DESTINATION
    // ==========================================

    @Synchronized
    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
        pendingDestinationLabel =
            if (routeActive) DestinationLabel.CHANGE_DESTINATION else DestinationLabel.SET_DESTINATION
        routeActive = true
        totalDistanceM = routeStartInfo.distance
        requester("getDestinationInfo")
    }

    @Synchronized
    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        val pending = pendingDestinationLabel
        // 에뮬레이터에서 확인: 경로가 없으면 빈 목적지(이름 "", 좌표 0,0)가 내려온다.
        val info = destinationInfo.takeUnless { NavigationEventMapper.isEmpty(it) }
        lastDestination = info

        if (pending != null) {
            pendingDestinationLabel = null
            emitDestination(pending, info)
            return
        }

        if (info == null) return

        // 앱 시작 시 이미 안내 중인 경로가 있는 경우도 진행 중으로 본다.
        routeActive = true
        if (info.remainDistance != lastEmittedRemainingM) {
            emitDestination(DestinationLabel.REMAINING_DISTANCE_CHANGED, info)
        }
    }

    @Synchronized
    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
        if (routeActive && remainingRequestThrottle.shouldEmit()) {
            requester("getDestinationInfo")
        }
    }

    @Synchronized
    override fun onRouteCancelled() {
        emitDestination(DestinationLabel.CANCEL_DESTINATION, lastDestination)
        clearRoute()
    }

    @Synchronized
    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        clearRoute()
    }

    @Synchronized
    override fun onRouteEnded() {
        clearRoute()
    }

    private fun clearRoute() {
        routeActive = false
        totalDistanceM = null
        pendingDestinationLabel = null
        lastDestination = null
        lastEmittedRemainingM = null
        remainingRequestThrottle.reset()
    }

    private fun emitDestination(label: DestinationLabel, info: DestinationInfo?) {
        lastEmittedRemainingM = info?.remainDistance
        emit(
            DestinationPayload(
                label = label,
                music = musicState.currentSummary(),
                destination = NavigationEventMapper.destination(label, info, totalDistanceM)
            )
        )
    }

    // ==========================================
    // WAYPOINT
    // ==========================================

    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
        requester("getWaypointInfo")
    }

    @Synchronized
    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
        // 경로도 경유지도 없으면 보낼 정보가 없다 (앱 시작 시 조회 응답)
        if (waypointInfoList.isEmpty() && lastDestination == null) return
        emit(
            WaypointPayload(
                music = musicState.currentSummary(),
                destination = NavigationEventMapper.waypointDestination(lastDestination),
                waypoints = NavigationEventMapper.waypoints(waypointInfoList)
            )
        )
    }

    // ==========================================
    // FAVORITES
    // ==========================================

    override fun onBookMarkChanged() {
        requester("getBookmarkInfo")
    }

    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
        for (bookmark in bookmarkInfoList) {
            val favorite = NavigationEventMapper.favorite(bookmark) ?: continue
            emit(
                FavoritesPayload(
                    music = musicState.currentSummary(),
                    favorite = favorite
                )
            )
        }
    }

    // ==========================================
    // TRAFFIC
    // ==========================================

    @Synchronized
    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        val items = routeStateInfo.trafficStatus?.congestionItemList ?: return
        val dtos = NavigationEventMapper.congestionItems(items)
        if (dtos == lastCongestion) return
        // 혼잡 구간이 한 번도 없었다면 빈 목록은 보내지 않는다 (있다가 사라진 경우는 보냄)
        if (dtos.isEmpty() && lastCongestion.isNullOrEmpty()) return
        lastCongestion = dtos
        emit(TrafficPayload(music = musicState.currentSummary(), congestionItemList = dtos))
    }

    private fun emit(payload: EventPayload) {
        publisher.publish(eventFactory.create(payload))
    }
}
