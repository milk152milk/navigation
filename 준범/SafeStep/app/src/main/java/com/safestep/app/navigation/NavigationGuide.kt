package com.safestep.app.navigation

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlin.math.*

/**
 * 경로 스텝 목록 + GPS 위치 업데이트를 받아
 * 턴바이턴 TTS 안내를 제공한다.
 */
class NavigationGuide(private val tts: TextToSpeech) {

    private val TAG = "NavGuide"

    private var steps = listOf<RouteStep>()
    private var currentIdx = 0
    private var isActive = false
    private var lastAnnouncedIdx = -1
    private var lastAnnounceMs = 0L

    /** 스텝이 바뀔 때 UI 갱신용 콜백 */
    var onStepChanged: ((RouteStep) -> Unit)? = null
    /** 목적지 도착 콜백 */
    var onArrived: (() -> Unit)? = null

    companion object {
        private const val STEP_ARRIVE_M  = 20.0  // 이 거리 이내면 현재 스텝 통과
        private const val ANNOUNCE_PRE_M = 30.0  // 다음 회전 미리 안내 거리
        private const val COOLDOWN_MS    = 8_000L // 같은 안내 반복 최소 간격
    }

    fun start(routeSteps: List<RouteStep>) {
        steps = routeSteps
        currentIdx = 0
        lastAnnouncedIdx = -1
        isActive = true
        announce(0)
        onStepChanged?.invoke(steps[0])
    }

    fun stop() {
        isActive = false
    }

    fun isRunning() = isActive

    /** GPS 위치가 업데이트될 때마다 호출 */
    fun updateLocation(lat: Double, lon: Double) {
        if (!isActive || steps.isEmpty()) return

        // 목적지 도착 확인
        val dest = steps.last()
        if (distM(lat, lon, dest.lat, dest.lon) < STEP_ARRIVE_M) {
            isActive = false
            tts.speak("목적지에 도착했습니다.", TextToSpeech.QUEUE_FLUSH, null, "arrived")
            onArrived?.invoke()
            return
        }

        // 현재 스텝 지점 통과 → 다음 스텝으로 전진
        if (currentIdx < steps.size - 1) {
            val cur = steps[currentIdx]
            if (distM(lat, lon, cur.lat, cur.lon) < STEP_ARRIVE_M) {
                currentIdx++
                announce(currentIdx)
                onStepChanged?.invoke(steps[currentIdx])
                return
            }
        }

        // 다음 스텝까지 ANNOUNCE_PRE_M 이내 → 미리 안내
        val nextIdx = currentIdx + 1
        if (nextIdx < steps.size) {
            val next = steps[nextIdx]
            val dist = distM(lat, lon, next.lat, next.lon)
            val now  = SystemClock.elapsedRealtime()
            if (dist < ANNOUNCE_PRE_M &&
                nextIdx != lastAnnouncedIdx &&
                now - lastAnnounceMs > COOLDOWN_MS
            ) {
                announce(nextIdx)
            }
        }
    }

    /** 현재 스텝 (UI 표시용) */
    fun currentStep(): RouteStep? = steps.getOrNull(currentIdx)

    /** 현재 위치 → 현재 스텝 지점까지 거리 (m) */
    fun distToCurrentStep(lat: Double, lon: Double): Int {
        val s = steps.getOrNull(currentIdx) ?: return 0
        return distM(lat, lon, s.lat, s.lon).toInt()
    }

    private fun announce(idx: Int) {
        val step = steps.getOrNull(idx) ?: return
        if (step.description.isBlank()) return
        lastAnnouncedIdx = idx
        lastAnnounceMs   = SystemClock.elapsedRealtime()
        Log.d(TAG, "안내[$idx] ${step.description}")
        tts.speak(step.description, TextToSpeech.QUEUE_ADD, null, "nav-$idx")
    }

    /** Haversine 거리 (m) */
    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
