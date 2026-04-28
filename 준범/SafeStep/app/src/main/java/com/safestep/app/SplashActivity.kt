package com.safestep.app

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SplashActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        tts = TextToSpeech(this, this)

        // 카메라 모드 → MapActivity
        findViewById<Button>(R.id.startButton).setOnClickListener {
            tts.stop()
            startActivity(Intent(this, MapActivity::class.java))
            finish()
        }

        // 동영상 테스트 모드 → VideoTestActivity
        findViewById<Button>(R.id.videoTestButton).setOnClickListener {
            tts.stop()
            startActivity(Intent(this, VideoTestActivity::class.java))
            finish()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.speak(
                "SafeStep. 카메라 모드 또는 동영상 테스트 모드를 선택해주세요.",
                TextToSpeech.QUEUE_FLUSH, null, "splash-intro"
            )
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
