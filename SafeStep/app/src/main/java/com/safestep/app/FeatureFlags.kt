package com.safestep.app

/**
 * Feature Flags — 기본값 참고용 (현재 코드에서 직접 사용 안 함)
 *
 * 실제 ON/OFF 제어는 사용자 설정에서:
 *   설정 → 실험 기능 → 음성 어시스턴트 / 신호등 감지
 *   (SharedPreferences: SettingsActivity.KEY_LLM_ENABLED / KEY_SIGNAL_ENABLED)
 *
 * 이 파일의 값은 SettingsActivity의 DEFAULT_LLM_ENABLED / DEFAULT_SIGNAL_ENABLED 와 동일.
 * 기본값을 바꾸려면 SettingsActivity의 DEFAULT_* 상수를 수정.
 */
object FeatureFlags {

    /** LLM 음성 어시스턴트 — 기본 ON */
    const val LLM_ENABLED = true

    /** 신호등 색상 감지 — 인식률 낮아 기본 OFF */
    const val SIGNAL_ENABLED = false

    /** Depth-Anything-V2 거리 추정 — 서버 자동 OFF 로직 별도 */
    const val DEPTH_ENABLED = true
}
