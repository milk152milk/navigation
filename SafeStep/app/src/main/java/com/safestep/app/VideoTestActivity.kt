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
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.safestep.app.detect.RemoteDetector
import com.safestep.app.detect.SegmentationClient
import com.safestep.app.detect.SegmentResult
import com.safestep.app.detect.SignalClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.safestep.app.detect.Detection
import com.safestep.app.detect.ObjectDetector
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView

    // SeekBar 업데이트 루프
    private val seekHandler = Handler(Looper.getMainLooper())
    private var videoDurationMs = 0
    private var isUserSeeking = false

    // ── Core ──────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var detector: ObjectDetector
    @Volatile private lateinit var segClient: SegmentationClient
    @Volatile private lateinit var signalClient: SignalClient
    private lateinit var segmentOverlay: ImageView
    private var vibrator: Vibrator? = null
    @Volatile private var frameCount = 0
    @Volatile private var lastSurfaceStatus = ""
    @Volatile private var lastSurfaceSpeakMs = 0L

    // 실시간 카메라(MapActivity)와 동일한 병렬 파이프라인 구조
    private lateinit var fastExecutor: ExecutorService
    private lateinit var detectExecutor: ExecutorService
    private lateinit var segExecutor: ExecutorService
    private lateinit var signalExecutor: ExecutorService

    private val fastBusy   = AtomicBoolean(false)
    private val detectBusy = AtomicBoolean(false)
    private val segBusy    = AtomicBoolean(false)
    private val signalBusy = AtomicBoolean(false)

    // 신호등 (사용자 토글 + 횡단보도 컨텍스트)
    @Volatile private var signalEnabled = false
    @Volatile private var lastTrafficColor: String? = null
    @Volatile private var lastTrafficSpeakMs = 0L

    // ── 프레임 추출 타이머 ─────────────────────────────────────────────────────
    private val frameHandler = Handler(Looper.getMainLooper())
    private var retriever: MediaMetadataRetriever? = null
    private var videoUri: Uri? = null
    private var isDetecting = false

    // ── 탐지 상태 ──────────────────────────────────────────────────────────────
    @Volatile private var lastSpoken = ""
    @Volatile private var lastSpeakMs = 0L

    companion object {
        private const val FRAME_INTERVAL_MS = 500L  // 탐지 간격 (ms)
        private const val DANGER_AREA       = 0.20f
        private const val VERY_CLOSE_AREA   = 0.40f
        private const val SPEAK_COOLDOWN_MS = 2500L
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
        seekBar          = findViewById(R.id.videoSeekBar)
        currentTimeText  = findViewById(R.id.currentTimeText)
        totalTimeText    = findViewById(R.id.totalTimeText)

        tts          = TextToSpeech(this, this)
        detector     = ObjectDetector.create(this)
        val resolvedUrl = RemoteDetector.SERVER_URL.ifBlank { SplashActivity.DEFAULT_SERVER_URL }
        segClient    = SegmentationClient(resolvedUrl)
        signalClient = SignalClient(resolvedUrl)
        segmentOverlay  = findViewById(R.id.segmentOverlay)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        // 병렬 파이프라인 — MapActivity와 동일 구조
        fastExecutor   = Executors.newSingleThreadExecutor()
        detectExecutor = Executors.newSingleThreadExecutor()
        segExecutor    = Executors.newSingleThreadExecutor()
        signalExecutor = Executors.newSingleThreadExecutor()

        // 신호등 토글 (사용자 설정)
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, MODE_PRIVATE)
        signalEnabled = prefs.getBoolean(
            SettingsActivity.KEY_SIGNAL_ENABLED,
            SettingsActivity.DEFAULT_SIGNAL_ENABLED
        )

        setupButtons()
        setupVideoView()
    }

    override fun onPause() {
        super.onPause()
        pauseVideo()
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 신호등 토글 새로고침
        val prefs = getSharedPreferences(SplashActivity.PREFS_NAME, MODE_PRIVATE)
        signalEnabled = prefs.getBoolean(
            SettingsActivity.KEY_SIGNAL_ENABLED,
            SettingsActivity.DEFAULT_SIGNAL_ENABLED
        )
    }

    override fun onDestroy() {
        stopFrameDetection()
        seekHandler.removeCallbacks(seekUpdateRunnable)
        retriever?.release()
        tts.shutdown()
        runCatching { detector.close() }
        // 파이프라인 Executor 안전 종료 (MapActivity와 동일 패턴)
        listOf(fastExecutor, detectExecutor, segExecutor, signalExecutor).forEach { ex ->
            runCatching {
                ex.shutdown()
                if (!ex.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    ex.shutdownNow()
                }
            }
        }
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

        // 좌상단 뒤로가기 버튼
        findViewById<android.view.View>(R.id.backToModeButton).setOnClickListener {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
        }

        // SeekBar 드래그
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                isUserSeeking = true
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTimeText.text = formatTime(progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                videoView.seekTo(sb.progress)
                isUserSeeking = false
            }
        })
    }

    private fun setupVideoView() {
        videoView.setOnCompletionListener {
            stopFrameDetection()
            seekHandler.removeCallbacks(seekUpdateRunnable)
            playPauseButton.text = "▶ 재생"
            detectionStatus.text = "동영상 재생 완료"
            bboxOverlay.clear()
            seekBar.progress = videoDurationMs
            currentTimeText.text = formatTime(videoDurationMs)
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
            videoDurationMs = mp.duration
            seekBar.max = videoDurationMs
            totalTimeText.text = formatTime(videoDurationMs)
            currentTimeText.text = "0:00"
        }
        videoView.requestFocus()
    }

    private fun playVideo() {
        videoView.start()
        playPauseButton.text = "⏸ 일시정지"
        startFrameDetection()
        seekHandler.post(seekUpdateRunnable)
    }

    private fun pauseVideo() {
        if (videoView.isPlaying) videoView.pause()
        playPauseButton.text = "▶ 재생"
        stopFrameDetection()
        seekHandler.removeCallbacks(seekUpdateRunnable)
        bboxOverlay.clear()
    }

    // SeekBar 위치를 100ms마다 갱신
    private val seekUpdateRunnable = object : Runnable {
        override fun run() {
            if (videoView.isPlaying && !isUserSeeking) {
                val pos = videoView.currentPosition
                seekBar.progress = pos
                currentTimeText.text = formatTime(pos)
            }
            seekHandler.postDelayed(this, 100)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 프레임 추출 → 탐지 루프
    // ══════════════════════════════════════════════════════════════════════════

    private val frameRunnable = object : Runnable {
        override fun run() {
            // 매 FRAME_INTERVAL_MS 마다 추출 시도 → 파이프라인은 각자 skip-if-busy 처리
            if (videoView.isPlaying) {
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

        // 프레임 추출 — 비교적 빠르므로 메인에서 동기로 시도, 실제 추출은 별도 스레드
        Thread {
            val bitmap = try {
                ret.getFrameAtTime(
                    positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } catch (e: Exception) { null } ?: return@Thread

            frameCount++

            // 🚀 ① Fast Lane — 매 프레임, 차량·사람 전용 (imgsz=160)
            //    실시간 카메라(MapActivity)와 동일한 충돌 회피 경로
            if (fastBusy.compareAndSet(false, true)) {
                fastExecutor.execute {
                    try {
                        val fastDet = detector as? RemoteDetector
                        if (fastDet != null) {
                            val detections = fastDet.detectFast(bitmap, 0)
                            if (detections.isNotEmpty()) {
                                runOnUiThread { handleDetections(detections) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("VideoTestActivity", "Fast Lane 실패: ${e.message}")
                    } finally {
                        fastBusy.set(false)
                    }
                }
            }

            // ② 일반 탐지 — 매 프레임, 모든 객체 + depth (Fast Lane 보완)
            if (detectBusy.compareAndSet(false, true)) {
                detectExecutor.execute {
                    try {
                        val detections = detector.detect(bitmap, 0)
                        runOnUiThread { handleDetections(detections) }
                    } catch (e: Exception) {
                        Log.w("VideoTestActivity", "탐지 실패: ${e.message}")
                    } finally {
                        detectBusy.set(false)
                    }
                }
            }

            // ③ 노면 세그멘테이션 — 2프레임마다
            if (frameCount % 2 == 0 && segBusy.compareAndSet(false, true)) {
                segExecutor.execute {
                    try {
                        val seg = segClient.segment(bitmap, 0)
                        if (seg != null) runOnUiThread { handleSegmentation(seg) }
                    } catch (e: Exception) {
                        Log.w("VideoTestActivity", "세그멘테이션 실패: ${e.message}")
                    } finally {
                        segBusy.set(false)
                    }
                }
            }

            // ④ 신호등 — 3프레임마다, 토글 ON + 횡단보도/차도 컨텍스트
            if (signalEnabled && frameCount % 3 == 0 &&
                (lastSurfaceStatus == "crosswalk" || lastSurfaceStatus == "road") &&
                signalBusy.compareAndSet(false, true)) {
                signalExecutor.execute {
                    try {
                        val signal = signalClient.detect(bitmap, 0)
                        if (signal != null) runOnUiThread { handleTrafficLight(signal.color) }
                    } catch (e: Exception) {
                        Log.w("VideoTestActivity", "신호등 감지 실패: ${e.message}")
                    } finally {
                        signalBusy.set(false)
                    }
                }
            }
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 탐지 결과 처리
    // ══════════════════════════════════════════════════════════════════════════

    private fun handleDetections(detections: List<Detection>) {
        // 바운딩박스 갱신
        bboxOverlay.updateDetections(detections)

        // 상태 텍스트
        detectionStatus.text = if (detections.isEmpty()) {
            "감지된 객체 없음"
        } else {
            val labels = detections.take(3).joinToString(", ") {
                "${it.label} ${(it.confidence * 100).toInt()}%"
            }
            "감지: $labels"
        }

        // 위험 경고: 사람은 매우 가까울 때만, 그 외 장애물은 DANGER_AREA 이상
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

    private fun handleSegmentation(seg: SegmentResult) {
        runOnUiThread { segmentOverlay.setImageBitmap(seg.maskBitmap) }

        val status = seg.status
        val now = SystemClock.elapsedRealtime()

        // 상태가 바뀌었고 쿨다운이 지났을 때만 음성 안내
        if (status == lastSurfaceStatus) return
        if (now - lastSurfaceSpeakMs < SPEAK_COOLDOWN_MS) return

        lastSurfaceStatus = status
        lastSurfaceSpeakMs = now

        val message = when (status) {
            "road"      -> "차도입니다. 보도로 이동하세요."
            "crosswalk" -> "횡단보도입니다."
            "alley"     -> "골목길입니다. 주의하세요."
            "caution"   -> if (seg.isStairs) "계단이 있습니다. 조심하세요." else "위험 구역입니다."
            else        -> return  // sidewalk, unknown 등은 안내 안 함
        }
        speak(message)
    }

    /**
     * 신호등 색상 결과 처리 — 횡단보도/차도 컨텍스트에서만 호출됨.
     * MapActivity.handleTrafficLight 와 동일한 로직.
     */
    private fun handleTrafficLight(color: String?) {
        if (color == null) return
        if (!signalEnabled) return
        if (lastSurfaceStatus != "crosswalk" && lastSurfaceStatus != "road") return

        val nowMs = SystemClock.elapsedRealtime()
        val sameColor = (color == lastTrafficColor)
        val cooldownMs = 5_000L
        if (sameColor && nowMs - lastTrafficSpeakMs < cooldownMs) return

        lastTrafficColor = color
        lastTrafficSpeakMs = nowMs

        val message = when (color) {
            "red"      -> "빨간불입니다. 멈추세요."
            "green"    -> "초록불입니다. 건너세요."
            "blinking" -> "신호가 깜빡입니다. 다음 신호를 기다리세요."
            else       -> return
        }
        speak(message)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 경고 / TTS / 진동
    // ══════════════════════════════════════════════════════════════════════════

    fun showWarning(message: String, side: String = "정면") {
        runOnUiThread {
            warningText.text = message
            warningBanner.visibility = View.VISIBLE
        }
        speak(message)
        vibrateForDirection(side)
        warningBanner.postDelayed({
            runOnUiThread { warningBanner.visibility = View.GONE }
        }, 3000)
    }

    @Volatile private var ttsReady = false

    private fun speak(text: String) {
        if (!ttsReady) return  // TTS 초기화 실패 또는 진행 중이면 무시
        val now = SystemClock.elapsedRealtime()
        if (text == lastSpoken && now - lastSpeakMs < SPEAK_COOLDOWN_MS) return
        lastSpoken = text
        lastSpeakMs = now
        runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "safestep-video-$now")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            ttsReady = true
        } else {
            Log.w("VideoTestActivity", "TTS 초기화 실패 (status=$status)")
        }
    }

    /**
     * 왼쪽 : 짧-짧-긴  (왼쪽으로 주의 유도)
     * 정면 : 긴-짧-긴  (강한 경고)
     * 오른쪽: 긴-짧-짧 (오른쪽으로 주의 유도)
     */
    private fun vibrateForDirection(side: String) {
        val pattern: LongArray = when (side) {
            "왼쪽"  -> longArrayOf(0, 120, 80, 120, 80, 350)
            "오른쪽" -> longArrayOf(0, 350, 80, 120, 80, 120)
            else    -> longArrayOf(0, 350, 80, 120, 80, 350)  // 정면
        }
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

     