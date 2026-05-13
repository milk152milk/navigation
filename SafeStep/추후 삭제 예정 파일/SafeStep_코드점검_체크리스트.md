# SafeStep 코드 점검 체크리스트

> 대상 경로: `C:\Users\User\github\navigation\준범\SafeStep`
> 작성일: 2026-05-11

---

## 🚨 선행 작업 — 신호등 기능 제거

**전체 신호등 코드 삭제**

### `server/server.py`
- [ ] 4행: `# - /signal : 신호등 색상 감지` 주석 제거
- [ ] 80-88행: `signal_model = YOLO("yolov8n.pt")` 로드 코드 제거
- [ ] 91행: `_light_history: deque = deque(maxlen=6)` 제거
- [ ] 315행: `_TRAFFIC_LIGHT_CLASS = 9` 상수 제거
- [ ] 318-348행: `_detect_light_color()` 함수 제거
- [ ] 351-375행: `_traffic_light_status()` 함수 제거
- [ ] 376행 이하: `_scan_for_light_blob()` 함수 제거
- [ ] 428행: `/health` 응답의 `"signal": "yolov8n" if signal_model` 항목 제거
- [ ] 726-773행: `@app.post("/signal")` 엔드포인트 전체 제거
- [ ] `yolov8n.pt` 파일 삭제

### `app/.../detect/SignalClient.kt`
- [ ] 파일 전체 삭제

### `app/.../detect/LatencyTracker.kt`
- [ ] 26행: `signalMs: ArrayDeque` 제거
- [ ] 58-59행: `fun recordSignal(rttMs)` 제거
- [ ] 76행: `fun avgSignal()` 제거
- [ ] 110-111행: `"SIG %3dms".format(avgSignal())` 오버레이 제거

### `app/.../MapActivity.kt`
- [ ] SignalClient import 제거
- [ ] signalClient 인스턴스 제거
- [ ] `analyzeFrame()` 내부 signal 호출 블록 (907-919행 부근) 제거
- [ ] `handleTrafficLight()` 함수 제거
- [ ] signalBusy AtomicBoolean 제거
- [ ] signalExecutor 제거 + onDestroy() 정리

---

## 🔥 1순위 — 정합성 + UX

- [ ] `SettingsActivity.kt:74` — SENSITIVITY_VALUES [0.10f, 0.20f, 0.30f] 설정값 ↔ `MainActivity.kt:77` DANGER_AREA = 0.20f 고정값 싱크
- [ ] `MapActivity.kt:280` — vibrationSwitch 설정 로드 후 실제 적용 확인
- [ ] `VideoTestActivity.kt:335` — `handleSegmentation()` 함수 미완성 (lastSurfaceStatus 업데이트 누락)
- [ ] `MainActivity.kt:282-293` — `labelToKoreanLegacy()` 함수 (`@Suppress("unused")`) 정리
- [ ] `SplashActivity.kt:150` — `checkServerConnection()` 스레드에서 isDestroyed 체크 시 Activity 참조 안전성 검토
- [ ] `MapActivity.kt:152` — `isListening` @Volatile + cameraExecutor 동기화
- [ ] 접근성: 모든 커스텀 뷰 contentDescription 확인 (BoundingBoxOverlay 등)
- [ ] TalkBack assertive 라이브 리전 검증

---

## 🥇 2순위 — 라이프사이클 + 동시성

### Activity 라이프사이클
- [ ] `MapActivity.kt:302-305` — linearAccelSensor / rotationSensor: onPause()에서만 unregisterListener → onDestroy()에도 안전망 추가
- [ ] `MapActivity.kt:421-436` — `onDestroy()`: mainHandler.removeCallbacks(statsRunnable) 실행 순서 검토
- [ ] `SplashActivity.kt:149-175` — `checkServerConnection()` 백그라운드 스레드: Activity 종료 시점 핸들링

### Executor / Thread shutdown
- [ ] `MapActivity.kt:427-429` — cameraExecutor / detectExecutor / segExecutor / fastExecutor 4종: shutdown() + awaitTermination 타임아웃 처리
- [ ] `VideoTestActivity.kt:122` — `stopFrameDetection()`: frameHandler.removeCallbacks ↔ isDetecting=false 타이밍 경쟁

### VoiceAssistant
- [ ] `VoiceAssistant.kt:118-121` — `release()`: tts.stop() / shutdown() 호출 시 발화 중 예외 가드
- [ ] `SplashActivity.kt:209` — `voiceAssistant.release()` → 내부 코루틴(scope.cancel()) + 네트워크 요청 중단 보증

### 기타
- [ ] `RecordActivity.kt:57-58` — JSONArray 파싱 예외 시 UI 업데이트 누락

---

## 🥈 3순위 — 성능

- [ ] `MapActivity.kt:107-109` — AtomicBoolean(detectBusy, segBusy, fastBusy) 매 프레임 체크 오버헤드 측정
- [ ] `MapActivity.kt:163` — areaHistory mutableMap: 객체 수 많을 때 메모리 증가 방지 (LRU 또는 size cap)
- [ ] `VideoTestActivity.kt:261-291` — `extractAndDetect()` 500ms마다 MediaMetadataRetriever.getFrameAtTime() → 디코드 캐시 활용
- [ ] Bitmap 회전 후 메모리 즉시 해제 확인 (`bitmap.recycle()`)
- [ ] 매 프레임 객체 생성 (Bitmap.createBitmap 등) 풀링 검토

---

## 🥉 4순위 — 경량화

- [ ] `Detection.kt:18-22` — depthM / group / approachSpeed 미사용 필드 정리
- [ ] `MainActivity.kt:283-293` — `labelToKoreanLegacy()` 레거시 함수 삭제
- [ ] 사용 안 하는 import 정리 (Android Studio: Optimize Imports)
- [ ] `server.py:173-194` — `_cache_get` / `_cache_set` 신호등 제거 후 사용처 재검토
- [ ] 매직 넘버 → 상수화 (예: 40m 이탈, 250ms depth off 임계값)

---

## 🪶 5순위 — 보안 + 문서

### 보안
- [ ] `SplashActivity.kt:30` — DEFAULT_SERVER_URL 하드코딩 (`unglued-grill-unlovely.ngrok-free.dev/detect`) → 빌드 설정/BuildConfig로 분리
- [ ] `RemoteDetector.kt:29` — SERVER_URL 하드코딩 (`ungloved-grill-unlovely.ngrok-free.dev/detect`) → 동일
- [ ] `assistant.py:39` — `GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")` → 빈 키 시 명확한 에러 메시지
- [ ] `.env` 파일 `.gitignore` 등재 확인
- [ ] `MapActivity.kt:288` / `SegmentationClient.kt:37-39` — URL 파싱 (`removeSuffix("/detect")`, `substringBeforeLast`) → 견고한 파싱으로 교체

### 문서
- [ ] `README.md` — 신호등 기능 언급 제거
- [ ] `HANDOFF.md` — 현재 상태 반영 (LLM 통합 완료, 신호등 제거)
- [ ] `실행순서.md` / `서버_실행방법.md` — 최신 엔드포인트 목록 반영
- [ ] 주요 함수에 KDoc / docstring 추가 (analyzeFrame, handleDetections, detect, segment 등)

---

## 작업 완료 후 체크

- [ ] `git status` 정리 → 변경된 파일 일관성 확인
- [ ] 빌드 테스트 (App 빌드 + 서버 실행)
- [ ] 시연 시나리오 5종 전부 동작 확인 (사람·골목길·차도·계단·맨홀)
- [ ] LatencyTracker 오버레이에 SIG 항목 없음 확인
- [ ] /health 응답에 signal 없음 확인
