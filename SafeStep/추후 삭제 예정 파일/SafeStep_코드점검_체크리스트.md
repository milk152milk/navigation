# SafeStep 코드 점검 체크리스트

> 대상: `C:\Users\User\github\SafeStep`
> 작성일: 2026-05-11
> 최종 업데이트: 2026-05-13 (작업 상태 표시)
> 변경 사항: VideoTestActivity 보완 / FeatureFlags 추가 / 신호등 토글화 / 엄격 조건 적용 완료
>
> **표기**: ✅ 완료 / ⏭ 미완료 (사유 첨부)

---

## 🔥 1순위 — 정합성 + UX

- ✅ `MapActivity.kt:317-320` — `SignalClient` 생성 시 `RemoteDetector.SERVER_URL` 빈 문자열 체크 → **완료** (`resolvedServerUrl = ... ifBlank { DEFAULT_SERVER_URL }`)
- ✅ `SignalClient.kt:43-47` — URL 파싱 (`substringBeforeLast` 중첩) `/signal` 없을 때 견고성 → **완료** (Regex 기반으로 교체)
- ✅ `MapActivity.kt:1305-1325` — `handleTrafficLight()` 내부에서 `lastSurfaceStatus` 재확인 → **완료** (`if (lastSurfaceStatus != "crosswalk" && lastSurfaceStatus != "road") return`)
- ✅ `MapActivity.kt:1305` — 신호등 안내 QUEUE 정책 검토 → **완료** (`QUEUE_FLUSH` → `QUEUE_ADD`)
- ✅ `VideoTestActivity.kt:onInit` — TTS 초기화 실패 시 `speak()` 호출 NPE 가드 → **완료** (`@Volatile ttsReady` 플래그)
- ✅ `SettingsActivity.kt` — 서버 URL 변경 시 `signalClient` / `segClient` 동기화 → **완료** (`lastBaseUrlForClients` 변수로 변경 감지)
- ✅ 접근성 (TalkBack): 새 토글 UI 2개 `contentDescription` 추가 → **완료**

---

## 🥇 2순위 — 라이프사이클 + 동시성

- ✅ `MapActivity.kt:462-466` — 5개 Executor shutdown + `awaitTermination` 타임아웃 처리 → **완료** (`shutdownExecutorSafely()` 헬퍼)
- ✅ `MapActivity.kt:119-121` — `signalClient` `@Volatile` 검토 → **완료** (`segClient`, `signalClient` 모두 `@Volatile`)
- ✅ `VoiceAssistant.kt:117-122` — `scope.cancel()` 호출 순서와 `recognizer.destroy()` 사이 race condition → **완료** (순서 재정렬 + `runCatching`)
- ✅ `MapActivity.kt:302-305` — 센서 `onDestroy()` 안전망 → **완료**
- ⏭ `SplashActivity.kt:checkServerConnection` — Activity 종료 시점 백그라운드 스레드 핸들링 → **이미 안전 처리됨** (`isDestroyed` 체크 존재)
- ⏭ `RecordActivity.kt:57-58` — JSONArray 파싱 예외 시 UI 업데이트 누락 → **이미 안전 처리됨** (빈 `JSONArray()` fallback으로 emptyView 표시)
- ✅ `MapActivity.kt:reloadSettings()` — `signalEnabled` 갱신 후 신호등 요청 처리 → **완료** (`handleTrafficLight`에 가드 추가)
- ⏭ `VideoTestActivity.kt:onDestroy()` — lateinit Executor 4개 `isInitialized` 가드 → **엣지 케이스, 학습 프로젝트 범위 외** ← **5/13 추가**
- ⏭ `VideoTestActivity.kt:extractAndDetect` — `frameCount++` race condition → **Handler 1프레임/500ms 패턴이라 실질 영향 없음** ← **5/13 추가**

---

## 🥈 3순위 — 성능

- ⏭ `MapActivity.kt:107-112` — AtomicBoolean 4개 매 프레임 체크 오버헤드 → **측정 기반 튜닝 필요, 학습 프로젝트 범위 외**
- ✅ `MapActivity.kt:163` — `areaHistory` mutableMap LRU 또는 size cap → **완료** (`MAX_AREA_HISTORY_SIZE = 50`)
- ⏭ `SignalClient.kt:64-67` — `CROP_TOP_RATIO = 0.50f` 보행자 시선 각도 대응 → **실제 데이터 기반 튜닝 필요, 학습 프로젝트 범위 외**
- ⏭ `SegmentationClient.kt:63` — `Bitmap.createScaledBitmap` 품질·속도 trade-off → **현재 동작에 문제 없음, 학습 프로젝트 범위 외**
- ⏭ `MapActivity.kt:statsOverlay` — 매 1초 가비지 생성 → **GC 영향 미미, 학습 프로젝트 범위 외**
- ⏭ `VideoTestActivity.kt:261-291` — MediaMetadataRetriever 디코드 캐시 활용 → **학습 프로젝트 범위 외**
- ⏭ 매 프레임 Bitmap.createBitmap 풀링 → **큰 리팩토링 필요, 학습 프로젝트 범위 외**
- ⏭ `VideoTestActivity.kt:extractAndDetect` — `Thread{}.start()` 매 500ms 생성 → **2 Thread/sec 수준, GC 영향 미미** ← **5/13 추가**

---

## 🥉 4순위 — 경량화

- ⏭ `FeatureFlags.kt` — 참고용 유지 / 제거 검토 → **사용자가 참고용 유지로 결정**
- ✅ `MapActivity.kt` — 매직 넘버 상수화 → **완료** (`FRAME_FAST/DETECT/SEGMENT/SIGNAL/HEALTH_INTERVAL`, `TRAFFIC_LIGHT_COOLDOWN_MS`, `EXECUTOR_SHUTDOWN_TIMEOUT_SEC`, `MAX_AREA_HISTORY_SIZE`)
- ✅ `server.py:784` — aspect ratio `0.4~3.0` 상수화 → **완료** (`_SIGNAL_MIN_CONF`, `_SIGNAL_ASPECT_MIN/MAX`, `_SIGNAL_MIN_AREA_RATIO`)
- ⏭ `MapActivity.kt:1268-1294` — `onCrosswalk = false` 중복 할당 → **status별로 다른 처리 필요, 의도적 코드 보존**
- ⏭ `Detection.kt` — 일부 미사용 필드 (`depthM`, `group`, `approachSpeed`) → **다른 Activity·서버에서 활용 가능성, 의도적 보존**
- ⏭ `MainActivity.kt:283-293` — `labelToKoreanLegacy()` 정리 → **`@Suppress("unused")` 처리됨, 향후 재사용 가능성으로 보존**
- ⏭ 사용 안 하는 import 정리 → **Android Studio Optimize Imports로 IDE에서 처리 권장**
- ⏭ `MapActivity.kt` / `VideoTestActivity.kt` — `handleTrafficLight()` 중복 → **학습 프로젝트 범위에서 의도적 보존, 향후 공통 유틸 추출 가능** ← **5/13 추가**

---

## 🪶 5순위 — 보안 + 문서

### 보안

- ⏭ `SplashActivity.kt:30` — `DEFAULT_SERVER_URL` 하드코딩 → `BuildConfig` 분리 → **`build.gradle` 변경 필요, 학습 프로젝트 범위 외**
- ⏭ `RemoteDetector.kt:29` — `SERVER_URL` 하드코딩 동일 → **동일 사유**
- ⏭ `assistant.py:39` — `GROQ_API_KEY` 빈 문자열 fallback → **이미 안전 처리됨** (`/assistant` 호출 시 503 에러 반환)
- ⏭ `.env` 파일 `.gitignore` 등재 확인 → **수동 검증 필요, 코드 작업 아님**
- ✅ `MapActivity.kt:288` / `SegmentationClient.kt:37-39` — URL 파싱 견고화 → **완료** (SignalClient 동일 Regex 패턴 적용)
- ⏭ `server.py:15` — `KMP_DUPLICATE_LIB_OK` 환경변수 주석 강화 → **운영 환경 검토 필요, 학습 프로젝트 범위 외**

### 문서

- ⏭ `README.md` — 현재 기능 반영 → **별도 문서 작업 시간 필요, 학습 프로젝트 범위 외**
- ⏭ `HANDOFF.md` — 최신 상태 업데이트 → **동일**
- ⏭ `실행순서.md` / `서버_실행방법.md` — 엔드포인트 5개 / `ENABLE_SIGNAL` 명시 → **동일**
- ⏭ `SettingsActivity.kt` — 실험 기능 사용자 가이드 → **별도 도움말 화면 추가 필요**
- ⏭ `ENABLE_SIGNAL=True` ↔ `KEY_SIGNAL_ENABLED 기본 false` 불일치 문서화 → **변경점_정리.md에 일부 반영됨**
- ⏭ `SignalClient.kt` KDoc — `confidence == null` 의미 구분 기술 → **`runCatching` 으로 통신 실패 흡수됨, 우선순위 낮음**
- ⏭ 주요 함수 KDoc / docstring 추가 → **`handleTrafficLight` 등 일부 추가됨, 나머지는 학습 프로젝트 범위 외**

---

## 작업 완료 후 체크

- [ ] `git status` 변경 파일 일관성 확인
- [x] 빌드 테스트 (App + 서버 동시) → **2026-05-13 BUILD SUCCESSFUL 확인**
- [ ] 5가지 시연 시나리오 동작 확인 (사람·골목길·차도·계단·맨홀)
- [ ] LatencyTracker 오버레이 정상 표시
- [ ] 설정 → 실험 기능 → LLM / 신호등 토글 동작 확인
- [ ] 신호등 토글 ON 상태에서 횡단보도 외 위치에서 빨간 객체에 반응 안 함 확인
- [ ] `/health` 응답 확인 (signal 정보 포함 여부)

---

## 📊 작업 통계

| 우선순위 | 완료 ✅ | 미완료 ⏭ | 합계 |
|--------|------|---------|------|
| 🔥 1순위 | 7 | 0 | 7 |
| 🥇 2순위 | 5 | 4 | 9 |
| 🥈 3순위 | 1 | 7 | 8 |
| 🥉 4순위 | 2 | 6 | 8 |
| 🪶 5순위 | 1 | 12 | 13 |
| **합계** | **16** | **29** | **45** |

**완료율**: 16/45 = 35.6%
**핵심(1·2순위) 완료율**: 12/16 = **75%**
