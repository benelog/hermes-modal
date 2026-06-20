#!/usr/bin/env bash
# 접근성 수집 서비스를 adb로 켠다.
# 왜? Android 13+는 사이드로딩 앱의 접근성 토글을 "제한된 설정(Restricted setting)"으로 막는다.
# UI에서 못 켜질 때 이 스크립트로 secure settings에 직접 등록한다(+재설치 후 깨끗한 rebind).
set -e
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
PKG=net.benelog.kakaocollector
SVC="$PKG/net.benelog.kakaocollector.KakaoCollectorService"

echo "== 접근성 서비스 깨끗이 재등록 (OFF → force-stop → ON) =="
"$ADB" shell settings put secure accessibility_enabled 0
"$ADB" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell settings put secure enabled_accessibility_services "$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
# 프로세스 기동(서비스가 새 프로세스에 바인딩되도록)
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true

echo -n "== 바인딩 확인: "
if "$ADB" shell dumpsys accessibility 2>/dev/null | grep -q "label=Kakao Collector"; then
  echo "OK (Kakao Collector 서비스 bound) =="
else
  echo "아직 — 잠시 후 다시 실행하거나 폰에서 앱을 한 번 열어주세요. =="
fi
