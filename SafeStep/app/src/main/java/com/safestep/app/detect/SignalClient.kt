package com.safestep.app.detect

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 신호등 색상 감지 결과.
 *
 * @param color "red" | "green" | "blinking" | null
 * @param confidence 0~1 (색상 감지 못한 경우 null)
 */
data class SignalResult(
    val color: String?,
    val confidence: Float?
)

/**
 * 서버 /signal 엔드포인트 호출 클라이언트.
 * SegmentationClient 와 동일한 패턴으로 동작.
 * 반드시 백그라운드 스레드에서 호출해야 합니다.
 */
class SignalClient(serverUrl: String) {

    companion object {
        private const val TAG          = "SignalClient"
        private const val JPEG_QUALITY = 70
        private const val MAX_SIDE     = 640  // 신호등은 작고 멀어서 고해상도 필요
        /** 신호등은 화면 상단에 있으므로 상단 50%만 크롭 → 작은 신호등이 상대적으로 커짐 */
        private const val CROP_TOP_RATIO = 0.50f
    }

    // SERVER_URL 형식이 http://host:port/detect 등 다양할 수 있어서 베이스만 추출
    private val signalUrl = serverUrl
        .trimEnd('/')
        .substringBeforeLast("/detect")
        .substringBeforeLast("/segment")
        .substringBeforeLast("/signal") + "/signal"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 신호등 색상 감지. null 반환 시 서버 통신 실패 또는 모델 비활성 */
    fun detect(bitmap: Bitmap, rotationDegrees: Int): SignalResult? {
        return try {
            // 회전 보정
            val corrected = if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap

            // 상단 40% 크롭 — 신호등은 항상 화면 위쪽에 있으므로
            // 잘라내면 신호등이 상대적으로 커져 탐지율 대폭 향상
            val cropH   = (corrected.height * CROP_TOP_RATIO).toInt().coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(corrected, 0, 0, corrected.width, cropH)

            // 리사이즈 — 긴 변 640px (작은 신호등 검출을 위해 고해상도 유지)
            val scale  = MAX_SIDE.toFloat() / maxOf(cropped.width, cropped.height)
            val toSend = if (scale < 1f)
                Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width  * scale).toInt(),
                    (cropped.height * scale).toInt(),
                    true   // 품질 우선 필터링
                )
            else cropped

            // JPEG 압축
            val stream = ByteArrayOutputStream()
            toSend.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()

            // Multipart POST
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "frame.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            val req   = Request.Builder().url(signalUrl).post(body).build()
            val t0    = SystemClock.elapsedRealtime()
            val resp  = client.newCall(req).execute()
            val rttMs = SystemClock.elapsedRealtime() - t0

            if (!resp.isSuccessful) {
                Log.w(TAG, "서버 응답 실패: ${resp.code}")
                return null
            }

            LatencyTracker.recordSignal(rttMs)
            parseResponse(resp.body?.string() ?: return null)
        } catch (e: Exception) {
            Log.w(TAG, "신호등 통신 실패: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String): SignalResult? {
        return try {
            val obj   = JSONObject(json)
            val color = obj.optString("color").takeIf { it.isNotEmpty() && it != "null" }
            val conf  = if (obj.isNull("confidence")) null
                        else obj.optDouble("confidence", 0.0).toFloat()
            SignalResult(color, conf)
        } catch (e: Exception) {
            Log.e(TAG, "신호등 응답 파싱 실패: ${e.message}")
            null
        }
    }
}
