package com.example.gleo_type.event

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TrafficCongestionInfo
import ai.pleos.playground.navi.data.WaypointInfo
import com.example.gleo_type.event.core.DestinationLabel
import com.example.gleo_type.event.core.DestinationPayload
import com.example.gleo_type.event.core.FavoritesPayload
import com.example.gleo_type.event.core.TrafficPayload
import com.example.gleo_type.event.core.WaypointPayload
import com.example.gleo_type.event.music.MusicSummaryDto
import com.example.gleo_type.event.navigation.NavigationEventMapper
import org.junit.Assert.assertEquals
import org.junit.Test

/** 스펙 DESTINATION / TRAFFIC / WAYPOINT / FAVORITES 예시(문법 오류 수정본)와 비교 */
class NavigationSerializationTest {

    private val fixtures = Fixtures.load("navigation.json")
    private val music = MusicSummaryDto(musicId = "101", currentPositionMs = "65000", isPlaying = "true")

    private fun destinationInfo(name: String, remainDistance: Int = 15_000, remainTime: Int = 11_700) =
        DestinationInfo(
            latitude = 37.26610833,
            longitude = 127.00011388,
            name = name,
            address = "",
            remainDistance = remainDistance,
            remainTime = remainTime,
            tollFare = 0,
            poiId = "1",
            poiSubId = null
        )

    private fun checkDestination(label: DestinationLabel, name: String) {
        val fixture = Fixtures.byLabel(fixtures, label.name)
        val payload = DestinationPayload(
            label = label,
            music = music,
            destination = NavigationEventMapper.destination(label, destinationInfo(name), totalDistanceM = 23_000)
        )
        assertMatchesFixture(createEvent(fixture, payload), fixture)
    }

    @Test
    fun setDestination() = checkDestination(DestinationLabel.SET_DESTINATION, "수원역 1호선")

    @Test
    fun cancelDestination() = checkDestination(DestinationLabel.CANCEL_DESTINATION, "변경된 목적지")

    @Test
    fun changeDestination() = checkDestination(DestinationLabel.CHANGE_DESTINATION, "변경된 목적지")

    @Test
    fun remainingDistanceChanged() =
        checkDestination(DestinationLabel.REMAINING_DISTANCE_CHANGED, "변경된 목적지")

    @Test
    fun cancelWithoutCachedDestination_sendsNullFields() {
        val dto = NavigationEventMapper.destination(DestinationLabel.CANCEL_DESTINATION, null, null)
        assertEquals("true", dto.cancelStatus)
        assertEquals(null, dto.name)
        assertEquals(null, dto.progressPercent)
    }

    @Test
    fun congestionSegmentsUpdated() {
        val fixture = Fixtures.byLabel(fixtures, "CONGESTION_SEGMENTS_UPDATED")
        val items = listOf(
            TrafficCongestionInfo(
                offset = 500.0,
                distance = 2000.0,
                trafficCongestionTime = 600L,
                trafficCongestionType = TrafficCongestionType.HEAVY,
                avrSpeed = 12.0,
                roadName = "강변북로",
                startCrossName = "성산대교 북단",
                endCrossName = "양화대교 북단"
            )
        )
        val payload = TrafficPayload(music, NavigationEventMapper.congestionItems(items))
        assertMatchesFixture(createEvent(fixture, payload), fixture)
    }

    @Test
    fun waypointListRetrieved() {
        val fixture = Fixtures.byLabel(fixtures, "LIST_RETRIEVED")
        val waypoints = listOf(
            WaypointInfo(
                name = "수원역 1호선", address = "", latitude = 0.0, longitude = 0.0,
                remainDistance = 23_000, remainTime = 2_700, poiId = "1", poiSubId = null
            ),
            WaypointInfo(
                name = "맥도날드 서판교DT점", address = "", latitude = 0.0, longitude = 0.0,
                remainDistance = 43_000, remainTime = 4_740, poiId = "2", poiSubId = null
            )
        )
        val payload = WaypointPayload(
            music = music,
            destination = NavigationEventMapper.waypointDestination(
                destinationInfo("성심당 DCC점", remainDistance = 181_000, remainTime = 11_700)
            ),
            waypoints = NavigationEventMapper.waypoints(waypoints)
        )
        assertMatchesFixture(createEvent(fixture, payload), fixture)
    }

    @Test
    fun getFavorites() {
        val fixture = Fixtures.byLabel(fixtures, "GET_FAVORITES")
        val bookmark = BookmarkInfo(
            latitude = 37.412721,
            longitude = 127.094681,
            name = "장소이름",
            poiId = "102",
            poiSubId = null,
            address = "",
            category = BookmarkType.HOME
        )
        val payload = FavoritesPayload(music, NavigationEventMapper.favorite(bookmark)!!)
        assertMatchesFixture(createEvent(fixture, payload), fixture)
    }
}
