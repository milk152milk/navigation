package com.safestep.app.assistant

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * /assistant 엔드포인트 호출 클라이언트.
 *
 * @param baseUrl  서버 base URL (예: "https://xxx.ngrok.io" — 끝에 슬래시 X)
 */
class AssistantClient(private val baseUrl: String) {

    /** API 키 미설정(503) */
    class ApiKeyException(msg: String) : Exception(msg)
    /** 그 외 서버 오류 */
    class ServerException(val code: Int, msg: String) : Exception(msg)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 사용자 발화 + 앱 컨텍스트를 보내고 AssistantAction 을 반환.
     * 호출 스레드를 블로킹하므로 코루틴(IO Dispatcher)에서 호출하세요.
     */
    fun ask(text: String, context: Map<String, Any?> = emptyMap()): AssistantAction {
        val body = JSONObject().apply {
            put("text", text)
            put("context", JSONObject(context))
        }.toString().toRequestBody(JSON_MEDIA)

        val url = "$baseUrl/assistant"
        Log.d("AssistantClient", "요청 URL: $url")

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                when (response.code) {
                    503 -> throw ApiKeyException("GOOGLE_API_KEY 미설정: $raw")
                    else -> throw ServerException(response.code, "HTTP ${response.code}: $raw")
                }
            }
            val json = JSONObject(raw)
            val action = json.optString("action", "speak_only")
            val tts    = json.optString("tts", "")
            val params = json.optJSONObject("params")?.toMap() ?: emptyMap()
            return AssistantAction.fromJson(action, params, tts)
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = buildMap {
        keys().forEach { put(it, this@toMap.opt(it)) }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
