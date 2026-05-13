package com.safestep.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.safestep.app.detect.RemoteDetector

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // 화면 설정
    private lateinit var tmSystem: TextView
    private lateinit var tmLight: TextView
    private lateinit var tmDark: TextView
    private lateinit var tsSmall: TextView
    private lateinit var tsNormal: TextView
    private lateinit var tsLarge: TextView
    private lateinit var switchKeepScreen: Switch

    // 음성 안내
    private lateinit var ttsSpeedSeek: SeekBar
    private lateinit var ttsSpeedLabel: TextView
    @Suppress("DEPRECATION") private lateinit var vibrationSwitch: Switch
    @Suppress("DEPRECATION") private lateinit var switchIntroSound: Switch

    // 탐지 설정
    private lateinit var sensSensitive: TextView
    private lateinit var sensNormal: TextView
    private lateinit var sensBlunt: TextView
    private lateinit var cdShort: TextView
    private lateinit var cdNormal: TextView
    private lateinit var cdLong: TextView
    private lateinit var fiFast: TextView
    private lateinit var fiNormal: TextView
    private lateinit var fiSave: TextView

    // 내비게이션
    private lateinit var ndEarly: TextView
    private lateinit var ndNormal: TextView
    private lateinit var ndLate: TextView

    // 실험 기능
    private lateinit var switchLlmEnabled: Switch
    private lateinit var switchSignalEnabled: Switch

    // 서버
    private lateinit var serverUrlInput: EditText
    private lateinit var saveUrlButton: MaterialButton

    companion object {
        const val KEY_TTS_SPEED        = "tts_speed"
        const val KEY_VIBRATION        = "vibration_enabled"
        const val KEY_DARK_MODE        = "dark_mode"       // 0=시스템 1=라이트 2=다크
        const val KEY_TEXT_SIZE        = "text_size"       // 0=작게 1=보통 2=크게
        const val KEY_KEEP_SCREEN      = "keep_screen_on"
        const val KEY_INTRO_SOUND      = "intro_sound"
        const val KEY_SENSITIVITY      = "detect_sensitivity" // 0=민감 1=보통 2=둔감
        const val KEY_COOLDOWN         = "warn_cooldown"      // 0=짧게 1=보통 2=길게
        const val KEY_FRAME_INTERVAL   = "frame_interval"     // 0=빠름 1=보통 2=절약
        const val KEY_NAV_DIST         = "nav_distance"       // 0=빠른 1=보통 2=늦은
        const val KEY_LLM_ENABLED      = "llm_enabled"        // LLM 음성 어시스턴트
        const val KEY_SIGNAL_ENABLED   = "signal_enabled"     // 신호등 감지
        const val DEFAULT_LLM_ENABLED    = true
        const val DEFAULT_SIGNAL_ENABLED = false

        val TTS_SPEED_LABELS  = listOf("느리게", "조금 느리게", "보통", "조금 빠르게", "빠르게")
        val TTS_SPEED_VALUES  = listOf(0.7f, 0.9f, 1.1f, 1.3f, 1.5f)

        // 탐지 감도 → DANGER_AREA 값
        val SENSITIVITY_VALUES = listOf(0.10f, 0.20f, 0.30f)

        // 경고 쿨다운 ms
        val COOLDOWN_VALUES = listOf(1500L, 2500L, 4000L)

        // 탐지 프레임 간격 ms
        val FRAME_INTERVAL_VALUES = listOf(300L, 500L, 800L)

        // 내비 안내 거리 (listOf listOf)
        val NAV_DIST_VALUES = listOf(
            listOf(200, 80, 30),   // 빠른 알림
            listOf(150, 60, 25),   // 보통 (기본)
            listOf(100, 40, 15)    // 늦은 알림
        )

        /** AppCompatDelegate에 다크모드 적용 (전역 호출용) */
        fun applyDarkMode(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1    -> AppCompatDelegate.MODE_NIGHT_NO
                    2    -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(SplashActivity.PREFS_NAME, MODE_PRIVATE)

        bindViews()
        loadSettings()
        setupListeners()
        setupBottomNav()
        applyKeepScreen()
    }

    private fun bindViews() {
        tmSystem        = findViewById(R.id.tmSystem)
        tmLight         = findViewById(R.id.tmLight)
        tmDark          = findViewById(R.id.tmDark)
        tsSmall         = findViewById(R.id.tsSmall)
        tsNormal        = findViewById(R.id.tsNormal)
        tsLarge         = findViewById(R.id.tsLarge)
        @Suppress("DEPRECATION")
        switchKeepScreen = findViewById(R.id.switchKeepScreen)

        ttsSpeedSeek    = findViewById(R.id.ttsSpeedSeek)
        ttsSpeedLabel   = findViewById(R.id.ttsSpeedLabel)
        @Suppress("DEPRECATION")
        vibrationSwitch = findViewById(R.id.vibrationSwitch)
        @Suppress("DEPRECATION")
        switchIntroSound = findViewById(R.id.switchIntroSound)

        sensSensitive   = findViewById(R.id.sensSensitive)
        sensNormal      = findViewById(R.id.sensNormal)
        sensBlunt       = findViewById(R.id.sensBlunt)
        cdShort         = findViewById(R.id.cdShort)
        cdNormal        = findViewById(R.id.cdNormal)
        cdLong          = findViewById(R.id.cdLong)
        fiFast          = findViewById(R.id.fiFast)
        fiNormal        = findViewById(R.id.fiNormal)
        fiSave          = findViewById(R.id.fiSave)

        ndEarly         = findViewById(R.id.ndEarly)
        ndNormal        = findViewById(R.id.ndNormal)
        ndLate          = findViewById(R.id.ndLate)

        @Suppress("DEPRECATION")
        switchLlmEnabled    = findViewById(R.id.switchLlmEnabled)
        @Suppress("DEPRECATION")
        switchSignalEnabled = findViewById(R.id.switchSignalEnabled)

        serverUrlInput  = findViewById(R.id.serverUrlInput)
        saveUrlButton   = findViewById(R.id.saveUrlButton)
    }

    private fun loadSettings() {
        // 화면 모드
        selectToggle(listOf(tmSystem, tmLight, tmDark), prefs.getInt(KEY_DARK_MODE, 0))

        // 글자 크기
        selectToggle(listOf(tsSmall, tsNormal, tsLarge), prefs.getInt(KEY_TEXT_SIZE, 1))

        // 화면 항상 켜기
        switchKeepScreen.isChecked = prefs.getBoolean(KEY_KEEP_SCREEN, true)

        // TTS 속도
        val speedIdx = prefs.getInt(KEY_TTS_SPEED, 2)
        ttsSpeedSeek.progress  = speedIdx
        ttsSpeedLabel.text     = TTS_SPEED_LABELS[speedIdx]

        // 진동 / 시작 안내음
        vibrationSwitch.isChecked  = prefs.getBoolean(KEY_VIBRATION, true)
        switchIntroSound.isChecked = prefs.getBoolean(KEY_INTRO_SOUND, true)

        // 실험 기능 (LLM / 신호등)
        switchLlmEnabled.isChecked    = prefs.getBoolean(KEY_LLM_ENABLED, DEFAULT_LLM_ENABLED)
        switchSignalEnabled.isChecked = prefs.getBoolean(KEY_SIGNAL_ENABLED, DEFAULT_SIGNAL_ENABLED)

        // 탐지 감도
        selectToggle(listOf(sensSensitive, sensNormal, sensBlunt), prefs.getInt(KEY_SENSITIVITY, 1))

        // 경고 쿨다운
        selectToggle(listOf(cdShort, cdNormal, cdLong), prefs.getInt(KEY_COOLDOWN, 1))

        // 프레임 간격
        selectToggle(listOf(fiFast, fiNormal, fiSave), prefs.getInt(KEY_FRAME_INTERVAL, 1))

        // 내비 거리
        selectToggle(listOf(ndEarly, ndNormal, ndLate), prefs.getInt(KEY_NAV_DIST, 1))

        // 서버 URL
        serverUrlInput.setText(
            prefs.getString(SplashActivity.KEY_SERVER_URL, SplashActivity.DEFAULT_SERVER_URL)
        )
    }

    private fun setupListeners() {
        // ── 화면 모드 ─────────────────────────────────────────────
        setToggleListeners(listOf(tmSystem, tmLight, tmDark), KEY_DARK_MODE) { idx ->
            applyDarkMode(idx)
            recreate()   // 즉시 테마 전환
        }

        // ── 글자 크기 ──────────────────────────────────────────────
        setToggleListeners(listOf(tsSmall, tsNormal, tsLarge), KEY_TEXT_SIZE) { _ ->
            Toast.makeText(this, "앱을 재시작하면 적용됩니다", Toast.LENGTH_SHORT).show()
        }

        // ── 화면 항상 켜기 ─────────────────────────────────────────
        switchKeepScreen.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN, checked).apply()
            applyKeepScreen()
        }

        // ── TTS 속도 ───────────────────────────────────────────────
        ttsSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                ttsSpeedLabel.text = TTS_SPEED_LABELS[progress]
                if (fromUser) prefs.edit().putInt(KEY_TTS_SPEED, progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── 진동 / 시작 안내음 ─────────────────────────────────────
        vibrationSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_VIBRATION, checked).apply()
        }
        switchIntroSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_INTRO_SOUND, checked).apply()
        }

        // ── 실험 기능 (LLM / 신호등) ───────────────────────────────
        switchLlmEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LLM_ENABLED, checked).apply()
        }
        switchSignalEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SIGNAL_ENABLED, checked).apply()
        }

        // ── 탐지 감도 ──────────────────────────────────────────────
        setToggleListeners(listOf(sensSensitive, sensNormal, sensBlunt), KEY_SENSITIVITY)

        // ── 경고 쿨다운 ────────────────────────────────────────────
        setToggleListeners(listOf(cdShort, cdNormal, cdLong), KEY_COOLDOWN)

        // ── 프레임 간격 ────────────────────────────────────────────
        setToggleListeners(listOf(fiFast, fiNormal, fiSave), KEY_FRAME_INTERVAL)

        // ── 내비 거리 ──────────────────────────────────────────────
        setToggleListeners(listOf(ndEarly, ndNormal, ndLate), KEY_NAV_DIST)

        // ── 서버 URL 저장 ──────────────────────────────────────────
        saveUrlButton.setOnClickListener {
            val url = serverUrlInput.text.toString().trim().trimEnd('/')
            if (url.isNotEmpty()) {
                prefs.edit().putString(SplashActivity.KEY_SERVER_URL, url).apply()
                RemoteDetector.SERVER_URL = url
            }
        }
    }

    // ── 토글 유틸 ──────────────────────────────────────────────────────────────

    private fun selectToggle(buttons: List<TextView>, selectedIdx: Int) {
        buttons.forEachIndexed { idx, btn ->
            if (idx == selectedIdx) {
                btn.setBackgroundResource(R.drawable.bg_toggle_selected)
                btn.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            } else {
                btn.setBackgroundResource(R.drawable.bg_toggle_unselected)
                btn.setTextColor(ContextCompat.getColor(this, R.color.ss_toggle_text))
            }
        }
    }

    private fun setToggleListeners(
        buttons: List<TextView>,
        prefKey: String,
        onSelected: ((Int) -> Unit)? = null
    ) {
        buttons.forEachIndexed { idx, btn ->
            btn.setOnClickListener {
                selectToggle(buttons, idx)
                prefs.edit().putInt(prefKey, idx).apply()
                onSelected?.invoke(idx)
            }
        }
    }

    private fun applyKeepScreen() {
        if (prefs.getBoolean(KEY_KEEP_SCREEN, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_settings
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, SplashActivity::class.java)); finish(); true
                }
                R.id.nav_guide -> {
                    startActivity(Intent(this, MapActivity::class.java)); finish(); true
                }
                R.id.nav_record -> {
                    startActivity(Intent(this, RecordActivity::class.java)); finish(); true
                }
                R.id.nav_settings -> true
                else -> false
            }
        }
    }
}
