# SafeStep 코드 점검 체크리스트

> 대상: `C:\Users\User\github\SafeStep`
> 작성일: 2026-05-11
> 변경 사항: VideoTestActivity 보완 / FeatureFlags 추가 / 신호등 토글화 / 엄격 조건 적용 완료

---

## 🔥 1순위 — 정합성 + UX

- [ ] `MapActivity.kt:317-320` — `SignalClient` 생성 시 `RemoteDetector.SERVER_URL` 빈 문자열 체크
- [ ] `SignalClient.kt:43-47` — URL 파싱 (`substringBeforeLast` 중첩) `/signal` 없을 때 견고성
- [ ] `MapActivity.kt:1305-1325` — `handleTrafficLight()` 내부에서 `lastSurfaceStatus` 재확인 (조건 변경 가능성)
- [ ] `MapActivity.kt:1305` — 신호등 안내가 다른 음성 안내(횡단보도 진입 등)와 겹칠 때 QUEUE 정책 검토
- [ ] `VideoTestActivity.kt:onInit` — TTS 초기화 실패 시 `speak()` 호출 NPE 가드
- [ ] `SettingsActivity.kt` — 서버 URL 변경 시 이미 생성된 `signalClient` / `segClient` 동기화
- [ ] 접근성 (TalkBack): 새 토글 UI 2개(`switchLlmEnabled`, `switchSignalEnabled`)에 `contentDescription` 추가

---

## 🥇 2순위 — 라이프사이클 + 동시성

- [ ] `MapActivity.kt:462-466` — 5개 Executor (camera/detect/seg/fast/signal) shutdown + `awaitTermination` 타임아웃 처리
- [ ] `MapActivity.kt:119-121` — `signalClient` 도 `@Volatile` 검토 (다중 스레드에서 접근)
- [ ] `VoiceAssistant.kt:117-122` — `scope.cancel()` 호출 순서와 `recognizer.destroy()` 사이 race condition
- [ ] `MapActivity.kt:302-305` — `linearAccelSensor` / `rotationSensor` `onDestroy()`에도 unregister 안전망
- [ ] `SplashActivity.kt:checkServerConnection` — Activity 종료 시점 백그라운드 스레드 핸들링
- [ ] `RecordActivity.kt:57-58` — JSONArray 파싱 예외 시 UI 업데이트 누락
- [ ] `MapActivity.kt:reloadSettings()` — `signalEnabled` 갱신 후 진행 중인 신호등 요청 처리 정책 (즉시 취소 vs 완료 대기)

---

## 🥈 3순위 — 성능

- [ ] `MapActivity.kt:107-112` — AtomicBoolean 4개(detect/seg/fast/signal) 매 프레임 체크 오버헤드
- [ ] `MapActivity.kt:163` — `areaHistory` mutableMap LRU 또는 size cap
- [ ] `SignalClient.kt:64-67` — `CROP_TOP_RATIO = 0.50f` 보행자 시선 각도에 따라 신호등이 하단으로 갈 때 대응
- [ ] `SegmentationClient.kt:63` — `Bitmap.createScaledBitmap` 품질·속도 trade-off 재검토
- [ ] `MapActivity.kt:statsOverlay` — 매 1초 `LatencyTracker.summary()` 호출 시 가비지 생성
- [ ] `VideoTestActivity.kt:261-291` — MediaMetadataRetriever 디코드 캐시 활용
- [ ] 매 프레임 Bitmap.createBitmap 풀링 검토 (회전 보정)

---

## 🥉 4순위 — 경량화

- [ ] `FeatureFlags.kt` — 현재 참고용으로만 유지 / 진짜 안 쓰면 제거 검토
- [ ] `MapActivity.kt` — 매직 넘버 상수화 (frameCount % 2/4/6/9, 40m 이탈, 3초 배너 등)
- [ ] `server.py:784` — aspect ratio 범위 `0.4~3.0` 상수화
- [ ] `MapActivity.kt:1268-1294` — `onCrosswalk = false` 중복 할당 통합
- [ ] `Detection.kt` — 일부 Activity에서 미사용 필드 (`depthM`, `group`, `approachSpeed`) 점검
- [ ] `MainActivity.kt:283-293` — `labelToKoreanLegacy()` `@Suppress("unused")` 정리
- [ ] 사용 안 하는 import 정리 (Android Studio: Optimize Imports)

---

## 🪶 5순위 — 보안 + 문서

### 보안

- [ ] `SplashActivity.kt:30` — `DEFAULT_SERVER_URL` 하드코딩 → `BuildConfig` 분리
- [ ] `RemoteDetector.kt:29` — `SERVER_URL` 하드코딩 동일
- [ ] `assistant.py:39` — `GROQ_API_KEY` 빈 문자열 fallback → 명확한 에러 메시지
- [ ] `.env` 파일 `.gitignore` 등재 확인
- [ ] `MapActivity.kt:288` / `SegmentationClient.kt:37-39` — URL 파싱 견고화
- [ ] `server.py:15` — `KMP_DUPLICATE_LIB_OK` 환경변수 설정 주석 강화

### 문서

- [ ] `README.md` — 현재 기능 반영 (LLM 통합 완료, 신호등 토글 + 엄격 조건)
- [ ] `HANDOFF.md` — 최신 상태 업데이트
- [ ] `실행순서.md` / `서버_실행방법.md` — 엔드포인트 5개, ENABLE_SIGNAL 정책 명시
- [ ] `SettingsActivity.kt` — 실험 기능 (LLM, 신호등) 사용자 가이드
- [ ] 서버 `ENABLE_SIGNAL=True` ↔ 앱 `KEY_SIGNAL_ENABLED 기본 false` 불일치 문서화
- [ ] `SignalClient.kt` KDoc — `confidence == null` 의미 (통신 실패 vs 색상 미탐지) 구분 기술
- [ ] 주요 함수에 KDoc / docstring 추가 (`analyzeFrame`, `handleDetections`, `handleSegmentation`, `handleTrafficLight`)

---

## 작업 완료 후 체크

- [ ] `git status` 변경 파일 일관성 확인
- [ ] 빌드 테스트 (App + 서버 동시)
- [ ] 5가지 시연 시나리오 동작 확인 (사람·골목길·차도·계단·맨홀)
- [ ] LatencyTracker 오버레이 정상 표시
- [ ] 설정 → 실험 기능 → LLM / 신호등 토글 동작 확인
- [ ] 신호등 토글 ON 상태에서 횡단보도 외 위치에서 빨간 객체에 반응 안 함 확인
- [ ] `/health` 응답 확인 (signal 정보 포함 여부)
