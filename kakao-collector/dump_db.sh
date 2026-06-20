#!/usr/bin/env bash
# 폰의 로컬 수집 DB(collector.db)를 꺼내 요약/최근 행을 보여준다.
set -e
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
PKG=net.benelog.kakaocollector
OUT=/tmp/collector.db

# run-as 로 앱 private DB를 바이너리 그대로 꺼냄(exec-out: CR/LF 변환 안 함).
"$ADB" exec-out run-as "$PKG" cat databases/collector.db > "$OUT" 2>/dev/null || {
  echo "DB를 못 꺼냄(아직 수집 0건이면 DB 미생성). 먼저 한 번 수집하세요."; exit 1; }

echo "== 방별 건수 / 미전송 =="
sqlite3 "$OUT" "select room, count(*) total, sum(case when sent_ok=0 then 1 else 0 end) unsent from messages group by room;"
echo
echo "== 최근 20건 (KST) =="
sqlite3 -header -column "$OUT" \
  "select datetime(collected_at/1000,'unixepoch','+9 hours') t, room, sender, sent_ok, substr(text,1,40) text from messages order by _id desc limit 20;"
