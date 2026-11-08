package com.example.gleo_type

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

import ai.pleos.playground.vehicle.Vehicle
import com.example.gleo_type.event.location.LocationEventSource
import com.example.gleo_type.event.navigation.NaviEventSource
import com.example.gleo_type.event.vehicle.VehicleEventSource
import com.example.gleo_type.location.FusedLocationMonitor
import com.example.gleo_type.music.MusicTestActivity
import com.example.gleo_type.navi.NaviMonitor
import com.example.gleo_type.vehicle.VehicleMonitor
import com.example.gleo_type.vehicle.AAOSVehicleMonitor
import com.example.gleo_type.vehicle.VehicleEvent
import com.example.gleo_type.vehicle.VehicleEventSender

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VehicleDTO"
        private const val EVENT_TAG = "GleoEvent"
        private const val NAVI_TAG = "GleoNavi"
        private const val FUSED_TAG = "GleoFused"
        private const val MUSIC_TAG = "GleoMusic"

        private const val PERMISSION_REQUEST_CODE = 901

        private const val SPEED_PERMISSION =
            "android.car.permission.CAR_SPEED"

        private const val PEDAL_PERMISSION =
            "android.car.permission.READ_CAR_PEDALS"

        private const val FUSED_PERMISSION =
            "pleos.car.permission.FUSED_LOCATION"

        private val REQUIRED_PERMISSIONS = arrayOf(
            SPEED_PERMISSION,
            PEDAL_PERMISSION,
            FUSED_PERMISSION
        )
    }

    // ==========================================
    // FusedLocation SDK
    // ==========================================

    private lateinit var fusedMonitor: FusedLocationMonitor
    private var fusedStarted = false

    // ==========================================
    // NaviHelper SDK
    // ==========================================

    private lateinit var naviMonitor: NaviMonitor
    private var naviStarted = false

    // ==========================================
    // Vehicle SDK 및 AAOS
    // ==========================================

    private lateinit var vehicleMonitor: VehicleMonitor
    private lateinit var aaosMonitor: AAOSVehicleMonitor
    private lateinit var eventSender: VehicleEventSender
    private lateinit var statusText: TextView

    // ==========================================
    // 이벤트 파이프라인 (SDK → Mapper → DTO → EventSender)
    // ==========================================

    private lateinit var vehicleEventSource: VehicleEventSource
    private lateinit var naviEventSource: NaviEventSource
    private lateinit var locationEventSource: LocationEventSource

    private var pleosStarted = false

    // Pleos Vehicle SDK 등록은 동기 binder 호출이 많아(리스너당 capability 조회 등)
    // 메인 스레드에서 하면 수십 초~수 분 UI가 멈춘다 → 전용 스레드에서 시작/중지
    private val vehicleSdkThread = HandlerThread("VehicleSdkWorker").apply { start() }
    private val vehicleSdkHandler = Handler(vehicleSdkThread.looper)
    private var aaosStarted = false
    private var activityVisible = false

    // ==========================================
    // Activity 생성
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "[LIFECYCLE] onCreate")
        Log.i(NAVI_TAG, "[LIFECYCLE] MainActivity onCreate 진입")

        // 1. 분석 서버 전송기

        eventSender = VehicleEventSender(
            applicationContext,
            BuildConfig.VEHICLE_EVENT_ENDPOINT
        )

        eventSender.start()

        Log.i(TAG, "[SERVER] VehicleEventSender 시작 완료")

        // 1-1. 이벤트 파이프라인

        val container = (application as GleoApplication).container

        vehicleEventSource = VehicleEventSource(
            { Vehicle(applicationContext) },
            container.eventFactory,
            container.musicState,
            container.eventPublisher
        )

        naviEventSource = NaviEventSource(
            container.eventFactory,
            container.musicState,
            container.eventPublisher,
            requester = { methodName -> naviMonitor.request(methodName) }
        )

        locationEventSource = LocationEventSource(
            container.eventFactory,
            container.musicState,
            container.eventPublisher
        )

        // 2. NaviHelper SDK

        Log.i(NAVI_TAG, "[INIT] NaviMonitor 객체 생성 시작")

        naviMonitor = NaviMonitor(applicationContext, naviEventSource)

        Log.i(NAVI_TAG, "[INIT] NaviMonitor 객체 생성 완료")

        // 3. Pleos Vehicle SDK

        vehicleMonitor = VehicleMonitor(
            applicationContext,
            eventSender
        )

        // 4. AAOS 차량 데이터 수집

        aaosMonitor = AAOSVehicleMonitor(
            context = applicationContext,
            onEvent = { dto ->

                val json = dto.toJson()

                Log.d(TAG, "[AAOS] $json")

                // 차량 속도(m/s) → SPEED_CHANGED
                if (dto.apiName == "PERF_VEHICLE_SPEED") {
                    val speed = dto.values.getOrNull(1)?.value as? Number
                    vehicleEventSource.onAaosSpeed(speed?.toFloat())
                }

                val event = VehicleEvent(
                    source = "AAOS",
                    domain = "AAOS",
                    eventType = "PROPERTY_CHANGED",
                    value = json.toString()
                )

                eventSender.emit(event)
            }
        )

        // 5. Pleos FusedLocation SDK

        Log.i(
            FUSED_TAG,
            "[INIT] FusedLocationMonitor 객체 생성 시작"
        )

        fusedMonitor = FusedLocationMonitor(
            applicationContext,
            onLocation = locationEventSource::onLocation
        )

        Log.i(
            FUSED_TAG,
            "[INIT] FusedLocationMonitor 객체 생성 완료"
        )

        // 6. UI 생성

        createScreen()

        // 7. 권한 요청

        requestRequiredPermissions()

        Log.i(
            NAVI_TAG,
            "[LIFECYCLE] MainActivity onCreate 완료"
        )
    }

    // ==========================================
    // 화면 생성
    // ==========================================

    private fun createScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "gleo-type Vehicle & Navi Monitor"
            textSize = 23f
        }

        statusText = TextView(this).apply {
            text = "차량, 내비게이션, 위치 모니터 초기화 완료"
            textSize = 15f
            setPadding(0, 24, 0, 24)
        }

        // 차량 및 내비게이션 수집 시작

        val startButton = Button(this).apply {
            text = "차량 및 내비게이션 수집 시작"

            setOnClickListener {

                Log.i(
                    NAVI_TAG,
                    "[BUTTON] 수집 시작 버튼 클릭"
                )

                startAllMonitoring()
            }
        }

        // 차량 및 내비게이션 수집 중지

        val stopButton = Button(this).apply {
            text = "차량 및 내비게이션 수집 중지"

            setOnClickListener {

                Log.i(
                    NAVI_TAG,
                    "[BUTTON] 수집 중지 버튼 클릭"
                )

                stopAllMonitoring()
            }
        }

        // 분석 서버 전송 테스트

        val testButton = Button(this).apply {
            text = "분석 서버 전송 테스트"

            setOnClickListener {

                val event = VehicleEvent(
                    source = "MANUAL_TEST",
                    domain = "TEST",
                    eventType = "BUTTON_CLICK",
                    value = "true"
                )

                eventSender.emit(event)

                statusText.text =
                    "테스트 이벤트 전송 큐 등록 요청 완료"

                Log.i(
                    EVENT_TAG,
                    "[TEST] 분석 서버 전송 테스트"
                )
            }
        }

        // ==========================================
        // 신규 추가: 음악 재생 테스트 버튼
        // ==========================================

        val musicButton = Button(this).apply {
            text = "음악 재생 테스트"

            setOnClickListener {

                Log.i(
                    MUSIC_TAG,
                    "[BUTTON] 음악 재생 테스트 화면 이동"
                )

                val intent = Intent(
                    this@MainActivity,
                    MusicTestActivity::class.java
                )

                startActivity(intent)
            }
        }

        // 화면에 버튼 등록

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(testButton)
        layout.addView(musicButton)

        val scrollView = ScrollView(this)
        scrollView.addView(layout)

        setContentView(scrollView)
    }

    // ==========================================
    // 권한 요청
    // ==========================================

    private fun requestRequiredPermissions() {

        val missingPermissions = REQUIRED_PERMISSIONS.filter {

            checkSelfPermission(it) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {

            Log.i(
                TAG,
                "[PERMISSION] 요청 권한 모두 허용됨"
            )

            return
        }

        Log.w(
            TAG,
            "[PERMISSION] 미허용 권한: $missingPermissions"
        )

        requestPermissions(
            missingPermissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    // ==========================================
    // 권한 확인
    // ==========================================

    private fun hasPermission(
        permission: String
    ): Boolean {

        return checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun hasSpeedPermission(): Boolean {
        return hasPermission(SPEED_PERMISSION)
    }

    private fun hasPedalPermission(): Boolean {
        return hasPermission(PEDAL_PERMISSION)
    }

    private fun hasFusedPermission(): Boolean {
        return hasPermission(FUSED_PERMISSION)
    }

    // ==========================================
    // 전체 모니터링 시작
    // ==========================================

    private fun startAllMonitoring() {

        Log.i(
            NAVI_TAG,
            "[TEST] startAllMonitoring 진입"
        )

        // 1. NaviHelper SDK

        if (!naviStarted) {

            try {

                Log.i(
                    NAVI_TAG,
                    "[TEST] NaviMonitor.start() 호출 직전"
                )

                naviMonitor.start()
                naviStarted = true

                Log.i(
                    NAVI_TAG,
                    "[NAVI] 초기화 요청 완료 - 연결 결과 대기"
                )

            } catch (e: Exception) {

                naviStarted = false

                Log.e(
                    NAVI_TAG,
                    "[NAVI] 초기화 실패",
                    e
                )
            }

        } else {

            Log.i(
                NAVI_TAG,
                "[NAVI] 이미 시작됨"
            )
        }

        // 2. AAOS 차량 데이터

        if (!aaosStarted) {

            Log.i(
                TAG,
                "[AAOS] 속도 권한: ${hasSpeedPermission()}"
            )

            Log.i(
                TAG,
                "[AAOS] 페달 권한: ${hasPedalPermission()}"
            )

            if (!hasPedalPermission()) {

                Log.w(
                    TAG,
                    "[AAOS] 페달 권한 없음 - 속도 구독은 독립적으로 시도"
                )
            }

            try {

                aaosMonitor.startMonitoring()
                aaosStarted = true

                Log.i(
                    TAG,
                    "[AAOS] startMonitoring 호출 완료"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "[AAOS] 시작 실패",
                    e
                )
            }
        }

        // 3. Pleos Vehicle SDK

        if (!pleosStarted) {

            pleosStarted = true

            vehicleSdkHandler.post {

                val begin = SystemClock.elapsedRealtime()

                try {

                    // 이벤트 파이프라인(리스너 19개)을 먼저 시작하고,
                    // 전체 API를 리플렉션으로 훑는 진단용 모니터는 그 뒤에 시작
                    vehicleEventSource.start()

                    Log.i(
                        TAG,
                        "[PLEOS] VehicleEventSource 시작 " +
                                "${SystemClock.elapsedRealtime() - begin}ms"
                    )

                    vehicleMonitor.start()

                    Log.i(
                        TAG,
                        "[PLEOS] VehicleMonitor 시작 " +
                                "${SystemClock.elapsedRealtime() - begin}ms"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "[PLEOS] 시작 실패",
                        e
                    )

                    runOnUiThread {
                        pleosStarted = false
                        updateStatus()
                    }
                }
            }
        }

        // 4. Pleos FusedLocation SDK

        if (!fusedStarted) {

            val permissionGranted = hasFusedPermission()

            Log.i(
                FUSED_TAG,
                "[PERMISSION] FusedLocation 권한: $permissionGranted"
            )

            if (permissionGranted) {

                try {

                    Log.i(
                        FUSED_TAG,
                        "[START] FusedLocationMonitor.start() 호출"
                    )

                    fusedMonitor.start()
                    fusedStarted = true

                    Log.i(
                        FUSED_TAG,
                        "[START] FusedLocation 시작 요청 완료"
                    )

                } catch (e: Exception) {

                    fusedStarted = false

                    Log.e(
                        FUSED_TAG,
                        "[START_FAILED] FusedLocation 시작 실패",
                        e
                    )
                }

            } else {

                Log.w(
                    FUSED_TAG,
                    "[PERMISSION_DENIED] FusedLocation 시작 보류"
                )
            }
        }

        // 5. 상태 표시

        updateStatus()

        Log.i(
            NAVI_TAG,
            "[TEST] startAllMonitoring 처리 완료"
        )
    }

    // ==========================================
    // 전체 모니터링 중지
    // ==========================================

    private fun stopAllMonitoring() {

        Log.i(
            TAG,
            "[MONITOR] 수집 중지 요청"
        )

        // 1. NaviHelper SDK

        if (naviStarted) {

            try {

                naviMonitor.stop()

                Log.i(
                    NAVI_TAG,
                    "[NAVI] 리소스 해제 완료"
                )

            } catch (e: Exception) {

                Log.e(
                    NAVI_TAG,
                    "[NAVI] 해제 실패",
                    e
                )

            } finally {

                naviStarted = false
            }
        }

        // 2. AAOS

        if (aaosStarted) {

            try {

                aaosMonitor.stopMonitoring()

                Log.i(
                    TAG,
                    "[AAOS] stopMonitoring 호출"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "[AAOS] 중지 실패",
                    e
                )

            } finally {

                aaosStarted = false
            }
        }

        // 3. Pleos Vehicle SDK

        if (pleosStarted) {

            pleosStarted = false

            // 시작 작업과 같은 스레드에서 순서대로 실행 → 시작 중이면 끝난 뒤 중지
            vehicleSdkHandler.post {

                try {

                    vehicleEventSource.stop()
                    vehicleMonitor.stop()

                    Log.i(
                        TAG,
                        "[PLEOS] VehicleMonitor 중지"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "[PLEOS] 중지 실패",
                        e
                    )
                }
            }
        }

        // 4. Pleos FusedLocation SDK

        if (fusedStarted) {

            try {

                Log.i(
                    FUSED_TAG,
                    "[STOP] FusedLocationMonitor.stop() 호출"
                )

                fusedMonitor.stop()

                Log.i(
                    FUSED_TAG,
                    "[STOP] FusedLocation 리소스 해제 완료"
                )

            } catch (e: Exception) {

                Log.e(
                    FUSED_TAG,
                    "[STOP_FAILED] FusedLocation 중지 실패",
                    e
                )

            } finally {

                fusedStarted = false
            }
        }

        updateStatus()
    }

    // ==========================================
    // 상태 표시
    // ==========================================

    private fun updateStatus() {

        if (!::statusText.isInitialized) {
            return
        }

        statusText.text =
            "NaviHelper 시작 요청: $naviStarted\n" +
                    "Pleos Vehicle 시작: $pleosStarted\n" +
                    "AAOS 시작 요청: $aaosStarted\n" +
                    "FusedLocation 시작 요청: $fusedStarted\n" +
                    "속도 권한: ${hasSpeedPermission()}\n" +
                    "페달 권한: ${hasPedalPermission()}\n" +
                    "FusedLocation 권한: ${hasFusedPermission()}\n" +
                    "분석 서버 전송기: 초기화 완료"
    }

    // ==========================================
    // Activity Lifecycle
    // ==========================================

    override fun onStart() {

        super.onStart()

        activityVisible = true

        Log.i(
            TAG,
            "[LIFECYCLE] onStart"
        )

        Log.i(
            NAVI_TAG,
            "[LIFECYCLE] onStart - 모니터링 자동 시작"
        )

        startAllMonitoring()
    }

    override fun onStop() {

        activityVisible = false

        // 기존 SDK 구독 유지

        Log.i(
            TAG,
            "[LIFECYCLE] onStop - 구독 유지"
        )

        Log.i(
            NAVI_TAG,
            "[LIFECYCLE] onStop - NaviHelper 유지"
        )

        Log.i(
            FUSED_TAG,
            "[LIFECYCLE] onStop - FusedLocation 유지"
        )

        super.onStop()
    }

    override fun onDestroy() {

        Log.i(
            TAG,
            "[LIFECYCLE] onDestroy"
        )

        Log.i(
            NAVI_TAG,
            "[LIFECYCLE] onDestroy"
        )

        stopAllMonitoring()

        // 대기 중인 중지 작업까지 처리한 뒤 스레드 종료
        vehicleSdkThread.quitSafely()

        aaosMonitor.release()

        eventSender.stop()

        super.onDestroy()
    }

    // ==========================================
    // 권한 요청 결과
    // ==========================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != PERMISSION_REQUEST_CODE) {
            return
        }

        for (i in permissions.indices) {

            val granted =
                grantResults.getOrNull(i) ==
                        PackageManager.PERMISSION_GRANTED

            val result = if (granted) {
                "허용"
            } else {
                "거부/미지원"
            }

            Log.i(
                TAG,
                "권한 ${permissions[i]}: $result"
            )
        }

        // 가능한 SDK 수집 계속 시도

        if (activityVisible) {

            Log.i(
                NAVI_TAG,
                "[PERMISSION] 권한 요청 완료 - 모니터링 재확인"
            )

            startAllMonitoring()
        }
    }
}
