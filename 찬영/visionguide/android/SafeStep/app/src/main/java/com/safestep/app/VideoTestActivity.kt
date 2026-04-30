package com.safestep.app

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.safestep.app.detect.RemoteDetector
import com.safestep.app.detect.SegmentationClient
import com.safestep.app.detect.SegmentResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.safestep.app.detect.Detection
import com.safestep.app.detect.ObjectDetector
import java.util.Locale

class VideoTestActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var videoView: VideoView
    private lateinit var bboxOverlay: BoundingBoxOverlay
    private lateinit var warningBanner: LinearLayout
    private lateinit var warningText: TextView
    private lateinit var emptyView: LinearLayout
    private lateinit var detectionStatus: TextView
    private lateinit var selectVideoButton: Button
    private lateinit var playPauseButton: Button

    // ── Core ──────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var detector: ObjectDetector
    private var remoteDetector: RemoteDetector? = null  // 캐스팅 캐시
    private lateinit var segClient: SegmentationClient
    private lateinit var segmentOverlay: ImageView
    private var vibrator: Vibrator? = null
    @Volatile private var frameCount = 0
    @Volatile private var lastSurfaceStatus = ""
    @Volatile private var lastSurfaceSpeakMs = 0L

    // ── 프레임 추출 타이머 ─────────────────────────────────────────────────────
    private val frameHandler = Handler(Looper.getMainLooper())
    private var retriever: MediaMetadataRetriever? = null
    private var videoUri: Uri? = null
    private var isDetecting = false

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

    // ── 탐지 상태 ──────────────────────────────────────────────────────────────
    @Volatile private var lastSpoken = ""
    @Volatile private var lastSpeakMs = 0L

    // ── 거리 기반 음성 쿨다운 ─────────────────────────────────────────────────
    @Volatile private var alert5mFired = false
    @Volatile private var alert1mFired = false
    @Volatile private var lastTrackedLabel = ""

    companion object {
        private const val FRAME_INTERVAL_MS = 500L  // 탐지 간격 (ms)
        private const val DANGER_AREA       = 0.20f
        private const val VERY_CLOSE_AREA   = 0.40f
        private const val SPEAK_COOLDOWN_MS = 2500L
        // 경고 그룹 분류 (Detection.label = 한국어 그룹명)
        private val FULL_ALERT_GROUPS   = setOf("차량", "개인이동장치") // 5m 음성+진동 + 1m 긴급
        private val PERSON_ALERT_GROUPS = setOf("사람/동물")            // 1m 긴급 음성만
        private val CLOSE_ALERT_GROUPS  = setOf("고정장애물")           // 1m 긴급 음성만
        private val SIGNAL_ALERT_GROUPS = setOf("신호등/표지판")        // 1m 진동만
        // 기타 → 알림 없음
    }

    // ── 동영상 선택 런처 ──────────────────────────────────────────────────────
    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadVideo(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_test)

        videoView        = findViewById(R.id.videoView)
        bboxOverlay      = findViewById(R.id.bboxOverlay)
        warningBanner    = findViewById(R.id.warningBanner)
        warningText      = findViewById(R.id.warningText)
        emptyView        = findViewById(R.id.emptyView)
        detectionStatus  = findViewById(R.id.detectionStatus)
        selectVideoButton = findViewById(R.id.selectVideoButton)
        playPauseButton  = findViewById(R.id.playPauseButton)

        tts          = TextToSpeech(this, this)
        detector       = ObjectDetector.create(this)
        remoteDetector = detector as? RemoteDetector
        segClient    = SegmentationClient(RemoteDetector.SERVER_URL)
        segmentOverlay = findViewById(R.id.segmentOverlay)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        setupButtons()
        setupVideoView()
    }

    override fun onPause() {
        super.onPause()
        pauseVideo()
    }

    override fun onDestroy() {
        stopFrameDetection()
        stopRoadRepeat()
        retriever?.release()
        tts.shutdown()
        runCatching { detector.close() }
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 버튼 설정
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupButtons() {
        selectVideoButton.setOnClickListener {
            pickVideo.launch("video/*")
        }

        playPauseButton.setOnClickListener {
            if (videoView.isPlaying) pauseVideo() else playVideo()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }
    }

    private fun setupVideoView() {
        videoView.setOnCompletionListener {
            stopFrameDetection()
            playPauseButton.text = "▶ 재생"
            detectionStatus.text = "동영상 재생 완료"
            bboxOverlay.clear()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 동영상 로드 / 재생 제어
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        stopFrameDetection()
        bboxOverlay.clear()

        // MediaMetadataRetriever 초기화
        retriever?.release()
        retriever = MediaMetadataRetriever().apply {
            setDataSource(this@VideoTestActivity, uri)
        }

        // VideoView 설정
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            emptyView.visibility = View.GONE
            playPauseButton.isEnabled = true
            detectionStatus.text = "재생 버튼을 눌러 탐지를 시작하세요"
        }
        videoView.requestFocus()
    }

    private fun playVideo() {
        videoView.start()
        playPauseButton.text = "⏸ 일시정지"
        startFrameDetection()
    }

    private fun pauseVideo() {
        if (videoView.isPlaying) videoView.pause()
        playPauseButton.text = "▶ 재생"
        stopFrameDetection()
        bboxOverlay.clear()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 프레임 추출 → 탐지 루프
    // ══════════════════════════════════════════════════════════════════════════

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (videoView.isPlaying && !isDetecting) {
                extractAndDetect(videoView.currentPosition)
            }
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun startFrameDetection() {
        frameHandler.removeCallbacks(frameRunnable)
        frameHandler.post(frameRunnable)
    }

    private fun stopFrameDetection() {
        frameHandler.removeCallbacks(frameRunnable)
        isDetecting = false
    }

    private fun extractAndDetect(positionMs: Int) {
        val ret = retriever ?: return
        isDetecting = true

        Thread {
            try {
                // 현재 재생 위치 프레임 추출 (마이크로초 단위)
                val bitmap = ret.getFrameAtTime(
                    positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (bitmap != null) {
                    val detections    = detector.detect(bitmap, 0)
                    val serverMessage = remoteDetector?.lastMessage ?: ""
                    val dodgeDir      = remoteDetector?.lastDodge ?: "정면"

                    // 세그멘테이션 (2프레임마다)
                    frameCount++
                    val seg = if (frameCount % 2 == 0) segClient.segment(bitmap, 0) else null

                    runOnUiThread {
                        handleDetections(detections, serverMessage, dodgeDir)
                        if (seg != null) handleSegmentation(seg)
                        isDetecting = false
                    }
                } else {
                    runOnUiThread { isDetecting = false }
                }
            } catch (e: Exception) {
                runOnUiThread { isDetecting = false }
            }
        }.start()
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

        // 상태 텍스트
        detectionStatus.text = if (detections.isEmpty()) {
            "감지된 객체 없음"
        } else {
            val labels = detections.take(3).joinToString(", ") {
                "${it.label} ${(it.confidence * 100).toInt()}%"
            }
            "감지: $labels"
        }

        // 경고 대상: 기타 그룹만 제외
        val alertCandidates = detections.filter {
            it.label in FULL_ALERT_GROUPS || it.label in PERSON_ALERT_GROUPS ||
            it.label in CLOSE_ALERT_GROUPS || it.label in SIGNAL_ALERT_GROUPS
        }
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

        // 5m 이상이면 무시
        if (depthM != null && depthM > 5f) return

        // 새로운 장애물이면 쿨다운 초기화
        if (worst.label != lastTrackedLabel) {
            alert5mFired  = false
            alert1mFired  = false
            lastTrackedLabel = worst.label
        }

        // 진동 세기 결정
        val amplitude = when {
            depthM == null -> 180
            depthM <= 1f   -> 255
            depthM <= 3f   -> 180
            else           -> 100
        }

        // ── 신호등/표지판: 1m 진동만 ──
        if (group in SIGNAL_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f) vibrateForDirection(dodgeDir, amplitude)
            return
        }

        // ── 사람/동물: 1m 긴급 음성만, 그 외 없음 ──
        if (group in PERSON_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f && !alert1mFired) {
                alert1mFired = true
                val msg = if (serverMessage.isNotEmpty()) serverMessage
                          else "위험! $dodgeDir 사람 매우 가깝습니다"
                showWarning(msg, dodgeDir, amplitude, speakNow = true)
            }
            return
        }

        // ── 고정장애물: 1m 긴급 음성만, 그 외 없음 ──
        if (group in CLOSE_ALERT_GROUPS) {
            if (depthM != null && depthM <= 1f && !alert1mFired) {
                alert1mFired = true
                val msg = if (serverMessage.isNotEmpty()) serverMessage
                          else "위험! $dodgeDir 장애물 매우 가깝습니다"
                showWarning(msg, dodgeDir, amplitude, speakNow = true)
            }
            return
        }

        // ── 차량 / 개인이동장치: 5m 음성+진동 + 1m 긴급 ──
        // 1m 이하 긴급 음성 (1번만)
        if (depthM != null && depthM <= 1f && !alert1mFired) {
            alert1mFired = true
            val msg = if (serverMessage.isNotEmpty()) serverMessage
                      else "위험! $dodgeDir ${worst.label} 매우 가깝습니다"
            showWarning(msg, dodgeDir, amplitude, speakNow = true)
            return
        }

        // 5m 진입 최초 음성 (1번만)
        if (!alert5mFired) {
            alert5mFired = true
            val msg = when {
                depthUnavailSpoken ->
                    "${dodgeDir}에 ${worst.label}이 있습니다"
                serverMessage.isNotEmpty() -> serverMessage
                else -> {
                    val distStr = if (depthM != null) "${String.format("%.1f", depthM)}m " else ""
                    "$dodgeDir $distStr${worst.label} 접근"
                }
            }
            showWarning(msg, dodgeDir, amplitude, speakNow = true)
            return
        }

        // 그 사이: 진동만
        vibrateForDirection(dodgeDir, amplitude)
    }

    private fun handleSegmentation(seg: SegmentResult) {
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
        if (status != "crosswalk") lastTrafficLightColor = ""

        val surfaceKey = if (frontCls.isNotEmpty()) frontCls else status
        if (surfaceKey == lastSurfaceStatus) return
        lastSurfaceStatus = surfaceKey

        stopRoadRepeat()

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
            val qMode = if (status == "crosswalk") TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(message, qMode, null, "surface-${SystemClock.elapsedRealtime()}")
        }

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
        warningText.text = message
        warningBanner.visibility = View.VISIBLE

        if (speakNow) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "warn-${SystemClock.elapsedRealtime()}")
        }

        vibrateForDirection(side, amplitude)
        warningBanner.postDelayed({ warningBanner.visibility = View.GONE }, 3_000)
    }

    private fun vibrateForDirection(side: String, amplitude: Int = 180) {
        val timings = when (side) {
            "왼쪽"  -> longArrayOf(0, 120, 80, 120, 80, 350)
            "오른쪽" -> longArrayOf(0, 350, 80, 120, 80, 120)
            else    -> longArrayOf(0, 250, 80, 100, 80, 250)
        }
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
                "동영상 테스트 모드입니다. 동영상 선택 버튼을 눌러주세요.",
                TextToSpeech.QUEUE_FLUSH, null, "video-intro"
            )
        }
    }
}
