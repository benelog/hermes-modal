#!/usr/bin/env bash
# Android 기기 USB 연결/디버깅 상태 진단 스크립트.
#   사용:  ./device_check.sh
# adb로 기기가 보이면 'device' 상태와 모델명을 출력하고 exit 0,
# 안 보이면 USB 레벨 감지 여부에 따라 원인 후보를 안내하고 exit 1.

ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"

if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then
  echo "✗ adb를 찾을 수 없음. ANDROID_SDK_ROOT 또는 PATH 확인."
  exit 2
fi

echo "== adb: $ADB =="
"$ADB" start-server >/dev/null 2>&1

echo
echo "== 1) USB 레벨(lsusb)에서 폰 감지 여부 =="
PHONE_USB="$(lsusb 2>/dev/null | grep -iE 'google|samsung|lg electronics|xiaomi|huawei|oneplus|sony|motorola|oppo|vivo|htc|asus|realme|nothing' || true)"
if [ -n "$PHONE_USB" ]; then
  echo "  ✓ USB에서 폰으로 보이는 장치:"
  echo "$PHONE_USB" | sed 's/^/    /'
else
  echo "  ✗ lsusb에 폰 vendor 없음 (충전전용 모드/데이터 안 되는 케이블이면 여기서 안 잡힘)"
fi

echo
echo "== 2) adb devices =="
"$ADB" devices -l

STATE="$("$ADB" get-state 2>/dev/null || true)"
SERIAL="$("$ADB" get-serialno 2>/dev/null || true)"

DEVICES_OUT="$("$ADB" devices)"

echo
echo "== 3) 판정 =="
if [ "$STATE" = "device" ]; then
  MODEL="$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  REL="$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  SDK="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "  ✓ 연결됨: $MODEL (Android $REL, API $SDK)  serial=$SERIAL"
  echo "  → 설치 가능:  ./gradlew installDebug"
  exit 0
elif echo "$DEVICES_OUT" | grep -q "no permissions"; then
  echo "  ⚠ 기기는 보이지만 'no permissions' — Linux udev 규칙이 없어 USB 노드가 root 전용입니다."
  echo "    아래를 '본인 터미널'에서 실행(비밀번호 입력 필요):"
  echo
  echo "      sudo tee /etc/udev/rules.d/51-android.rules >/dev/null <<'EOF'"
  echo "      SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", MODE=\"0660\", GROUP=\"plugdev\""
  echo "      EOF"
  echo "      sudo udevadm control --reload-rules && sudo udevadm trigger"
  echo
  echo "    그 다음 폰을 '뽑았다가 다시 꽂고' 이 스크립트를 재실행하세요."
  echo "    (임시방편: sudo \"$ADB\" kill-server && sudo \"$ADB\" start-server)"
  exit 1
elif echo "$DEVICES_OUT" | grep -q "unauthorized"; then
  echo "  ⚠ 기기는 보이지만 'unauthorized' — 폰 화면의 'USB 디버깅 허용' 팝업을 '허용'하세요."
  exit 1
elif echo "$DEVICES_OUT" | grep -q "offline"; then
  echo "  ⚠ 'offline' — 케이블 다시 꽂거나 'adb kill-server && adb start-server' 후 재시도."
  exit 1
else
  echo "  ✗ adb가 기기를 못 봄. 폰에서 확인:"
  echo "    1. USB 모드 = '파일 전송(MTP)' 또는 'PTP' (충전전용 X)"
  echo "    2. 개발자 옵션 → USB 디버깅 ON"
  echo "    3. 데이터 전송용 USB 케이블 사용 (충전전용 케이블 X)"
  echo "    4. 화면의 'USB 디버깅 허용' 팝업 허용"
  exit 1
fi
