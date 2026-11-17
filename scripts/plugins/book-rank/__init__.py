"""`/kyobo <순위>` — 메신저에서 확인한 교보문고 순위를 기록한다.

교보문고는 클라우드 IP에서 빈 응답을 주므로 자동 수집이 안 된다. 사람이 확인한
순위를 이 명령으로 보내면 Modal의 `book_rank_manual` 함수가 이력(Volume)에 넣고
갱신된 요약을 돌려준다. LLM을 거치지 않는 결정적 명령이다.
"""

from __future__ import annotations

APP_NAME = "hermes-telegram-gateway"
FUNCTION_NAME = "book_rank_manual"

USAGE = (
    "교보문고 순위 수동 기록\n"
    "• /kyobo 117 — 오늘, 직전 부문으로 117위\n"
    "• /kyobo 컴퓨터/IT 117위 — 부문 지정\n"
    "• /kyobo 2026-11-18 컴퓨터/IT 117 — 날짜 지정\n"
    "• /kyobo 없음 — 순위권 밖"
)


async def _kyobo(raw_args: str) -> str:
    text = (raw_args or "").strip()
    if not text:
        return USAGE
    try:
        import modal

        fn = modal.Function.from_name(APP_NAME, FUNCTION_NAME)
        return await fn.remote.aio(text)
    except Exception as exc:  # 오류를 메신저로 그대로 알려 준다
        return f"⚠️ 기록 실패: {type(exc).__name__}: {exc}"[:500]


def register(ctx) -> None:
    ctx.register_command(
        "kyobo",
        _kyobo,
        description="교보문고 순위 수동 기록",
        args_hint="[YYYY-MM-DD] [부문] <순위>",
    )
