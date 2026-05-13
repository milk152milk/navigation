# SafeStep 음성 어시스턴트 — /assistant 엔드포인트 (google-generativeai)
#
# 핵심 흐름:
#   사용자 음성 텍스트 + 화면 컨텍스트 → Gemini → action JSON
#
# 화면별 동작:
#   - SplashActivity (모드 선택 화면): 카메라 모드 / 동영상 테스트 진입만
#   - MapActivity   (지도 화면)        : 내비·위치·주변 설명 등 풀 기능
#
# 통합 방법:
#   1. 이 파일을 SafeStep/server/ 폴더에 복사
#   2. server.py 에 라우터 등록
#   3. .env 에 GOOGLE_API_KEY 추가
#   4. pip install google-generativeai python-dotenv

import asyncio
import json
import os
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import requests as _requests
from dotenv import load_dotenv
from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

_executor = ThreadPoolExecutor(max_workers=2)

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# API KEY 로드
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
load_dotenv()

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 모델 설정 — Groq (OpenAI 호환 API, 무료)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
MODEL_NAME   = "llama-3.1-8b-instant"
_API_KEY     = os.getenv("GROQ_API_KEY", "")
_GROQ_URL    = "https://api.groq.com/openai/v1/chat/completions"

if _API_KEY:
    print(f"[Assistant] Groq ({MODEL_NAME}) 준비 완료 ✅")
else:
    print("[Assistant] ⚠️  GROQ_API_KEY 미설정 — /assistant 비활성화")


def _call_groq(system: str, user: str) -> str:
    """Groq API 호출 (blocking)."""
    headers = {
        "Authorization": f"Bearer {_API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": MODEL_NAME,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user",   "content": user},
        ],
        "temperature": 0.2,
        "max_tokens": 256,
    }
    resp = _requests.post(_GROQ_URL, headers=headers, json=payload, timeout=20)
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 도구 목록 (프롬프트 텍스트로 LLM 에 전달)
# 실제 실행은 안드로이드 앱(Kotlin)이 action 이름을 보고 처리합니다.
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

def _tool_list_for(screen: str) -> str:
    """화면별 도구 목록을 프롬프트 텍스트로 반환."""
    if screen == "splash":
        return """[사용 가능한 명령]
- enter_camera_mode : 카메라 모드로 진입  (예: "카메라 모드", "시작", "출발", "보행 모드")
- enter_video_test_mode : 동영상 테스트 모드로 진입  (예: "동영상 테스트", "테스트 모드")

반드시 아래 JSON 형식 중 하나로만 답하세요 (다른 텍스트 없이 JSON 만):
명령이 해당될 때: {"action": "명령이름", "params": {}, "tts": "사용자에게 읽어줄 짧은 문장"}
해당 없을 때:    {"action": "speak_only", "params": {}, "tts": "응답 문장"}"""
    return """[사용 가능한 명령]
- start_navigation   : 목적지 내비게이션/경로 안내 시작  params: {"destination": "목적지명"}
  ★ "~로 안내해줘", "~가줘", "~까지 길 알려줘", "~로 데려다줘" → 반드시 이 명령 사용
  ★ destination에는 장소명만 넣으세요 (예: "강남역", "스타벅스", "상우고등학교")
  ★ "학교", "병원" 같은 시설 카테고리가 아니라 구체적 장소명이 있으면 이 명령 사용
- cancel_navigation  : 내비게이션 취소  예: "내비 그만", "안내 종료"
- reroute            : 경로 재탐색  예: "다시 길 찾아줘", "재탐색"
- get_current_location : 현재 위치 안내  예: "지금 어디야?", "내 위치 알려줘"
- get_remaining_info : 남은 거리/시간 안내 (내비 중일 때만)  예: "얼마나 남았어?"
- describe_surroundings : 주변 상황 설명  예: "주변에 뭐 있어?"
- find_nearby_poi    : 주변 시설 종류 검색 (구체적 장소명 없을 때만)  params: {"category": "시설종류"}
  예: "근처 화장실 찾아줘", "편의점 어디 있어?"

반드시 아래 JSON 형식 중 하나로만 답하세요 (다른 텍스트 없이 JSON 만):
명령이 해당될 때: {"action": "명령이름", "params": {}, "tts": "사용자에게 읽어줄 짧은 문장"}
해당 없을 때:    {"action": "speak_only", "params": {}, "tts": "응답 문장"}"""


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 시스템 프롬프트 (화면별로 다르게)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SYSTEM_PROMPT_BASE = """당신은 시각장애인용 보행 안전 앱 SafeStep의 음성 어시스턴트입니다.

[역할]
- 사용자 발화를 분석해 등록된 도구(tool) 중 가장 적절한 것을 호출하세요.
- 도구 호출이 필요 없는 일반 대화/질문이면 도구 호출 없이 한국어로 짧게 답하세요.
- 응답은 항상 짧고 명확해야 합니다. 시각장애인이 음성으로 듣기 때문입니다.
- 1~2문장 이내로 간결하게 답하세요.

[말투]
- 존댓말, 따뜻하고 차분하게.
- 불필요한 설명·반복 금지.
"""

SPLASH_GUIDE = """
[현재 화면: 모드 선택 화면]
- 사용자는 카메라 모드 또는 동영상 테스트 모드를 선택해야 합니다.
- 등록된 두 도구(enter_camera_mode, enter_video_test_mode)만 호출 가능합니다.
- 그 외 발화에는 "카메라 모드 또는 동영상 테스트 중 선택해주세요." 라고 답하세요.
"""

MAP_GUIDE = """
[현재 화면: 지도/카메라 화면]
- 사용자 메시지에는 현재 앱 상태(내비 진행 여부, 현재 위치, 최근 감지 객체 등)가
  함께 제공될 수 있습니다. 이를 참고해 더 정확하게 답하세요.
- 예: is_navigating=False 인데 "남은 거리?" 라고 물으면 get_remaining_info 를
  호출하지 말고 "현재 내비게이션 중이 아닙니다." 라고 답하세요.
- describe_surroundings(카메라에 보이는 객체)와 find_nearby_poi(지도상 시설)는
  서로 다른 도구입니다. 사용자 의도에 맞게 골라주세요.
"""


def _system_prompt_for(screen: str) -> str:
    if screen == "splash":
        return SYSTEM_PROMPT_BASE + SPLASH_GUIDE
    return SYSTEM_PROMPT_BASE + MAP_GUIDE


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Pydantic 스키마
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class AssistantRequest(BaseModel):
    text: str
    context: dict[str, Any] = {}


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# FastAPI 라우터
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
router = APIRouter(tags=["assistant"])


@router.post("/assistant")
async def assistant(req: AssistantRequest):
    """음성 텍스트 → Gemini → action JSON 반환."""
    print(f"[Assistant] 요청 수신: '{req.text[:60]}'")

    if not _API_KEY:
        raise HTTPException(503, "LLM 이 초기화되지 않았습니다. GOOGLE_API_KEY 를 확인하세요.")
    if not req.text.strip():
        raise HTTPException(400, "text 가 비어있습니다.")

    screen    = str(req.context.get("screen", "map"))
    user_text = _build_user_message(req.text, req.context)
    system    = _system_prompt_for(screen) + "\n\n" + _tool_list_for(screen)

    try:
        loop = asyncio.get_event_loop()
        raw = await asyncio.wait_for(
            loop.run_in_executor(_executor, _call_groq, system, user_text),
            timeout=25.0
        )
        print(f"[Assistant] Groq 원본 응답:\n{raw}")
    except asyncio.TimeoutError:
        print(f"[Assistant] Groq 25초 타임아웃")
        raise HTTPException(504, "LLM 응답 타임아웃")
    except Exception as e:
        print(f"[Assistant] LLM 호출 실패: {e}")
        raise HTTPException(502, f"LLM 호출 실패: {e}")

    parsed = _parse_raw_response(raw, req.text)
    # start_navigation일 때 TMap 검색이 더 잘 되도록 원문을 destination으로 덮어씀
    if parsed.get("action") == "start_navigation":
        dest = parsed.get("params", {}).get("destination", "").strip()
        if not dest:
            parsed.setdefault("params", {})["destination"] = req.text
        # 추출된 목적지가 너무 짧거나 카테고리명이면 원문 사용
        if len(dest) < 2 or dest in ("학교", "병원", "역", "카페", "편의점", "약국"):
            parsed["params"]["destination"] = req.text
    print(f"[Assistant] 파싱 결과: {json.dumps(parsed, ensure_ascii=False)}")
    return JSONResponse(parsed)


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 응답 파싱
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
def _default_tts(action: str, params: dict) -> str:
    """action 이름으로 짧은 한국어 TTS 생성."""
    if action == "enter_camera_mode":
        return "카메라 모드를 시작합니다."
    if action == "enter_video_test_mode":
        return "동영상 테스트 모드를 시작합니다."
    if action == "start_navigation":
        dest = params.get("destination", "목적지")
        return f"{dest}으로 안내를 시작합니다."
    if action == "cancel_navigation":
        return "내비게이션을 종료합니다."
    if action == "reroute":
        return "경로를 다시 탐색합니다."
    if action == "get_current_location":
        return "현재 위치를 확인합니다."
    if action == "get_remaining_info":
        return "남은 거리를 확인합니다."
    if action == "describe_surroundings":
        return "주변 상황을 확인합니다."
    if action == "find_nearby_poi":
        cat = params.get("category", "시설")
        return f"주변 {cat}을 검색합니다."
    return ""


def _parse_raw_response(raw: str, original_text: str) -> dict[str, Any]:
    """LLM 텍스트 응답 → 앱이 쓰기 좋은 action dict 로 변환."""
    import re
    # 코드 블록 제거 후 JSON 추출
    cleaned = re.sub(r'```(?:json)?\s*', '', raw).strip().rstrip('`').strip()
    match = re.search(r'\{.*\}', cleaned, re.DOTALL)
    if match:
        try:
            result = json.loads(match.group())
            result.setdefault("action", "speak_only")
            result.setdefault("params", {})
            action = result["action"]
            params = result.get("params", {})
            # tts가 없거나 너무 길거나 JSON처럼 생겼으면 기본값 사용
            tts = result.get("tts", "")
            if not tts or len(tts) > 60 or "{" in tts:
                tts = _default_tts(action, params)
            result["tts"] = tts
            return result
        except Exception:
            pass
    # JSON 파싱 실패 → 텍스트를 그대로 TTS (단, 짧으면)
    tts = raw.strip()
    if len(tts) > 60 or "{" in tts:
        tts = "죄송합니다. 다시 말씀해주세요."
    return {"action": "speak_only", "params": {}, "tts": tts}


# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 사용자 메시지 빌더 — 컨텍스트 결합
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
def _build_user_message(text: str, context: dict[str, Any]) -> str:
    """사용자 발화 + 앱 상태 컨텍스트를 하나의 메시지로 조립."""
    if not context:
        return text

    lines = ["[현재 앱 상태]"]
    if context.get("screen"):
        lines.append(f"- 화면: {context['screen']}")
    if "is_navigating" in context:
        lines.append(f"- 내비 진행중: {bool(context['is_navigating'])}")
    if context.get("current_lat") is not None and context.get("current_lon") is not None:
        lines.append(f"- 현재 위치: ({context['current_lat']:.5f}, {context['current_lon']:.5f})")
    if context.get("remaining_distance_m") is not None:
        lines.append(f"- 남은 거리: {context['remaining_distance_m']}m")
    if context.get("recent_detections"):
        labels = ", ".join(context["recent_detections"][:5])
        lines.append(f"- 최근 감지된 객체: {labels}")

    lines.append("")
    lines.append("[사용자 발화]")
    lines.append(text)
    return "\n".join(lines)
