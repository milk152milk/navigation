"""WebSocket 라우팅 테스트 - main.py 직접 라우트 vs 라우터 포함"""
from fastapi import FastAPI, WebSocket
from fastapi.testclient import TestClient
from backend.routers import camera_router

app = FastAPI()

# 직접 등록
@app.websocket("/ws/direct")
async def ws_direct(websocket: WebSocket):
    await websocket.accept()
    await websocket.send_text("direct ok")
    await websocket.close()

# 라우터 포함
app.include_router(camera_router.router)

client = TestClient(app)

print("=== 등록된 라우트 ===")
for r in app.routes:
    print(f"  {type(r).__name__:25} {getattr(r, 'path', '?')}")

print("\n=== WebSocket 테스트 ===")
try:
    with client.websocket_connect("/ws/direct") as ws:
        data = ws.receive_text()
        print(f"  /ws/direct: OK ({data})")
except Exception as e:
    print(f"  /ws/direct: FAIL ({e})")

try:
    with client.websocket_connect("/ws/dashboard") as ws:
        data = ws.receive_json()
        print(f"  /ws/dashboard: OK ({data})")
except Exception as e:
    print(f"  /ws/dashboard: FAIL ({e})")
