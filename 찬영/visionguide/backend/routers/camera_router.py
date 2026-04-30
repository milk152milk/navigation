"""
카메라 + 대시보드 WebSocket 라우터

- /ws/camera      : 핸드폰이 프레임을 업로드하는 채널
- /ws/dashboard   : 노트북 대시보드가 실시간으로 프레임을 구독하는 채널
- /camera/stream  : MJPEG 스트리밍 엔드포인트 (대시보드 img src용)
"""
import asyncio
import json
import time
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse

from backend.utils.image_utils import decode_base64_image
from backend.utils.logger import get_logger
from backend.services import inference_service
from backend.services.inference_service import shared_executor

_inference_running = False

# MJPEG 스트리밍용 전역 버퍼
_latest_jpeg: bytes = b""
_frame_counter: int = 0


logger = get_logger(__name__)
router = APIRouter()


# ============================================
# 브로드캐스트 매니저
# ============================================
class DashboardManager:
    def __init__(self):
        self._clients: set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket):
        await ws.accept()
        async with self._lock:
            self._clients.add(ws)
        logger.info(f"🖥️  대시보드 연결: 현재 {len(self._clients)}개 클라이언트")

    async def disconnect(self, ws: WebSocket):
        async with self._lock:
            self._clients.discard(ws)
        logger.info(f"🖥️  대시보드 해제: 현재 {len(self._clients)}개 클라이언트")

    async def broadcast(self, message: dict):
        if not self._clients:
            return
        dead = []
        for ws in list(self._clients):
            try:
                await ws.send_json(message)
            except Exception as e:
                logger.error(f"브로드캐스트 실패 (type={message.get('type')}): {e}")
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self._clients.discard(ws)

    @property
    def count(self) -> int:
        return len(self._clients)


dashboard_manager = DashboardManager()


def update_latest_frame(jpeg_bytes: bytes):
    """SafeStep /detect 등 REST 엔드포인트에서 프레임을 업데이트할 때 호출."""
    global _latest_jpeg, _frame_counter
    _latest_jpeg = jpeg_bytes
    _frame_counter += 1


# ============================================
# MJPEG 스트리밍 엔드포인트
# ============================================
@router.get("/camera/stream")
async def camera_mjpeg_stream():
    """대시보드 img src로 사용하는 MJPEG 스트림."""
    async def generate():
        last_seen = -1
        while True:
            if _frame_counter != last_seen and _latest_jpeg:
                last_seen = _frame_counter
                yield (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n\r\n" +
                    _latest_jpeg + b"\r\n"
                )
            await asyncio.sleep(0.02)  # 최대 50fps 폴링

    return StreamingResponse(
        generate(),
        media_type="multipart/x-mixed-replace; boundary=frame",
        headers={"Cache-Control": "no-cache"},
    )


# ============================================
# 백그라운드 AI 추론
# ============================================
async def _run_live_inference(frame_id: int, image_bytes: bytes):
    """추론 중이면 이 프레임 스킵. 완료 시 frame_overlay 브로드캐스트."""
    global _inference_running
    if _inference_running:
        return
    _inference_running = True
    try:
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            shared_executor, inference_service.analyze_frame, image_bytes
        )
        await dashboard_manager.broadcast({
            "type": "frame_overlay",
            "frame_id": frame_id,
            "surface_masks": result.get("surface_masks", {}),
            "obstacle_items": result.get("obstacle_items", []),
            "img_width": result.get("img_width", 0),
            "img_height": result.get("img_height", 0),
            "message": result.get("message", ""),
            "detail": result.get("detail", {}),
            "analysis": {
                "obstacle": result.get("obstacle", {}),
                "surface": result.get("surface", {}),
            },
        })
        logger.info(f"🤖 추론 완료 (프레임 #{frame_id}): {result.get('message', '')}")
    except Exception as e:
        logger.error(f"추론 오류 (프레임 #{frame_id}): {e}")
    finally:
        _inference_running = False


# ============================================
# /ws/camera  (핸드폰 → 서버)
# ============================================
@router.websocket("/ws/camera")
async def camera_websocket(websocket: WebSocket):
    global _latest_jpeg, _frame_counter

    await websocket.accept()
    client = f"{websocket.client.host}:{websocket.client.port}"
    logger.info(f"카메라 연결: {client}")
    await websocket.send_json({"type": "ack", "message": "서버에 연결되었습니다"})
    frame_count = 0

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if payload.get("type") != "frame":
                continue
            try:
                image_bytes = decode_base64_image(payload.get("image", ""))
            except Exception:
                await websocket.send_json({"type": "error", "message": "이미지 디코딩 실패"})
                continue
            frame_count += 1
            _latest_jpeg = image_bytes
            _frame_counter += 1
            kb = len(image_bytes) / 1024
            logger.info(f"AI 프레임 #{frame_count} | {kb:.1f} KB")
            await websocket.send_json({
                "type": "result",
                "frame_id": frame_count,
                "timestamp": payload.get("timestamp", 0),
                "server_time": int(time.time() * 1000),
                "image_size_kb": round(kb, 1),
                "message": "처리 중...",
            })
            asyncio.create_task(_run_live_inference(frame_count, image_bytes))
    except WebSocketDisconnect:
        logger.info(f"카메라 해제: {client} (총 {frame_count} 프레임)")
        await dashboard_manager.broadcast({"type": "phone_disconnect", "phone": client})
    except Exception as e:
        logger.exception(f"카메라 WebSocket 오류: {e}")
        try:
            await websocket.close()
        except Exception:
            pass


# ============================================
# /ws/dashboard  (노트북 ← 서버)
# ============================================
@router.websocket("/ws/dashboard")
async def dashboard_websocket(websocket: WebSocket):
    await dashboard_manager.connect(websocket)
    try:
        await websocket.send_json({"type": "ack", "message": "대시보드 연결됨"})
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await dashboard_manager.disconnect(websocket)
    except Exception as e:
        logger.exception(f"대시보드 WebSocket 오류: {e}")
        await dashboard_manager.disconnect(websocket)
