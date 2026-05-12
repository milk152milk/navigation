package com.safestep.app

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.safestep.app.assistant.AssistantAction
import com.safestep.app.assistant.VoiceAssistant
import com.safestep.app.detect.RemoteDetector
import java.util.Locale

class SplashActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var serverUrlInput: EditText
    private lateinit var voiceAssistant: VoiceAssistant

    companion object {
        const val PREFS_NAME = "safestep_prefs"
        const val KEY_SERVER_URL   = "server_url"
        const val KEY_INTRO_SHOWN  = "intro_shown"   // 최초 1회 인트로 안내 여부
        const val DEFAULT_SERVER_URL = "https://unglued-grill-unlovely.ngrok-free.dev/detect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        tts = TextToSpeech(this, this)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 다크모드 적용
        SettingsActivity.applyDarkMode(prefs.getInt(SettingsActivity.KEY_DARK_MODE, 0))

        // 글자 크기 적용
        val fontScale = listOf(0.85f, 1.0f, 1.15f)[prefs.getInt(SettingsActivity.KEY_TEXT_SIZE, 1)]
        val config = resources.configuration
        if (config.fontScale != fontScale) {
            config.fontScale = fontScale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }

        // 화면 항상 켜기
        if (prefs.getBoolean(SettingsActivity.KEY_KEEP_SCREEN, true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // 서버 URL 입력창 — 저장된 값 불러오기
        serverUrlInput = findViewById(R.id.serverUrlInput)
        val savedUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        serverUrlInput.setText(savedUrl)
        RemoteDetector.SERVER_URL = savedUrl

        // 카메라 모드 카드
        findViewById<android.view.View>(R.id.startButton).setOnClickListener {
            saveServerUrl()
            tts.stop()
            startActivity(Intent(this, MapActivity::class.java))
            finish()
        }

        // 동영상 테스트 카드
        findViewById<android.view.View>(R.id.videoTestButton).setOnClickListener {
            saveServerUrl()
            tts.stop()
            startActivity(Intent(this, VideoTestActivity::class.java))
            finish()
        }

        // 음성 어시스턴트
        val baseUrl = savedUrl.trimEnd('/').removeSuffix("/detect")
        voiceAssistant = VoiceAssistant(
            activity      = this,
            serverBaseUrl = baseUrl,
            screen        = "splash",
            onAction      = { action -> handleAssistantAction(action) },
        )
        voiceAssistant.attachLongPressTo(findViewById(android.R.id.content))

        // 하단 탭바
        setupBottomNav()
    }

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_home
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_guide -> {
                    saveServerUrl()
                    tts.stop()
                    startActivity(Intent(this, MapActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_record -> {
                    startActivity(Intent(this, RecordActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun handleAssistantAction(action: AssistantAction) {
        when (action) {
            is AssistantAction.EnterCameraMode -> {
                saveServerUrl()
                tts.stop()
                startActivity(Intent(this, MapActivity::class.java))
                finish()
            }
            is AssistantAction.EnterVideoTestMode -> {
                saveServerUrl()
                tts.stop()
                startActivity(Intent(this, VideoTestActivity::class.java))
                finish()
            }
            else -> { /* SpeakOnly 등은 VoiceAssistant가 자동으로 TTS 발화 */ }
        }
    }

    private fun saveServerUrl() {
        val url = serverUrlInput.text.toString().trim().trimEnd('/')
        if (url.isNotEmpty()) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_URL, url)
                .apply()
            RemoteDetector.SERVER_URL = url
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            val prefs   = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val introOn = prefs.getBoolean(SettingsActivity.KEY_INTRO_SOUND, true)
            // 앱 설치 후 최초 1회만 인트로 음성 안내
            val alreadyShown = prefs.getBoolean(KEY_INTRO_SHOWN, false)
            if (introOn && !alreadyShown) {
                prefs.edit().putBoolean(KEY_INTRO_SHOWN, true).apply()
                tts.speak(
                    "SafeStep. 카메라 모드 또는 동영상 테스트 모드를 선택해주세요.",
                    TextToSpeech.QUEUE_FLUSH, null, "splash-intro"
                )
            }
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        voiceAssistant.release()
        super.onDestroy()
    }
}
