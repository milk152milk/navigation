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

    // ── Speech ────────────────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Segmentation ──────────────────────────────────────────────────────────
    @Volatile private var frameCount = 0
    @Volatile private var lastSurfaceStatus = ""
    @Volatile private var lastSurfaceSpeakMs = 0L

    // ── Detection ─────────────────────────────────────────────────────────────
    @Volatile private var lastSpoken = ""
    @Volatile private var lastSpeakMs = 0L

    companion object {
        private const val TAG        = "MapActivity"
        private const val REQ_PERM   = 200
        private const val DANGER_AREA      = 0.20f
        private const val VERY_CLOSE_AREA  = 0.40f
        private const val SPEAK_COOLDOWN_MS = 2500L
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

        tts            = TextToSpeech(this, this)
        @Suppress("DEPRECATION")
        vibrator       = getSystemService(VIBRATOR_SERVICE) as Vibrator
        cameraExecutor = Executors.newSingleThreadExecutor()
        detector       = ObjectDetector.create(this)
        segClient      = SegmentationClient(RemoteDetector.SERVER_URL)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 나침반 센서
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(compassListener)
    }

    override fun onDestroy() {
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

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val bitmap   = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            val detections = detector.detect(bitmap, rotation)
            handleDetections(detections)

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

    private fun handleDetections(detections: List<Detection>) {
        bboxOverlay.updateDetections(detections)

        val worst = detections.maxByOrNull { d ->
            val cw = 1f - kotlin.math.abs(d.centerX() - 0.5f)
            d.area() * (0.7f + 0.3f * cw) * d.confidence
        } ?: return

        val isPerson  = worst.label in listOf("사람", "person")
        val threshold = if (isPerson) 0.60f else DANGER_AREA
        if (worst.area() < threshold) return

        val side = when {
            worst.centerX() < 0.33f -> "왼쪽"
            worst.centerX() > 0.66f -> "오른쪽"
            else                     -> "정면"
        }
        val distTag = if (worst.area() >= VERY_CLOSE_AREA) "매우 가까이 " else ""
        val msg = "$side $distTag${worst.label} 접근"
        showWarning(msg, side)
    }

    private fun handleSegmentation(seg: com.safestep.app.detect.SegmentResult) {
        runOnUiThread { segmentOverlay.setImageBitmap(seg.maskBitmap) }

        val status  = seg.status
        if (status == lastSurfaceStatus) return   // 상태 변화 없으면 무시
        lastSurfaceStatus = status

        when (status) {
            "road"    -> tts.speak("차도입니다. 주의하세요.",     TextToSpeech.QUEUE_ADD, null, "road-warn")
            "caution" -> tts.speak("위험 구역입니다. 주의하세요.", TextToSpeech.QUEUE_ADD, null, "caution-warn")
            "alley"   -> tts.speak("골목길입니다. 주의하세요.",   TextToSpeech.QUEUE_ADD, null, "alley-warn")
        }
    }

    private fun showWarning(message: String, side: String) {
        runOnUiThread {
            warningText.text = message
            warningBanner.visibility = View.VISIBLE
        }
        val now = SystemClock.elapsedRealtime()
        if (message != lastSpoken || now - lastSpeakMs >= SPEAK_COOLDOWN_MS) {
            lastSpoken  = message
            lastSpeakMs = now
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "warn-$now")
        }
        vibrateForDirection(side)
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
            tts.speak(
                "SafeStep 시작됩니다. 마이크 버튼을 눌러 목적지를 말씀해주세요.",
                TextToSpeech.QUEUE_FLUSH, null, "intro"
            )
        }
    }
}
