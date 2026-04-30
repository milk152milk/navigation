"""등록된 라우트 확인"""
from backend.main import app

print("=== 등록된 라우트 ===")
for r in app.routes:
    print(f"  {type(r).__name__:30} path={getattr(r, 'path', '?')!r}")
