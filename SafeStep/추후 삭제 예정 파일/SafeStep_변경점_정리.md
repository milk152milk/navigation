# SafeStep 코드 변경점 정리

> **기준 코드**: `C:\Users\User\github\navigation\준범\SafeStep` (이전 분석본)
> **적용 위치**: `C:\Users\User\github\SafeStep`
> **최초 작성**: 2026-05-11
> **최종 업데이트**: 2026-05-13 (옵션 C, VideoTestActivity 풀 업그레이드, 연결 상태 실시간 확인 추가)
> **목적**: 다음 작업자(Claude 또는 사람)가 무엇이 바뀌었는지 빠르게 파악

---

## 📋 한눈에 보기

| 영역 | 변경 수 | 빌드 영향 |
|------|--------|---------|
| 🔧 빌드 에러 수정 | 1건 (VideoTestActivity 보완) | ❗ 필수 |
| 🆕 신규 기능 (5/11~13 작업) | Feature Flags + 사용자 UI 토글 + 신호등 통합 + VideoTestActivity 풀 업그레이드 + 설정 화면 실시간 연결 확인 | 큼 |
| 🔥 정합성·UX | 7건 + α (handleTrafficLight 컨텍스트·signalEnabled 가드 등) | 안정성 ↑ |
| 🥇 라이프사이클·동시성 | 5건 (Executor shutdown 타임아웃, VoiceAssistant release 순서, @Volatile clients 등) | 누수 차단 |
| 🥈 성능 | 1건 (memoryguard) | 안정성 ↑ |
| 🥉 경량화 | 3건 (상수화) | 가독성 ↑ |
| 🪶 보안·문서 | 0건 (기존 처리 충분) | — |

---

## 🆕 1. 신규 기능 추가

### 1-1. Feature Flags 도입

**새 파일**: `app/src/main/java/com/safestep/app/FeatureFlags.kt`

기본값 참고용 객체. 실제 ON/OFF 제어는 사용자 설정으로 이관됨.

```kotlin
object FeatureFlags {
    const val LLM_ENABLED = true
    const val SIGNAL_ENABLED = false
    const val DEPTH_ENABLED = true
}
```

### 1-2. 설정 화면에 "실험 기능" 카드 추가

**수정**: `app/src/main/res/layout/activity_settings.xml`

서버 설정 ↔ 앱 정보 사이에 카드 신설:
- 음성 어시스턴트 토글 (기본 ON)
- 신호등 감지 토글 (기본 OFF)

**수정**: `app/src/main/java/com/safestep/app/SettingsActivity.kt`
- `KEY_LLM_ENABLED`, `KEY_SIGNAL_ENABLED` 상수
- `DEFAULT_LLM_ENABLED = true`, `DEFAULT_SIGNAL_ENABLED = false`
- bind / load / listener 추가

### 1-3. 신호등 호출 통합 (MapActivity)

**수정**: `app/src/main/java/com/safestep/app/MapActivity.kt`

- `SignalClient` import + 인스턴스 추가
- `signalExecutor`, `signalBusy` 추가
- `signalEnabled` 토글 (SharedPreferences)
- `analyzeFrame()`에 호출 코드:
  - 토글 ON
  - `lastSurfaceStatus == "crosswalk"` 또는 `"road"` (B 옵션 — 횡단보도/차도 근처)
  - 9프레임마다
  - skip-if-busy
  - vehicleUrgent 중 중단
- `handleTrafficLight()` 함수 추가 (TTS, 5초 쿨다운)
- `reloadSettings()`에서 실시간 갱신
- `onDestroy()` signalExecutor 정리

### 1-4. 서버 /signal 엔드포인트 엄격 조건

**수정**: `server/server.py`

오탐 방지 (빨간 차/간판 차단):
- `_scan_for_light_blob` 폴백 제거
- YOLO conf ≥ **0.30** (이전 0.15)
- 박스 종횡비 **0.4 ~ 3.0** (신호등 모양 검증)
- 박스 면적 ≥ 이미지 **0.2%** (멀어서 작은 건 무시)
- `ENABLE_SIGNAL = True` (모델 로드 유지, 클라이언트가 호출 여부 결정)

### 1-5. VideoTestActivity 풀 업그레이드 (5/13 추가)

**수정**: `app/src/main/java/com/safestep/app/VideoTestActivity.kt`

기존 단일 Thread 직렬 처리 → **MapActivity와 동일 병렬 파이프라인**.

추가된 것:
- `SignalClient` import + 인스턴스
- 4개 Executor (`fastExecutor`, `detectExecutor`, `segExecutor`, `signalExecutor`)
- 4개 AtomicBoolean (skip-if-busy)
- `signalEnabled` 토글 + 횡단보도/차도 컨텍스트 가드
- `handleTrafficLight()` 함수 (MapActivity와 동일 로직)
- `extractAndDetect()` 재작성 — Fast Lane 매 프레임 + Detect 매 프레임 + Segment 2프레임마다 + Signal 3프레임마다
- `onResume()` 추가 — 설정에서 토글 변경 시 즉시 반영
- `onDestroy()` 4개 Executor 안전 종료

→ 동영상 모드도 실시간 카메라와 거의 동일한 분석 능력.

### 1-6. 설정 화면 연결 상태 실시간 확인 (5/13 추가)

**수정**:
- `app/src/main/res/layout/activity_settings.xml` — `settingsConnectionDot`, `settingsConnectionStatus` ID 부여, 초기값 회색 "확인 중..."
- `app/src/main/java/com/safestep/app/SettingsActivity.kt` — `checkServerConnection()` 함수 추가

기존: "연결됨"이 XML에 하드코딩 (항상 초록색)
변경: `/health` 호출로 실제 연결 확인 → 회색→초록 또는 빨강
- `onCreate()` + `onResume()` 진입 시마다 확인
- 서버 URL 저장 시 즉시 재확인

---

## 🔧 2. 빌드 에러 수정 (긴급)

### VideoTestActivity.kt 미완성 보완

**문제**: 빌드 에러 3건
- `Class is not abstract and does not implement onInit()`
- `Unresolved reference 'showWarning'`
- `Syntax error at line 334`

**해결**: `app/src/main/java/com/safestep/app/VideoTestActivity.kt`
- `handleSegmentation()` 함수 완성 (노면 상태별 TTS)
- `showWarning()` 함수 추가
- `speak()` 헬퍼 + `ttsReady` 가드
- `onInit()` override
- `vibrateForDirection()` 진동 패턴
- 클래스 닫는 `}` 추가

---

## 🔥 3. 1순위 — 정합성 + UX (7건)

### 3-1, 3-2. URL 파싱 견고화

**수정**: `app/src/main/java/com/safestep/app/detect/SignalClient.kt:42-47`

**이전**:
```kotlin
private val signalUrl = serverUrl
    .trimEnd('/')
    .substringBeforeLast("/detect")
    .substringBeforeLast("/segment")
    .substringBeforeLast("/signal") + "/signal"
```

**변경 후**: Regex 기반으로 모든 엔드포인트 안전 처리
```kotlin
private val signalUrl: String = run {
    val trimmed = serverUrl.trimEnd('/')
    val baseUrl = trimmed.replace(Regex("/(detect|segment|signal|fast|health|assistant)$"), "")
    "$baseUrl/signal"
}
```

### 3-3. SignalClient 빈 URL 가드

**수정**: `MapActivity.kt:314-322`

`RemoteDetector.SERVER_URL`이 빈 문자열일 때 `DEFAULT_SERVER_URL` 사용.

### 3-4. handleTrafficLight 컨텍스트 재확인

**수정**: `MapActivity.kt` handleTrafficLight 함수

응답 도착 시점에 다시 체크:
- `signalEnabled` (사용자가 OFF 했으면 무시)
- `lastSurfaceStatus`가 여전히 crosswalk/road인지

`QUEUE_FLUSH` → `QUEUE_ADD`로 변경 (다른 안내 안 끊음).

### 3-5. VideoTestActivity TTS 초기화 가드

**수정**: `VideoTestActivity.kt`

- `@Volatile ttsReady` 추가
- `speak()` 호출 전 `ttsReady` 확인
- 초기화 실패 시 Log 출력

### 3-6. 서버 URL 변경 시 클라이언트 재생성

**수정**: `MapActivity.kt`

`lastBaseUrlForClients` 변수로 변경 감지. `reloadSettings()`에서 URL이 바뀌면 `segClient`, `signalClient` 재생성.

### 3-7. 토글 UI 접근성

**수정**: `activity_settings.xml`

- `switchLlmEnabled` → `contentDescription="음성 어시스턴트 켜기 끄기"`
- `switchSignalEnabled` → `contentDescription="신호등 감지 켜기 끄기"`

### 3-8. handleTrafficLight signalEnabled 가드 (5/13 추가)

**수정**: `MapActivity.kt:handleTrafficLight()` + `VideoTestActivity.kt:handleTrafficLight()`

사용자가 도중에 토글 OFF 했을 경우, 요청 후 도착한 응답도 무시:
```kotlin
if (!signalEnabled) return
if (lastSurfaceStatus != "crosswalk" && lastSurfaceStatus != "road") return
```

QUEUE_FLUSH → QUEUE_ADD 변경 (다른 안내 안 끊음).

### 3-9. 설정 화면 부제목 명확화 (5/13 추가)

**수정**: `activity_settings.xml`

- 음성 어시스턴트: "화면 길게 누르면 자연어 명령 (LLM)"
- 신호등 감지: "빨강·초록 신호등 색상 감지 (HSV)" ← "실험 기능 — 인식률 낮음" 보다 명확

---

## 🥇 4. 2순위 — 라이프사이클 + 동시성 (5건)

### 4-1. Executor 안전 종료

**수정**: `MapActivity.kt`

새 helper `shutdownExecutorSafely()`:
- 1초 대기 (`awaitTermination`)
- 안 끝나면 `shutdownNow()` 강제 종료

5개 Executor 모두 적용 (`camera`, `detect`, `seg`, `fast`, `signal`).

### 4-2. 클라이언트 @Volatile

**수정**: `MapActivity.kt`

```kotlin
@Volatile private lateinit var segClient: SegmentationClient
@Volatile private lateinit var signalClient: SignalClient
```

→ `reloadSettings()`에서 재할당 가능하므로 멀티스레드 안전.

### 4-3. VoiceAssistant release 순서 개선

**수정**: `app/src/main/java/com/safestep/app/assistant/VoiceAssistant.kt`

**이전 순서**: `scope.cancel()` → `recognizer.destroy()` → `tts.stop()`
**변경 후**: 
1. `tts.stop()` (즉시 발화 차단)
2. `recognizer.setRecognitionListener(null)` (콜백 차단)
3. `scope.cancel()` (코루틴 중단)
4. `recognizer.cancel()`
5. `recognizer.destroy()`
6. `tts.shutdown()`

모든 단계 `runCatching`으로 감싸 예외 안전.

### 4-4. 센서 onDestroy 안전망

**수정**: `MapActivity.kt:onDestroy()`

`compassListener`, `motionListener` 해제 코드 추가. `onPause()`에서 이미 해제했어도 직접 onDestroy 호출 시 대비.

### 4-5. signalEnabled 변경 후 응답 가드

**수정**: `MapActivity.kt:handleTrafficLight()`

```kotlin
if (!signalEnabled) return  // 사용자가 도중에 OFF 했으면 무시
```

---

## 🥈 5. 3순위 — 성능 (1건)

### 5-1. areaHistory 메모리 보호

**수정**: `MapActivity.kt:handleDetections()`

```kotlin
if (areaHistory.size > MAX_AREA_HISTORY_SIZE) {
    areaHistory.entries
        .sortedBy { it.value.firstOrNull()?.second ?: 0L }
        .take(areaHistory.size - MAX_AREA_HISTORY_SIZE)
        .forEach { areaHistory.remove(it.key) }
}
```

→ 동시 추적 라벨 50개 상한.

---

## 🥉 6. 4순위 — 경량화 (3건)

### 6-1. MapActivity 매직 넘버 상수화

**수정**: `MapActivity.kt:companion object`

추가된 상수:
```kotlin
private const val FRAME_FAST_INTERVAL    = 2
private const val FRAME_DETECT_INTERVAL  = 4
private const val FRAME_SEGMENT_INTERVAL = 6
private const val FRAME_SIGNAL_INTERVAL  = 9
private const val FRAME_HEALTH_INTERVAL  = 300
private const val TRAFFIC_LIGHT_COOLDOWN_MS = 5_000L
private const val EXECUTOR_SHUTDOWN_TIMEOUT_SEC = 1L
private const val MAX_AREA_HISTORY_SIZE = 50
```

`frameCount % 2/4/6/9/300` 같은 매직 넘버 → 모두 상수 참조.

### 6-2. server.py /signal 조건 상수화

**수정**: `server.py`

```python
_SIGNAL_MIN_CONF      = 0.30
_SIGNAL_ASPECT_MIN    = 0.4
_SIGNAL_ASPECT_MAX    = 3.0
_SIGNAL_MIN_AREA_RATIO = 0.002
```

### 6-3. FeatureFlags.kt 주석 명확화

**수정**: `FeatureFlags.kt`

"참고용으로만 유지, 실제 제어는 SettingsActivity로 이관됨" 명시.

---

## ⏭️ 적용 안 한 항목 (참고)

### Skip 사유: 이미 처리됨
- SplashActivity `checkServerConnection` 안전성 → 기존 `isDestroyed` 체크로 충분
- RecordActivity JSON 예외 처리 → 빈 JSONArray fallback으로 UI 정상 업데이트
- assistant.py 빈 API 키 → 기존에 503 에러 반환 로직 있음

### Skip 사유: 학습 프로젝트 범위 초과
- BuildConfig로 서버 URL 분리 (build.gradle 변경 필요)
- 매 프레임 Bitmap 풀링 (큰 리팩토링)
- 전 Activity contentDescription 검토
- README/HANDOFF 전면 업데이트

### Skip 사유: 의도적 코드 보존
- `labelToKoreanLegacy` (`@Suppress("unused")` 처리됨, 향후 사용 가능성)
- Detection 필드 (`depthM`, `group`, `approachSpeed`) — 다른 곳에서 활용 가능
- `onCrosswalk = false` 중복 할당 (status별로 다른 처리 필요)

---

## 🚦 신호등 기능 정책 (중요)

### 현재 상태
- **앱**: 기본 OFF (사용자가 설정에서 켜야 작동)
- **서버**: `ENABLE_SIGNAL = True` (모델 항상 로드, 호출 시 처리)

### 작동 흐름 (사용자가 토글 ON 시)
```
사용자가 횡단보도 앞에 섬
  ↓ 노면 세그가 "crosswalk" 감지
  ↓ 9프레임마다 /signal 호출
서버: YOLO traffic_light (conf≥0.30, 종횡비, 면적 검증)
  ↓ HSV 색상 분석
  ↓ 6프레임 다수결
앱: "빨간불입니다. 멈추세요." (5초 쿨다운, QUEUE_ADD)
```

### 오탐 방지 메커니즘
1. 토글 OFF면 호출 자체 안 함
2. 노면 컨텍스트 (crosswalk/road) 외엔 호출 안 함
3. 서버에서 YOLO + 종횡비 + 면적 3중 검증
4. 응답 도착 시점 컨텍스트 재확인
5. 5초 쿨다운 (같은 색 반복 안내 방지)

---

## 🧪 빌드 / 테스트 체크리스트

- [ ] `Build → Rebuild Project` 통과
- [ ] 앱 설치 + 실행 정상
- [ ] 설정 → 실험 기능 → 두 토글 표시 정상
- [ ] 신호등 토글 OFF 상태에서 `/signal` 호출 안 됨 확인
- [ ] 신호등 토글 ON + 횡단보도 영상에서만 동작 확인
- [ ] LatencyTracker 오버레이 정상 표시
- [ ] 5가지 시연 시나리오 동작 확인
- [ ] LLM 토글 OFF 시 길게 누름 무반응 확인
- [ ] `/health` 응답에 `signal: "yolov8n"` 표시 확인 (서버 정상)

---

## 📂 변경된 파일 목록

```
app/src/main/java/com/safestep/app/
├── FeatureFlags.kt              🆕 신규
├── MapActivity.kt               ✏️  대폭 변경 (신호등 + 상수화 + 라이프사이클)
├── SettingsActivity.kt          ✏️  실험 기능 토글 추가
├── SplashActivity.kt            ✏️  LLM 토글 적용
├── VideoTestActivity.kt         ✏️  미완성 보완 (빌드 에러 수정)
└── assistant/
    └── VoiceAssistant.kt        ✏️  release 순서 개선

app/src/main/java/com/safestep/app/detect/
└── SignalClient.kt              ✏️  URL 파싱 견고화

app/src/main/res/layout/
└── activity_settings.xml        ✏️  실험 기능 카드 추가 + contentDescription

server/
└── server.py                    ✏️  /signal 엄격 조건 + 상수화
```

---

## 🔗 관련 파일

- `SafeStep_코드점검_체크리스트.md` — 미적용 항목 + 향후 작업
- `README.md` — 기능 설명 (업데이트 필요)
- `HANDOFF.md` — 프로젝트 인계 문서 (업데이트 필요)
