#!/usr/bin/env bash
# Modal 프로덕션 배포 — 라이브 Telegram/Kakao 봇이 교체된다(docs/decisions.md #16).
# 실수 방지를 위해 서버 단위 테스트를 먼저 돌리고, 확인 프롬프트를 거친 뒤 배포한다.
#
# 사용법:
#   ./deploy.sh       # 테스트 → 확인 프롬프트 → modal deploy modal_app.py
#   ./deploy.sh -y    # 확인 프롬프트 생략(정말 의도한 경우만)
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v modal >/dev/null 2>&1; then
  echo "modal CLI가 PATH에 없습니다 — 'pipx install modal' 후 다시 실행하세요." >&2
  exit 1
fi

echo "▶ 서버 단위 테스트 (python3 -m unittest discover -s tests)"
python3 -m unittest discover -s tests

if [[ "${1:-}" != "-y" ]]; then
  echo
  echo "⚠ 프로덕션 배포입니다 — 라이브 Telegram/Kakao 봇이 새 코드로 교체됩니다."
  read -r -p "계속할까요? [y/N] " answer
  if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "중단했습니다."
    exit 1
  fi
fi

echo "▶ modal deploy modal_app.py"
modal deploy modal_app.py
