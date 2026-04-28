# SafeStep 🦺

시각장애인을 위한 AI 보행 안전 앱

카메라로 주변 장애물을 실시간 탐지하고 노면 상태를 분석해 TTS·진동으로 경고하며, Tmap API 기반 도보 내비게이션을 제공합니다.

---

## 시스템 구성

```
[Android 앱] ──── HTTP ────► [Python 서버 (PC)]
   카메라 프레임 전송              YOLO 객체 탐지 (bbox.pt)
   TTS / 진동 경고 수신            노면 세그멘테이션 (surface.pt)
   Tmap 도보 내비게이션
   osmdroid 지도
```

---

## 서버 실행 (PC)

### 요구사항

- Python 3.9+
- 패키지 설치

```bash
pip install fastapi uvicorn ultralytics opencv-python pillow numpy
```

### 매일 시작 루틴

**① Wi-Fi 확인** — PC Wi-Fi를 폰과 같은 공유기에 연결

**② IP 확인**
```powershell
ipconfig
```
`Wireless LAN adapter Wi-Fi` 항목의 IPv4 주소 확인

**③ 앱 IP 업데이트** (IP가 바뀐 경우)

`app/src/main/java/com/safestep/app/detect/RemoteDetector.kt` 에서:
```kotlin
const val SERVER_URL = "http://[여기에 IP]:8000/detect"
```

**④ 서버 시작**
```powershell
cd SafeStep/server
python server.py
```

정상 실행 시 로그:
```
[SafeStep] YOLO 탐지 로드 완료 ✅
[SafeStep] 노면 세그멘테이션 로드 완료 ✅
[SafeStep] 서버 시작 (포트 8000)
```

**⑤ 연결 확인** — 폰 브라우저에서 접속:
```
http://[PC IP]:8000/health
```
`{"status":"ok"}` 가 뜨면 정상

### 서버 강제 종료
```powershell
Get-Process python | Stop-Process -Force
```

### 방화벽 설정 (최초 1회, 관리자 PowerShell)
```powershell
New-NetFirewallRule -DisplayName "SafeStep Server" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow
```

---

## 앱 빌드 (Android Studio)

### 요구사항
- Android Studio Hedgehog 이상
- Android SDK 34
- 실기기 권장 (에뮬레이터 카메라 제한)

### 빌드 순서
1. Android Studio에서 프로젝트 열기
2. `RemoteDetector.kt` 에서 서버 IP 확인
3. ▶ Run (Shift+F10)

### 필요 권한 (앱 최초 실행 시 허용)
- 카메라
- 마이크 (음성 목적지 입력)
- 위치 (GPS 내비게이션)

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| 객체 탐지 | 사람·볼라드·오토바이·휠체어 등 22종 실시간 감지 |
| 노면 세그멘테이션 | 보도·차도·골목·위험구역 색상 오버레이 |
| 방향별 진동 | 왼쪽·정면·오른쪽 위험에 따라 다른 진동 패턴 |
| TTS 경고 | 차도·골목·위험구역 진입 시 음성 경고 |
| 도보 내비게이션 | 음성으로 목적지 입력 → Tmap 턴바이턴 안내 |
| Heading-up 지도 | 나침반 기반 진행 방향이 항상 위 |

---

## 프로젝트 구조

```
SafeStep/
├── server/
│   ├── server.py          # FastAPI 서버 (/detect, /segment, /health)
│   ├── bbox.pt            # YOLO 객체 탐지 모델
│   └── surface.pt         # 한국 도로 노면 세그멘테이션 모델
│
└── app/src/main/
    ├── java/com/safestep/app/
    │   ├── SplashActivity.kt          # 모드 선택 화면
    │   ├── MapActivity.kt             # 메인 (지도 + 카메라 + 내비)
    │   ├── VideoTestActivity.kt       # 동영상 테스트 모드
    │   ├── BoundingBoxOverlay.kt      # 바운딩박스 커스텀 뷰
    │   ├── detect/
    │   │   ├── RemoteDetector.kt      # 객체 탐지 서버 통신 ★ IP 설정
    │   │   ├── SegmentationClient.kt  # 노면 세그멘테이션 서버 통신
    │   │   ├── ObjectDetector.kt
    │   │   └── Detection.kt
    │   └── navigation/
    │       ├── TmapService.kt         # Tmap POI·경로 API
    │       ├── NavigationGuide.kt     # 턴바이턴 안내 로직
    │       └── RouteStep.kt
    └── res/
        └── layout/
            ├── activity_map.xml
            ├── activity_splash.xml
            └── activity_video_test.xml
```

---

## API 키

| 서비스 | 위치 |
|--------|------|
| Tmap | `navigation/TmapService.kt` → `API_KEY` |

---

## 서버 엔드포인트

| 엔드포인트 | 메서드 | 설명 |
|-----------|--------|------|
| `/health` | GET | 서버 상태 확인 |
| `/detect` | POST | YOLO 객체 탐지 (이미지 → 바운딩박스) |
| `/segment` | POST | 노면 세그멘테이션 (이미지 → RGBA 마스크) |

---

## 노면 세그멘테이션 클래스

| 색상 | 카테고리 | TTS |
|------|---------|-----|
| 🟢 초록 | 보도 (sidewalk) | 없음 |
| 🔴 빨강 | 차도 (road) | "차도입니다. 주의하세요." |
| 🟡 노랑 | 횡단보도 / 골목 | "골목길입니다. 주의하세요." |
| 🟠 주황 | 위험구역 (맨홀·계단·격자) | "위험 구역입니다. 주의하세요." |
