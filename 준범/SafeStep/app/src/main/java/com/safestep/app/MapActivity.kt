package com.safestep.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.safestep.app.detect.Detection
import com.safestep.app.detect.ObjectDetector
import com.safestep.app.detect.RemoteDetector
import com.safestep.app.detect.SegmentationClient
import com.safestep.app.detect.SignalClient
import com.safestep.app.navigation.NavigationGuide
import com.safestep.app.navigation.PoiResult
import com.safestep.app.navigation.RouteResult
import com.safestep.app.navigation.TmapService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MapActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var mapView: MapView
    private lateinit var previewView: PreviewView
    private lateinit var bboxOverlay: BoundingBoxOverlay
    private lateinit var segmentOverlay: ImageView
    private lateinit var destinationText: TextView
    private lateinit var destMicButton: Button
    private lateinit var warningBanner: LinearLayout
    private lateinit var warningText: TextView
    private lateinit var navArrow: TextView
    private lateinit var navInstruction: TextView
    private lateinit var navDistance: TextView
    private lateinit var navStepCount: TextView
    private lateinit var backToModeButton: Button

    // ── Core ──────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var segExecutor: ExecutorService    // 세그멘테이션 전용 스레드
    private lateinit var signalExecutor: ExecutorService // 신호등 전용 스레드
    private var vibrator: Vibrator? = null
    private lateinit var detector: ObjectDetector
    private lateinit var segClient: SegmentationClient
    private lateinit var signalClient: SignalClient

    // ── Location ──────────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null

    // ── Compass (Heading-up) ──────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    @Volatile private var currentAzimuth = 0f
    private val rotMatrix  = FloatArray(9)
    private val orientVals = FloatArray(3)

    // ── Map overlays ──────────────────────────────────────────────────────────
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var routePolyline: Polyline? = null
    private var destMarker: Marker? = null

    // ── Navigation ────────────────────────────────────────────────────────────
    private lateinit var navGuide: NavigationGuide
    private var totalSteps = 0
    /** 전체 경로 포인트 (잔여 경로 업데이트 + 이탈 감지용) */
    private var allPathPoints: List<org.osmdroid.util.GeoPoint> = emptyList()
    /** 재탐색용 목적지 저장 */
    private var currentDest: PoiResult? = null
    /** 경로 이탈 경고 마지막 시각 */
    @Volatile private var lastOffRouteWarnMs = 0L

    // ── Speech ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Segmentation ──────────────────────────────────────────────────────────
    @Volatile private var frameCount = 0
    @Volatile private var lastSurfaceStatus = ""
    @Volatile private var lastSurfaceSpeakMs = 0L

    // ── Signal (신호등) ────────────────────────────────────────────────────────
    @Volatile private var lastTrafficLightStatus: String? = "init"  // 최초 발화 방지

    // ── Surface zones (3구역 회피 방향) ──────────────────────────────────────
    @Volatile private var lastZones: Map<String, String> = emptyMap()

    // ── Area-growth 접근 속도 (서버 depth 없을 때 fallback) ───────────────
    // label → deque of (area, elapsedMs)
    private val areaHistory = mutableMapOf<String, ArrayDeque<Pair<Float, Long>>>()

    // ── Detection ─────────────────────────────────────────────────────────────
    @Volatile private var lastSpoken = ""
    @Volatile private var lastSpeakMs = 0L
    /** 보행 모드: 앱 시작부터 장애물 TTS 활성화 (목적지 없어도 경고) */
    @Volatile private var detectionTtsEnabled = true
    /** 가속도계 기반 이동 여부 — false 이면 배터리 절약 모드 */
    @Volatile private var isMoving = true
    private var stationaryCount = 0
    private var linearAccelSensor: Sensor? = null

    companion object {
        private const val TAG               = "MapActivity"
        private const val REQ_PERM          = 200
        private const val DANGER_AREA       = 0.20f
        private const val VERY_CLOSE_AREA   = 0.40f
        private const val SPEAK_COOLDOWN_MS = 2000L

        // 서버 depth 기반 접근 속도 임계값 (m/s)
        private val GROUP_SPEED_THRESH = mapOf(
            "vehicle" to 0.3f, "micro" to 1.2f, "person" to 2.0f
        )
        // depth fallback 임계값 (m)
        private val GROUP_DEPTH_THRESH = mapOf(
            "vehicle" to 4.0f, "micro" to 2.5f, "person" to 2.0f
        )
        // 클라이언트 면적 증가율 임계값 (화면 비율/초)
        private val AREA_GROW_THRESH = mapOf(
            "vehicle" to 0.04f, "micro" to 0.03f, "person" to 0.02f
        )

        private val PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", 0))
        setContentView(R.layout.activity_map)

        mapView         = findViewById(R.id.mapView)
        previewView     = findViewById(R.id.previewView)
        bboxOverlay     = findViewById(R.id.bboxOverlay)
        segmentOverlay  = findViewById(R.id.segmentOverlay)
        destinationText = findViewById(R.id.destinationText)
        destMicButton   = findViewById(R.id.destMicButton)
        warningBanner   = findViewById(R.id.warningBanner)
        warningText     = findViewById(R.id.warningText)
        navArrow        = findViewById(R.id.navArrow)
        navInstruction  = findViewById(R.id.navInstruction)
        navDistance     = findViewById(R.id.navDistance)
        navStepCount    = findViewById(R.id.navStepCount)
        backToModeButton = findViewById(R.id.backToModeButton)

        backToModeButton.setOnClickListener {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }

        tts            = TextToSpeech(this, this)
        @Suppress("DEPRECATION")
        vibrator       = getSystemService(VIBRATOR_SERVICE) as Vibrator
        cameraExecutor = Executors.newSingleThreadExecutor()
        segExecutor    = Executors.newSingleThreadExecutor()
        signalExecutor = Executors.newSingleThreadExecutor()
        detector       = ObjectDetector.create(this)
        segClient      = SegmentationClient(RemoteDetector.SERVER_URL)
        signalClient   = SignalClient(RemoteDetector.SERVER_URL)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 나침반 + 이동 감지 센서
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        navGuide = NavigationGuide(tts).apply {
            onStepChanged = { step ->
                runOnUiThread { updateNavUI(step.description, step.distance) }
            }
            onArrived = { runOnUiThread { onArrived() } }
        }

        setupMap()
        setupMicButton()
        buildLocationCallback()

        if (hasAllPermissions()) startAll()
        else ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERM)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(compassListener)
        sensorManager.unregisterListener(motionListener)
    }

    override fun onDestroy() {
        mapView.onDetach()
        cameraExecutor.shutdown()
        segExecutor.shutdown()
        signalExecutor.shutdown()
        tts.shutdown()
        speechRecognizer?.destroy()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
        runCatching { detector.close() }
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 나침반 → Heading-up 지도 회전
    // ══════════════════════════════════════════════════════════════════════════

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientVals)
            val az = Math.toDegrees(orientVals[0].toDouble()).toFloat()
            currentAzimuth = (az + 360f) % 360f
            mapView.setMapOrientation(-currentAzimuth)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** 선형 가속도 기반 이동/정지 판단 — 정지 시 detection 주기 줄여 배터리 절약 */
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val mag = Math.sqrt(
                (event.values[0] * event.values[0] +
                 event.values[1] * event.values[1] +
                 event.values[2] * event.values[2]).toDouble()
            ).toFloat()
            if (mag > 0.6f) {          // 움직임 감지
                stationaryCount = 0
                isMoving = true
            } else if (++stationaryCount > 20) {  // 20샘플 연속 정지 (~2초)
                isMoving = false
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private fun hasAllPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && hasAllPermissions()) startAll()
    }

    private fun startAll() {
        startLocationUpdates()
        startCamera()
        setupSpeechRecognizer()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Map
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)

        // 내 위치 표시 (자동 팔로우는 위치 콜백에서 직접 처리)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView).apply {
            enableMyLocation()
        }
        mapView.overlays.add(myLocationOverlay)
    }

    private fun showRouteOnMap(result: RouteResult, dest: PoiResult) {
        allPathPoints = result.pathPoints   // 전체 경로 저장 (잔여선 + 이탈 감지)

        routePolyline?.let { mapView.overlays.remove(it) }
        destMarker?.let    { mapView.overlays.remove(it) }

        routePolyline = Polyline(mapView).apply {
            setPoints(result.pathPoints)
            outlinePaint.color       = Color.parseColor("#F97316")
            outlinePaint.strokeWidth = 10f
        }
        mapView.overlays.add(routePolyline)

        destMarker = Marker(mapView).apply {
            position = GeoPoint(dest.lat, dest.lon)
            title    = dest.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(destMarker)
        mapView.invalidate()
    }

    // 사용자 위치를 지도 중앙에 즉시 고정
    private fun centerMapOnUser(loc: Location) {
        mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Location
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLocation = loc
                navGuide.updateLocation(loc.latitude, loc.longitude)

                // 지도 중앙 고정
                centerMapOnUser(loc)

                if (navGuide.isRunning()) {
                    val dist = navGuide.distToCurrentStep(loc.latitude, loc.longitude)
                    val zoom = when {
                        dist <= 30  -> 20.0
                        dist <= 80  -> 19.0
                        dist <= 200 -> 18.0
                        else        -> 17.0
                    }
                    mapView.controller.setZoom(zoom)

                    // 거리 UI 실시간 갱신
                    val step = navGuide.currentStep()
                    runOnUiThread {
                        navDistance.text = formatDist(dist)
                        if (step != null) navInstruction.text = step.description
                    }

                    // 잔여 경로선 업데이트
                    updateRemainingRoute(loc.latitude, loc.longitude)

                    // 경로 이탈 감지
                    checkOffRoute(loc.latitude, loc.longitude)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(1f)
            .build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Navigation UI
    // ══════════════════════════════════════════════════════════════════════════

    private fun updateNavUI(instruction: String, distanceM: Int) {
        navInstruction.text = instruction
        navDistance.text    = formatDist(distanceM)
        navArrow.text       = directionArrow(instruction)
    }

    private fun formatDist(m: Int): String = when {
        m <= 0    -> ""
        m < 1000  -> "${m}m"
        else      -> "${"%.1f".format(m / 1000.0)}km"
    }

    private fun directionArrow(instruction: String): String = when {
        instruction.contains("좌회전")          -> "↰"
        instruction.contains("우회전")          -> "↱"
        instruction.contains("좌측")            -> "↖"
        instruction.contains("우측")            -> "↗"
        instruction.contains("유턴")            -> "↩"
        instruction.contains("목적지") ||
                instruction.contains("도착")   -> "🏁"
        instruction.contains("출발")            -> "↑"
        else                                    -> "↑"
    }

    private fun onArrived() {
        navArrow.text        = "🏁"
        navInstruction.text  = "목적지 도착"
        navDistance.text     = ""
        navStepCount.text    = ""
        destinationText.text = "목적지를 말씀해주세요"
        mapView.controller.setZoom(18.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Voice — 목적지 입력
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupMicButton() {
        destMicButton.setOnClickListener {
            if (isListening) stopListening() else startListening()
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            destMicButton.isEnabled = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(destinationRecognitionListener)
        }
    }

    private fun startListening() {
        isListening = true
        destMicButton.text = "⏹"
        tts.speak("목적지를 말씀해주세요.", TextToSpeech.QUEUE_FLUSH, null, "dest-prompt")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        isListening = false
        destMicButton.text = "🎙"
        speechRecognizer?.stopListening()
    }

    private fun handleDestinationQuery(query: String) {
        destinationText.text = "\"$query\" 검색 중…"
        // 목적지 인식 시점부터 장애물 경고 TTS 활성화
        detectionTtsEnabled = true
        tts.speak("$query 검색합니다.", TextToSpeech.QUEUE_FLUSH, null, "searching")

        TmapService.searchPoi(query) { pois ->
            if (pois.isEmpty()) {
                tts.speak("검색 결과가 없습니다. 다시 말씀해주세요.", TextToSpeech.QUEUE_FLUSH, null, "no-poi")
                destinationText.text = "목적지를 말씀해주세요"
                return@searchPoi
            }
            val dest = pois.first()
            destinationText.text = dest.name
            tts.speak("${dest.name}(으)로 경로를 탐색합니다.", TextToSpeech.QUEUE_FLUSH, null, "found")

            val loc = currentLocation
            if (loc == null) {
                tts.speak("현재 위치를 가져오는 중입니다. 잠시 후 다시 시도해주세요.",
                    TextToSpeech.QUEUE_ADD, null, "no-loc")
                return@searchPoi
            }
            fetchRoute(loc.latitude, loc.longitude, dest)
        }
    }

    private fun fetchRoute(sLat: Double, sLon: Double, dest: PoiResult) {
        currentDest = dest   // 재탐색용 저장
        TmapService.searchPedestrianRoute(sLat, sLon, dest.lat, dest.lon, dest.name) { result ->
            if (result == null || result.steps.isEmpty()) {
                tts.speak("경로를 찾을 수 없습니다.", TextToSpeech.QUEUE_FLUSH, null, "no-route")
                return@searchPedestrianRoute
            }
            totalSteps = result.steps.size
            showRouteOnMap(result, dest)
            navGuide.start(result.steps)

            // 첫 안내 UI 표시
            val first = result.steps.first()
            runOnUiThread {
                updateNavUI(first.description, first.distance)
                navStepCount.text = "1/$totalSteps"
            }

            val dist = if (result.totalDistanceM >= 1000)
                "${"%.1f".format(result.totalDistanceM / 1000.0)}km"
            else "${result.totalDistanceM}m"
            val min = result.totalTimeSec / 60

            tts.speak(
                "경로 탐색 완료. 총 $dist, 약 ${min}분 소요됩니다. ${first.description}",
                TextToSpeech.QUEUE_ADD, null, "route-ready"
            )
        }
    }

    private val destinationRecognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            isListening = false
            destMicButton.text = "🎙"
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            handleDestinationQuery(text)
        }
        override fun onError(error: Int) {
            isListening = false
            destMicButton.text = "🎙"
            tts.speak("음성 인식에 실패했습니다. 다시 눌러주세요.", TextToSpeech.QUEUE_FLUSH, null, "rec-err")
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Camera
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            frameCount++

            // 배터리 절약: 정지 중이면 3프레임에 1번만 처리
            if (!isMoving && frameCount % 3 != 0) {
                imageProxy.close()
                return
            }

            val bitmap   = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            // ① 탐지 — 메인 카메라 스레드
            val detections = detector.detect(bitmap, rotation)
            handleDetections(detections)

            // ② 세그멘테이션 — 2프레임마다, 별도 스레드 (탐지 블로킹 없음)
            if (frameCount % 2 == 0) {
                segExecutor.execute {
                    try {
                        val seg = segClient.segment(bitmap, rotation)
                        if (seg != null) handleSegmentation(seg)
                    } catch (e: Exception) { Log.w(TAG, "세그멘테이션 실패: ${e.message}") }
                }
            }

            // ③ 신호등 — 5프레임마다, 별도 스레드 (탐지 블로킹 없음)
            if (frameCount % 5 == 0) {
                signalExecutor.execute {
                    try {
                        val signal = signalClient.detect(bitmap, rotation)
                        handleTrafficLight(signal?.color)
                    } catch (e: Exception) { Log.w(TAG, "신호등 감지 실패: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 분석 실패", e)
        } finally {
            imageProxy.close()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 탐지 결과 처리
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleDetections(detections: List<Detection>) {
        bboxOverlay.updateDetections(detections)

        // 오래된 면적 히스토리 정리 (5초 이상 미감지 객체)
        val nowMs = SystemClock.elapsedRealtime()
        areaHistory.entries.removeAll { (_, h) ->
            h.isEmpty() || nowMs - h.last().second > 5_000L
        }

        // 화면 중심 가중 점수로 가장 위험한 객체 선택
        val worst = detections.maxByOrNull { d ->
            val cw = 1f - kotlin.math.abs(d.centerX() - 0.5f)
            d.area() * (0.7f + 0.3f * cw) * d.confidence
        } ?: return

        val grp        = worst.group
        val speedDepth = worst.approachSpeed          // 서버 depth 기반
        val speedArea  = areaGrowthRate(worst.label, worst.area())  // 클라이언트 면적 기반

        // ── 경고 여부 판단 ────────────────────────────────────────────────────
        val shouldWarn = when {
            grp == null ->
                worst.area() >= DANGER_AREA           // 고정 장애물: 면적 기반

            // 차량: 면적증가율 제외 — 내가 다가가는 것도 증가하므로 오경보 발생
            // 서버 접근속도 있으면 우선, 없으면 depth 2.5m 이하일 때만 경고
            grp == "vehicle" -> when {
                speedDepth != null -> speedDepth >= (GROUP_SPEED_THRESH["vehicle"] ?: 0.3f)
                worst.depthM != null -> worst.depthM!! < 2.5f
                else -> worst.area() >= 0.65f         // depth 도 없으면 화면 65% 이상
            }

            speedDepth != null ->
                speedDepth >= (GROUP_SPEED_THRESH[grp] ?: 0.3f)   // ① 서버 depth 속도

            speedArea != null ->
                speedArea >= (AREA_GROW_THRESH[grp] ?: 0.04f)     // ② 클라이언트 면적 증가율

            worst.depthM != null ->
                worst.depthM!! < (GROUP_DEPTH_THRESH[grp] ?: 3.0f) // ③ depth 거리 임계값

            grp == "person"  -> worst.area() >= 0.60f
            else             -> worst.area() >= DANGER_AREA
        }
        if (!shouldWarn) return

        // ── 방향 판단 ─────────────────────────────────────────────────────────
        val cx = worst.centerX()
        val side = when {
            cx < 0.33f -> "왼쪽"
            cx > 0.66f -> "오른쪽"
            else        -> "정면"
        }

        // ── 메시지 빌드 ───────────────────────────────────────────────────────
        // displayMsg: UI 배너 (거리 포함, 상세)
        val distTag    = if (worst.depthM != null) worst.distStr() + " " else ""
        val displayMsg = "$side $distTag${worst.label} 접근"

        // spokenMsg: TTS (거리 제외, 짧게, 회피 방향 합산)
        val dodge     = buildDodgeHint(side)
        val dodgeShort = dodgeHintShort(dodge)
        val spokenMsg = if (dodgeShort != null) "$side ${worst.label}, $dodgeShort"
                        else "$side ${worst.label}"

        showWarning(displayMsg, spokenMsg, side)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 신호등 상태 처리 — 상태 변화 시에만 TTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private fun handleTrafficLight(color: String?) {
        if (color == lastTrafficLightStatus) return
        lastTrafficLightStatus = color
        val msg = when (color) {
            "red"      -> "빨간불입니다. 멈추세요."
            "green"    -> "초록불입니다. 건너도 됩니다."
            "blinking" -> "초록불이 깜빡입니다. 서두르세요."
            else       -> return  // null → 신호등 없음, 발화 안 함
        }
        tts.speak(msg, TextToSpeech.QUEUE_ADD, null, "tl-$color")
    }

    private fun handleSegmentation(seg: com.safestep.app.detect.SegmentResult) {
        runOnUiThread { segmentOverlay.setImageBitmap(seg.maskBitmap) }

        // 3구역 정보 저장 (회피 방향 판단에 사용)
        if (seg.zones.isNotEmpty()) lastZones = seg.zones

        // 계단 감지 — 최우선 즉시 경고 (상태 변화 무관)
        if (seg.isStairs) {
            if (lastSurfaceStatus != "stairs") {
                lastSurfaceStatus = "stairs"
                tts.speak("계단이 있습니다. 조심하세요.", TextToSpeech.QUEUE_FLUSH, null, "stairs-warn")
            }
            return
        }

        val status = seg.status
        if (status == lastSurfaceStatus) return   // 상태 변화 없으면 무시
        lastSurfaceStatus = status

        when (status) {
            "road" -> {
                // 내비 중이면 어느 쪽 보도로 갈지 방향 힌트 포함
                val msg = if (navGuide.isRunning()) {
                    val lDanger = zoneDanger(seg.zones["left"]  ?: "unknown")
                    val rDanger = zoneDanger(seg.zones["right"] ?: "unknown")
                    when {
                        lDanger < rDanger -> "차도입니다. 왼쪽 보도로 이동하세요."
                        rDanger < lDanger -> "차도입니다. 오른쪽 보도로 이동하세요."
                        else              -> "차도입니다. 보도로 이동하세요."
                    }
                } else "차도입니다. 주의하세요."
                tts.speak(msg, TextToSpeech.QUEUE_ADD, null, "road-warn")
            }
            "caution" -> tts.speak("위험 구역입니다. 주의하세요.", TextToSpeech.QUEUE_ADD, null, "caution-warn")
            "alley"   -> tts.speak("골목길입니다. 주의하세요.",   TextToSpeech.QUEUE_ADD, null, "alley-warn")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 3구역 회피 방향 판단
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 클라이언트 면적 증가율 (서버 depth 없을 때 접근 속도 fallback)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 동일 라벨 bbox 면적의 시간당 변화율 (화면 비율/초).
     * 양수 = 커지는 중 = 접근, 충분한 샘플(≥3)이 쌓일 때까지 null.
     */
    private fun areaGrowthRate(label: String, area: Float): Float? {
        val hist = areaHistory.getOrPut(label) { ArrayDeque() }
        val now  = SystemClock.elapsedRealtime()
        hist.addLast(area to now)
        while (hist.size > 8) hist.removeFirst()
        // 3초보다 오래된 샘플 제거
        while (hist.size > 1 && now - hist.first().second > 3_000L) hist.removeFirst()
        if (hist.size < 3) return null
        val dt = (now - hist.first().second) / 1000f
        if (dt < 0.15f) return null
        return (area - hist.first().first) / dt
    }

    /** 회피 방향 TTS를 짧은 형태로 변환 ("오른쪽으로" 등) */
    private fun dodgeHintShort(hint: String?): String? = when {
        hint == null                      -> null
        hint.contains("오른쪽으로 조심히") -> "오른쪽 조심"
        hint.contains("왼쪽으로 조심히")  -> "왼쪽 조심"
        hint.contains("오른쪽")          -> "오른쪽으로"
        hint.contains("왼쪽")            -> "왼쪽으로"
        hint.contains("멈추세요")         -> "멈춰"
        else                             -> null
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 내비게이션 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 위경도 간 평면 근사 거리 (m) — 수백 m 이내 정확 */
    private fun distLatLon(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 111320.0
        val dLon = (lon2 - lon1) * 111320.0 * Math.cos(Math.toRadians(lat1))
        return Math.sqrt(dLat * dLat + dLon * dLon)
    }

    /**
     * 사용자와 가장 가까운 경로 포인트 이후 구간만 폴리라인에 남김.
     * GPS 이동에 따라 지나온 선이 사라지는 효과.
     */
    private fun updateRemainingRoute(lat: Double, lon: Double) {
        val pts = allPathPoints
        if (pts.size < 2) return

        // 가장 가까운 포인트 인덱스 탐색
        var nearestIdx = 0
        var minDist = Double.MAX_VALUE
        for (i in pts.indices) {
            val d = distLatLon(lat, lon, pts[i].latitude, pts[i].longitude)
            if (d < minDist) { minDist = d; nearestIdx = i }
        }

        val remaining = pts.subList(nearestIdx, pts.size)
        runOnUiThread {
            routePolyline?.setPoints(remaining)
            mapView.invalidate()
        }
    }

    /**
     * 경로 이탈 감지 — 경로상 모든 포인트와의 최소 거리가 40m 초과 시 TTS 경고 + 자동 재탐색.
     * 20초 쿨다운 적용.
     */
    private fun checkOffRoute(lat: Double, lon: Double) {
        val pts = allPathPoints
        if (pts.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOffRouteWarnMs < 20_000L) return

        val minDist = pts.minOf { p -> distLatLon(lat, lon, p.latitude, p.longitude) }
        if (minDist > 40.0) {
            lastOffRouteWarnMs = now
            tts.speak("경로를 벗어났습니다. 경로를 재탐색합니다.", TextToSpeech.QUEUE_FLUSH, null, "off-route")
            val dest = currentDest ?: return
            fetchRoute(lat, lon, dest)
        }
    }

    /** 노면 카테고리의 위험도 점수 (높을수록 위험) */
    private fun zoneDanger(cat: String): Int = when (cat) {
        "caution"   -> 3
        "road"      -> 2
        "alley"     -> 1
        "crosswalk" -> 1
        "sidewalk"  -> 0
        else        -> 0   // unknown
    }

    /**
     * 장애물이 있는 방향을 받아 반대쪽 구역 안전도를 확인하고 회피 TTS 문자열 반환.
     * 구역 정보가 없으면 null (발화 안 함).
     */
    private fun buildDodgeHint(obstacleSide: String): String? {
        val zones = lastZones
        if (zones.isEmpty()) return null

        val leftDanger  = zoneDanger(zones["left"]   ?: "unknown")
        val rightDanger = zoneDanger(zones["right"]  ?: "unknown")

        return when (obstacleSide) {
            "왼쪽" -> when {
                rightDanger == 0 -> "오른쪽으로 피하세요."
                rightDanger == 1 -> "오른쪽으로 조심히 피하세요."
                else             -> "멈추세요."
            }
            "오른쪽" -> when {
                leftDanger == 0  -> "왼쪽으로 피하세요."
                leftDanger == 1  -> "왼쪽으로 조심히 피하세요."
                else             -> "멈추세요."
            }
            else -> when {   // 정면
                leftDanger < rightDanger  -> "왼쪽으로 피하세요."
                rightDanger < leftDanger  -> "오른쪽으로 피하세요."
                leftDanger == 0           -> "왼쪽으로 피하세요."
                else                      -> "멈추세요."
            }
        }
    }

    /**
     * @param displayMsg UI 배너에 표시할 전체 메시지 (거리 포함)
     * @param spokenMsg  TTS로 읽을 짧은 메시지 (거리 제외, 회피 방향 합산)
     */
    private fun showWarning(displayMsg: String, spokenMsg: String, side: String) {
        // 바운딩박스 배너는 항상 표시
        runOnUiThread {
            warningText.text = displayMsg
            warningBanner.visibility = View.VISIBLE
        }

        // 목적지 미설정 또는 음성인식 중이면 TTS/진동 억제
        if (!detectionTtsEnabled || isListening) {
            warningBanner.postDelayed({ runOnUiThread { warningBanner.visibility = View.GONE } }, 3_000)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (spokenMsg != lastSpoken || now - lastSpeakMs >= SPEAK_COOLDOWN_MS) {
            lastSpoken  = spokenMsg
            lastSpeakMs = now
            // 진동 + TTS 동시에
            vibrateForDirection(side)
            tts.speak(spokenMsg, TextToSpeech.QUEUE_FLUSH, null, "warn-$now")
        }
        warningBanner.postDelayed({ runOnUiThread { warningBanner.visibility = View.GONE } }, 3_000)
    }

    private fun vibrateForDirection(side: String) {
        val pattern = when (side) {
            "왼쪽"  -> longArrayOf(0, 120, 80, 120, 80, 350)
            "오른쪽" -> longArrayOf(0, 350, 80, 120, 80, 120)
            else    -> longArrayOf(0, 250, 80, 100, 80, 250)
        }
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TTS
    // ══════════════════════════════════════════════════════════════════════════

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(1.5f)   // 빠르게 읽기
            tts.speak(
                "SafeStep 시작됩니다. 마이크 버튼을 눌러 목적지를 말씀해주세요.",
                TextToSpeech.QUEUE_FLUSH, null, "intro"
            )
        }
    }
}
