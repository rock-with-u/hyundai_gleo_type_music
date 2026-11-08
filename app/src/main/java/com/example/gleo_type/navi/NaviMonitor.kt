package com.example.gleo_type.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * gleo-type NaviHelper 데이터 수집 모니터
 *
 * 기존 기능:
 * - NaviHelper 연결 및 초기화
 * - 전체 내비게이션 이벤트 수집
 * - 위치, 목적지, 경유지, 경로, TBT 조회
 * - SDK 객체 JSON 직렬화
 * - onCurrentRoadSpeedLimit 이벤트 제외
 * - 중복 실행 방지 및 리스너 해제
 *
 * 추가 기능:
 * - 교통 상태 전용 요청 진단
 * - RouteStateInfo 콜백 추적
 * - TrafficStatus 전체 JSON 출력
 * - 혼잡 구간 및 요약 정보 출력
 *
 * Logcat:
 * - GleoNavi: 전체 내비게이션
 * - GleoTraffic: 교통 정보 전용
 */
class NaviMonitor(
    context: Context,
    // 이벤트 파이프라인(NaviEventSource)으로 콜백을 그대로 전달할 리스너
    private val eventListener: NaviHelperEventListener? = null
) {

    companion object {
        private const val TAG = "GleoNavi"
        private const val TRAFFIC_TAG = "GleoTraffic"

        private const val MAX_DEPTH = 6
        private const val MAX_ITEMS = 100
        private const val LOG_CHUNK_SIZE = 3000

        private val INFO_REQUESTS = listOf(
            "getCurrentLocationInfo",
            "getDestinationInfo",
            "getWaypointInfo",
            "getRouteStateInfo",
            "getTBTInfo",
            "getRecentDestinationInfo",
            "getBookmarkInfo",
            "getChargerOperatorInfo"
        )

        private const val IGNORED_EVENT =
            "onCurrentRoadSpeedLimit"

        private val DOCUMENTED_EVENTS = setOf(
            "onRouteStarted",
            "onRoutingCompleted",
            "onRouteCancelled",
            "onReRouteEnded",
            "onRouteOptionChanged",
            "onDestinationChanged",
            "onDestinationArrived",
            "onWaypointChanged",
            "onWayPointArrived",
            "onBookmarkInfo",
            "onBookMarkChanged",
            "onRecentDestinationInfo",
            "onCurrentLocationInfo",
            "onDestinationInfo",
            "onWayPointInfo",
            "onRouteStateInfo",
            "onTBTInfo",
            "onChargerOperatorInfo",
            "onTripInfo",
            "onNavigationStatus",
            "onDrivingInfo",
            "onCameraAlert",
            "onCautionPointInfo",
            "onError"
        )
    }

    private val appContext = context.applicationContext

    private var naviHelper: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null

    @Volatile
    private var started = false

    @Volatile
    private var connected = false

    @Volatile
    private var generation = 0

    @Volatile
    private var trafficCallbackCount = 0

    // ==========================================
    // 1. NaviHelper 초기화
    // ==========================================

    @Synchronized
    fun start() {
        if (started) {
            Log.i(TAG, "[START] 이미 시작된 상태")
            return
        }

        started = true
        connected = false
        generation += 1
        trafficCallbackCount = 0

        val currentGeneration = generation

        Log.i(TAG, "================================")
        Log.i(TAG, "[START] NaviHelper 데이터 수집 시작")
        Log.i(TAG, "================================")

        Log.i(
            TRAFFIC_TAG,
            "[INIT] 교통 혼잡도 모니터 준비"
        )

        var createdHelper: NaviHelper? = null

        try {
            Log.i(TAG, "[INIT] NaviHelper 객체 생성 시도")

            val helper = NaviHelper(appContext)
            createdHelper = helper
            naviHelper = helper

            Log.i(TAG, "[INIT] NaviHelper 객체 생성 성공")

            helper.initialize(
                onConnected = {
                    if (!started || generation != currentGeneration) {
                        Log.w(
                            TAG,
                            "[CONNECTED] 이전 연결 콜백 무시"
                        )
                    } else {
                        connected = true

                        Log.i(
                            TAG,
                            "[연결성공] NaviHelper SDK 연결됨"
                        )

                        Log.i(
                            TRAFFIC_TAG,
                            "[CONNECTED] NaviHelper 연결 성공"
                        )

                        registerAllListeners(helper)
                        inspectSdkMethods(helper)
                        requestAllInformation()
                    }
                },
                onFailed = { error ->
                    if (started && generation == currentGeneration) {
                        connected = false

                        Log.e(
                            TAG,
                            "[연결실패] NaviHelper",
                            error
                        )

                        Log.e(
                            TRAFFIC_TAG,
                            "[CONNECT_FAILED] NaviHelper 연결 실패",
                            error
                        )
                    }
                }
            )

            Log.i(TAG, "[INIT] initialize() 호출 반환")

        } catch (error: Exception) {
            started = false
            connected = false
            generation += 1

            Log.e(TAG, "[INIT_ERROR] 초기화 실패", error)
            Log.e(
                TRAFFIC_TAG,
                "[INIT_ERROR] 초기화 실패",
                error
            )

            try {
                createdHelper?.release()
            } catch (releaseError: Exception) {
                Log.e(
                    TAG,
                    "[INIT_ERROR] 해제 중 오류",
                    releaseError
                )
            }

            naviHelper = null
            throw error
        }
    }

    // ==========================================
    // 2. 리스너 등록
    // ==========================================

    private fun registerAllListeners(helper: NaviHelper) {
        if (naviListener != null) {
            Log.i(TAG, "[LISTENER] 이미 등록됨")
            return
        }

        try {
            val listenerClass =
                NaviHelperEventListener::class.java

            Log.i(
                TAG,
                "[LISTENER] interface=${listenerClass.isInterface}"
            )

            if (!listenerClass.isInterface) {
                Log.e(
                    TAG,
                    "[LISTENER] 동적 리스너 생성 불가: 인터페이스 아님"
                )

                Log.e(
                    TRAFFIC_TAG,
                    "[LISTENER_FAILED] 인터페이스 아님"
                )
                return
            }

            val handler = InvocationHandler { proxy, method, args ->

                when (method.name) {
                    "toString" -> {
                        "GleoNaviAllEventListener"
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        proxy === args?.getOrNull(0)
                    }

                    IGNORED_EVENT -> {
                        defaultReturnValue(method.returnType)
                    }

                    else -> {
                        try {
                            if (started) {
                                handleNaviEvent(method, args)
                                forwardEvent(method, args)
                            }
                        } catch (error: Exception) {
                            Log.e(
                                TAG,
                                "[EVENT_ERROR] ${method.name}",
                                error
                            )
                        }

                        defaultReturnValue(method.returnType)
                    }
                }
            }

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
                handler
            )

            val listener =
                proxy as NaviHelperEventListener

            helper.addListener(listener)
            naviListener = listener

            Log.i(
                TAG,
                "[LISTENER] 전체 이벤트 리스너 등록 성공"
            )

            Log.i(
                TRAFFIC_TAG,
                "[LISTENER_READY] 전체 이벤트 등록 완료"
            )

            inspectListenerMethods()

        } catch (error: Exception) {
            Log.e(
                TAG,
                "[LISTENER_ERROR] 리스너 등록 실패",
                error
            )

            Log.e(
                TRAFFIC_TAG,
                "[LISTENER_ERROR] 등록 실패",
                error
            )
        }
    }

    // ==========================================
    // 3. 이벤트 JSON 변환
    // ==========================================

    private fun handleNaviEvent(
        method: Method,
        args: Array<out Any?>?
    ) {
        val eventName = method.name

        if (eventName == IGNORED_EVENT) {
            return
        }

        // 교통 상태 콜백이면 전용 로그 출력
        if (eventName == "onRouteStateInfo") {
            handleTrafficEvent(args)
        }

        val documented =
            DOCUMENTED_EVENTS.contains(eventName)

        val category = classifyEvent(eventName)

        val json = JSONObject()

        json.put("source", "NaviHelper")
        json.put("eventType", eventName)
        json.put("category", category)
        json.put("documented", documented.toString())
        json.put("timestamp", System.currentTimeMillis().toString())

        val parameters = JSONArray()
        val values = args ?: emptyArray<Any?>()

        for (index in values.indices) {
            val parameter = JSONObject()

            parameter.put("index", index.toString())

            val parameterType =
                method.parameterTypes.getOrNull(index)

            parameter.put(
                "type",
                parameterType?.name ?: "unknown"
            )

            val visited = newVisitedSet()

            parameter.put(
                "value",
                serializeValue(
                    values[index],
                    0,
                    visited
                )
            )

            parameters.put(parameter)
        }

        json.put("parameters", parameters)

        if (eventName == "onError") {
            logLongMessage(
                Log.ERROR,
                "[ERROR] $eventName $json"
            )
        } else {
            logLongMessage(
                Log.INFO,
                "[EVENT][$category] $eventName $json"
            )
        }
    }

    private fun forwardEvent(
        method: Method,
        args: Array<out Any?>?
    ) {
        val listener = eventListener ?: return

        try {
            method.invoke(listener, *(args ?: emptyArray()))
        } catch (error: Exception) {
            Log.e(TAG, "[FORWARD_ERROR] ${method.name}", error.cause ?: error)
        }
    }

    /** NaviEventSource가 추가 조회(getDestinationInfo 등)를 요청할 때 사용 */
    @Synchronized
    fun request(methodName: String) {
        val helper = naviHelper

        if (!started || !connected || helper == null) {
            Log.w(TAG, "[REQUEST_SKIPPED] $methodName SDK 미연결")
            return
        }

        requestInformation(helper, methodName)
    }

    // ==========================================
    // 4. 교통 혼잡도 전용 콜백
    // ==========================================

    private fun handleTrafficEvent(
        args: Array<out Any?>?
    ) {
        trafficCallbackCount += 1

        Log.i(
            TRAFFIC_TAG,
            "[CALLBACK_RECEIVED] onRouteStateInfo " +
                    "count=$trafficCallbackCount"
        )

        val values = args ?: emptyArray<Any?>()

        if (values.isEmpty()) {
            Log.w(
                TRAFFIC_TAG,
                "[CALLBACK_EMPTY] 전달된 인자가 없음"
            )
            return
        }

        for (index in values.indices) {
            val value = values[index]

            Log.i(
                TRAFFIC_TAG,
                "[CALLBACK_PARAM] index=$index " +
                        "type=${value?.javaClass?.name ?: "null"}"
            )
        }

        val routeStateInfo = values.firstOrNull { value ->
            value != null &&
                    value.javaClass.methods.any { method ->
                        method.name == "getTrafficStatus" &&
                                method.parameterTypes.isEmpty()
                    }
        }

        if (routeStateInfo == null) {
            Log.w(
                TRAFFIC_TAG,
                "[ROUTE_STATE_MISSING] " +
                        "trafficStatus Getter를 가진 인자를 찾지 못함"
            )

            return
        }

        logTrafficStatus(routeStateInfo)
    }

    // ==========================================
    // 5. TrafficStatus JSON 출력
    // ==========================================

    private fun logTrafficStatus(routeStateInfo: Any) {
        try {
            Log.i(
                TRAFFIC_TAG,
                "[TRAFFIC_READ] RouteStateInfo 분석 시작"
            )

            val trafficStatus = readGetter(
                routeStateInfo,
                "getTrafficStatus"
            )

            if (trafficStatus == null) {
                Log.w(
                    TRAFFIC_TAG,
                    "[TRAFFIC_NULL] trafficStatus가 null"
                )
                return
            }

            Log.i(
                TRAFFIC_TAG,
                "[TRAFFIC_TYPE] ${trafficStatus.javaClass.name}"
            )

            // TrafficStatus 원본의 모든 공개 Getter를 JSON화
            val rawJson = serializeValue(
                trafficStatus,
                0,
                newVisitedSet()
            )

            logTrafficMessage(
                Log.INFO,
                "[TRAFFIC_RAW_JSON] $rawJson"
            )

            // 개별 필드 조회
            val summary = readGetter(
                trafficStatus,
                "getSummary"
            )

            val congestionItemList = readGetter(
                trafficStatus,
                "getCongestionItemList"
            )

            val trafficEventList = readGetter(
                trafficStatus,
                "getTrafficEventList"
            )

            val trafficJson = JSONObject()

            trafficJson.put(
                "summary",
                serializeValue(
                    summary,
                    0,
                    newVisitedSet()
                )
            )

            trafficJson.put(
                "congestionItemList",
                serializeValue(
                    congestionItemList,
                    0,
                    newVisitedSet()
                )
            )

            trafficJson.put(
                "trafficEventList",
                serializeValue(
                    trafficEventList,
                    0,
                    newVisitedSet()
                )
            )

            val result = JSONObject()
            result.put("traffic", trafficJson)

            logTrafficMessage(
                Log.INFO,
                "[TRAFFIC_JSON] $result"
            )

            // 혼잡 구간 수
            if (congestionItemList is Collection<*>) {
                Log.i(
                    TRAFFIC_TAG,
                    "[CONGESTION_COUNT] " +
                            congestionItemList.size
                )

                if (congestionItemList.isEmpty()) {
                    Log.i(
                        TRAFFIC_TAG,
                        "[CONGESTION_EMPTY] " +
                                "전달된 혼잡 구간이 없음"
                    )
                } else {
                    logCongestionItems(congestionItemList)
                }
            } else {
                Log.w(
                    TRAFFIC_TAG,
                    "[CONGESTION_UNAVAILABLE] " +
                            "혼잡 목록을 Collection으로 읽지 못함"
                )
            }

            // 전체 혼잡 요약
            if (summary != null) {
                logCongestionSummary(summary)
            } else {
                Log.w(
                    TRAFFIC_TAG,
                    "[SUMMARY_NULL] 혼잡 요약 없음"
                )
            }

        } catch (error: Exception) {
            Log.e(
                TRAFFIC_TAG,
                "[TRAFFIC_ERROR] 교통정보 분석 실패",
                error
            )
        }
    }

    // ==========================================
    // 6. 혼잡 구간별 데이터 출력
    // ==========================================

    private fun logCongestionItems(
        items: Collection<*>
    ) {
        var index = 0

        for (item in items) {
            if (index >= MAX_ITEMS) {
                Log.w(
                    TRAFFIC_TAG,
                    "[CONGESTION_TRUNCATED] 출력 제한"
                )
                break
            }

            if (item == null) {
                index += 1
                continue
            }

            val json = JSONObject()

            json.put(
                "offset",
                readGetter(item, "getOffset")?.toString()
                    ?: JSONObject.NULL
            )

            json.put(
                "distance",
                readGetter(item, "getDistance")?.toString()
                    ?: JSONObject.NULL
            )

            json.put(
                "trafficCongestionTime",
                readGetter(
                    item,
                    "getTrafficCongestionTime"
                )?.toString() ?: JSONObject.NULL
            )

            json.put(
                "trafficCongestionType",
                readGetter(
                    item,
                    "getTrafficCongestionType"
                )?.toString() ?: JSONObject.NULL
            )

            json.put(
                "avrSpeed",
                readGetter(item, "getAvrSpeed")?.toString()
                    ?: JSONObject.NULL
            )

            json.put(
                "roadName",
                readGetter(item, "getRoadName")?.toString()
                    ?: JSONObject.NULL
            )

            json.put(
                "startCrossName",
                readGetter(
                    item,
                    "getStartCrossName"
                )?.toString() ?: JSONObject.NULL
            )

            json.put(
                "endCrossName",
                readGetter(
                    item,
                    "getEndCrossName"
                )?.toString() ?: JSONObject.NULL
            )

            logTrafficMessage(
                Log.INFO,
                "[CONGESTION_ITEM][$index] $json"
            )

            index += 1
        }
    }

    // ==========================================
    // 7. 혼잡 요약 데이터 출력
    // ==========================================

    private fun logCongestionSummary(summary: Any) {
        val json = JSONObject()

        json.put(
            "totalCongestionDist",
            readGetter(
                summary,
                "getTotalCongestionDist"
            )?.toString() ?: JSONObject.NULL
        )

        json.put(
            "totalCongestionTime",
            readGetter(
                summary,
                "getTotalCongestionTime"
            )?.toString() ?: JSONObject.NULL
        )

        json.put(
            "totalCongestionAvrSpeed",
            readGetter(
                summary,
                "getTotalCongestionAvrSpeed"
            )?.toString() ?: JSONObject.NULL
        )

        logTrafficMessage(
            Log.INFO,
            "[CONGESTION_SUMMARY] $json"
        )
    }

    // ==========================================
    // 8. Getter 조회
    // ==========================================

    private fun readGetter(
        target: Any,
        getterName: String
    ): Any? {
        return try {
            val getter = target.javaClass.methods.firstOrNull {
                it.name == getterName &&
                        it.parameterTypes.isEmpty() &&
                        Modifier.isPublic(it.modifiers)
            }

            if (getter == null) {
                Log.w(
                    TRAFFIC_TAG,
                    "[GETTER_MISSING] " +
                            "${target.javaClass.simpleName}.$getterName"
                )
                null
            } else {
                getter.invoke(target)
            }

        } catch (error: Exception) {
            Log.e(
                TRAFFIC_TAG,
                "[GETTER_ERROR] $getterName",
                error
            )
            null
        }
    }

    private fun newVisitedSet(): MutableSet<Any> {
        return Collections.newSetFromMap(
            IdentityHashMap<Any, Boolean>()
        )
    }

    private fun logTrafficMessage(
        priority: Int,
        message: String
    ) {
        if (message.length <= LOG_CHUNK_SIZE) {
            Log.println(priority, TRAFFIC_TAG, message)
            return
        }

        val totalChunks =
            (message.length + LOG_CHUNK_SIZE - 1) /
                    LOG_CHUNK_SIZE

        var index = 0
        var offset = 0

        while (offset < message.length) {
            val end = minOf(
                offset + LOG_CHUNK_SIZE,
                message.length
            )

            val chunk = message.substring(offset, end)

            Log.println(
                priority,
                TRAFFIC_TAG,
                "[CHUNK ${index + 1}/$totalChunks] $chunk"
            )

            offset = end
            index += 1
        }
    }

    // ==========================================
    // 9. 이벤트 분류
    // ==========================================

    private fun classifyEvent(name: String): String {
        return when (name) {
            "onRouteStarted",
            "onRoutingCompleted",
            "onRouteCancelled",
            "onReRouteEnded",
            "onRouteOptionChanged",
            "onRouteStateInfo" -> "ROUTE"

            "onDestinationChanged",
            "onDestinationArrived",
            "onDestinationInfo",
            "onRecentDestinationInfo" -> "DESTINATION"

            "onWaypointChanged",
            "onWayPointArrived",
            "onWayPointInfo" -> "WAYPOINT"

            "onCurrentLocationInfo" -> "LOCATION"

            "onTBTInfo" -> "TBT"

            "onDrivingInfo",
            "onTripInfo",
            "onNavigationStatus" -> "DRIVING"

            "onCameraAlert" -> "CAMERA"

            "onCautionPointInfo" -> "CAUTION"

            "onBookmarkInfo",
            "onBookMarkChanged" -> "BOOKMARK"

            "onChargerOperatorInfo" -> "CHARGING"

            "onError" -> "ERROR"

            else -> "OTHER"
        }
    }

    // ==========================================
    // 10. 전체 정보 조회
    // ==========================================

    @Synchronized
    fun requestAllInformation() {
        if (!started || !connected) {
            Log.w(
                TAG,
                "[REQUEST] SDK 미연결 - 정보 조회 불가"
            )

            Log.w(
                TRAFFIC_TAG,
                "[REQUEST_SKIPPED] SDK 미연결"
            )
            return
        }

        val helper = naviHelper

        if (helper == null) {
            Log.w(TAG, "[REQUEST] NaviHelper 객체 없음")
            return
        }

        Log.i(TAG, "================================")
        Log.i(TAG, "[REQUEST] 전체 정보 조회 시작")
        Log.i(TAG, "================================")

        for (methodName in INFO_REQUESTS) {
            requestInformation(helper, methodName)
        }

        Log.i(
            TAG,
            "[REQUEST] 전체 정보 조회 요청 완료"
        )
    }

    // ==========================================
    // 11. 교통 상태만 직접 재조회
    // ==========================================

    @Synchronized
    fun requestTrafficInformation() {
        if (!started || !connected) {
            Log.w(
                TRAFFIC_TAG,
                "[TRAFFIC_REQUEST_SKIPPED] SDK 미연결"
            )
            return
        }

        val helper = naviHelper

        if (helper == null) {
            Log.w(
                TRAFFIC_TAG,
                "[TRAFFIC_REQUEST_SKIPPED] helper 없음"
            )
            return
        }

        Log.i(
            TRAFFIC_TAG,
            "[TRAFFIC_REQUEST_MANUAL] 경로 상태 재조회"
        )

        requestInformation(helper, "getRouteStateInfo")
    }

    // ==========================================
    // 12. 개별 조회 API 실행
    // ==========================================

    private fun requestInformation(
        helper: NaviHelper,
        methodName: String
    ) {
        val trafficRequest =
            methodName == "getRouteStateInfo"

        try {
            val method = helper.javaClass.methods.firstOrNull {
                it.name == methodName &&
                        it.parameterTypes.isEmpty() &&
                        Modifier.isPublic(it.modifiers)
            }

            if (method == null) {
                Log.w(
                    TAG,
                    "[UNSUPPORTED] $methodName - 인자 없는 API 없음"
                )

                if (trafficRequest) {
                    Log.w(
                        TRAFFIC_TAG,
                        "[TRAFFIC_API_UNSUPPORTED] " +
                                "getRouteStateInfo() 없음"
                    )
                }

                return
            }

            Log.i(TAG, "[REQUEST] $methodName 호출")

            if (trafficRequest) {
                Log.i(
                    TRAFFIC_TAG,
                    "[TRAFFIC_REQUEST_SENT] " +
                            "getRouteStateInfo() 호출"
                )
            }

            val result = method.invoke(helper)

            Log.i(
                TAG,
                "[REQUEST_OK] $methodName 요청 반환"
            )

            if (trafficRequest) {
                Log.i(
                    TRAFFIC_TAG,
                    "[TRAFFIC_REQUEST_RETURNED] " +
                            "메서드 호출 반환, 콜백 수신은 별도 확인"
                )
            }

            if (result != null && result !is Unit) {
                val value = serializeValue(
                    result,
                    0,
                    newVisitedSet()
                )

                logLongMessage(
                    Log.INFO,
                    "[RETURN] $methodName = $value"
                )

                if (trafficRequest) {
                    logTrafficMessage(
                        Log.INFO,
                        "[TRAFFIC_DIRECT_RETURN] $value"
                    )

                    // 직접 반환 객체에 trafficStatus가 있다면 분석
                    val hasTrafficGetter =
                        result.javaClass.methods.any {
                            it.name == "getTrafficStatus" &&
                                    it.parameterTypes.isEmpty()
                        }

                    if (hasTrafficGetter) {
                        logTrafficStatus(result)
                    }
                }
            } else if (trafficRequest) {
                Log.i(
                    TRAFFIC_TAG,
                    "[TRAFFIC_NO_DIRECT_DATA] " +
                            "직접 반환 데이터 없음, 콜백 대기"
                )
            }

        } catch (error: Exception) {
            val actualError =
                if (
                    error is java.lang.reflect.InvocationTargetException
                ) {
                    error.targetException ?: error
                } else {
                    error
                }

            Log.e(
                TAG,
                "[REQUEST_ERROR] $methodName",
                actualError
            )

            if (trafficRequest) {
                Log.e(
                    TRAFFIC_TAG,
                    "[TRAFFIC_REQUEST_ERROR]",
                    actualError
                )
            }
        }
    }

    // ==========================================
    // 13. SDK 메서드 목록
    // ==========================================

    private fun inspectSdkMethods(helper: NaviHelper) {
        Log.i(
            TAG,
            "[SDK_METHODS] NaviHelper 메서드 목록 시작"
        )

        val methods = helper.javaClass.methods
            .filter {
                Modifier.isPublic(it.modifiers)
            }
            .filter {
                it.declaringClass != Any::class.java &&
                        it.declaringClass != Object::class.java
            }
            .sortedBy {
                it.name
            }

        for (method in methods) {
            val params = method.parameterTypes.joinToString(",") {
                it.simpleName
            }

            Log.i(
                TAG,
                "[SDK_METHOD] ${method.name}($params)"
            )

            if (method.name == "getRouteStateInfo") {
                Log.i(
                    TRAFFIC_TAG,
                    "[SDK_METHOD_FOUND] " +
                            "${method.name}($params)"
                )
            }
        }

        Log.i(TAG, "[SDK_METHODS] 목록 출력 완료")
    }

    // ==========================================
    // 14. 리스너 콜백 목록
    // ==========================================

    private fun inspectListenerMethods() {
        Log.i(TAG, "[CALLBACKS] 리스너 콜백 목록 시작")

        val methods = NaviHelperEventListener::class.java.methods
            .filter {
                it.name != IGNORED_EVENT
            }
            .sortedBy {
                it.name
            }

        for (method in methods) {
            val params = method.parameterTypes.joinToString(",") {
                it.simpleName
            }

            Log.i(
                TAG,
                "[CALLBACK] ${method.name}($params)"
            )

            if (method.name == "onRouteStateInfo") {
                Log.i(
                    TRAFFIC_TAG,
                    "[SDK_CALLBACK_FOUND] " +
                            "${method.name}($params)"
                )
            }
        }

        Log.i(TAG, "[CALLBACKS] 리스너 콜백 목록 완료")
    }

    // ==========================================
    // 15. SDK 데이터 JSON 직렬화
    // 모든 기본 데이터는 String
    // ==========================================

    private fun serializeValue(
        value: Any?,
        depth: Int,
        visited: MutableSet<Any>
    ): Any {

        if (value == null) {
            return JSONObject.NULL
        }

        if (depth >= MAX_DEPTH) {
            return value.toString()
        }

        if (
            value is String ||
            value is Number ||
            value is Boolean ||
            value is Char
        ) {
            return value.toString()
        }

        if (value is Enum<*>) {
            return value.name
        }

        // Map 처리
        if (value is Map<*, *>) {
            if (!visited.add(value)) {
                return "[Circular Reference]"
            }

            try {
                val json = JSONObject()
                var count = 0

                for ((key, item) in value) {
                    if (count >= MAX_ITEMS) {
                        json.put(
                            "_truncated",
                            "Maximum items reached"
                        )
                        break
                    }

                    json.put(
                        key.toString(),
                        serializeValue(
                            item,
                            depth + 1,
                            visited
                        )
                    )

                    count += 1
                }

                return json
            } finally {
                visited.remove(value)
            }
        }

        // List 및 Iterable 처리
        if (value is Iterable<*>) {
            if (!visited.add(value)) {
                return "[Circular Reference]"
            }

            try {
                val array = JSONArray()
                var count = 0

                for (item in value) {
                    if (count >= MAX_ITEMS) {
                        array.put("[Truncated]")
                        break
                    }

                    array.put(
                        serializeValue(
                            item,
                            depth + 1,
                            visited
                        )
                    )

                    count += 1
                }

                return array
            } finally {
                visited.remove(value)
            }
        }

        // 배열 처리
        if (value.javaClass.isArray) {
            if (!visited.add(value)) {
                return "[Circular Reference]"
            }

            try {
                val array = JSONArray()

                val length =
                    java.lang.reflect.Array.getLength(value)

                val limit = minOf(length, MAX_ITEMS)

                for (index in 0 until limit) {
                    val item = java.lang.reflect.Array.get(
                        value,
                        index
                    )

                    array.put(
                        serializeValue(
                            item,
                            depth + 1,
                            visited
                        )
                    )
                }

                if (length > MAX_ITEMS) {
                    array.put("[Truncated]")
                }

                return array
            } finally {
                visited.remove(value)
            }
        }

        // NaviHelper SDK 객체만 리플렉션
        val className = value.javaClass.name

        if (!className.startsWith("ai.pleos.playground.navi.")) {
            return value.toString()
        }

        if (!visited.add(value)) {
            return "[Circular Reference]"
        }

        try {
            val json = JSONObject()
            json.put("_type", className)

            val getters = value.javaClass.methods
                .filter {
                    it.parameterTypes.isEmpty()
                }
                .filter {
                    Modifier.isPublic(it.modifiers)
                }
                .filter {
                    !Modifier.isStatic(it.modifiers)
                }
                .filter {
                    it.name != "getClass"
                }
                .filter {
                    it.name.startsWith("get") ||
                            it.name.startsWith("is")
                }
                .sortedBy {
                    it.name
                }

            for (getter in getters) {
                val propertyName =
                    if (getter.name.startsWith("get")) {
                        getter.name.removePrefix("get")
                    } else {
                        getter.name.removePrefix("is")
                    }

                if (propertyName.isEmpty()) {
                    continue
                }

                val normalizedName =
                    propertyName.replaceFirstChar {
                        it.lowercase()
                    }

                try {
                    val propertyValue = getter.invoke(value)

                    json.put(
                        normalizedName,
                        serializeValue(
                            propertyValue,
                            depth + 1,
                            visited
                        )
                    )

                } catch (error: Exception) {
                    json.put(
                        normalizedName,
                        "[Read Error: ${error.javaClass.simpleName}]"
                    )
                }
            }

            if (getters.isEmpty()) {
                json.put("_value", value.toString())
            }

            return json

        } finally {
            visited.remove(value)
        }
    }

    // ==========================================
    // 16. Proxy 반환값
    // ==========================================

    private fun defaultReturnValue(type: Class<*>): Any? {
        if (
            type == Void.TYPE ||
            type == Void::class.java
        ) {
            return null
        }

        if (type == Boolean::class.javaPrimitiveType) {
            return false
        }

        if (type == Byte::class.javaPrimitiveType) {
            return 0.toByte()
        }

        if (type == Short::class.javaPrimitiveType) {
            return 0.toShort()
        }

        if (type == Int::class.javaPrimitiveType) {
            return 0
        }

        if (type == Long::class.javaPrimitiveType) {
            return 0L
        }

        if (type == Float::class.javaPrimitiveType) {
            return 0f
        }

        if (type == Double::class.javaPrimitiveType) {
            return 0.0
        }

        if (type == Char::class.javaPrimitiveType) {
            return '\u0000'
        }

        if (type == Unit::class.java) {
            return Unit
        }

        return null
    }

    // ==========================================
    // 17. 긴 로그 분할 출력
    // ==========================================

    private fun logLongMessage(
        priority: Int,
        message: String
    ) {
        if (message.length <= LOG_CHUNK_SIZE) {
            Log.println(priority, TAG, message)
            return
        }

        val totalChunks =
            (message.length + LOG_CHUNK_SIZE - 1) /
                    LOG_CHUNK_SIZE

        var chunkIndex = 0
        var offset = 0

        while (offset < message.length) {
            val end = minOf(
                offset + LOG_CHUNK_SIZE,
                message.length
            )

            val chunk = message.substring(offset, end)

            Log.println(
                priority,
                TAG,
                "[CHUNK ${chunkIndex + 1}/$totalChunks] $chunk"
            )

            offset = end
            chunkIndex += 1
        }
    }

    // ==========================================
    // 18. SDK 중지 및 해제
    // ==========================================

    @Synchronized
    fun stop() {
        if (!started && naviHelper == null) {
            return
        }

        Log.i(TAG, "[STOP] NaviHelper 수집 중지")

        Log.i(
            TRAFFIC_TAG,
            "[STOP] 교통 정보 모니터 중지"
        )

        started = false
        connected = false
        generation += 1

        val helper = naviHelper
        val listener = naviListener

        naviHelper = null
        naviListener = null

        if (helper != null) {
            if (listener != null) {
                try {
                    helper.removeListener(listener)

                    Log.i(
                        TAG,
                        "[STOP] 리스너 해제 완료"
                    )

                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "[STOP_ERROR] 리스너 해제 실패",
                        error
                    )
                }
            }

            try {
                helper.release()

                Log.i(
                    TAG,
                    "[해제완료] NaviHelper.release()"
                )

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "[STOP_ERROR] SDK 해제 실패",
                    error
                )
            }
        }
    }
}
