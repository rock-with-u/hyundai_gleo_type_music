package com.example.gleo_type.event

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.DrivingState
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TrafficCongestionInfo
import ai.pleos.playground.navi.data.TrafficCongestionSummary
import ai.pleos.playground.navi.data.TrafficStatus
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import com.example.gleo_type.event.core.DestinationLabel
import com.example.gleo_type.event.core.EventDomain
import com.example.gleo_type.event.core.FavoritesPayload
import com.example.gleo_type.event.core.IntervalThrottle
import com.example.gleo_type.event.core.TrafficPayload
import com.example.gleo_type.event.core.WaypointPayload
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.navigation.NaviEventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NaviHelper 콜백 순서에 따른 DESTINATION / WAYPOINT / FAVORITES / TRAFFIC 발행 검증 */
class NaviEventSourceTest {

    private lateinit var h: SourceTestHarness
    private lateinit var requests: MutableList<String>
    private lateinit var source: NaviEventSource

    @Before
    fun setUp() {
        h = SourceTestHarness()
        requests = mutableListOf()
        source = NaviEventSource(
            eventFactory = h.factory,
            musicState = h.musicState,
            publisher = h.publisher,
            requester = { requests += it },
            remainingRequestThrottle = IntervalThrottle(minIntervalMs = 5_000, now = h.clock)
        )
        h.playMusic()
    }

    // ------------------------------------------
    // SDK 데이터 생성 도우미
    // ------------------------------------------

    private fun routeStart(distance: Int = 23_000) =
        RouteStartInfo(duration = 3_600, distance = distance, tollFare = 0, name = "경로", remainDestinationChargingValue = 0)

    private fun destination(name: String = "수원역 1호선", remain: Int = 15_000, time: Int = 11_700) =
        DestinationInfo(
            latitude = 37.26610833, longitude = 127.00011388, name = name, address = "",
            remainDistance = remain, remainTime = time, tollFare = 0, poiId = "1", poiSubId = null
        )

    private fun drivingInfo() = DrivingInfo(
        destination = DrivingState(name = "d", duration = 100, distance = 100, remainDestinationChargingValue = 0),
        waypoints = emptyList()
    )

    private fun routeState(items: List<TrafficCongestionInfo>?) = RouteStateInfo(
        termsAgreement = true,
        naviMode = NaviMode.ROUTE_GUIDING,
        isRouting = true,
        routeOption = RouteOption.RECOMMENDED,
        remainDestinationChargingInt = 0,
        remainWaypointChargingInt = emptyList(),
        trafficStatus = items?.let { TrafficStatus(TrafficCongestionSummary(0.0, 0L, 0.0), it, emptyList()) }
    )

    private fun congestion(type: TrafficCongestionType = TrafficCongestionType.HEAVY) = TrafficCongestionInfo(
        offset = 500.0, distance = 2000.0, trafficCongestionTime = 600L, trafficCongestionType = type,
        avrSpeed = 12.0, roadName = "강변북로", startCrossName = "성산대교 북단", endCrossName = "양화대교 북단"
    )

    private fun waypoint(name: String, remain: Int, time: Int) = WaypointInfo(
        name = name, address = "", latitude = 0.0, longitude = 0.0,
        remainDistance = remain, remainTime = time, poiId = name, poiSubId = null
    )

    private fun labels() = h.destinations().map { it.label }

    // ------------------------------------------
    // DESTINATION
    // ------------------------------------------

    @Test
    fun routeStarted_requestsDestination_thenEmitsSetDestinationWithProgress() {
        source.onRouteStarted(routeStart(distance = 23_000))

        assertEquals(listOf("getDestinationInfo"), requests)
        assertTrue("상세 정보가 오기 전엔 발행하지 않음", h.events.isEmpty())

        source.onDestinationInfo(destination(remain = 15_000))

        val event = h.events.single()
        assertEquals(EventDomain.DESTINATION, event.domain)
        assertEquals(DestinationLabel.SET_DESTINATION, event.label)
        assertEquals("11", event.driveSessionId)

        val dto = h.destinations().single().destination
        assertEquals("수원역 1호선", dto.name)
        assertEquals("37.26610833", dto.latitude)
        assertEquals("23000", dto.totalDistanceM)
        assertEquals("15000", dto.remainingDistanceM)
        assertEquals("11700", dto.remainingTimeSec)
        assertEquals("34.78", dto.progressPercent)
        assertEquals("false", dto.cancelStatus)
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun routeStartedWhileGuiding_isChangeDestination() {
        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination("A"))
        source.onRouteStarted(routeStart(distance = 40_000))
        source.onDestinationInfo(destination("B", remain = 40_000))

        assertEquals(listOf(DestinationLabel.SET_DESTINATION, DestinationLabel.CHANGE_DESTINATION), labels())
        val changed = h.destinations().last().destination
        assertEquals("B", changed.name)
        assertEquals("40000", changed.totalDistanceM)
        assertEquals("0.0", changed.progressPercent)
    }

    @Test
    fun routeCancelled_usesCachedDestination_andNextRouteIsSetAgain() {
        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination("수원역 1호선"))
        source.onRouteCancelled()

        val cancel = h.destinations().last()
        assertEquals(DestinationLabel.CANCEL_DESTINATION, cancel.label)
        assertEquals("수원역 1호선", cancel.destination.name)
        assertEquals("true", cancel.destination.cancelStatus)

        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination("새 목적지"))

        assertEquals(
            listOf(DestinationLabel.SET_DESTINATION, DestinationLabel.CANCEL_DESTINATION, DestinationLabel.SET_DESTINATION),
            labels()
        )
    }

    @Test
    fun routeCancelledWithoutKnownDestination_sendsNullFields() {
        source.onRouteCancelled()

        val dto = h.destinations().single().destination
        assertEquals("true", dto.cancelStatus)
        assertEquals(null, dto.name)
        assertEquals(null, dto.latitude)
        assertEquals(null, dto.totalDistanceM)
        assertEquals(null, dto.progressPercent)
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun drivingInfo_requestsDestinationAtMostEvery5Seconds_whileGuiding() {
        source.onDrivingInfo(drivingInfo())
        assertTrue("경로 없으면 조회하지 않음", requests.isEmpty())

        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination())
        requests.clear()

        source.onDrivingInfo(drivingInfo())
        h.now += 1_000
        source.onDrivingInfo(drivingInfo())
        h.now += 4_000
        source.onDrivingInfo(drivingInfo())

        assertEquals(listOf("getDestinationInfo", "getDestinationInfo"), requests)
    }

    @Test
    fun remainingDistanceChanged_onlyWhenRemainingDistanceDiffers() {
        source.onRouteStarted(routeStart(distance = 23_000))
        source.onDestinationInfo(destination(remain = 15_000))

        source.onDestinationInfo(destination(remain = 15_000))   // 변화 없음
        source.onDestinationInfo(destination(remain = 14_000))   // 변화
        source.onDestinationInfo(destination(remain = 14_000))   // 변화 없음

        assertEquals(
            listOf(DestinationLabel.SET_DESTINATION, DestinationLabel.REMAINING_DISTANCE_CHANGED),
            labels()
        )
        val remaining = h.destinations().last().destination
        assertEquals("14000", remaining.remainingDistanceM)
        assertEquals("39.13", remaining.progressPercent)
        assertEquals("false", remaining.cancelStatus)
    }

    @Test
    fun destinationInfoAtAppStart_withoutRouteStart_isRemainingDistanceChanged() {
        source.onDestinationInfo(destination(remain = 9_000))

        val payload = h.destinations().single()
        assertEquals(DestinationLabel.REMAINING_DISTANCE_CHANGED, payload.label)
        assertEquals(null, payload.destination.totalDistanceM)
        assertEquals(null, payload.destination.progressPercent)
        assertEquals("9000", payload.destination.remainingDistanceM)
    }

    @Test
    fun arrivalOrRouteEnd_clearsRoute_withoutEvent() {
        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination())
        source.onDestinationArrived(DestinationArrivedInfo(duration = 1, distance = 1, name = "x", address = ""))
        requests.clear()

        source.onDrivingInfo(drivingInfo())
        assertTrue("도착 후엔 남은 거리 조회 안 함", requests.isEmpty())

        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination())
        source.onRouteEnded()
        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination())

        assertEquals(
            listOf(DestinationLabel.SET_DESTINATION, DestinationLabel.SET_DESTINATION, DestinationLabel.SET_DESTINATION),
            labels()
        )
    }

    // ------------------------------------------
    // WAYPOINT
    // ------------------------------------------

    @Test
    fun waypointChanged_requestsList_thenEmitsListWithCachedDestination() {
        source.onRouteStarted(routeStart())
        source.onDestinationInfo(destination("성심당 DCC점", remain = 181_000, time = 11_700))
        requests.clear()

        source.onWaypointChanged(WaypointChangedInfo(WaypointIndex.FIRST, WaypointCmdType.ADD, routeStart()))
        assertEquals(listOf("getWaypointInfo"), requests)

        source.onWayPointInfo(
            listOf(
                waypoint("수원역 1호선", 23_000, 2_700),
                waypoint("맥도날드 서판교DT점", 43_000, 4_740)
            )
        )

        val payload = h.payloadsOf<WaypointPayload>().single()
        assertEquals(EventDomain.WAYPOINT, payload.label.domain)
        assertEquals("성심당 DCC점", payload.destination.name)
        assertEquals("181000", payload.destination.remainingDistanceM)
        assertEquals("false", payload.destination.deleteStatus)
        assertEquals(listOf("1", "2"), payload.waypoints.map { it.order })
        assertEquals(listOf("23000", "43000"), payload.waypoints.map { it.remainingDistanceM })
        h.assertAllSerializeAsStrings()
    }

    @Test
    fun waypointListWithoutDestination_marksDeleteStatusTrue() {
        source.onWayPointInfo(listOf(waypoint("수원역 1호선", 23_000, 2_700)))

        val payload = h.payloadsOf<WaypointPayload>().single()
        assertEquals("true", payload.destination.deleteStatus)
        assertEquals(null, payload.destination.name)
        assertEquals(listOf("1"), payload.waypoints.map { it.order })
    }

    @Test
    fun emptyNaviResponsesAtStartup_produceNoEvents() {
        // 에뮬레이터에서 확인: 경로가 없을 때 앱 시작 조회 응답이 모두 빈 값으로 내려온다.
        source.onDestinationInfo(destination(name = "", remain = 0, time = 0).copy(latitude = 0.0, longitude = 0.0))
        source.onWayPointInfo(emptyList())
        source.onRouteStateInfo(routeState(emptyList()))
        source.onDrivingInfo(drivingInfo())

        assertTrue(h.events.isEmpty())
        assertTrue("경로가 없으니 남은 거리 조회도 안 함", requests.isEmpty())
    }

    @Test
    fun congestionClearedAfterSegments_isSentAsEmptyList() {
        source.onRouteStateInfo(routeState(listOf(congestion())))
        source.onRouteStateInfo(routeState(emptyList()))

        val traffic = h.payloadsOf<TrafficPayload>()
        assertEquals(listOf(1, 0), traffic.map { it.congestionItemList.size })
    }

    // ------------------------------------------
    // FAVORITES
    // ------------------------------------------

    @Test
    fun bookmarkChanged_requestsList_thenEmitsOneEventPerFavorite() {
        source.onBookMarkChanged()
        assertEquals(listOf("getBookmarkInfo"), requests)

        source.onBookmarkInfo(
            listOf(
                BookmarkInfo(37.412721, 127.094681, name = "집", poiId = "102", poiSubId = null, address = "", category = BookmarkType.HOME),
                BookmarkInfo(37.5, 127.0, name = "회사", poiId = "103", poiSubId = null, address = "", category = BookmarkType.OFFICE)
            )
        )

        val favorites = h.payloadsOf<FavoritesPayload>()
        assertEquals(listOf("집", "회사"), favorites.map { it.favorite.name })
        assertEquals(listOf("HOME", "OFFICE"), favorites.map { it.favorite.placeType })
        assertEquals(listOf("102", "103"), favorites.map { it.favorite.favoritePlaceId })
        assertEquals("37.412721", favorites.first().favorite.latitude)
        assertTrue(h.events.all { it.domain == EventDomain.FAVORITES })
    }

    @Test
    fun emptyBookmarkSlots_areSkipped() {
        // 에뮬레이터에서 확인: 미등록 집/회사 슬롯이 name="", 좌표 0,0 으로 내려온다.
        source.onBookmarkInfo(
            listOf(
                BookmarkInfo(0.0, 0.0, name = "", poiId = "", poiSubId = null, address = "", category = BookmarkType.HOME),
                BookmarkInfo(0.0, 0.0, name = "", poiId = "", poiSubId = null, address = "", category = BookmarkType.OFFICE),
                BookmarkInfo(37.41255277, 127.09591666, name = "가든서비스", poiId = "36k", poiSubId = null, address = "", category = BookmarkType.DEFAULT)
            )
        )

        val favorites = h.payloadsOf<FavoritesPayload>()
        assertEquals(listOf("가든서비스"), favorites.map { it.favorite.name })
        assertEquals("DEFAULT", favorites.single().favorite.placeType)
    }

    // ------------------------------------------
    // TRAFFIC
    // ------------------------------------------

    @Test
    fun routeStateInfo_emitsTrafficOnlyWhenListChanges() {
        source.onRouteStateInfo(routeState(null))                     // trafficStatus 없음
        source.onRouteStateInfo(routeState(listOf(congestion())))
        source.onRouteStateInfo(routeState(listOf(congestion())))     // 동일
        source.onRouteStateInfo(routeState(listOf(congestion(TrafficCongestionType.SLOW))))

        val traffic = h.payloadsOf<TrafficPayload>()
        assertEquals(2, traffic.size)
        assertEquals(listOf("HEAVY", "SLOW"), traffic.map { it.congestionItemList.single().trafficCongestionType })
        assertEquals("500.0", traffic.first().congestionItemList.single().offset)
        h.assertAllSerializeAsStrings()
    }

    // ------------------------------------------
    // music 요약
    // ------------------------------------------

    @Test
    fun everyEvent_carriesCurrentMusicSummary() {
        h.playMusic(musicId = "101", positionMs = 60_000, playing = true)
        h.now += 5_000   // 재생 중 5초 경과 → 위치 추정

        source.onRouteCancelled()

        assertEquals(MusicSummaryDto("101", "65000", "true"), h.destinations().single().music)
    }

    @Test
    fun noMusic_sendsEmptyMusicSummary() {
        h.musicState.clear()
        source.onRouteCancelled()
        assertEquals(MusicSummaryDto.EMPTY, h.destinations().single().music)
    }
}
