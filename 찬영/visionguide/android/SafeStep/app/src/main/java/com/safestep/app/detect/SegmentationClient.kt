package com.safestep.app.detect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class SegmentResult(
    val maskBitmap: Bitmap?,      // RGBA 마스크 이미지 (오버레이용)
    val roadRatio: Float,          // 중앙 구역 차도 비율 0~1
    val sidewalkRatio: Float,      // 중앙 구역 보도 비율 0~1
    val status: String,            // "sidewalk" | "road" | "crosswalk" | "alley" | "unknown"
    val frontCls: String = "",     // 정면 노면 세부 클래스명 (예: "caution_zone_manhole")
    val leftStatus: String = "",   // 왼쪽 구역 status (방향 유도용)
    val rightStatus: String = "",  // 오른쪽 구역 status (방향 유도용)
    val trafficLightColor: String = ""  // "red" | "green" | "" (횡단보도일 때만)
)

class SegmentationClient(serverUrl: String) {

    companion object {
        private const val TAG = "SegmentClient"
        private const val JPEG_QUALITY = 70
    }

    private val segmentUrl = serverUrl.trimEnd('/').let {
        it.substringBeforeLast("/detect").substringBeforeLast("/segment") + "/segment"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 동기 호출 — 반드시 백그라운드 스레드에서 실행 */
    fun segment(bitmap: Bitmap, rotationDegrees: Int): SegmentResult? {
        return try {
            // 회전 보정
            val corrected = if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap

            // JPEG 압축
            val stream = ByteArrayOutputStream()
            corrected.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()

            // 전송
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "frame.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            val req = Request.Builder().url(segmentUrl).post(body).build()
            val resp = client.newCall(req).execute()

            if (!resp.isSuccessful) {
                Log.w(TAG, "서버 응답 실패: ${resp.code}")
                return null
            }

            parseResponse(resp.body?.string() ?: return null)
        } catch (e: Exception) {
            Log.w(TAG, "세그멘테이션 통신 실패: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String): SegmentResult? {
        return try {
            val obj    = JSONObject(json)
            val maskB64 = obj.getString("mask_b64")
            val status  = obj.getString("status")

            // 새 서버 응답: ratios 객체 안에 road/sidewalk/crosswalk/alley 포함
            val ratios       = obj.optJSONObject("ratios")
            val roadRatio    = ratios?.optDouble("road", 0.0)?.toFloat()
                ?: obj.optDouble("road_ratio", 0.0).toFloat()
            val sidewalkRatio = ratios?.optDouble("sidewalk", 0.0)?.toFloat()
                ?: obj.optDouble("sidewalk_ratio", 0.0).toFloat()

            // base64 → Bitmap
            val bytes      = Base64.decode(maskB64, Base64.DEFAULT)
            val maskBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val frontCls          = obj.optString("front_cls", "")
            val leftStatus        = obj.optString("left_status", "")
            val rightStatus       = obj.optString("right_status", "")
            val trafficLightColor = obj.optString("traffic_light_color", "")

            SegmentResult(maskBitmap, roadRatio, sidewalkRatio, status, frontCls, leftStatus, rightStatus, trafficLightColor)
        } catch (e: Exception) {
            Log.e(TAG, "응답 파싱 실패: ${e.message}")
            null
        }
    }
}
