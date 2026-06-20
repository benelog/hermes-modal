#!/usr/bin/env bash
# 앱 빌드 + 폰 설치 (JDK 17·Android SDK 환경을 자동 지정).
#   사용:  ./install.sh                # = assembleDebug + installDebug
#         ./install.sh assembleDebug  # 임의 gradle task 전달 가능
#
# 왜 필요? 전역 기본 JDK가 25면 Gradle 8.7 / AGP 8.5.2가
#   "What went wrong: 25" 로 빌드 자체가 실패한다. 이 스크립트는 JDK 17을 강제한다.

set -euo pipefail
cd "$(dirname "$0")"

# 1) 빌드용 JDK 17(또는 21) 찾기 — Gradle 8.7은 JDK 25에서 동작 안 함.
pick_jdk() {
  # 우선순위: 명시된 17.0.16-tem → sdkman의 17.* → 17 또는 21 아무거나
  local c="$HOME/.sdkman/candidates/java"
  for cand in "$c/17.0.16-tem" "$c"/17.*-tem "$c"/17.* "$c"/21.*; do
    [ -x "$cand/bin/javac" ] && { echo "$cand"; return 0; }
  done
  return 1
}

if ! JH="$(pick_jdk)"; then
  echo "✗ JDK 17(또는 21)을 못 찾음. 'sdk install java 17.0.16-tem' 후 재시도."
  exit 2
fi
export JAVA_HOME="$JH"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
echo "== JAVA_HOME=$JAVA_HOME =="
echo "== ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT =="
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# 2) 기기 연결 확인 (installDebug면 필수). device_check.sh가 있으면 활용.
TASK="${*:-installDebug}"
if printf '%s' "$TASK" | grep -q install; then
  ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
  STATE="$("$ADB" get-state 2>/dev/null || true)"
  if [ "$STATE" != "device" ]; then
    echo
    echo "⚠ adb에 'device' 상태 기기가 없습니다. 먼저 ./device_check.sh 로 점검하세요."
    echo "  (설치 없이 APK만 만들려면:  ./install.sh assembleDebug )"
    exit 1
  fi
  echo "== 기기: $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r') =="
fi

# 3) 실행
echo "== ./gradlew $TASK =="
./gradlew $TASK
echo
echo "✓ 완료: $TASK"
