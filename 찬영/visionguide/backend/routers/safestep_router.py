"""
SafeStep 호환 API 엔드포인트
- POST /detect   : 장애물 탐지 (SafeStep RemoteDetector.kt 호환)
- POST /segment  : 노면 세그멘테이션 (SafeStep SegmentationClient.kt 호환)

SafeStep 앱은 /detect, /segment 를 같은 프레임으로 따로 요청함.
캐시를 통해 같은 이미지는 한 번만 추론하도록 처리.
"""
import asyncio
import base64
import hashlib
import time

import cv2
import numpy as np
from fastapi import APIRouter, UploadFile, File

from backend.services import inference_service
from backend.services.inference_service import shared_executor
from backend.utils.logger import get_logger
from backend.routers.camera_router import update_latest_frame, dashboard_manager

logger = get_logger(__name__)
router = APIRouter()

# ── 캐시 + 진행 중 대기 (같은 프레임 2번 추론 방지) ──────────────────────
_cache: dict = {}      # {md5_hex: (result, timestamp)}
_seg_cache: dict = {}  # {md5_hex: (seg_result, timestamp)} - /segment 처리 결과 캐시
_pending: dict = {}    # {md5_hex: asyncio.Event} - 추론 진행 중인 키
_CACHE_TTL = 2.0       # 2초 이내 동일 이미지 → 재사용


def _md5(image_bytes: bytes) -> str:
    return hashlib.md5(image_bytes).hexdigest()


def _get_cached(key: str) -> dict | None:
    entry = _cache.get(key)
    if entry:
        result, ts = entry
        if time.time() - ts < _CACHE_TTL:
            return result
        del _cache[key]
    return None


def _set_cache(key: str, result: dict):
    _cache[key] = (result, time.time())
    # 만료된 캐시 정리
    now = time.time()
    expired = [k for k, (_, ts) in list(_cache.items()) if now - ts >= _CACHE_TTL]
    for k in expired:
        _cache.pop(k, None)


async def _get_or_infer(image_bytes: bytes) -> dict:
    """
    캐시 확인 → 추론 중이면 대기 → 없으면 추론 후 캐시 저장.
    같은 프레임으로 /detect, /segment가 동시에 와도 추론은 1번만 실행.
    """
    key = _md5(image_bytes)

    # 1) 캐시 히트
    cached = _get_cached(key)
    if cached is not None:
        return cached

    # 2) 추론 진행 중이면 끝날 때까지 대기
    if key in _pending:
        await _pending[key].wait()
        return _get_cached(key) or {}

    # 3) 직접 추론
    event = asyncio.Event()
    _pending[key] = event
    try:
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            shared_executor, inference_service.analyze_frame, image_bytes
        )
        _set_cache(key, result)
        return result
    finally:
        event.set()
        _pending.pop(key, None)


# ── 노면 클래스 → SafeStep status 매핑 ────────────────────────────────────
_CLS_TO_SAFESTEP = {
    "roadway_normal":              "road",
    "roadway_crosswalk":           "crosswalk",
    "alley_normal":                "alley",
    "alley_crosswalk":             "crosswalk",
    "alley_damaged":               "alley",
    "alley_speed_bump":            "alley",
    "sidewalk_asphalt":            "sidewalk",
    "sidewalk_blocks":             "sidewalk",
    "sidewalk_cement":             "sidewalk",
    "sidewalk_urethane":           "sidewalk",
    "sidewalk_soil_stone":         "sidewalk",
    "sidewalk_other":              "sidewalk",
    "sidewalk_damaged":            "sidewalk",
    "braille_guide_blocks_normal": "sidewalk",
    "braille_guide_blocks_damaged":"sidewalk",
    "caution_zone_grating":        "sidewalk",
    "caution_zone_manhole":        "sidewalk",
    "caution_zone_repair_zone":    "sidewalk",
    "caution_zone_stairs":         "sidewalk",
    "caution_zone_tree_zone":      "sidewalk",
    "bike_lane":                   "road",
}

# SURFACE_CLASS_TO_GROUP의 group → SafeStep status (정면 구역 status 결정용)
_GROUP_TO_STATUS = {
    "danger":  "road",
    "caution": "alley",
    "safe":    "sidewalk",
    "braille": "sidewalk",
    "bike":    "road",
}


# ══════════════════════════════════════════════════════════════════════════════
# POST /detect
# ══════════════════════════════════════════════════════════════════════════════
@router.post("/detect")
async def detect(file: UploadFile = File(...)):
    """
    SafeStep RemoteDetector.kt 호환 엔드포인트.

    반환 포맷:
    {
        "detections": [
            {
                "label":      "person",     # 영문 클래스명
                "label_ko":   "사람",       # 한글 이름
                "confidence": 0.87,
                "box":        [x1,y1,x2,y2],
                "direction":  "왼쪽",       # 왼쪽/정면/오른쪽
                "depth_m":    4.8           # 거리(m), 없으면 null
            }
        ]
    }
    """
    image_bytes = await file.read()
    update_latest_frame(image_bytes)   # 대시보드 라이브 카메라 업데이트
    result = await _get_or_infer(image_bytes)

    detections = [
        {
            "label":      item["cls_name"],
            "label_ko":   item["group"],
            "confidence": round(float(item["conf"]), 3),
            "box":        item["bbox"],          # [x1, y1, x2, y2]
            "direction":  item.get("direction", "정면"),
            "depth_m":    item.get("depth_m"),
        }
        for item in result.get("obstacle_items", [])
    ]

    # message: Ollama ON이면 자연어 메시지, OFF면 규칙 기반 메시지
    message = result.get("message", "")

    # dodge: 회피 방향 (왼쪽/정면/오른쪽) - 진동 방향에 사용
    dodge_text = result.get("detail", {}).get("dodge", "")
    if "왼쪽" in dodge_text:
        dodge = "왼쪽"
    elif "오른쪽" in dodge_text:
        dodge = "오른쪽"
    else:
        dodge = "정면"

    logger.info(f"[SafeStep /detect] 탐지 {len(detections)}개 | dodge={dodge} | message={message}")

    # 대시보드 사이드바용 analysis 객체 (노면/장애물 카드 업데이트용)
    analysis = {
        "obstacle": result.get("obstacle", {}),
        "surface":  result.get("surface",  {}),
    }

    # 대시보드에 라이브 오버레이 브로드캐스트 (MJPEG + 박스 표시)
    if dashboard_manager.count > 0:
        asyncio.create_task(dashboard_manager.broadcast({
            "type": "frame_overlay",
            "surface_masks": result.get("surface_masks", {}),
            "obstacle_items": result.get("obstacle_items", []),
            "img_width": result.get("img_width", 0),
            "img_height": result.get("img_height", 0),
            "message": message,
            "detail": result.get("detail", {}),
            "analysis": {
                "obstacle": result.get("obstacle", {}),
                "surface": result.get("surface", {}),
            },
        }))

    return {"detections": detections, "message": message, "dodge": dodge, "analysis": analysis, "detail": result.get("detail", {})}


# ══════════════════════════════════════════════════════════════════════════════
# POST /segment
# ══════════════════════════════════════════════════════════════════════════════
@router.post("/segment")
async def segment(file: UploadFile = File(...)):
    """
    SafeStep SegmentationClient.kt 호환 엔드포인트.

    반환 포맷:
    {
        "mask_b64":  "<base64 PNG>",   # 전체 합쳐진 BGRA 마스크 이미지
        "status":    "sidewalk",       # sidewalk | road | crosswalk | alley | unknown
        "ratios": {
            "road":      0.1,
            "sidewalk":  0.8,
            "crosswalk": 0.0,
            "alley":     0.1
        }
    }
    """
    image_bytes = await file.read()
    key = _md5(image_bytes)

    # 세그먼트 처리 결과 캐시 확인 (마스크 합치기 연산 반복 방지)
    seg_entry = _seg_cache.get(key)
    if seg_entry:
        seg_result, ts = seg_entry
        if time.time() - ts < _CACHE_TTL:
            return seg_result
        del _seg_cache[key]

    result = await _get_or_infer(image_bytes)

    surface_masks = result.get("surface_masks", {})
    img_w = result.get("img_width", 640)
    img_h = result.get("img_height", 480)
    total_pixels = max(img_w * img_h, 1)

    # ── 마스크 합치기 + 비율 계산 ───────────────────────────────────────
    combined = np.zeros((img_h, img_w, 4), dtype=np.uint8)
    ratios = {"road": 0.0, "sidewalk": 0.0, "crosswalk": 0.0, "alley": 0.0}

    for cls_name, b64_png in surface_masks.items():
        # "data:image/png;base64,..." → 순수 base64 추출
        b64_data = b64_png.split(",")[1] if "," in b64_png else b64_png
        try:
            png_bytes = base64.b64decode(b64_data)
            arr = np.frombuffer(png_bytes, dtype=np.uint8)
            mask_img = cv2.imdecode(arr, cv2.IMREAD_UNCHANGED)
        except Exception:
            continue
        if mask_img is None or mask_img.ndim < 3:
            continue

        # 알파 채널로 유효 픽셀 수 계산
        alpha = mask_img[:, :, 3]
        pixel_count = int(np.count_nonzero(alpha))
        safestep_key = _CLS_TO_SAFESTEP.get(cls_name, "")
        if safestep_key in ratios:
            ratios[safestep_key] += pixel_count / total_pixels

        # combined에 오버레이 (나중에 그린 클래스가 위에 올라옴)
        mask = alpha > 0
        combined[mask] = mask_img[mask]

    # ── status 결정 (정면 구역 기준) ───────────────────────────────────
    surface_zones = result.get("surface", {})
    front_group = surface_zones.get("정면", {}).get("group", "")
    status = _GROUP_TO_STATUS.get(front_group, "unknown")

    # crosswalk 별도 체크 (정면 cls_name이 crosswalk이면 우선)
    front_cls = surface_zones.get("정면", {}).get("cls_name", "")
    if "crosswalk" in front_cls:
        status = "crosswalk"

    # ── 왼쪽 / 오른쪽 구역 status 결정 (방향 유도용) ────────────────────
    def _zone_to_status(zone_info: dict) -> str:
        cls = zone_info.get("cls_name", "")
        if "crosswalk" in cls:
            return "crosswalk"
        grp = zone_info.get("group", "")
        return _GROUP_TO_STATUS.get(grp, "unknown")

    left_status  = _zone_to_status(surface_zones.get("왼쪽", {}))
    right_status = _zone_to_status(surface_zones.get("오른쪽", {}))

    # ── 신호등 색상 (횡단보도일 때만) ────────────────────────────────────
    traffic_light_color = ""
    if status == "crosswalk":
        for item in result.get("obstacle_items", []):
            if item.get("cls_name") == "traffic_light" and item.get("light_color"):
                traffic_light_color = item["light_color"]  # "red" | "green"
                break

    # ── combined → base64 PNG ───────────────────────────────────────────
    _, buf = cv2.imencode(".png", combined, [cv2.IMWRITE_PNG_COMPRESSION, 6])
    mask_b64 = base64.b64encode(buf.tobytes()).decode()

    seg_result = {
        "mask_b64":     mask_b64,
        "status":       status,
        "ratios":       {k: round(v, 4) for k, v in ratios.items()},
        "front_cls":            front_cls,
        "left_status":          left_status,
        "right_status":         right_status,
        "traffic_light_color":  traffic_light_color,  # "red" | "green" | ""
    }
    _seg_cache[key] = (seg_result, time.time())
    # 만료된 세그먼트 캐시 정리
    now = time.time()
    expired = [k for k, (_, ts) in list(_seg_cache.items()) if now - ts >= _CACHE_TTL]
    for k in expired:
        _seg_cache.pop(k, None)

    logger.info(f"[SafeStep /segment] status={status}, ratios={ratios}")
    return seg_result
