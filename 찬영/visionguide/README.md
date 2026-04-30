# 🦮 VisionGuide

> 시각장애인을 위한 실시간 AI 보행 비서  
> 스마트폰 카메라 → AI 추론(장애물 탐지 + 노면 분류 + 거리 추정) → 음성 안내

---

## ✅ 현재 구현된 기능

### 🌐 웹 클라이언트 (스마트폰 브라우저)
- 스마트폰 웹페이지 (홈 화면 → 길찾기 → 카메라 실시간)
- 후면 카메라 실시간 프레임 캡처 + WebSocket 전송
- T-map 보행자 길찾기 + 실시간 경로 안내 (음성 인식 목적지 입력)
- TTS 음성 출력

### 📱 SafeStep Android 앱
- 실시간 카메라 분석 (서버 `/detect` + `/segment` 호출)
- T-map 보행자 네비게이션 + 음성 경로 안내
- 거리 기반 진동 강도 (1m 이하: 최강 / 3m 이하: 중간 / 5m 이하: 약)
- 방향별 진동 패턴 (왼쪽 / 정면 / 오른쪽 구분)
- 5m 진입 / 1m 긴급 음성 알림 (각 1회 쿨다운)
- 노면 세그멘테이션 오버레이 (차도 / 인도 / 골목 실시간 표시)
- 동영상 파일 테스트 모드 (VideoTestActivity)
- ngrok URL 입력 화면 (앱 최초 실행 시 자동 표시, SharedPreferences 저장)

### 🤖 AI 추론 (서버 공통)
- YOLO 장애물 탐지 (차량 / 자전거 / 사람 / 고정 장애물 / 신호등 등)
- YOLO 노면 세그멘테이션 (인도 / 차도 / 골목 / 점자블록 등)
- Depth-Anything-V2 거리 추정 (미터 단위)
- 장애물 방향 감지 (왼쪽 / 정면 / 오른쪽)
- 노면 3구역 분석 (왼쪽 / 정면 / 오른쪽 각각 노면 판별)
- 접근 속도 기반 위험도 판단 (그룹별 임계값)
- 회피 방향 안내 (노면 위험 구역 고려해서 안전한 방향 제시)
- 신호등 색상 감지 (빨간불 / 초록불 / 깜빡임)
- Ollama LLaMA 자연어 안내 메시지 생성 (대시보드에서 on/off 가능)
- 동일 프레임 중복 추론 방지 (MD5 캐시 + asyncio.Event)

### 💻 노트북 대시보드
- 실시간 영상 + 분석 결과 확인
- AI 분석 판단 근거 표시 (장애물 목록 / 노면 3구역 / 회피 방향)
- 영상 / 사진 파일 업로드 분석 (시크바로 탐색)
- TTS on/off 토글
- Ollama on/off 토글
- ngrok 자동 실행 (서버 시작 시 고정 URL 자동 발급)

---

## 📦 1. 설치

### Python 환경 세팅

```bash
# 1) 가상환경 생성 (conda 권장)
conda create -n min python=3.10
conda activate min

# 2) 패키지 설치
pip install -r requirements.txt
```

> ⚠️ Python **3.10 이상** 필요

### PyTorch (GPU 사용 시 속도 대폭 향상)

GPU가 있는 경우 CUDA 버전으로 재설치하면 추론 속도가 10배 이상 빨라집니다:

```bash
pip uninstall torch torchvision
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
```

> GPU 없어도 실행은 됩니다. CPU에서는 Depth 추정이 프레임당 3~8초로 느립니다.

### Ollama 설치 (선택 - LLaMA 자연어 안내)

1. https://ollama.com/download 에서 Windows용 설치
2. 설치 후 모델 다운로드 (1회만):

```bash
ollama pull llama3.2
```

> Ollama가 없어도 서버는 동작합니다. 안내 메시지가 rule-based로 대체됩니다.  
> 대시보드에서 **Ollama on/off** 버튼으로 실시간 전환 가능합니다.

---

## ⚙️ 2. 환경 설정

### 서버 환경변수 (.env)

프로젝트 루트에 `.env` 파일을 팀장에게 받아 위치시킵니다:

```
visionguide/
└── .env   ← 여기
```

`.env` 내용 형식:

```
NGROK_AUTH_TOKEN=토큰값
NGROK_DOMAIN=고정도메인.ngrok-free.app
TMAP_API_KEY=티맵API키
OLLAMA_MODEL=llama3.2
```

> `.env`는 `.gitignore`에 포함되어 Git에 올라가지 않습니다. 팀 내부에서만 공유하세요.

### Android 앱 API 키 (local.properties)

SafeStep Android 앱 빌드 시 T-map API 키가 필요합니다.  
`android/SafeStep/local.properties` 파일을 팀장에게 받아 위치시킵니다:

```
android/SafeStep/
└── local.properties   ← 여기
```

`local.properties` 내용 형식:

```
TMAP_API_KEY=티맵API키
```

> `local.properties`는 `.gitignore`에 포함되어 Git에 올라가지 않습니다.  
> 빌드 시 `BuildConfig.TMAP_API_KEY`로 자동 주입됩니다.

---

## 🔧 3. ngrok 실행 파일 배치

`tools/ngrok.exe` 파일을 팀장에게 받아 아래 위치에 놓습니다:

```
visionguide/
└── tools/
    └── ngrok.exe   ← 여기
```

> `tools/ngrok.exe`도 `.gitignore`에 포함되어 Git에 올라가지 않습니다.  
> ngrok은 서버 시작 시 **자동으로 실행**됩니다. 별도로 실행할 필요 없습니다.

---

## 🤖 4. AI 모델 배치

YOLO 모델 파일(`.pt`)을 아래 경로에 배치합니다:

```
visionguide/
└── ai_models/
    ├── yolo_obstacle/
    │   └── weights/
    │       └── best.pt   ← 장애물 탐지 모델
    └── yolo_surface/
        └── weights/
            └── best.pt   ← 노면 세그멘테이션 모델
```

> Depth-Anything-V2 모델은 첫 실행 시 HuggingFace에서 자동 다운로드됩니다 (인터넷 필요, 약 100MB).

---

## 🚀 5. 서버 실행

프로젝트 **루트(visionguide/)** 에서:

```bash
uvicorn backend.main:app --host 0.0.0.0 --port 8000
```

성공하면 콘솔에 다음과 같이 나타납니다:

```
==================================================
🦮 VisionGuide 서버 시작
==================================================
📱 핸드폰 접속: https://고정도메인.ngrok-free.app
💻 노트북 대시보드: http://localhost:8000/dashboard
==================================================
```

- **스마트폰**: 로그에 출력된 `https://...ngrok-free.app` 주소로 접속
- **노트북 대시보드**: `http://localhost:8000/dashboard`

---

## 📱 6. 스마트폰 사용법

### 웹 클라이언트 (브라우저)

1. 스마트폰 브라우저(크롬)에서 서버 로그의 ngrok URL 접속
2. 홈 화면에서 **"카메라 실시간"** 버튼 탭
3. 카메라 권한 허용
4. 하단 **노란색 버튼** 탭 → 실시간 분석 시작
5. 정지하려면 같은 버튼 다시 탭

### 길찾기 사용법 (웹)

1. 홈 화면에서 **"길찾기"** 버튼 탭
2. 목적지 검색 또는 음성 버튼으로 말하기
3. 결과 목록에서 목적지 선택
4. **"경로 찾기"** → **"안내 시작"** 탭
5. 카메라 실시간 화면으로 전환되며 우측 상단 미니맵 + 경로 안내 시작

### SafeStep Android 앱

1. Android Studio에서 `android/SafeStep/` 폴더를 열기
2. `local.properties`에 T-map API 키 추가 (위 환경 설정 참고)
3. 앱 빌드 후 실행 (Android 8.0+ 권장)
4. 앱 최초 실행 시 **서버 URL 입력 화면** 자동 표시
   - 서버 로그의 ngrok URL 입력 (예: `https://abc123.ngrok-free.app`)
   - **"연결"** 버튼 탭 → URL이 앱에 저장됨
5. 메인 화면에서 카메라 자동 시작 → 실시간 장애물 탐지 + 진동 안내
6. 하단 **"길찾기"** 버튼으로 T-map 네비게이션 사용 가능
7. **"영상 테스트"** 버튼으로 저장된 영상 파일로 탐지 테스트 가능

> **서버 URL 변경**: 서버를 재시작해 ngrok URL이 바뀐 경우,  
> 앱 내 설정에서 URL을 다시 입력하면 됩니다.

---

## 💻 7. 대시보드 사용법

`http://localhost:8000/dashboard` 접속 (노트북에서만)

### 라이브 탭
- 스마트폰에서 전송되는 실시간 영상 + AI 분석 결과 확인
- **AI 분석** 카드: TTS 음성 메시지 + 판단 근거 (장애물 목록 / 노면 3구역 / 회피 방향)

### 영상 탭
- 영상 / 사진 파일 업로드 → AI 분석
- 시크바로 프레임별 탐색 가능
- 새 파일 업로드 시 이전 분석 자동 취소

### 헤더 버튼
| 버튼 | 기능 |
|---|---|
| **Ollama on/off** | LLaMA 자연어 안내 메시지 사용 여부 (off 시 rule-based 메시지 사용) |
| **음성 on/off** | 대시보드 TTS 음성 출력 on/off |
| **대시보드 연결됨** | WebSocket 연결 상태 표시 |

---

## 🧠 8. AI 분석 동작 방식

### 분석 흐름

```
스마트폰 카메라 프레임
        ↓
YOLO 장애물 탐지  ─┐
YOLO 노면 분석   ──┼→ _make_guidance() → rule_message (TTS용)
Depth 거리 추정  ─┘         ↓
                    Ollama ON이면 LLaMA로 다듬기
                            ↓
                    _build_detail() → 판단 근거 (화면 표시용)
                            ↓
                    WebSocket → 대시보드 브로드캐스트
```

### 장애물 그룹 및 경고 기준

| 그룹 | 해당 클래스 | 경고 기준 |
|---|---|---|
| vehicle | 차, 버스, 트럭, 오토바이 | 접근 속도 > 0.3m/s |
| micro | 자전거, 스쿠터 | 접근 속도 > 1.2m/s |
| person | 사람, 유모차, 휠체어 등 | 접근 속도 > 2.0m/s |
| fixed | 볼라드, 벤치, 기둥 등 | 정면 5m 이내 |
| signal | 신호등, 교통표지판 | 빨간불 / 초록불 판별 |

### 방향별 경고 정책

- **정면**: 모든 그룹 경고
- **왼쪽 / 오른쪽**: 빠르게 접근 중인 이동 물체만 경고 (지나가는 것은 생략)
- **고정 장애물 옆**: 직진 경로에 방해 없으므로 생략

### 회피 방향 계산

1. 장애물 위치로 반대 방향 계산
2. 반대 방향 노면이 차도(danger)이면 → "모든 방향 위험, 멈추세요"
3. 정면 장애물일 때 주변 장애물 위치 + 노면 안전 여부 종합 판단

---

## 🗂️ 9. 폴더 구조

```
visionguide/
├── android/
│   └── SafeStep/                    # Android 앱 (Kotlin, Android Studio)
│       └── app/src/main/java/com/safestep/app/
│           ├── SplashActivity.kt    # 서버 URL 입력 화면
│           ├── MapActivity.kt       # 메인 카메라 + 실시간 탐지
│           ├── VideoTestActivity.kt # 동영상 파일 테스트 모드
│           ├── BoundingBoxOverlay.kt
│           ├── detect/
│           │   ├── ObjectDetector.kt
│           │   ├── RemoteDetector.kt    # /detect 호출, lastMessage/lastDodge
│           │   ├── SegmentationClient.kt # /segment 호출
│           │   └── Detection.kt
│           └── navigation/
│               └── TmapService.kt   # T-map API (BuildConfig.TMAP_API_KEY)
│
├── backend/
│   ├── main.py                      # FastAPI 진입점 + ngrok 자동 실행
│   ├── routers/
│   │   ├── camera_router.py         # WebSocket (/ws/camera, /ws/dashboard)
│   │   ├── video_router.py          # 영상/사진 분석 (/analyze-file, /ollama-toggle)
│   │   ├── safestep_router.py       # SafeStep 호환 (/detect, /segment)
│   │   └── nav_router.py            # T-map 길찾기 API (/api/nav/*)
│   ├── services/
│   │   └── inference_service.py     # YOLO + Depth 추론, 안내 메시지 생성
│   └── utils/
│       ├── image_utils.py
│       └── logger.py
│
├── frontend/
│   ├── index.html                   # 스마트폰용 메인 페이지
│   ├── dashboard.html               # 노트북용 대시보드
│   ├── css/style.css
│   └── js/
│       ├── camera.js                # 카메라 접근 + 프레임 캡처
│       ├── websocket.js             # WebSocket 클라이언트
│       └── ui.js                   # 화면 전환, 길찾기, TTS
│
├── ai_models/
│   ├── yolo_obstacle/weights/       # 장애물 탐지 모델 (.pt)
│   └── yolo_surface/weights/        # 노면 세그멘테이션 모델 (.pt)
│
├── tools/
│   └── ngrok.exe                    # ngrok 실행 파일 (Git 제외)
│
├── tests/
│   ├── test_obstacle_det.py
│   └── test_surface_seg.py
│
├── .env                             # 환경변수 (Git 제외)
├── .gitignore
├── requirements.txt
└── README.md
```

---

## 🔌 10. API 엔드포인트

서버가 실행되면 아래 URL들이 열립니다.

### HTTP

| 메서드 | URL | 설명 |
|---|---|---|
| GET | `/` | 스마트폰용 메인 페이지 |
| GET | `/dashboard` | 노트북 대시보드 |
| GET | `/health` | 서버 상태 확인 |
| POST | `/analyze-file` | 영상/사진 파일 업로드 → AI 분석 시작 |
| POST | `/ollama-toggle` | Ollama on/off 전환 |
| GET | `/api/nav/config` | T-map API 키 반환 |
| POST | `/api/nav/search` | T-map POI 검색 |
| POST | `/api/nav/directions` | 보행자 경로 안내 |
| POST | `/detect` | **SafeStep 전용** 장애물 탐지 (RemoteDetector.kt 호환) |
| POST | `/segment` | **SafeStep 전용** 노면 세그멘테이션 (SegmentationClient.kt 호환) |

#### `/detect` 응답 포맷

```json
{
  "detections": [
    {
      "label":      "bicycle",
      "label_ko":   "micro",
      "confidence": 0.87,
      "box":        [120, 80, 340, 300],
      "direction":  "정면",
      "depth_m":    4.8
    }
  ],
  "message": "4.8m 정면에 자전거가 있습니다. 왼쪽으로 피하세요.",
  "dodge":   "왼쪽"
}
```

> `dodge` 필드: 회피 방향 (왼쪽/정면/오른쪽). 앱의 진동 방향에 사용.  
> `direction` 필드: 장애물 위치 방향 (장애물이 어디 있는지). 표시용.

#### `/segment` 응답 포맷

```json
{
  "mask_b64": "<base64 PNG>",
  "status":   "sidewalk",
  "ratios": {
    "road":      0.1,
    "sidewalk":  0.8,
    "crosswalk": 0.0,
    "alley":     0.1
  }
}
```

> `status`: 정면 구역 노면 상태 (sidewalk / road / crosswalk / alley / unknown)  
> 같은 프레임으로 `/detect`와 `/segment`를 동시 요청해도 추론은 1번만 실행됩니다 (MD5 캐시).

### WebSocket

| URL | 설명 |
|---|---|
| `ws://localhost:8000/ws/camera` | 스마트폰 → 서버 (프레임 전송) |
| `ws://localhost:8000/ws/dashboard` | 서버 → 대시보드 (분석 결과 브로드캐스트) |

---

## 📨 11. WebSocket 메시지 포맷

서버가 대시보드로 보내는 메시지 종류입니다.

### `frame_overlay` - 실시간 카메라 분석 결과

```json
{
  "type": "frame_overlay",
  "frame_id": 42,
  "message": "⚠️ 4.8m 정면에 자전거가 있습니다. 왼쪽으로 피하세요.",
  "detail": {
    "obstacles": [
      {
        "rank": 1,
        "kr": "자전거",
        "direction": "정면",
        "depth_m": 4.8,
        "conf": 87,
        "group": "micro",
        "approaching": false
      }
    ],
    "surface_zones": {
      "왼쪽": { "kr": "인도", "group": "safe" },
      "정면": { "kr": "인도", "group": "safe" },
      "오른쪽": { "kr": "차도", "group": "danger" }
    },
    "dodge": "왼쪽으로 피하세요"
  },
  "surface_masks": { "sidewalk_asphalt": "data:image/png;base64,..." },
  "obstacle_items": [
    {
      "bbox": [120, 80, 340, 300],
      "group": "micro",
      "cls_name": "bicycle",
      "conf": 0.87,
      "depth_m": 4.8,
      "direction": "정면"
    }
  ],
  "img_width": 640,
  "img_height": 480,
  "analysis": {
    "obstacle": { "available": true, "counts": { "micro": 1 } },
    "surface": { "available": true, "foot_zone": { "cls_name": "sidewalk_asphalt", "group": "safe" } }
  }
}
```

### `frame` - 영상/사진 파일 분석 결과

위 `frame_overlay`와 동일한 필드에 추가로:

```json
{
  "type": "frame",
  "source": "video",
  "step": 15,
  "total_steps": 300,
  "base_image": "data:image/jpeg;base64,...",
  "size_kb": 42.3,
  "timestamp": 1714000000000
}
```

### 기타 메시지

```json
{ "type": "video_start", "filename": "test.mp4", "total_steps": 300 }
{ "type": "video_done",  "total_steps": 300 }
{ "type": "video_error", "message": "오류 내용" }
{ "type": "phone_disconnect", "phone": "client_id" }
```

---

## 🏷️ 12. 감지 클래스 목록

### 장애물 모델 (yolo_obstacle)

| 그룹 | 클래스 |
|---|---|
| vehicle (차량) | car, bus, truck, motorcycle |
| micro (개인이동장치) | bicycle, scooter |
| person (사람/동물) | person, cat, dog, stroller, wheelchair, carrier, movable_signage |
| fixed (고정 장애물) | barricade, bench, bollard, chair, fire_hydrant, kiosk, parking_meter, pole, potted_plant, power_controller, stop, table, tree_trunk, traffic_light_controller |
| signal (신호) | traffic_light, traffic_sign |

### 노면 모델 (yolo_surface)

| 그룹 | 클래스 |
|---|---|
| safe (안전) | sidewalk_asphalt, sidewalk_blocks, sidewalk_cement, sidewalk_urethane, sidewalk_soil_stone, sidewalk_other |
| danger (위험) | roadway_normal, roadway_crosswalk |
| caution (주의) | alley_normal, alley_crosswalk, alley_damaged, alley_speed_bump, sidewalk_damaged, caution_zone_grating, caution_zone_manhole, caution_zone_repair_zone, caution_zone_stairs, caution_zone_tree_zone |
| braille (점자블록) | braille_guide_blocks_normal, braille_guide_blocks_damaged |
| bike (자전거도로) | bike_lane |

---

## ⚙️ 13. 주요 상수 및 조정 방법

모두 `backend/services/inference_service.py` 상단에 있습니다.

### 거리 보정 배수

```python
DEPTH_SCALE = 0.65   # 모델이 실제보다 멀게 측정하는 경향 보정
                     # 측정값이 너무 멀면 낮추고, 너무 가까우면 높임
```

### 그룹별 접근 속도 위험 임계값 (m/s)

```python
_APPROACH_SPEED_THRESHOLD = {
    "vehicle": 0.3,   # 차량: 0.3m/s 이상 접근 시 위험 경고
    "micro":   1.2,   # 자전거: 1.2m/s 이상 (자전거 정상 주행 약 4m/s)
    "person":  2.0,   # 사람: 2.0m/s 이상 (뛰어오는 경우)
    "fixed":   None,  # 고정 장애물: 속도 무관, 거리로만 판단
    "signal":  None,  # 신호등: 색상으로만 판단
}
```

### 고정 장애물 경고 거리

```python
# _make_guidance() 내부
elif depth_m is not None and depth_m < 5.0:   # 5m 이내일 때만 경고
```

### Ollama 호출 최소 간격

```python
_LLM_MIN_INTERVAL = 3.0   # 최소 3초마다 1번만 Ollama 호출 (과호출 방지)
```

### Ollama 시스템 프롬프트 수정

`_generate_guidance_llm()` 함수 내 `"role": "system"` 부분을 수정하면 됩니다.  
현재 설정: 15글자 이내, 우선순위(신호등 > 차량 > 자전거 > 사람 > 고정 장애물)

---

## 🛠️ 14. 트러블슈팅

| 증상 | 원인 / 해결 |
|---|---|
| 스마트폰에서 카메라 권한이 안 뜸 | HTTPS가 아님. 반드시 ngrok URL(https://...)로 접속 |
| ngrok 실행 실패 | `tools/ngrok.exe` 위치 확인, `.env` 토큰 확인 |
| WebSocket이 계속 재연결됨 | 서버가 실행 중인지, 방화벽이 8000 포트를 막는지 확인 |
| `ModuleNotFoundError: No module named 'backend'` | 프로젝트 **루트에서** uvicorn 실행 필요 |
| AI 추론이 안 됨 | `ai_models/` 안에 `.pt` 모델 파일이 있는지 확인 |
| 분석이 매우 느림 | CPU 환경 한계. GPU 있는 PC에서 CUDA 버전 PyTorch 설치 권장 |
| Ollama 호출 실패 | Ollama 앱이 실행 중인지 확인. `ollama pull llama3.2` 완료 여부 확인 |
| 거리가 실제보다 멀게 표시됨 | `inference_service.py`의 `DEPTH_SCALE` 값 조정 (현재 0.65) |
| **[Android]** 앱 빌드 오류 (`TMAP_API_KEY`) | `android/SafeStep/local.properties`에 `TMAP_API_KEY=...` 추가 필요 |
| **[Android]** `/detect` 연결 실패 | 앱 URL 설정 확인. ngrok URL이 바뀌었으면 앱 설정에서 재입력 |
| **[Android]** 진동이 없음 | Android 13+ 기기에서 진동 권한 확인 (`VIBRATE` permission) |
| **[Android]** 음성 안내가 안 나옴 | 기기 볼륨 확인. TTS 엔진 설치 여부 확인 (설정 → 접근성 → TTS) |
