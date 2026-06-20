#!/usr/bin/env bash
# Android adb USB 접근을 위한 udev 규칙 설치 (방법 A — 영구 해결).
#   사용:  ./setup_udev.sh           (필요 시 내부에서 sudo로 권한 상승, 비밀번호 입력)
#         sudo ./setup_udev.sh      (이미 root면 그대로 진행)
#
# 동작: /etc/udev/rules.d/51-android.rules 작성 → 규칙 reload/trigger →
#       adb 서버 재시작 → device_check.sh로 상태 확인.
# 주의: 이미 연결된 기기엔 새 규칙이 자동 적용 안 될 수 있으니, 안내대로 폰을 뽑았다 다시 꽂으세요.

set -euo pipefail

RULE_FILE="/etc/udev/rules.d/51-android.rules"
# 흔한 Android 제조사 vendor ID (Google/Pixel=18d1 포함). 필요하면 더 추가 가능.
VENDORS=(18d1 04e8 22b8 2717 12d1 2a70 0fce 19d2 1004 0bb4 0e8d 2916 05c6)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"

# root가 아니면 sudo로 자기 자신을 다시 실행(이때 비밀번호 1회 입력).
if [ "$(id -u)" -ne 0 ]; then
  echo "== root 권한이 필요합니다. sudo로 다시 실행합니다 (비밀번호 입력) =="
  # 원래 사용자/홈/adb 경로를 환경으로 넘겨서 root 셸에서도 동일하게 동작.
  exec sudo INVOKING_USER="${SUDO_USER:-$USER}" REAL_HOME="$HOME" ADB_PATH="$ADB" \
    SCRIPT_DIR="$SCRIPT_DIR" bash "$0" "$@"
fi

# 여기부터는 root.
INVOKING_USER="${INVOKING_USER:-${SUDO_USER:-root}}"
REAL_HOME="${REAL_HOME:-$HOME}"
ADB="${ADB_PATH:-$ADB}"
SCRIPT_DIR="${SCRIPT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

echo "== 1) udev 규칙 작성: $RULE_FILE =="
{
  echo "# Android adb — vendor별 USB 접근 허용 (MODE 0660, GROUP plugdev). setup_udev.sh 생성."
  for v in "${VENDORS[@]}"; do
    echo "SUBSYSTEM==\"usb\", ATTR{idVendor}==\"$v\", MODE=\"0660\", GROUP=\"plugdev\""
  done
} > "$RULE_FILE"
chmod 644 "$RULE_FILE"
echo "  작성 완료:"
sed 's/^/    /' "$RULE_FILE"

# 호출 사용자가 plugdev 그룹에 없으면 추가(이미 있으면 무해).
if [ "$INVOKING_USER" != "root" ] && id "$INVOKING_USER" >/dev/null 2>&1; then
  if ! id -nG "$INVOKING_USER" | tr ' ' '\n' | grep -qx plugdev; then
    echo "== '$INVOKING_USER'를 plugdev 그룹에 추가 (재로그인 후 반영) =="
    usermod -aG plugdev "$INVOKING_USER" || true
  fi
fi

echo
echo "== 2) udev 규칙 reload & trigger =="
udevadm control --reload-rules
udevadm trigger
echo "  완료."

echo
echo "== 3) adb 서버 재시작 (원래 사용자 권한으로) =="
if [ -x "$ADB" ]; then
  if [ "$INVOKING_USER" != "root" ] && command -v sudo >/dev/null 2>&1; then
    sudo -u "$INVOKING_USER" "$ADB" kill-server >/dev/null 2>&1 || true
    sudo -u "$INVOKING_USER" "$ADB" start-server >/dev/null 2>&1 || true
  else
    "$ADB" kill-server >/dev/null 2>&1 || true
    "$ADB" start-server >/dev/null 2>&1 || true
  fi
  echo "  완료."
else
  echo "  (adb 경로를 못 찾아 건너뜀: $ADB)"
fi

echo
echo "=================================================================="
echo " 규칙 설치 완료."
echo " ⚠ 이미 연결돼 있던 기기라면 지금 'USB 케이블을 뽑았다가 다시 꽂으세요'."
echo "   (새 udev 규칙은 재연결 시 확실히 적용됩니다.)"
echo " 그다음 확인:  ./device_check.sh"
echo "=================================================================="
