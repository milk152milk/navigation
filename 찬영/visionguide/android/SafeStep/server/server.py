# SafeStep Detection Server
# - /detect  : YOLO11n 객체 탐지 (bbox.pt)
# - /segment : YOLO 노면 세그멘테이션 (surface.pt) — 한국 도로 데이터 학습
# - /health  : 서버 상태 확인

import base64
import io
from pathlib import Path

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from PIL import Image
from ultralytics import YOLO

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# YOLO 객체 탐지 모델 (bbox.pt)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
MODEL_PATH = Path(__file__).parent / "bbox.pt"
if not MODEL_PATH.exists():
    raise FileNotFoundError(f"bbox.pt 를 서버 폴더에 복사해주세요: {MODEL_PATH.parent}")

print(f"[SafeStep] YOLO 탐지 모델 로드 중: {MODEL_PATH}")
yolo_model = YOLO(str(MODEL_PATH))
print("[SafeStep] YOLO 탐지 로드 완료 ✅")

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# YOLO 노면 세그멘테이션 모델 (surface.pt)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SURFACE_PATH = Path(__file__).parent / "surface.pt"
surface_model = None

if SURFACE_PATH.exists():
    print(f"[SafeStep] 노면 세그멘테이션 모델 로드 중: {SURFACE_PATH}")
    try:
        surface_model = YOLO(str(SURFACE_PATH))
        print("[SafeStep] 노면 세그멘테이션 로드 완료 ✅")
        print(f"[SafeStep] 클래스 목록: {surface_model.names}")
    except Exception as e:
        print(f"[SafeStep] 노면 모델 로드 실패: {e}")
else:
    print("[SafeStep] surface.pt 없음 — 서버 폴더에 복사해주세요")

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 클래스 → 카테고리 매핑
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SURFACE_CATEGORY = {
    # 보도 (안전)
    "sidewalk_asphalt":             "sidewalk",
    "sidewalk_blocks":              "sidewalk",
    "sidewalk_cement":              "sidewalk",
    "sidewalk_other":               "sidewalk",
    "sidewalk_soil_stone":          "sidewalk",
    "sidewalk_urethane":            "sidewalk",
    "braille_guide_blocks_normal":  "sidewalk",   # 점자 유도블록 (정상)
    # 차도 (위험)
    "roadway_normal":               "road",
    # 횡단보도 (주의)
    "roadway_crosswalk":            "crosswalk",
    "alley_crosswalk":              "crosswalk",
    # 골목 (주의)
    "alley_normal":                 "alley",
    "alley_speed_bump":             "alley",
    "bike_lane":                    "alley",
    # 위험 구역 (즉시 경고)
    "sidewalk_damaged":             "caution",
    "alley_damaged":                "caution",
    "braille_guide_blocks_damaged": "caution",
    "caution_zone_grating":         "caution",
    "caution_zone_manhole":         "caution",
    "caution_zone_repair_zone":     "caution",
    "caution_zone_stairs":          "caution",
    "caution_zone_tree_zone":       "caution",
}

# 카테고리 정수 인코딩 (픽셀 마스크 연산용)
CAT_INT = {"sidewalk": 1, "road": 2, "crosswalk": 3, "alley": 4, "caution": 5}

# RGBA 색상
COLOR_MAP = {
    "sidewalk":  (50,  200, 80,  160),   # 🟢 초록  — 보도
    "road":      (220, 50,  50,  160),   # 🔴 빨강  — 차도
    "crosswalk": (230, 180, 30,  170),   # 🟡 노랑  — 횡단보도
    "alley":     (230, 180, 30,  120),   # 🟡 연노랑 — 골목
    "caution":   (255, 120, 0,   180),   # 🟠 주황  — 위험구역
}

KOREAN_LABELS = {
    "person": "사람", "bicycle": "자전거", "car": "자동차", "truck": "트럭",
    "bus": "버스", "motorcycle": "오토바이", "scooter": "스쿠터",
    "wheelchair": "휠체어", "stroller": "유모차", "bollard": "볼라드",
    "barricade": "바리케이드", "pole": "기둥", "kiosk": "키오스크",
    "movable_signage": "이동식 간판", "chair": "의자", "potted_plant": "화분",
    "carrier": "카트", "fire_hydrant": "소화전", "parking_meter": "주차 장치",
    "power_controller": "전력 장치", "dog": "개", "cat": "고양이",
}

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FastAPI
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
app = FastAPI(title="SafeStep Server")


@app.get("/health")
def health():
    return {
        "status":  "ok",
        "yolo":    MODEL_PATH.name,
        "surface": SURFACE_PATH.name if surface_model else None,
    }


@app.post("/detect")
async def detect(file: UploadFile = File(...)):
    try:
        image = Image.open(io.BytesIO(await file.read())).convert("RGB")
    except Exception as e:
        raise HTTPException(400, f"이미지 파싱 실패: {e}")

    w, h    = image.size
    results = yolo_model(image, imgsz=416, verbose=False)
    detections = []
    for result in results:
        for box in result.boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            conf  = float(box.conf[0])
            label = yolo_model.names[int(box.cls[0])]
            nx1, ny1 = x1 / w, y1 / h
            nx2, ny2 = x2 / w, y2 / h
            detections.append({
                "label":      label,
                "label_ko":   KOREAN_LABELS.get(label, label),
                "confidence": round(conf, 3),
                "box":        [round(nx1,4), round(ny1,4), round(nx2,4), round(ny2,4)],
                "cx":         round((nx1+nx2)/2, 4),
                "area":       round((nx2-nx1)*(ny2-ny1), 4),
            })
    detections.sort(key=lambda d: d["area"], reverse=True)
    return JSONResponse({"detections": detections})


@app.post("/segment")
async def segment(file: UploadFile = File(...)):
    if surface_model is None:
        raise HTTPException(503, "surface.pt 가 없습니다. 서버 폴더에 복사 후 재시작해주세요.")

    try:
        raw   = await file.read()
        image = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as e:
        raise HTTPException(400, f"이미지 파싱 실패: {e}")

    orig_w, orig_h = image.size

    # ── YOLO 세그멘테이션 추론 ─────────────────────────────
    results = surface_model(image, imgsz=640, verbose=False)
    result  = results[0]

    # ── 픽셀 마스크 생성 ────────────────────────────────────
    cat_mask  = np.zeros((orig_h, orig_w), dtype=np.uint8)
    mask_rgba = np.zeros((orig_h, orig_w, 4), dtype=np.uint8)

    if result.masks is not None:
        seg_masks = result.masks.data.cpu().numpy()   # (N, mH, mW)
        cls_arr   = result.boxes.cls.cpu().numpy().astype(int)
        conf_arr  = result.boxes.conf.cpu().numpy()

        # 신뢰도 낮은 것부터 먼저 그려 높은 것이 위에 덮이게
        order = np.argsort(conf_arr)
        for i in order:
            if conf_arr[i] < 0.25:
                continue
            cls_name = surface_model.names[cls_arr[i]]
            category = SURFACE_CATEGORY.get(cls_name)
            if category is None:
                continue

            # 마스크를 원본 크기로 리사이즈
            mask_bin = cv2.resize(
                seg_masks[i].astype(np.float32), (orig_w, orig_h),
                interpolation=cv2.INTER_LINEAR
            ) > 0.5

            cat_mask[mask_bin]  = CAT_INT[category]
            mask_rgba[mask_bin] = COLOR_MAP[category]

    # ── 중앙 하단 1/3 구역으로 상태 판단 ──────────────────
    cy0       = orig_h * 2 // 3
    cx0, cx1  = orig_w // 3, orig_w * 2 // 3
    region    = cat_mask[cy0:, cx0:cx1]

    counts = {cat: int(np.sum(region == ci)) for cat, ci in CAT_INT.items()}
    total  = max(sum(counts.values()), 1)
    ratios = {k: round(v / total, 3) for k, v in counts.items()}

    # caution/road는 소량이어도 우선 경고
    if counts["caution"] > total * 0.05:
        status = "caution"
    elif counts["road"] > total * 0.10:
        status = "road"
    else:
        dominant = max(counts, key=counts.get)
        status   = dominant if counts[dominant] > 0 else "unknown"

    # ── PNG → base64 ───────────────────────────────────────
    pil_mask = Image.fromarray(mask_rgba, mode="RGBA")
    buf = io.BytesIO()
    pil_mask.save(buf, format="PNG", optimize=True)
    mask_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    return JSONResponse({
        "mask_b64": mask_b64,
        "status":   status,
        "ratios":   ratios,
    })


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
if __name__ == "__main__":
    print("[SafeStep] 서버 시작 (포트 8000)")
    uvicorn.run(app, host="0.0.0.0", port=8000)
