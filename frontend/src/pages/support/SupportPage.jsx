import { useCallback, useEffect, useState } from "react";
import PropTypes from "prop-types";

import { useTranslation } from "i18n/I18nProvider";
import { useAuthContext } from "store/AuthContext";
import {
    closeSupportThread, getMySupportHistory, getMySupportThread, getSupportThread, openSupportThread, rateSupportThread,
} from "util/api";
import { isNotice, useSupportConversation } from "hook/useSupportConversation";
import SupportConversation from "components/support/SupportConversation";
import SupportStomp from "components/support/SupportStomp";
import { Avatar, Stars, StatusBadge, SUPPORT_TOPICS, TopicChip } from "components/support/supportUi";
import { formatDate, formatDateTime } from "util/datetime";
import { localizeChatName, localizeChatNotice } from "util/chatNotice";

/**
 * 고객센터 채팅 — 고객 화면 (docs/58 §11).
 *
 * <p>고객과 고객지원팀의 <b>1:1 상담</b>이다(여러 사람이 드나드는 단체 대화방 /chat 과 다르다). 고객은 열린 상담을 하나만
 * 갖는다 — "상담 시작하기" 를 두 번 눌러도, 두 탭에서 눌러도 같은 상담이다(서버의 부분 유니크 인덱스).
 *
 * <ul>
 *   <li>시작할 때 문의 유형을 고른다 — 데스크가 유형별로 나눠 본다.</li>
 *   <li>대기 중에는 순번("앞에 N명")을 보여 준다. 상담원이 맡으면 안내가 오고 상태를 다시 읽는다.</li>
 *   <li>끝나면 별점과 한 줄 의견을 한 번 남길 수 있다.</li>
 *   <li>오른쪽에 지난 상담 — 누르면 그때의 대화를 읽기 전용으로 다시 본다.</li>
 * </ul>
 */
export default function SupportPage() {
    return (
        <SupportStomp>
            <SupportRoom />
        </SupportStomp>
    );
}

export function SupportRoom() {
    const { t } = useTranslation();
    const { user } = useAuthContext();
    // undefined = 읽는 중, null = 열린 상담 없음
    const [thread, setThread] = useState(undefined);
    const [history, setHistory] = useState([]);
    const [viewing, setViewing] = useState(null);
    const [error, setError] = useState(false);
    const [busy, setBusy] = useState(false);

    const loadHistory = useCallback(() => {
        getMySupportHistory().then(setHistory).catch(() => {});
    }, []);

    useEffect(() => {
        getMySupportThread().then(setThread).catch(() => setError(true));
        loadHistory();
    }, [loadHistory]);

    const threadId = thread?.threadId;
    const refreshThread = useCallback(() => {
        if (threadId) getSupportThread(threadId).then(setThread).catch(() => {});
    }, [threadId]);

    // 탭이 뒤에 있을 때 상담원이 답하면 창 제목에 표시한다(docs/58 §10). 보고 있는 동안은 벨 알림이 오지 않으므로
    // (서버가 구독 중인 고객에게는 보내지 않는다) 뒤에 둔 탭에서 답을 놓치지 않게 하는 몫이 여기다.
    const [unseen, setUnseen] = useState(0);
    const myId = user?.userId;
    const onMessage = useCallback((m) => {
        if (document.hidden && !isNotice(m) && String(m.senderId) !== String(myId)) setUnseen((n) => n + 1);
    }, [myId]);
    useEffect(() => {
        const reset = () => { if (!document.hidden) setUnseen(0); };
        document.addEventListener("visibilitychange", reset);
        return () => document.removeEventListener("visibilitychange", reset);
    }, []);
    useEffect(() => {
        const original = document.title.replace(/^\(\d+\) /, "");
        document.title = unseen > 0 ? `(${unseen}) ${original}` : original;
        return () => { document.title = original; };
    }, [unseen]);

    const conversation = useSupportConversation(thread, { onNotice: refreshThread, onMessage });

    const run = async (action) => {
        setBusy(true);
        setError(false);
        try {
            const next = await action();
            setThread(next);
            return next;
        } catch {
            setError(true);
            return null;
        } finally {
            setBusy(false);
        }
    };

    const start = (topic) => { setViewing(null); return run(() => openSupportThread(topic)); };
    const close = async () => {
        if (!window.confirm(t("support.close_confirm"))) return;
        if (await run(() => closeSupportThread(threadId))) loadHistory();
    };
    const onRated = (rated) => {
        setThread(rated);
        loadHistory();
    };

    const status = thread?.status;
    const open = status === "WAITING" || status === "ACTIVE";

    return (
        <section className="mx-auto w-full max-w-6xl px-4 py-6 sm:py-8">
            <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
                <div>
                    <h1 className="text-2xl font-bold text-gray-900">{t("support.title")}</h1>
                    <p className="mt-1 text-sm text-gray-500">{t("support.intro")}</p>
                </div>
                {thread && open && <ConnectionDot connected={conversation.isConnected} />}
            </div>

            {error && <p role="alert" className="mb-4 rounded-lg bg-red-50 p-3 text-sm text-red-700">{t("support.error")}</p>}

            <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
                <div className="isolate flex h-[min(46rem,calc(100vh-16.5rem))] min-h-[28rem] flex-col overflow-hidden rounded-2xl border border-gray-200 bg-gray-50 shadow-sm">
                    {viewing ? (
                        <PastThread thread={viewing} myId={myId} onBack={() => setViewing(null)} />
                    ) : thread === undefined && !error ? (
                        <div className="m-auto h-6 w-6 animate-spin rounded-full border-2 border-gray-300 border-t-blue-600" aria-label={t("support.loading")} />
                    ) : !thread ? (
                        <StartPanel onStart={start} busy={busy} />
                    ) : (
                        <>
                            <ThreadHeader thread={thread} onClose={close} onNew={() => setThread(null)} busy={busy} />
                            {status === "WAITING" && (
                                <div className="flex items-start gap-2 border-b border-amber-100 bg-amber-50 px-4 py-2.5 text-xs text-amber-900">
                                    <span className="mt-0.5 inline-block h-2 w-2 shrink-0 animate-pulse rounded-full bg-amber-500" aria-hidden="true" />
                                    <p data-testid="queue">
                                        {thread.queuePosition
                                            ? t("support.queue", { n: thread.queuePosition })
                                            : t("support.waiting_hint")}
                                        {thread.queuePosition ? ` · ${t("support.waiting_hint")}` : ""}
                                    </p>
                                </div>
                            )}
                            <div className="min-h-0 flex-1">
                                <SupportConversation
                                    messages={conversation.messages}
                                    myId={myId}
                                    canSend={open}
                                    readOnlyHint={status === "CLOSED"
                                        ? <RatingPanel thread={thread} onRated={onRated} onNew={() => setThread(null)} />
                                        : null}
                                    onSend={conversation.send}
                                    hasMore={conversation.hasMore}
                                    onLoadMore={conversation.loadMore}
                                    isConnected={conversation.isConnected}
                                    emptyHint={t("support.empty_customer")}
                                />
                            </div>
                        </>
                    )}
                </div>

                <aside className="space-y-4">
                    <GuideCard />
                    <HistoryCard history={history} activeId={viewing?.threadId} onPick={setViewing} />
                </aside>
            </div>
        </section>
    );
}

function ConnectionDot({ connected }) {
    const { t } = useTranslation();
    return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-white px-2.5 py-1 text-xs text-gray-600 ring-1 ring-gray-200"
            data-testid="connection">
            <span aria-hidden="true" className={`h-2 w-2 rounded-full ${connected ? "bg-emerald-500" : "animate-pulse bg-gray-400"}`} />
            {connected ? t("support.connected") : t("support.connecting")}
        </span>
    );
}
ConnectionDot.propTypes = { connected: PropTypes.bool };

/** 문의 시작 — 유형을 고르고 연다. 고르지 않으면 "기타". */
function StartPanel({ onStart, busy }) {
    const { t } = useTranslation();
    const [topic, setTopic] = useState(null);
    return (
        <div className="m-auto w-full max-w-lg px-6 py-10 text-center">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-600 text-white shadow-lg">
                <svg viewBox="0 0 24 24" className="h-7 w-7" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M20.25 8.511c.884.284 1.5 1.128 1.5 2.097v4.286c0 1.136-.847 2.1-1.98 2.193-.34.027-.68.052-1.02.072v3.091l-3-3c-1.354 0-2.694-.055-4.02-.163a2.115 2.115 0 01-.825-.242m9.345-8.334a2.126 2.126 0 00-.476-.095 48.64 48.64 0 00-8.048 0c-1.131.094-1.976 1.057-1.976 2.192v4.286c0 .837.46 1.58 1.155 1.951m9.345-8.334V6.637c0-1.621-1.152-3.026-2.76-3.235A48.455 48.455 0 0011.25 3c-2.115 0-4.198.137-6.24.402-1.608.209-2.76 1.614-2.76 3.235v6.226c0 1.621 1.152 3.026 2.76 3.235.577.075 1.157.14 1.74.194V21l4.155-4.155" />
                </svg>
            </div>
            <h2 className="text-lg font-bold text-gray-900">{t("support.start_title")}</h2>
            <p className="mt-1 text-sm text-gray-500">{t("support.start_desc")}</p>

            <fieldset className="mt-6">
                <legend className="mb-2 text-xs font-semibold text-gray-500">{t("support.topic.pick")}</legend>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {SUPPORT_TOPICS.map((key) => (
                        <button key={key} type="button" aria-pressed={topic === key} onClick={() => setTopic(key)}
                            className={`rounded-xl border px-3 py-2.5 text-sm transition ${topic === key
                                ? "border-blue-600 bg-blue-50 font-semibold text-blue-700 ring-2 ring-blue-100"
                                : "border-gray-200 bg-white text-gray-700 hover:border-gray-300"}`}>
                            {t(`support.topic.${key}`)}
                        </button>
                    ))}
                </div>
            </fieldset>

            <button type="button" onClick={() => onStart(topic || "OTHER")} disabled={busy}
                className="mt-6 w-full rounded-xl bg-blue-600 px-5 py-3 font-semibold text-white shadow-sm hover:bg-blue-700 disabled:opacity-40">
                {t("support.start")}
            </button>
        </div>
    );
}
StartPanel.propTypes = { onStart: PropTypes.func.isRequired, busy: PropTypes.bool };

function ThreadHeader({ thread, onClose, onNew, busy }) {
    const { t } = useTranslation();
    const active = thread.status === "ACTIVE" && thread.agentName;
    const open = thread.status !== "CLOSED";
    const title = active ? t("support.agent", { name: localizeChatName(t, thread.agentName) }) : t("support.team");
    return (
        <header className="flex items-center justify-between gap-3 border-b border-gray-200 bg-white px-4 py-3">
            <div className="flex min-w-0 items-center gap-3">
                <Avatar name={active ? localizeChatName(t, thread.agentName) : t("support.team")} tone={active ? "green" : "blue"} />
                <div className="min-w-0">
                    <p className="truncate font-semibold text-gray-900">{title}</p>
                    <div className="mt-0.5 flex flex-wrap items-center gap-1.5" data-testid="support-status">
                        <StatusBadge status={thread.status} />
                        <TopicChip topic={thread.topic} />
                    </div>
                </div>
            </div>
            {open ? (
                <button type="button" onClick={onClose} disabled={busy}
                    className="shrink-0 rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50">
                    {t("support.close")}
                </button>
            ) : (
                <button type="button" onClick={onNew} disabled={busy}
                    className="shrink-0 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-blue-700">
                    {t("support.new")}
                </button>
            )}
        </header>
    );
}
ThreadHeader.propTypes = { thread: PropTypes.object.isRequired, onClose: PropTypes.func, onNew: PropTypes.func, busy: PropTypes.bool };

/** 끝난 상담 — 평가(한 번)와 새 문의. */
export function RatingPanel({ thread, onRated, onNew }) {
    const { t } = useTranslation();
    const [score, setScore] = useState(0);
    const [hover, setHover] = useState(0);
    const [comment, setComment] = useState("");
    const [sending, setSending] = useState(false);
    const [failed, setFailed] = useState(false);

    if (thread.rating) {
        return (
            <span className="flex flex-col items-center gap-1" data-testid="rated">
                <Stars value={thread.rating} className="scale-125" />
                <span>{t("support.rating.thanks")}</span>
            </span>
        );
    }

    const submit = async () => {
        if (!score) return;
        setSending(true);
        setFailed(false);
        try {
            onRated(await rateSupportThread(thread.threadId, score, comment.trim()));
        } catch {
            setFailed(true);
        } finally {
            setSending(false);
        }
    };

    return (
        <span className="mx-auto flex max-w-md flex-col items-center gap-2 text-gray-700" data-testid="rating">
            <span className="text-sm">{t("support.closed_hint")}</span>
            <span className="text-sm font-semibold text-gray-900">{t("support.rating.question")}</span>
            <span className="flex gap-1" role="radiogroup" aria-label={t("support.rating.question")}>
                {[1, 2, 3, 4, 5].map((n) => (
                    <button key={n} type="button" role="radio" aria-checked={score === n}
                        aria-label={t("support.rating.stars", { n })}
                        onMouseEnter={() => setHover(n)} onMouseLeave={() => setHover(0)} onClick={() => setScore(n)}
                        className="p-0.5">
                        <svg viewBox="0 0 20 20" aria-hidden="true"
                            className={`h-7 w-7 transition ${n <= (hover || score) ? "fill-amber-400" : "fill-gray-200"}`}>
                            <path d="M10 1.5l2.6 5.3 5.9.9-4.25 4.1 1 5.8L10 14.9l-5.25 2.7 1-5.8L1.5 7.7l5.9-.9z" />
                        </svg>
                    </button>
                ))}
            </span>
            {score > 0 && (
                <>
                    <textarea value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} rows={2}
                        placeholder={t("support.rating.comment")} aria-label={t("support.rating.comment")}
                        className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                    <button type="button" onClick={submit} disabled={sending}
                        className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-semibold text-white disabled:opacity-40">
                        {t("support.rating.submit")}
                    </button>
                </>
            )}
            {failed && <span role="alert" className="text-xs text-red-600">{t("support.error")}</span>}
            <button type="button" onClick={onNew} className="text-xs text-blue-600 underline">{t("support.new")}</button>
        </span>
    );
}
RatingPanel.propTypes = { thread: PropTypes.object.isRequired, onRated: PropTypes.func.isRequired, onNew: PropTypes.func };

/** 지난 상담 하나 — 읽기 전용. */
function PastThread({ thread, myId, onBack }) {
    const { t } = useTranslation();
    const conversation = useSupportConversation(thread);
    return (
        <>
            <header className="flex items-center gap-3 border-b border-gray-200 bg-white px-4 py-3">
                <button type="button" onClick={onBack}
                    className="rounded-lg px-2 py-1 text-sm text-blue-600 hover:bg-blue-50">
                    ← {t("support.history.back")}
                </button>
                <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-gray-900">
                        {t("support.history.title_of", { date: formatDate(thread.createdAt) })}
                    </p>
                    <div className="mt-0.5 flex items-center gap-1.5"><TopicChip topic={thread.topic} /><Stars value={thread.rating} /></div>
                </div>
            </header>
            <div className="min-h-0 flex-1">
                <SupportConversation
                    messages={conversation.messages}
                    myId={myId}
                    canSend={false}
                    readOnlyHint={t("support.history.readonly")}
                    onSend={() => false}
                    hasMore={conversation.hasMore}
                    onLoadMore={conversation.loadMore}
                    isConnected={conversation.isConnected}
                />
            </div>
        </>
    );
}
PastThread.propTypes = { thread: PropTypes.object.isRequired, myId: PropTypes.string, onBack: PropTypes.func.isRequired };

function GuideCard() {
    const { t } = useTranslation();
    return (
        <div className="rounded-2xl border border-gray-200 bg-white p-4 text-sm shadow-sm">
            <h2 className="font-semibold text-gray-900">{t("support.guide.title")}</h2>
            <ul className="mt-2 space-y-1.5 text-gray-600">
                <li>{t("support.guide.hours")}</li>
                <li>{t("support.guide.reply")}</li>
                <li>{t("support.guide.privacy")}</li>
            </ul>
        </div>
    );
}

function HistoryCard({ history, activeId, onPick }) {
    const { t } = useTranslation();
    return (
        <div className="rounded-2xl border border-gray-200 bg-white shadow-sm">
            <h2 className="border-b border-gray-100 px-4 py-3 text-sm font-semibold text-gray-900">{t("support.history.title")}</h2>
            {history.length === 0 ? (
                <p className="px-4 py-4 text-xs text-gray-400">{t("support.history.empty")}</p>
            ) : (
                <ul className="max-h-80 divide-y divide-gray-100 overflow-y-auto">
                    {history.map((h) => (
                        <li key={h.threadId}>
                            <button type="button" onClick={() => onPick(h)}
                                className={`w-full px-4 py-3 text-left hover:bg-gray-50 ${activeId === h.threadId ? "bg-blue-50" : ""}`}>
                                <div className="flex items-center justify-between gap-2">
                                    <TopicChip topic={h.topic} />
                                    <span className="text-[11px] text-gray-400">{formatDateTime(h.closedAt || h.createdAt)}</span>
                                </div>
                                <p className="mt-1 truncate text-xs text-gray-600">{localizeChatNotice(t, h.lastMessagePreview) || "—"}</p>
                                {h.rating ? <Stars value={h.rating} className="mt-1" /> : null}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
HistoryCard.propTypes = { history: PropTypes.array.isRequired, activeId: PropTypes.string, onPick: PropTypes.func.isRequired };
