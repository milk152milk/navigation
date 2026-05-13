package com.safestep.app.detect

import android.os.SystemClock

/**
 * 엔드포인트별 RTT(Round-Trip Time)와 추론 FPS를 추적하는 싱글톤.
 *
 * 사용법:
 *   val t0 = SystemClock.elapsedRealtime()
 *   // ... HTTP 요청 ...
 *   LatencyTracker.recordFast(SystemClock.elapsedRealtime() - t0)
 *
 * 측정 항목:
 *   RTT  = 클라이언트 기준 왕복 시간 (이미지 인코딩 + 네트워크 + 서버 추론 + 응답 파싱)
 *   FPS  = 성공 응답 수 / 경과 시간 (sliding window)
 *   proc = 서버가 JSON에 포함해 보낸 순수 추론 시간 (proc_ms 필드)
 */
object LatencyTracker {

    private const val WINDOW = 10   // 롤링 윈도우 크기 (최근 N회)

    // ─── RTT 기록 (ms) ───────────────────────────────────────
    private val fastMs   = ArrayDeque<Long>()
    private val detectMs = ArrayDeque<Long>()
    private val segMs    = ArrayDeque<Long>()
    private val signalMs = ArrayDeque<Long>()

    // ─── 서버 측 순수 추론 시간 (proc_ms) ───────────────────
    private val fastProcMs   = ArrayDeque<Long>()
    private val detectProcMs = ArrayDeque<Long>()
    private val segProcMs    = ArrayDeque<Long>()

    // ─── FPS 계산용 성공 타임스탬프 ─────────────────────────
    private val fastTs   = ArrayDeque<Long>()
    private val detectTs = ArrayDeque<Long>()
    private val segTs    = ArrayDeque<Long>()

    // ─── 기록 함수 ───────────────────────────────────────────

    /** /fast 엔드포인트 RTT + (선택적) 서버 proc_ms */
    fun recordFast(rttMs: Long, procMs: Long? = null) {
        add(fastMs, rttMs); addTs(fastTs)
        procMs?.let { add(fastProcMs, it) }
    }

    /** /detect 엔드포인트 RTT + (선택적) 서버 proc_ms */
    fun recordDetect(rttMs: Long, procMs: Long? = null) {
        add(detectMs, rttMs); addTs(detectTs)
        procMs?.let { add(detectProcMs, it) }
    }

    /** /segment 엔드포인트 RTT + (선택적) 서버 proc_ms */
    fun recordSeg(rttMs: Long, procMs: Long? = null) {
        add(segMs, rttMs); addTs(segTs)
        procMs?.let { add(segProcMs, it) }
    }

    /** /signal 엔드포인트 RTT */
    fun recordSignal(rttMs: Long) { add(signalMs, rttMs) }

    private fun add(q: ArrayDeque<Long>, v: Long) {
        if (q.size >= WINDOW) q.removeFirst()
        q.addLast(v)
    }

    private fun addTs(q: ArrayDeque<Long>) {
        val now = SystemClock.elapsedRealtime()
        if (q.size >= WINDOW) q.removeFirst()
        q.addLast(now)
    }

    // ─── 평균 RTT ────────────────────────────────────────────
    fun avgFast()   = avg(fastMs)
    fun avgDetect() = avg(detectMs)
    fun avgSeg()    = avg(segMs)
    fun avgSignal() = avg(signalMs)

    // ─── 평균 서버 proc_ms ───────────────────────────────────
    fun avgFastProc()   = avg(fastProcMs)
    fun avgDetectProc() = avg(detectProcMs)
    fun avgSegProc()    = avg(segProcMs)

    private fun avg(q: ArrayDeque<Long>) = if (q.isEmpty()) 0L else q.average().toLong()

    // ─── FPS ─────────────────────────────────────────────────
    fun fastFps()   = fps(fastTs)
    fun detectFps() = fps(detectTs)
    fun segFps()    = fps(segTs)

    private fun fps(q: ArrayDeque<Long>): Float {
        if (q.size < 2) return 0f
        val span = q.last() - q.first()
        return if (span > 0) (q.size - 1) * 1000f / span else 0f
    }

    // ─── 화면 표시용 요약 문자열 ─────────────────────────────
    fun summary(): String = buildString {
        if (fastMs.isNotEmpty()) {
            val proc = if (fastProcMs.isNotEmpty()) " [srv:${avgFastProc()}ms]" else ""
            appendLine("FAST  %3dms  %.1ffps%s".format(avgFast(), fastFps(), proc))
        }
        if (detectMs.isNotEmpty()) {
            val proc = if (detectProcMs.isNotEmpty()) " [srv:${avgDetectProc()}ms]" else ""
            appendLine("DET   %3dms  %.1ffps%s".format(avgDetect(), detectFps(), proc))
        }
        if (segMs.isNotEmpty()) {
            val proc = if (segProcMs.isNotEmpty()) " [srv:${avgSegProc()}ms]" else ""
            appendLine("SEG   %3dms  %.1ffps%s".format(avgSeg(), segFps(), proc))
        }
        if (signalMs.isNotEmpty()) {
            appendLine("SIG   %3dms".format(avgSignal()))
        }
    }.trimEnd()
}
