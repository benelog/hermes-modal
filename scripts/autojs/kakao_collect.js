// 아카라카북클럽 방 메시지 수집기 (AutoJs6, AccessibilityService 기반)
//
// 동작: 카카오톡에서 대상 방을 열면 화면의 메시지를 읽어 Modal /ingest로 POST.
// 위로 자동 스크롤하며 목표 기간 또는 이미 본 메시지에 도달할 때까지 수집.
// 카톡에는 아무것도 보내지 않음(읽기 전용).
//
// 사용 전:
//  1) AutoJs6에 "접근성 권한"을 부여한다.
//  2) 아래 CONFIG를 채운다. SELECTOR 값은 calibrate()로 확인 후 보정한다.
//  3) 대상 방을 화면에 띄운 뒤 이 스크립트를 실행한다.

const CONFIG = {
    ROOM_NAME: "아카라카북클럽",          // 카톡 채팅방 상단에 보이는 정확한 표시명
    INGEST_URL: "https://benelog--kakao-ingest.modal.run", // 배포된 ingest URL
    TOKEN: "<KAKAO_COLLECTOR_TOKEN>",     // 시크릿과 동일한 토큰
    MAX_SCROLLS: 40,                      // 위로 스크롤 최대 횟수(기간 안전장치)
    SCROLL_PAUSE_MS: 700,
    // calibrate()로 확인해 채울 선택자 (카톡 버전에 따라 다름)
    SELECTOR: {
        TITLE_ID: "com.kakao.talk:id/title",          // 채팅방 제목 노드 id
        MESSAGE_TEXT_ID: "com.kakao.talk:id/message", // 말풍선 본문 노드 id
        SENDER_NAME_ID: "com.kakao.talk:id/name",     // 보낸이 이름 노드 id
        TIME_ID: "com.kakao.talk:id/created_at",      // 시각 노드 id
    },
};

const KAKAO_PKG = "com.kakao.talk";

// 화면 노드 트리를 덤프해 위 SELECTOR.* id를 확인하기 위한 보정 도구.
// 대상 방을 띄운 상태에서 이 함수만 호출해 로그를 보고 CONFIG.SELECTOR를 채운다.
function calibrate() {
    function walk(node, depth) {
        if (node == null) return;
        const id = node.id && node.id();
        const text = node.text && node.text();
        const cls = node.className && node.className();
        if ((id && id.indexOf(KAKAO_PKG) === 0) || (text && text.length > 0)) {
            log(" ".repeat(depth) + "[" + cls + "] id=" + id + " text=" + JSON.stringify(text));
        }
        const n = node.childCount ? node.childCount() : 0;
        for (let i = 0; i < n; i++) walk(node.child(i), depth + 1);
    }
    log("=== calibrate: 노드 트리 ===");
    walk(auto.root, 0);
    log("=== end ===");
}

function isTargetRoomOpen() {
    if (currentPackage() !== KAKAO_PKG) return false;
    // 제목 노드 우선, 없으면 화면 어딘가에 방 이름 텍스트가 있는지로 보조 판별
    const title = id(CONFIG.SELECTOR.TITLE_ID).findOne(1000);
    if (title && title.text() && title.text().indexOf(CONFIG.ROOM_NAME) >= 0) return true;
    return text(CONFIG.ROOM_NAME).exists();
}

function postMessage(rec) {
    try {
        const res = http.postJson(CONFIG.INGEST_URL + "?token=" + encodeURIComponent(CONFIG.TOKEN), rec);
        return res && res.statusCode === 200;
    } catch (e) {
        log("post error: " + e);
        return false;
    }
}

// 현재 화면에 보이는 메시지들을 (sender, text, time)로 수집.
// 연속 메시지는 보낸이가 한 번만 보일 수 있어, 직전 보낸이를 승계한다.
function scrapeVisible(seen, lastSenderRef) {
    const texts = id(CONFIG.SELECTOR.MESSAGE_TEXT_ID).find();
    const out = [];
    for (let i = 0; i < texts.size(); i++) {
        const t = texts.get(i);
        const body = (t.text() || "").trim();
        if (!body) continue;

        // 같은 행/부모에서 보낸이·시각 노드를 탐색(없으면 직전 보낸이 승계)
        let sender = "";
        let ctime = "";
        const parent = t.parent();
        if (parent) {
            const nameNode = parent.findOne(id(CONFIG.SELECTOR.SENDER_NAME_ID));
            if (nameNode && nameNode.text()) sender = nameNode.text().trim();
            const timeNode = parent.findOne(id(CONFIG.SELECTOR.TIME_ID));
            if (timeNode && timeNode.text()) ctime = timeNode.text().trim();
        }
        if (!sender) sender = lastSenderRef.value;
        else lastSenderRef.value = sender;

        const dedupe = sender + "" + body + "" + ctime;
        if (seen[dedupe]) continue;
        seen[dedupe] = true;
        out.push({ room: CONFIG.ROOM_NAME, sender: sender, text: body, ts: ctime });
    }
    return out;
}

function main() {
    if (!isTargetRoomOpen()) {
        toast("대상 방(" + CONFIG.ROOM_NAME + ")을 먼저 열어주세요");
        log("대상 방이 화면에 없습니다. 종료.");
        return;
    }
    const seen = {};
    const lastSender = { value: "" };
    let posted = 0;

    for (let s = 0; s < CONFIG.MAX_SCROLLS; s++) {
        const batch = scrapeVisible(seen, lastSender);
        for (let i = 0; i < batch.length; i++) {
            if (postMessage(batch[i])) posted++;
        }
        // 위로 스크롤(과거 메시지 로드). 스크롤이 안 먹으면 종료.
        const before = Object.keys(seen).length;
        scrollUp();
        sleep(CONFIG.SCROLL_PAUSE_MS);
        const grew = Object.keys(seen).length;
        if (grew === before && s > 1) {
            // 더 이상 새 메시지가 안 보이면 맨 위 도달로 간주
            // (한 번 더 확인 후 종료)
            scrapeVisible(seen, lastSender);
            if (Object.keys(seen).length === grew) break;
        }
    }
    toast("수집 완료: " + posted + "건 전송");
    log("posted=" + posted);
}

// 보정이 필요하면 main() 대신 calibrate()를 실행:
// calibrate();
main();
