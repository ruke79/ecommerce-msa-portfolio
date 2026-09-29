import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import PropTypes from "prop-types";

import { useTranslation } from "i18n/I18nProvider";
import { isNotice } from "hook/useSupportConversation";
import { formatDate, formatTime, parseServerDate } from "util/datetime";
import { localizeChatName, localizeChatNotice } from "util/chatNotice";
import { Avatar } from "components/support/supportUi";

/** 한 번에 보낼 수 있는 글자 수 — 서버 메시지 본문의 상한과 맞춘다. */
export const MAX_MESSAGE_LENGTH = 1000;
/** 같은 사람이 이 시간 안에 이어 보낸 말은 한 묶음으로(이름 · 동그라미는 첫 말에만). */
const GROUP_GAP_MS = 5 * 60 * 1000;
/** 바닥에서 이만큼 안이면 "바닥에 있다" — 새 메시지가 오면 따라 내려간다. */
const STICK_PX = 80;

const dayKey = (value) => {
    const d = parseServerDate(value);
    return d ? `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}` : "";
};
const ms = (value) => parseServerDate(value)?.getTime() ?? 0;

/**
 * 상담 대화창 — 고객 화면과 상담 데스크가 같이 쓴다 (docs/58 §11).
 *
 * <ul>
 *   <li>날짜가 바뀌면 구분선, 같은 사람이 이어 말하면 한 묶음(시각은 묶음의 마지막 말에).</li>
 *   <li>바닥을 보고 있을 때만 새 메시지를 따라 내려간다. 위를 읽는 중이면 "새 메시지 N" 알림만 띄운다 — 읽던 곳이 튀지 않게.</li>
 *   <li>"이전 대화 더 보기" 뒤에도 읽던 자리를 지킨다(위에 붙은 만큼 스크롤을 옮긴다).</li>
 *   <li>상담원에게는 빠른 답변(quickReplies)을 입력칸에 넣는 목록이 있다.</li>
 * </ul>
 *
 * <p>"내 메시지인가" 는 senderId 를 <b>문자열로</b> 비교한다 — 회원 id 는 19자리 TSID 라 숫자로 바꾸면 끝자리가 뭉개진다.
 *
 * <p>⚠️ 떠 있는 요소는 이 카드 안에서만 뜬다({@code isolate} — z-index 가 헤더 메뉴 위로 새지 않는다).
 */
export default function SupportConversation({
    messages, myId, canSend, readOnlyHint, onSend, hasMore, onLoadMore, isConnected, quickReplies, emptyHint,
}) {
    const { t } = useTranslation();
    const [text, setText] = useState("");
    const [unseen, setUnseen] = useState(0);
    const [menuOpen, setMenuOpen] = useState(false);
    const listRef = useRef(null);
    const inputRef = useRef(null);
    const atBottom = useRef(true);
    const prependFrom = useRef(null);
    const lastId = useRef(null);

    const isMine = useCallback((m) => myId != null && String(m.senderId) === String(myId), [myId]);

    const scrollToBottom = useCallback(() => {
        const el = listRef.current;
        if (el) el.scrollTop = el.scrollHeight;
        atBottom.current = true;
        setUnseen(0);
    }, []);

    const onScroll = () => {
        const el = listRef.current;
        if (!el) return;
        atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_PX;
        if (atBottom.current) setUnseen(0);
    };

    // 새 메시지(끝에 붙은 것) — 바닥이면 따라가고, 아니면 알림 수를 센다. 내가 보낸 것은 언제나 따라간다.
    useLayoutEffect(() => {
        const last = messages[messages.length - 1];
        if (!last || last.id === lastId.current) return;
        const first = lastId.current === null;
        lastId.current = last.id;
        if (prependFrom.current !== null) return;   // 위에 붙은 것(더 보기) — 아래 효과가 자리를 맞춘다
        if (first || atBottom.current || isMine(last)) {
            scrollToBottom();
        } else {
            setUnseen((n) => n + 1);
        }
    }, [messages, isMine, scrollToBottom]);

    // "더 보기" — 위에 붙은 높이만큼 내려 읽던 자리를 지킨다.
    useLayoutEffect(() => {
        const el = listRef.current;
        if (el && prependFrom.current !== null) {
            el.scrollTop = el.scrollHeight - prependFrom.current;
            prependFrom.current = null;
        }
    }, [messages]);

    const loadMore = async () => {
        prependFrom.current = listRef.current ? listRef.current.scrollHeight - listRef.current.scrollTop : 0;
        try {
            await onLoadMore?.();
        } finally {
            // 더 온 것이 없으면(오류 · 빈 페이지) 표시만 지운다.
            setTimeout(() => { prependFrom.current = null; }, 0);
        }
    };

    useEffect(() => { if (!canSend) setMenuOpen(false); }, [canSend]);

    const submit = (e) => {
        e?.preventDefault();
        if (onSend(text)) {
            setText("");
            scrollToBottom();
        }
    };

    const onKeyDown = (e) => {
        if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent?.isComposing) submit(e);
        if (e.key === "Escape") setMenuOpen(false);
    };

    const insertReply = (reply) => {
        setText((cur) => (cur.trim() ? `${cur.replace(/\s+$/, "")}\n${reply}` : reply).slice(0, MAX_MESSAGE_LENGTH));
        setMenuOpen(false);
        inputRef.current?.focus();
    };

    const rows = Math.min(5, Math.max(1, text.split("\n").length));
    const nearLimit = text.length > MAX_MESSAGE_LENGTH * 0.8;

    return (
        <div className="relative isolate flex h-full min-h-0 flex-col">
            <ol ref={listRef} onScroll={onScroll} role="log" aria-live="polite"
                className="flex-1 overflow-y-auto px-4 py-4">
                {hasMore && (
                    <li className="mb-3 text-center">
                        <button type="button" onClick={loadMore}
                            className="rounded-full border border-gray-200 bg-white px-3 py-1 text-xs text-gray-600 shadow-sm hover:bg-gray-50">
                            {t("support.load_more")}
                        </button>
                    </li>
                )}
                {messages.length === 0 && (
                    <li className="flex h-full flex-col items-center justify-center gap-2 py-10 text-center text-sm text-gray-400">
                        <svg viewBox="0 0 24 24" className="h-10 w-10 text-gray-300" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M8.25 9.75h7.5m-7.5 3h4.5M21 12c0 4.556-4.03 8.25-9 8.25a9.77 9.77 0 01-4.255-.97L3 20.25l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
                        </svg>
                        {emptyHint || t("support.empty")}
                    </li>
                )}
                {messages.map((m, i) => {
                    const prev = messages[i - 1];
                    const next = messages[i + 1];
                    const newDay = !prev || dayKey(prev.createdAt) !== dayKey(m.createdAt);
                    const divider = newDay && dayKey(m.createdAt) && (
                        <li className="my-4 flex items-center gap-3 text-[11px] text-gray-400" data-kind="day">
                            <span className="h-px flex-1 bg-gray-200" />
                            {dayKey(m.createdAt) === dayKey(Date.now()) ? t("support.today") : formatDate(m.createdAt)}
                            <span className="h-px flex-1 bg-gray-200" />
                        </li>
                    );
                    if (isNotice(m)) {
                        return (
                            <Fragment key={m.id}>
                                {divider}
                                <li className="my-3 text-center text-xs text-gray-500" data-kind="notice">
                                    <span className="inline-block rounded-full bg-gray-100 px-3 py-1">{localizeChatNotice(t, m.content)}</span>
                                </li>
                            </Fragment>
                        );
                    }
                    const mine = isMine(m);
                    const sameAsPrev = prev && !isNotice(prev) && !newDay && String(prev.senderId) === String(m.senderId)
                        && ms(m.createdAt) - ms(prev.createdAt) < GROUP_GAP_MS;
                    const sameAsNext = next && !isNotice(next) && dayKey(next.createdAt) === dayKey(m.createdAt)
                        && String(next.senderId) === String(m.senderId) && ms(next.createdAt) - ms(m.createdAt) < GROUP_GAP_MS;
                    const name = localizeChatName(t, m.nickname);
                    return (
                        <Fragment key={m.id}>
                            {divider}
                            <li className={`flex items-end gap-2 ${mine ? "justify-end" : "justify-start"} ${sameAsPrev ? "mt-0.5" : "mt-3"}`}
                                data-kind={mine ? "mine" : "theirs"}>
                                {!mine && (sameAsNext ? <span className="w-7 shrink-0" /> : <Avatar name={name} tone="green" size="sm" />)}
                                <div className={`flex max-w-[78%] flex-col ${mine ? "items-end" : "items-start"}`}>
                                    {!mine && !sameAsPrev && <span className="mb-0.5 ml-1 text-xs text-gray-500">{name}</span>}
                                    <p className={`whitespace-pre-wrap break-words px-3.5 py-2 text-sm leading-relaxed shadow-sm ${
                                        mine
                                            ? "rounded-2xl rounded-br-md bg-blue-600 text-white"
                                            : "rounded-2xl rounded-bl-md border border-gray-200 bg-white text-gray-900"}`}>
                                        {m.content}
                                    </p>
                                    {!sameAsNext && (
                                        <span className="mx-1 mt-0.5 text-[10px] text-gray-400">{formatTime(m.createdAt)}</span>
                                    )}
                                </div>
                            </li>
                        </Fragment>
                    );
                })}
            </ol>

            {unseen > 0 && (
                <button type="button" onClick={scrollToBottom} data-testid="new-messages"
                    className="absolute bottom-24 left-1/2 z-10 -translate-x-1/2 rounded-full bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white shadow-lg">
                    {t("support.new_messages", { n: unseen })} ↓
                </button>
            )}

            {canSend ? (
                <form onSubmit={submit} className="relative border-t border-gray-200 bg-white p-3">
                    {menuOpen && quickReplies?.length > 0 && (
                        <ul role="menu" aria-label={t("support.quick.title")}
                            className="absolute bottom-full left-3 z-10 mb-2 max-h-64 w-[min(28rem,calc(100%-1.5rem))] overflow-y-auto rounded-lg border border-gray-200 bg-white py-1 shadow-xl">
                            {quickReplies.map((r) => (
                                <li key={r.key} role="none">
                                    <button type="button" role="menuitem" onClick={() => insertReply(r.text)}
                                        className="block w-full px-3 py-2 text-left text-sm hover:bg-blue-50">
                                        <span className="block text-xs font-semibold text-gray-500">{r.label}</span>
                                        <span className="line-clamp-2 text-gray-800">{r.text}</span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                    <div className="flex items-end gap-2 rounded-xl border border-gray-300 bg-white px-2 py-1.5 focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-100">
                        {quickReplies?.length > 0 && (
                            <button type="button" onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen}
                                aria-label={t("support.quick.title")} title={t("support.quick.title")}
                                className="mb-0.5 rounded-md p-1.5 text-gray-500 hover:bg-gray-100 hover:text-blue-600">
                                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z" />
                                </svg>
                            </button>
                        )}
                        <textarea
                            ref={inputRef}
                            className="max-h-32 flex-1 resize-none border-0 bg-transparent px-1 py-1.5 text-sm outline-none focus:ring-0"
                            rows={rows}
                            maxLength={MAX_MESSAGE_LENGTH}
                            value={text}
                            onChange={(e) => setText(e.target.value)}
                            onKeyDown={onKeyDown}
                            placeholder={t("support.placeholder")}
                            aria-label={t("support.placeholder")}
                        />
                        <button type="submit" disabled={!isConnected || !text.trim()} aria-label={t("support.send")}
                            className="mb-0.5 inline-flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-40">
                            {t("support.send")}
                        </button>
                    </div>
                    <div className="mt-1 flex justify-between px-1 text-[10px] text-gray-400">
                        <span>{t("support.enter_hint")}</span>
                        {nearLimit && <span data-testid="char-count">{text.length}/{MAX_MESSAGE_LENGTH}</span>}
                    </div>
                </form>
            ) : (
                readOnlyHint && (
                    <div className="border-t border-gray-200 bg-white p-3 text-center text-sm text-gray-500">{readOnlyHint}</div>
                )
            )}
        </div>
    );
}

SupportConversation.propTypes = {
    messages: PropTypes.arrayOf(PropTypes.object).isRequired,
    myId: PropTypes.string,
    canSend: PropTypes.bool,
    readOnlyHint: PropTypes.node,
    onSend: PropTypes.func.isRequired,
    hasMore: PropTypes.bool,
    onLoadMore: PropTypes.func,
    isConnected: PropTypes.bool,
    /** 상담원 빠른 답변 — [{ key, label, text }] */
    quickReplies: PropTypes.arrayOf(PropTypes.shape({ key: PropTypes.string, label: PropTypes.string, text: PropTypes.string })),
    emptyHint: PropTypes.string,
};
