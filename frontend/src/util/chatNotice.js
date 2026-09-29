/**
 * 채팅의 시스템 문구를 화면 언어로 (docs/60 §8).
 *
 * <p>입장·퇴장 안내, 상담 연결·종료 안내, 대체 닉네임("고객지원팀"·"탈퇴한 사용자"·"회원")은 서버가 <b>한국어(일부 영어)
 * 문구 그대로</b> 만들어 메시지로 저장한다 — Kafka(Avro)로 흘러 Mongo 에 남는다. 코드를 싣도록 바꾸려면 Avro 스키마를 두
 * 릴리스에 걸쳐 바꿔야 하고, 그래도 이미 저장된 메시지는 문구뿐이다. 그래서 화면이 서버의 <b>정해진 모양</b>을 알아보고
 * 사전으로 다시 그린다 — 저장된 옛 메시지도 번역된다.
 *
 * <p>⚠️ 모양의 원본은 서버 {@code chat-service/.../service/ChatNotices.java} 다. 아래 {@link SERVER_FORMATS} 는 그 목록과
 * 같은 글자여야 한다 — 서버의 ChatNoticesContractTest 가 이 파일을 읽어 대조한다. 알아보지 못한 글은 원문 그대로 보여 준다
 * (사람이 쓴 채팅 메시지는 절대 바꾸지 않는다 — 시스템 안내 자리에서만 부른다).
 */

/** 서버가 만드는 모양 — {name}·{id} 가 바뀌는 자리. ChatNotices.FORMATS 와 같아야 한다. */
export const SERVER_FORMATS = [
    "{name} entered.",
    "{name} leaved.",
    "상담원 {name} 님이 연결되었습니다.",
    "고객님이 상담을 종료했습니다.",
    "상담원이 상담을 종료했습니다.",
    "상담원이 바뀝니다. 잠시만 기다려 주세요.",
    "고객지원팀",
    "탈퇴한 사용자",
    "회원",
    "회원 {id}",
];

const NOTICE_RULES = [
    { re: /^(.+) entered\.$/, key: "chat.notice.entered" },
    { re: /^(.+) leaved\.$/, key: "chat.notice.left" },
    { re: /^상담원 (.+) 님이 연결되었습니다\.$/, key: "chat.notice.agent_joined" },
    { re: /^고객님이 상담을 종료했습니다\.$/, key: "chat.notice.closed_by_customer" },
    { re: /^상담원이 상담을 종료했습니다\.$/, key: "chat.notice.closed_by_agent" },
    { re: /^상담원이 바뀝니다\. 잠시만 기다려 주세요\.$/, key: "chat.notice.released" },
];

const NAME_RULES = [
    { re: /^고객지원팀$/, key: "chat.name.support_team" },
    { re: /^탈퇴한 사용자$/, key: "chat.name.withdrawn" },
    { re: /^회원$/, key: "chat.name.member" },
    { re: /^회원 ([0-9a-z]+)$/, key: "chat.name.member_n", arg: "id" },
];

/** 이름 — 서버가 대신 붙인 이름이면 화면 언어로, 사람이 정한 닉네임은 그대로. */
export function localizeChatName(t, name) {
    if (typeof name !== "string") return name;
    for (const rule of NAME_RULES) {
        const m = name.match(rule.re);
        if (m) return t(rule.key, rule.arg ? { [rule.arg]: m[1] } : undefined);
    }
    return name;
}

/** 시스템 안내 한 줄 — 알아보면 화면 언어로, 모르면 그대로. 안내 안의 이름도 바꾼다(탈퇴한 사용자 등). */
export function localizeChatNotice(t, text) {
    if (typeof text !== "string") return text;
    for (const rule of NOTICE_RULES) {
        const m = text.match(rule.re);
        if (m) return t(rule.key, m[1] !== undefined ? { name: localizeChatName(t, m[1]) } : undefined);
    }
    return text;
}

/** 입장·퇴장·상담 안내인가 — 서버가 만든 글이라 번역해도 되는 자리. */
export const isSystemNotice = (m) => m?.type === "TEXT_ENTER" || m?.type === "TEXT_LEAVE";

/** 메시지 본문 — 시스템 안내면 번역, 사람이 쓴 글이면 그대로. */
export function chatMessageText(t, m) {
    return isSystemNotice(m) ? localizeChatNotice(t, m?.content) : m?.content;
}
