import React from 'react';
import styled from 'styled-components';
import { MyChat, OthersChat, OthersChatWithThumbnail } from './ChatMessages';
import { useAuthContext } from 'store/AuthContext';
import { parseServerDate } from "util/datetime";
import { sameUserId } from "util/jwtUser";
import { useTranslation } from "i18n/I18nProvider";
import { chatMessageText } from "util/chatNotice";


const Wrapper = styled.main`
  flex: 1;
  min-height: 0;
  overflow: auto;
`;

/**
 * 화면 언어. I18nProvider 가 `<html lang>` 을 실제로 바꾸므로 그것을 읽는다 —
 * 이 컴포넌트가 Provider 밖에서 그려져도(테스트·독립 마운트) 던지지 않는다.
 * 비어 있으면 브라우저 기본 로케일.
 */
function currentLocale() {
    const lang = typeof document !== "undefined" ? document.documentElement?.lang : "";
    return lang || undefined;
}

/** `createdAt`(epoch millis · ISO 문자열) → Date. 없거나 못 읽으면 null. */
export function toChatDate(createdAt) {
    if (createdAt === undefined || createdAt === null || createdAt === "") return null;
    return parseServerDate(createdAt);
}

/** 같은 날인지 비교하기 위한 키. 로케일 문자열이 아니라 숫자에서 만든다. */
export function dayKey(date) {
    if (!date) return "";
    return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
}

/**
 * 날짜 구분선 문구 — "2026년 9월 18일 금요일".
 *
 * <h3>🐛 회귀 대상: 로케일 문자열을 `.` 로 쪼개던 코드</h3>
 *
 * <p>예전에는 이랬다:
 *
 * <pre>{@code
 * const splitDate = createdAt.toLocaleDateString().split('.');
 * return `${splitDate[0].trim()}년 ${splitDate[1].trim()}월 ${splitDate[2].trim()}일 ${day}`;
 * }</pre>
 *
 * <p>{@code toLocaleDateString()} 은 <b>브라우저 언어</b>대로 찍는다. 점으로 나뉘는 것은
 * {@code ko-KR}("2026. 9. 18.") 뿐이다. {@code en-US} 는 "9/18/2026", {@code ja-JP} 는
 * "2026/9/18" — 점이 없으니 {@code split('.')} 은 원소 하나짜리 배열이고,
 * {@code splitDate[1]} 은 undefined 다. 그 뒤의 {@code .trim()} 이 사용자가 본
 * <b>"Cannot read properties of undefined (reading 'trim')"</b> 이다.
 *
 * <p>이 함수는 <b>첫 메시지</b>에서 반드시 호출된다(이전 메시지가 없으면 "날짜가 바뀌었다"
 * 로 판정한다). 그래서 "채팅방을 만들고 첫 메시지를 보내는 순간" 이 정확히 재현 조건이고,
 * 한국어 브라우저에서만 안 보이던 버그다.
 *
 * <p>문자열을 쪼개지 않는다. {@code Intl} 에 형식을 <b>요청</b>한다 — 한국어면
 * "2026년 9월 18일 금요일", 일본어면 "2026年9月18日金曜日", 영어면
 * "Friday, September 18, 2026" 이 <b>같은 코드에서</b> 나온다.
 */
export function formatChatDate(date, locale = currentLocale()) {
    if (!date) return "";
    return date.toLocaleDateString(locale, {
        year: "numeric",
        month: "long",
        day: "numeric",
        weekday: "long",
    });
}

/**
 * 말풍선 옆 시각 — 초 없이.
 *
 * <p>예전에는 {@code toLocaleTimeString()} 의 <b>끝 세 글자를 잘라</b> 초를 뗐다
 * ("오후 3:24:05" → "오후 3:24"). 영어 로케일("3:24:05 PM")에서는 그 세 글자가
 * " PM" 이라 초는 남고 오전/오후가 사라진다. 형식을 요청하면 잘라낼 것이 없다.
 */
export function formatChatTime(date, locale = currentLocale()) {
    if (!date) return "";
    return date.toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" });
}


const ChatContent = props => {

    const { user } = useAuthContext();
    // 입장·퇴장 안내는 서버가 문구로 저장한다("홍길동 entered.") — 화면 언어로 다시 그린다(util/chatNotice).
    const { t } = useTranslation();
    // 🔒 "내 메시지" 는 uid 로 가린다(docs/48 §6-⑥). 예전에는 이메일로 비교했고, 그래서 메시지마다 발신자 이메일이
    //    방의 모든 참여자에게 전달됐다. 서버는 이제 senderId(문자열)만 보낸다. 비교는 글자로 — 19자리 id 가 Number 를
    //    지나면 뭉개진다(util/jwtUser.uidOf).
    const myId = user?.userId;
    const isMine = (d) => sameUserId(d.senderId, myId);

    const { chattingList, messageRef } = props;

    /**
     * 메시지 한 건을 그리는 데 필요한 값들.
     *
     * <p>{@code createdAt} 이 없을 수 있다 — 발신자의 회원 복제 행이 없으면 서버가 시각을
     * 붙이지 않은 채 보내던 경로가 있었다(docs/48 · ChatService.sendMessage). 그때도
     * 화면이 죽으면 안 된다: 시각·날짜 없이 말풍선만 그린다.
     */
    const describe = (chat) => {
        const date = toChatDate(chat?.createdAt);
        return {
            senderId: chat?.senderId,
            date,
            day: dayKey(date),
            time: formatChatTime(date),
        };
    };

    const renderChatting = chattingList && chattingList.map((chat, idx) => {

        const cur = describe(chat);
        const prev = idx >= 1 ? describe(chattingList[idx - 1]) : null;

        const isPrevSender = prev ? sameUserId(prev.senderId, cur.senderId) : false;
        const isSameDate = prev ? prev.day === cur.day : false;

        // 지금 채팅 날짜가 이전 채팅 날짜와 다르면 날짜 구분선을 넣는다.
        const dateLabel = isSameDate ? "" : formatChatDate(cur.date);
        const key = chat?.id ?? `idx-${idx}`;

        // 마지막 채팅인 경우
        if (idx === chattingList.length - 1) {
            // 내가 보낸 채팅인 경우
            if (isMine(cur)) {
                return (
                    <MyChat
                        msg={chatMessageText(t, chat)}
                        localeTime={cur.time}
                        content={dateLabel}
                        key={key}
                    />
                );
            }

            // 이전에 보낸 채팅과 사람, 날짜가 동일한 경우
            if (isPrevSender && isSameDate) {
                return (
                    <OthersChat
                        msg={chatMessageText(t, chat)}
                        localeTime={cur.time}
                        key={key}
                    />
                );
            }
            return (
                <OthersChatWithThumbnail
                    msg={chatMessageText(t, chat)}
                    localeTime={cur.time}
                    content={dateLabel}
                    key={key}
                />
            );
        }

        /**
         * 채팅 시간 표시 여부:
         *   1. 기준 채팅과 다음 채팅을 보낸 사람이 다르면 시간을 표시한다.
         *   2. 기준 채팅과 다음 채팅의 시각(분 단위)이나 날짜가 다르면 시간을 표시한다.
         */
        const next = describe(chattingList[idx + 1]);
        const time =
            !sameUserId(next.senderId, cur.senderId) || next.time !== cur.time || next.day !== cur.day
                ? cur.time
                : "";

        // 내가 보낸 경우
        if (isMine(cur)) {
            return (
                <MyChat
                    msg={chatMessageText(t, chat)}
                    localeTime={time}
                    content={dateLabel}
                    key={key}
                />
            );
        }
        // 이전 채팅과 지금 채팅이 보낸 사람, 날짜가 같고, 보낸 시각이 같을 경우
        if (isPrevSender && isSameDate && prev.time === cur.time) {
            return (
                <OthersChat
                    msg={chatMessageText(t, chat)}
                    localeTime={time}
                    key={key}
                />
            );
        }
        return (
            <OthersChatWithThumbnail
                msg={chatMessageText(t, chat)}
                localeTime={time}
                content={dateLabel}
                key={key}
            />
        );
    });

    return (
        <Wrapper ref={messageRef}>
            {renderChatting}
        </Wrapper>
    );

}

export default ChatContent;
