package com.safestep.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
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

    // ── Core ──────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService
    private var vibrator: Vibrator? = null
    private lateinit var detector: ObjectDetector
    private var remoteDetector: com.safestep.app.detect.RemoteDetector? = null  // 캐스팅 캐시
    private lateinit var segClient: SegmentationClient

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
    private var currentDest: PoiResult? = null

    // ── Speech ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Segmentation ──────────────────────────────────────────────────────────
    @Volatile private var frameCount = 0
    @Volatile private var lastSurfaceStatus = ""   // frontCls 또는 status (세밀한 변화 감지)
    @Volatile private var lastSurfaceSpeakMs = 0L

    // ── Detection ─────────────────────────────────────────────────────────────
    @Volatile private var lastSpoken = ""
    @Volatile private var lastSpeakMs = 0L

    // ── 거리 기반 음성 쿨다운 ─────────────────────────────────────────────────
    @Volatile private var alert5mFired = false
    @Volatile private var alert1mFired = false
    @Volatile private var lastTrackedLabel = ""

    // ── 서버 연결 상태 ────────────────────────────────────────────────────────
    @Volatile private var wasServerConnected = true
    @Volatile private var serverStateAnnounced = false

    // ── GPS 상태 ──────────────────────────────────────────────────────────────
    private var wasGpsEnabled = true
    private lateinit var locationManager: LocationManager

    // ── Depth 상태 ────────────────────────────────────────────────────────────
    @Volatile private var depthNullStreak = 0
    @Volatile private var depthUnavailSpoken = false

    // ── 차도/자전거도로 5초 반복 안내 ─────────────────────────────────────────
    private val roadRepeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var isRoadRepeatRunning = false

    // ── 신호등 색상 (횡단보도) ────────────────────────────────────────────────
    @Volatile private var lastTrafficLightColor = ""

    // ── 차량 근접 여부 (횡단보도 "건너셔도 됩니다" 판단용) ──────────────────
    @Volatile private var hasNearbyVehicle = false

    // ── 서버 재연결 실패 30초 반복 ────────────────────────────────────────────
    private val serverReconnectHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG        = "MapActivity"
        private const val REQ_PERM   = 200
        private const val DANGER_AREA      = 0.20f
        private const val VERY_CLOSE_AREA  = 0.40f
        private const val SPEAK_COOLDOWN_MS = 2500L
        // 경고 그룹 분류 (Detection.label = 한국어 그룹명)
        private val FULL_ALERT_GROUPS   = setOf("차량", "개인이동장치") // 5m 음성+진동 + 1m 긴급
        private val PERSON_ALERT_GROUPS = setOf("사람/동물")            // 1m 긴급 음성만
        private val CLOSE_ALERT_GROUPS  = setOf("고정장애물")           // 1m 긴급 음성만
        private val SIGNAL_ALERT_GROUPS = setOf("신호등/표지판")        // 1m 진동만
        // 기타 → 알림 없음
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

        findViewById<Button>(R.id.backButton).setOnClickListener {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }

        tts            = TextToSpeech(this, this)
        @Suppress("DEPRECATION")
        vibrator       = getSystemService(VIBRATOR_SERVICE) as Vibrator
        cameraExecutor = Executors.newSingleThreadExecutor()
        detector       = ObjectDetector.create(this)
        remoteDetector = detector as? com.safestep.app.detect.RemoteDetector
        segClient      = SegmentationClient(RemoteDetector.SERVER_URL)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 나침반 센서
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        navGuide = NavigationGuide(tts).apply {
            onStepChanged = { step ->
                runOnUiThread { updateNavUI(step.description, step.distance) }
            }
            onArrived = { runOnUiThread { onArrived() } }
            onOffRoute = reroute@{
                val dest = currentDest ?: return@reroute
                val loc  = currentLocation ?: return@reroute
                tts.speak("경로를 이탈했습니다. 재탐색합니다.", TextToSpeech.QUEUE_FLUSH, null, "reroute")
                fetchRoute(loc.latitude, loc.longitude, dest)
            }
            onWrongDirection = {
                tts.speak("반대 방향입니다. 돌아서세요.", TextToSpeech.QUEUE_FLUSH, null, "wrong-dir")
            }
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
        registerReceiver(gpsStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(compassListener)
        runCatching { unregisterReceiver(gpsStateReceiver) }
    }

    override fun onDestroy() {
        stopRoadRepeat()
        stopServerReconnectAlert()
        mapView.onDetach()
        cameraExecutor.shutdown()
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

    // ── GPS 상태 변화 감지 ────────────────────────────────────────────────────
    private val gpsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (enabled && !wasGpsEnabled) {
                wasGpsEnabled = true
                if (navGuide.isRunning()) {
                    // 내비 중이었으면 쿨다운 초기화 → 다음 위치 업데이트에서 즉시 이탈 감지
                    navGuide.resetOffRouteCooldown()
                    tts.speak("위치 정보가 연결되었습니다. 경로를 확인합니다.",
                        TextToSpeech.QUEUE_ADD, null, "gps-on")
                } else {
                    tts.speak("위치 정보가 연결되었습니다. 목적지 안내를 사용할 수 있습니다.",
                        TextToSpeech.QUEUE_ADD, null, "gps-on")
                }
            } else if (!enabled && wasGpsEnabled) {
                wasGpsEnabled = false
                tts.speak("위치 정보 연결이 끊겼습니다. 목적지 안내를 사용할 수 없습니다. 주의하세요.",
                    TextToSpeech.QUEUE_FLUSH, null, "gps-off")
            }
        }
    }

    // ── 앱 시작 초기 상태 안내 ────────────────────────────────────────────────
    private fun checkInitialStates() {
        wasGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!wasGpsEnabled) {
            tts.speak("위치 정보를 사용할 수 없습니다. 목적지 안내 기능을 사용할 수 없습니다.",
                TextToSpeech.QUEUE_ADD, null, "gps-init-off")
        }
        // 서버 연결 상태는 첫 프레임 탐지 후 안내 (analyzeFrame에서 처리)
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

    // 진행 방향으로 지도 중심 오프셋 (사용자가 화면 하단 1/3에 보이도록)
    private fun centerMapOnUser(loc: Location) {
        val zoom = mapView.zoomLevelDouble
        // 줌 레벨별 픽셀당 미터 (위도 보정 포함)
        val metersPerPx = 156543.03392 *
                Math.cos(Math.toRadians(loc.latitude)) / Math.pow(2.0, zoom)
        // 화면 높이의 1/3만큼 진행 방향으로 오프셋
        val shiftM = (mapView.height / 3.0) * metersPerPx
        val bearingRad = Math.toRadians(currentAzimuth.toDouble())
        val dLat = shiftM / 111320.0 * Math.cos(bearingRad)
        val dLon = shiftM / (111320.0 * Math.cos(Math.toRadians(loc.latitude))) *
                Math.sin(bearingRad)
        mapView.controller.animateTo(GeoPoint(loc.latitude + dLat, loc.longitude + dLon))
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

                // 지도 중심 이동 (진행 방향 기준 오프셋)
                centerMapOnUser(loc)

                // 내비 중 줌 자동 조정
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
        tts.speak("$query 검색합니다.", TextToSpeech.QUEUE_FLUSH, null, "searching")

        TmapService.searchPoi(query) { pois ->
            if (pois.isEmpty()) {
                tts.speak("검색 결과가 없습니다. 다시 말씀해주세요.", TextToSpeech.QUEUE_FLUSH, null, "no-poi")
                destinationText.text = "목적지를 말씀해주세요"
                return@searchPoi
            }
            val dest = pois.first()
            currentDest = dest
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

    @ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val bitmap   = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            val detections    = detector.detect(bitmap, rotation)
            val serverMessage = remoteDetector?.lastMessage ?: ""
            val dodgeDir      = remoteDetector?.lastDodge ?: "정면"

            // ── 서버 연결 상태 체크 ──────────────────────────────────────────
            val nowConnected = remoteDetector?.isConnected ?: true
            if (!serverStateAnnounced) {
                serverStateAnnounced = true
                wasServerConnected = nowConnected
                if (nowConnected) {
                    tts.speak("서버에 연결되었습니다. 모든 기능을 사용할 수 있습니다.",
                        TextToSpeech.QUEUE_ADD, null, "server-init-ok")
                } else {
                    tts.speak("서버에 연결할 수 없습니다. 장애물 탐지, 노면 안내, 신호등 인식 기능을 사용할 수 없습니다.",
                        TextToSpeech.QUEUE_ADD, null, "server-init-fail")
                }
            } else if (!nowConnected && wasServerConnected) {
                wasServerConnected = false
                tts.speak("서버 연결이 끊겼습니다. 장애물 탐지, 노면 안내, 신호등 인식 기능을 사용할 수 없습니다. 주의하세요.",
                    TextToSpeech.QUEUE_FLUSH, null, "server-off")
                startServerReconnectAlert()
            } else if (nowConnected && !wasServerConnected) {
                wasServerConnected = true
                stopServerReconnectAlert()
                tts.speak("서버에 다시 연결되었습니다. 모든 기능을 사용할 수 있습니다.",
                    TextToSpeech.QUEUE_ADD, null, "server-on")
            }

            handleDetections(detections, serverMessage, dodgeDir)

            frameCount++
            if (frameCount % 2 == 0) {
                val seg = segClient.segment(bitmap, rotation)
                if (seg != null) handleSegmentation(seg)
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

    private fun handleDetections(detections: List<Detection>, serverMessage: String = "", dodgeDir: String = "정면") {
        // ── Depth 상태 추적 ──────────────────────────────────────────────────
        if (detections.isNotEmpty()) {
            if (detections.all { it.depthM == null }) {
                depthNullStreak++
                if (depthNullStreak >= 10 && !depthUnavailSpoken) {
                    depthUnavailSpoken = true
                    tts.speak("거리 측정을 사용할 수 없습니다. 장애물 거리를 알 수 없습니다.",
                        TextToSpeech.QUEUE_ADD, null, "depth-off")
                }
            } else {
                if (depthUnavailSpoken) {
                    depthUnavailSpoken = false
                    tts.speak("거리 측정이 활성화되었습니다.", TextToSpeech.QUEUE_ADD, null, "depth-on")
                }
                depthNullStreak = 0
            }
        }

        // 경고 대상: 기타 그룹만 제외
        val alertCandidates = detections.filter {
            it.label in FULL_ALERT_GROUPS || it.label in PERSON_ALERT_GROUPS ||
            it.label in CLOSE_ALERT_GROUPS || it.label in SIGNAL_ALERT_GROUPS
        }

        // 차량/개인이동장치 근접 여부 (횡단보도 "건너셔도 됩니다" 판단용)
        hasNearbyVehicle = alertCandidates.any { it.label in FULL_ALERT_GROUPS }

        val worst = alertCandidates.maxByOrNull { d ->
            val cw = 1f - kotlin.math.abs(d.centerX() - 0.5f)
            d.area() * (0.7f + 0.3f * cw) * d.confidence
        } ?: run {
            // 경고 대상 없음 → 상태 초기화
            alert5mFired = false
            alert1mFired = false
            lastTrackedLabel = ""
            return
        }

        val depthM = worst.depthM
        val group  = worst.label
        val side   = dodgeDir  // 회피 방향 기준으로 진동 (TTS와 일치)

        // 5m 이상이면 무시
        if (depthM != null && depthM > 5f) return

        // 새로운 장애물 추적 시작 → 쿨다운 초기화
        if (worst.label != lastTrackedLabel) {
            alert5mFired  = false
            alert1mFired  = false
            lastTrackedLabel = worst.label
        }

        // 진동 세기 결정 (depth_m 기반)
        val amplitude = when {
            depthM == null -> 180
            depthM <= 1f   -> 255  // 최대 (매우 가까움)
            depthM <= 3f   -> 180  // 중간
            else           -> 100  // 약함 (5m 근처)
        }

        // ── 신호등/표지판: 1m 진동만 ──
        if (group in SIGNAL_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f) vibrateForDirection(side, amplitude)
            return
        }

        // ── 사람/동물: 1m 긴급 음성만, 그 외 없음 ──
        if (group in PERSON_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f && !alert1mFired) {
                alert1mFired = true
                val urgentMsg = if (serverMessage.isNotEmpty()) serverMessage
                                else "위험! $side 사람 매우 가깝습니다"
                showWarning(urgentMsg, side, amplitude, speakNow = true)
            }
            return
        }

        // ── 고정장애물: 1m 긴급 음성만, 그 외 없음 ──
        if (group in CLOSE_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f && !alert1mFired) {
                alert1mFired = true
                val urgentMsg = if (serverMessage.isNotEmpty()) serverMessage
                                else "위험! $side 장애물 매우 가깝습니다"
                showWarning(urgentMsg, side, amplitude, speakNow = true)
            }
            return
        }

        // ── 차량 / 개인이동장치: 5m 음성+진동 + 1m 긴급 ──
        // 1m 이하 긴급 음성 (1번만)
        if (depthM != null && depthM <= 1f && !alert1mFired) {
            alert1mFired = true
            val urgentMsg = if (serverMessage.isNotEmpty()) serverMessage
                            else "위험! $side ${worst.label} 매우 가깝습니다"
            showWarning(urgentMsg, side, amplitude, speakNow = true)
            return
        }

        // 5m 진입 최초 음성 (1번만)
        if (!alert5mFired) {
            alert5mFired = true
            val msg = when {
                depthUnavailSpoken ->
                    // Depth 사용 불가: 거리 없이 방향+그룹만 안내
                    "${side}에 ${worst.label}이 있습니다"
                serverMessage.isNotEmpty() -> serverMessage
                else -> {
                    val distStr = if (depthM != null) "${String.format("%.1f", depthM)}m " else ""
                    "$side $distStr${worst.label} 접근"
                }
            }
            showWarning(msg, side, amplitude, speakNow = true)
            return
        }

        // 그 사이: 진동만 (음성 없음)
        vibrateForDirection(side, amplitude)
    }

    private fun handleSegmentation(seg: com.safestep.app.detect.SegmentResult) {
        val status            = seg.status
        val frontCls          = seg.frontCls
        val leftStatus        = seg.leftStatus
        val rightStatus       = seg.rightStatus
        val trafficLightColor = seg.trafficLightColor

        // ── 신호등 색상 안내 (횡단보도 위에서만) ─────────────────────────
        if (status == "crosswalk" && trafficLightColor != lastTrafficLightColor) {
            lastTrafficLightColor = trafficLightColor
            when (trafficLightColor) {
                "green" -> tts.speak("초록불입니다. 건너세요.",         TextToSpeech.QUEUE_FLUSH, null, "light-green")
                "red"   -> tts.speak("빨간불입니다. 멈추세요.",         TextToSpeech.QUEUE_FLUSH, null, "light-red")
                ""      -> {
                    tts.speak("신호등을 확인할 수 없습니다. 주변을 살펴보세요.",
                        TextToSpeech.QUEUE_ADD, null, "light-unknown")
                    if (!hasNearbyVehicle) {
                        tts.speak("건너셔도 됩니다.", TextToSpeech.QUEUE_ADD, null, "light-safe")
                    }
                }
            }
        }
        // 횡단보도 벗어나면 색상 추적 초기화
        if (status != "crosswalk") lastTrafficLightColor = ""

        // frontCls 우선으로 변화 감지 (세부 클래스 단위 추적)
        val surfaceKey = if (frontCls.isNotEmpty()) frontCls else status
        if (surfaceKey == lastSurfaceStatus) return
        lastSurfaceStatus = surfaceKey

        // 이전 차도/자전거도로 반복 중단
        stopRoadRepeat()

        // 세부 클래스 → 음성 메시지 결정
        val message = when {
            frontCls == "sidewalk_damaged"             -> "파손된 인도입니다. 주의하세요."
            frontCls == "braille_guide_blocks_damaged" -> "파손된 점자블록입니다. 주의하세요."
            frontCls == "caution_zone_grating"         -> "격자 덮개입니다. 주의하세요."
            frontCls == "caution_zone_manhole"         -> "맨홀이 있습니다. 주의하세요."
            frontCls == "caution_zone_repair_zone"     -> "공사 구역입니다. 주의하세요."
            frontCls == "caution_zone_stairs"          -> "계단입니다. 주의하세요."
            frontCls == "caution_zone_tree_zone"       -> "나무 구역입니다. 주의하세요."
            frontCls == "bike_lane"                    -> "자전거 도로입니다. 주의하세요."
            status == "crosswalk"                      -> "횡단보도입니다. 멈추고 신호를 확인하세요."
            status == "road"                           -> "차도입니다. 주의하세요."
            status == "alley"                          -> "골목길입니다. 주의하세요."
            status == "sidewalk"                       -> "인도입니다."
            else                                       -> null
        }

        if (message != null) {
            // 횡단보도는 우선순위 높음 → QUEUE_FLUSH
            val qMode = if (status == "crosswalk") TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(message, qMode, null, "surface-${SystemClock.elapsedRealtime()}")
        }

        // 차도 / 자전거도로: 5초마다 방향 유도 반복
        if (status == "road" || frontCls == "bike_lane") {
            val situation = if (frontCls == "bike_lane") "자전거 도로입니다." else "차도입니다."
            startRoadRepeat(situation, leftStatus, rightStatus)
        }
    }

    private fun startRoadRepeat(situation: String, leftStatus: String, rightStatus: String) {
        isRoadRepeatRunning = true
        val runnable = object : Runnable {
            override fun run() {
                if (!isRoadRepeatRunning) return
                val guide = buildDirectionGuide(situation, leftStatus, rightStatus)
                tts.speak(guide, TextToSpeech.QUEUE_ADD, null, "road-repeat-${SystemClock.elapsedRealtime()}")
                roadRepeatHandler.postDelayed(this, 5_000)
            }
        }
        roadRepeatHandler.postDelayed(runnable, 5_000)
    }

    private fun stopRoadRepeat() {
        isRoadRepeatRunning = false
        roadRepeatHandler.removeCallbacksAndMessages(null)
    }

    private fun startServerReconnectAlert() {
        stopServerReconnectAlert()
        val runnable = object : Runnable {
            override fun run() {
                tts.speak("서버에 연결할 수 없습니다. 주의하세요.",
                    TextToSpeech.QUEUE_ADD, null, "server-retry-${SystemClock.elapsedRealtime()}")
                serverReconnectHandler.postDelayed(this, 30_000)
            }
        }
        serverReconnectHandler.postDelayed(runnable, 30_000)
    }

    private fun stopServerReconnectAlert() {
        serverReconnectHandler.removeCallbacksAndMessages(null)
    }

    private fun buildDirectionGuide(situation: String, leftStatus: String, rightStatus: String): String {
        val leftSafe   = leftStatus == "sidewalk"
        val rightSafe  = rightStatus == "sidewalk"
        val leftAlley  = leftStatus == "alley"
        val rightAlley = rightStatus == "alley"
        val leftCross  = leftStatus == "crosswalk"
        val rightCross = rightStatus == "crosswalk"
        return when {
            leftCross || rightCross -> {
                val dir = if (leftCross) "왼쪽" else "오른쪽"
                "$situation $dir 횡단보도 방향으로 이동하세요."
            }
            leftSafe && rightSafe   -> "$situation 왼쪽 또는 오른쪽 인도로 이동하세요."
            leftSafe                -> "$situation 왼쪽 인도로 이동하세요."
            rightSafe               -> "$situation 오른쪽 인도로 이동하세요."
            leftAlley && rightAlley -> "$situation 왼쪽 또는 오른쪽 골목 방향으로 이동하세요."
            leftAlley               -> "$situation 왼쪽 골목 방향으로 이동하세요."
            rightAlley              -> "$situation 오른쪽 골목 방향으로 이동하세요."
            else                    -> "$situation 천천히 멈추고 주변을 확인하세요."
        }
    }

    private fun showWarning(message: String, side: String, amplitude: Int = 180, speakNow: Boolean = false) {
        runOnUiThread {
            warningText.text = message
            warningBanner.visibility = View.VISIBLE
        }
        if (speakNow) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "warn-${SystemClock.elapsedRealtime()}")
        }
        vibrateForDirection(side, amplitude)
        warningBanner.postDelayed({ runOnUiThread { warningBanner.visibility = View.GONE } }, 3_000)
    }

    private fun vibrateForDirection(side: String, amplitude: Int = 180) {
        val timings = when (side) {
            "왼쪽"  -> longArrayOf(0, 120, 80, 120, 80, 350)
            "오른쪽" -> longArrayOf(0, 350, 80, 120, 80, 120)
            else    -> longArrayOf(0, 250, 80, 100, 80, 250)
        }
        // 홀수 인덱스(진동 구간)에만 amplitude 적용, 짝수(정지)는 0
        val amplitudes = IntArray(timings.size) { i -> if (i % 2 == 0) 0 else amplitude }
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TTS
    // ══════════════════════════════════════════════════════════════════════════

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.speak(
                "SafeStep 시작됩니다. 마이크 버튼을 눌러 목적지를 말씀해주세요.",
                TextToSpeech.QUEUE_FLUSH, null, "intro"
            )
            checkInitialStates()
        }
    }
}
