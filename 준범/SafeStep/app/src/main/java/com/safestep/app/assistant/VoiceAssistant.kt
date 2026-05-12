package com.safestep.app.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * SafeStep 음성 어시스턴트.
 *
 * 동작:
 *   1. attachLongPressTo(view) 로 화면에 부착
 *   2. 화면 1초 길게 누르면 → 진동 + SpeechRecognizer 시작
 *   3. 인식된 텍스트를 서버 /assistant 로 전송 (screen 컨텍스트 포함)
 *   4. 받은 AssistantAction 을 onAction 콜백으로 전달
 *   5. action.tts 를 TTS 로 자동 발화
 *
 * @param activity        호스트 액티비티 (권한 체크용)
 * @param serverBaseUrl   서버 base URL (예: "https://xxx.ngrok.io")
 * @param screen          현재 화면 식별자 — "splash" 또는 "map".
 *                        서버가 화면별로 다른 도구 세트를 LLM 에 바인딩합니다.
 * @param onAction        action 처리 콜백 (UI 스레드에서 호출됨)
 */
class VoiceAssistant(
    private val activity: Activity,
    serverBaseUrl: String,
    private val screen: String,
    private val onAction: (AssistantAction) -> Unit,
    private val onBusy: ((Boolean) -> Unit)? = null,  // true=처리중, false=완료
) {
    private val client       = AssistantClient(serverBaseUrl)
    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer   = SpeechRecognizer.createSpeechRecognizer(activity)
    private val tts: TextToSpeech
    private val vibrator     = activity.getSystemService(Vibrator::class.java)

    /** 외부에서 주기적으로 갱신해주는 앱 컨텍스트. */
    private var context: Map<String, Any?> = emptyMap()

    private var inFlight: Job? = null
    private var ttsReady = false
    @Volatile private var isBusy = false  // 처리 중 또는 TTS 발화 중

    init {
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                ttsReady = true
            } else {
                Log.e(TAG, "TTS 초기화 실패")
            }
        }
        recognizer.setRecognitionListener(buildListener())
    }

    /** 컨텍스트 업데이트 — MapActivity 에서 위치/내비 상태 변할 때 호출. */
    fun updateContext(
        isNavigating: Boolean? = null,
        lat: Double? = null,
        lon: Double? = null,
        remainingDistanceM: Int? = null,
        recentDetections: List<String>? = null,
    ) {
        context = buildMap {
            isNavigating?.let       { put("is_navigating", it) }
            lat?.let                { put("current_lat", it) }
            lon?.let                { put("current_lon", it) }
            remainingDistanceM?.let { put("remaining_distance_m", it) }
            recentDetections?.let   { put("recent_detections", it) }
        }
    }

    /** 화면(또는 특정 뷰)에 long press 리스너 부착. (1.5초 유지 시 활성화) */
    fun attachLongPressTo(view: View) {
        val handler = Handler(Looper.getMainLooper())
        var pendingRunnable: Runnable? = null

        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    pendingRunnable = Runnable { onLongPressTriggered() }
                    handler.postDelayed(pendingRunnable!!, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingRunnable?.let { handler.removeCallbacks(it) }
                    pendingRunnable = null
                }
            }
            v.performClick()
            false  // 다른 터치 핸들러 동작 방해 안 함
        }
    }

    /** 액티비티 onDestroy 에서 호출. */
    fun release() {
        scope.cancel()
        recognizer.destroy()
        if (ttsReady) tts.stop()
        tts.shutdown()
    }

    // ────────────────────────────────────────────────────
    // 내부
    // ────────────────────────────────────────────────────

    private fun onLongPressTriggered() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC,
            )
            return
        }
        if (isBusy) {
            Log.d(TAG, "처리 중 또는 TTS 발화 중 — 무시")
            return
        }
        vibrate(100)
        onBusy?.invoke(true)
        startListening()
    }

    private fun hasMicPermission(): Boolean =
        ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "듣기 시작")
        }
        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text  = texts?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                speak("다시 말씀해주세요.")
                return
            }
            Log.d(TAG, "인식: $text")
            askServer(text)
        }
        override fun onError(error: Int) {
            Log.w(TAG, "음성 인식 에러: $error")
            speak("다시 말씀해주세요.")
            isBusy = false
            onBusy?.invoke(false)
        }
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech()       { vibrate(50) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun askServer(text: String) {
        isBusy = true
        val ctx = context + mapOf("screen" to screen)
        inFlight = scope.launch {
            val action = try {
                withContext(Dispatchers.IO) { client.ask(text, ctx) }
            } catch (e: AssistantClient.ApiKeyException) {
                Log.e(TAG, "API 키 미설정", e)
                speak("서버에 API 키가 설정되지 않았습니다. 서버 관리자에게 문의하세요.")
                isBusy = false
                return@launch
            } catch (e: AssistantClient.ServerException) {
                Log.e(TAG, "서버 오류: ${e.code}", e)
                speak("서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.")
                isBusy = false
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "서버 호출 실패: ${e.javaClass.simpleName} — ${e.message}", e)
                speak("서버에 연결할 수 없습니다. URL 설정을 확인해주세요.")
                isBusy = false
                return@launch
            }
            if (action.tts.isNotBlank()) speak(action.tts)
            onAction(action)
            // TTS 발화 예상 시간(2초) 후 잠금 해제
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isBusy = false
                onBusy?.invoke(false)
            }, 2000L)
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant")
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(ms)
        }
    }

    companion object {
        private const val TAG           = "VoiceAssistant"
        private const val REQ_MIC       = 9201
        private const val LONG_PRESS_MS = 1500L   // 오작동 방지: 기본 500ms → 1.5초
    }
}
