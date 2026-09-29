import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import PropTypes from "prop-types";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";

import { useTranslation } from "i18n/I18nProvider";
import { useAuthContext } from "store/AuthContext";
import {
    claimSupportThread, closeSupportThread, getSupportDesk, getSupportThread, releaseSupportThread, updateSupportNote,
} from "util/api";
import { useSupportConversation } from "hook/useSupportConversation";
import SupportConversation from "components/support/SupportConversation";
import SupportStomp from "components/support/SupportStomp";
import { Avatar, Stars, StatusBadge, SUPPORT_TOPICS, TopicChip } from "components/support/supportUi";
import { hasAdminRole } from "components/ProtectedRoute";
import { formatDateTime, formatTime } from "util/datetime";
import { useStomp } from "util/stompProvider";
import { localizeChatName, localizeChatNotice } from "util/chatNotice";

export const DESK_VIEWS = ["WAITING", "MINE", "ACTIVE", "CLOSED"];

/**
 * 목록을 다시 읽는 간격 — <b>안전망</b>이다. 새 문의·새 메시지는 {@link DESK_TOPIC} 알림으로 곧바로 온다(docs/58 §9).
 * 소켓이 끊겨 알림을 놓쳐도 이 주기 안에는 목록이 맞춰진다.
 */
export const DESK_REFRESH_MS = 30000;

/** 상담원 전용 알림 주소. 서버는 번호와 종류만 보낸다 — 본문은 목록을 다시 읽어 얻는다. */
export const DESK_TOPIC = "/sub/support/desk";

/** 빠른 답변 — 사전의 support.quick.<key>.{label,text}. 상담원이 입력칸에 넣고 고쳐서 보낸다. */
export const QUICK_REPLY_KEYS = ["greet", "check", "order_no", "wait", "resolved", "closing"];

const canNotify = () => typeof window !== "undefined" && "Notification" in window;

/** 탭이 뒤에 있을 때만 브라우저 알림 — 앞에 있으면 토스트로 충분하다. 권한은 상담원이 버튼으로 켠다. */
function browserNotify(title, body) {
    if (!canNotify() || window.Notification.permission !== "granted" || !document.hidden) return;
    try {
        // eslint-disable-next-line no-new
        new window.Notification(title, { body, tag: "support-desk" });
    } catch {
        // 일부 브라우저(모바일)는 생성자를 막는다 — 토스트가 있다.
    }
}

/**
 * 상담 데스크 — 고객지원팀 상담원·관리자 (docs/58 §11).
 *
 * <p>세 칸이다: 왼쪽 목록(대기·내 담당·전체·종료 + 유형 · 검색), 가운데 대화, 오른쪽 상담 정보(고객 · 지난 상담 수 ·
 * 시각 · 평가 · 🔒 내부 메모). 좁은 화면에서는 목록 ↔ 대화를 오가고, 정보는 대화 위에 서랍으로 연다.
 *
 * <p>대기 중인 상담은 "상담 맡기" 를 눌러야 답할 수 있다 — 두 상담원이 같은 문의에 동시에 답하지 않게(서버가 조건부
 * 갱신으로 한 명만 통과시킨다). 맡은 상담은 "반납" 으로 다시 대기열에 돌릴 수 있다. 이 화면의 가드(ProtectedRoute
 * supportOnly)는 UI 가드일 뿐이고, 서버가 같은 규칙을 강제한다.
 */
export default function SupportDesk() {
    return (
        <SupportStomp>
            <Desk />
        </SupportStomp>
    );
}

export function Desk() {
    const { t } = useTranslation();
    const { user } = useAuthContext();
    const queryClient = useQueryClient();
    const [view, setView] = useState("WAITING");
    const [topic, setTopic] = useState("");
    const [search, setSearch] = useState("");
    const [selected, setSelected] = useState(null);
    const [showInfo, setShowInfo] = useState(false);
    const [error, setError] = useState(false);

    const { data: list = [], isLoading } = useQuery({
        queryKey: ["support-desk", view, topic],
        queryFn: () => getSupportDesk(view, topic || undefined),
        refetchInterval: DESK_REFRESH_MS,
    });
    // 탭 배지용 — 지금 보고 있는 탭 · 유형과 무관하게 대기 · 내 담당은 늘 읽어 둔다.
    const { data: waiting = [] } = useQuery({
        queryKey: ["support-desk", "WAITING", ""],
        queryFn: () => getSupportDesk("WAITING"),
        refetchInterval: DESK_REFRESH_MS,
    });
    const { data: mineList = [] } = useQuery({
        queryKey: ["support-desk", "MINE", ""],
        queryFn: () => getSupportDesk("MINE"),
        refetchInterval: DESK_REFRESH_MS,
    });
    const badge = {
        WAITING: waiting.length,
        MINE: mineList.filter((th) => th.awaitingReply).length,
    };

    const shown = useMemo(() => {
        const q = search.trim().toLowerCase();
        if (!q) return list;
        return list.filter((th) => [localizeChatName(t, th.customerName), th.lastMessagePreview, th.agentName]
            .some((v) => String(v ?? "").toLowerCase().includes(q)));
    }, [list, search, t]);

    const selectedId = selected?.threadId;
    const refreshSelected = useCallback(() => {
        if (selectedId) getSupportThread(selectedId).then(setSelected).catch(() => {});
        queryClient.invalidateQueries({ queryKey: ["support-desk"] });
    }, [selectedId, queryClient]);

    const pick = (th) => {
        setSelected(th);
        setShowInfo(false);
        // 목록에는 없는 것(지난 상담 수)을 채운다.
        Promise.resolve().then(() => getSupportThread(th.threadId))
            .then((detail) => detail && setSelected((cur) => (cur?.threadId === detail.threadId ? detail : cur)))
            .catch(() => {});
    };

    const conversation = useSupportConversation(selected, { onNotice: refreshSelected });

    useDeskAlerts({ myId: user?.userId, selectedId, mineList, queryClient, t });

    // 탭 제목에 기다리는 수 — 다른 탭에서 일하다가도 보인다.
    const pending = badge.WAITING + badge.MINE;
    useEffect(() => {
        const original = document.title.replace(/^\(\d+\) /, "");
        document.title = pending > 0 ? `(${pending}) ${original}` : original;
        return () => { document.title = original; };
    }, [pending]);

    const [permission, setPermission] = useState(() => (canNotify() ? window.Notification.permission : "unsupported"));
    const askPermission = async () => {
        try {
            setPermission(await window.Notification.requestPermission());
        } catch {
            setPermission("denied");
        }
    };

    useEffect(() => { setError(false); }, [selectedId]);

    const act = async (action) => {
        setError(false);
        try {
            setSelected(await action());
            queryClient.invalidateQueries({ queryKey: ["support-desk"] });
            return true;
        } catch (e) {
            setError(e?.response?.data?.message || t("support.error"));
            return false;
        }
    };

    const myId = user?.userId;
    const mine = selected && String(selected.agentId) === String(myId);
    const canSend = selected?.status === "ACTIVE" && mine;
    const canRelease = selected?.status === "ACTIVE" && (mine || hasAdminRole(user));

    const quickReplies = useMemo(() => QUICK_REPLY_KEYS.map((key) => ({
        key,
        label: t(`support.quick.${key}.label`),
        text: t(`support.quick.${key}.text`, { name: localizeChatName(t, selected?.customerName) }),
    })), [t, selected?.customerName]);

    return (
        <section className="mx-auto w-full max-w-[96rem] px-4 py-5">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                    <h1 className="text-xl font-bold text-gray-900">{t("support.desk.title")}</h1>
                    <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-800">
                        {t("support.desk.summary_waiting", { n: badge.WAITING })}
                    </span>
                    <span className="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-700">
                        {t("support.desk.summary_reply", { n: badge.MINE })}
                    </span>
                </div>
                {permission === "default" && (
                    <button type="button" onClick={askPermission}
                        className="rounded-lg border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-50">
                        {t("support.desk.enable_notifications")}
                    </button>
                )}
            </div>

            <div className="isolate grid h-[calc(100vh-14.5rem)] min-h-[32rem] gap-4 md:grid-cols-[20rem_minmax(0,1fr)] xl:grid-cols-[20rem_minmax(0,1fr)_18rem]">
                {/* ── 목록 ── */}
                <aside className={`${selected ? "hidden md:flex" : "flex"} min-h-0 flex-col overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm`}>
                    <div role="tablist" className="grid grid-cols-4 border-b border-gray-200 text-xs">
                        {DESK_VIEWS.map((v) => (
                            <button key={v} type="button" role="tab" aria-selected={view === v}
                                onClick={() => setView(v)}
                                className={`relative px-1 py-2.5 ${view === v ? "font-semibold text-blue-700" : "text-gray-500 hover:text-gray-800"}`}>
                                {t(`support.desk.view.${v}`)}
                                {badge[v] > 0 && (
                                    <span data-testid={`badge-${v}`}
                                        className="ml-1 rounded-full bg-red-600 px-1.5 text-[10px] font-semibold text-white">
                                        {badge[v]}
                                    </span>
                                )}
                                {view === v && <span className="absolute inset-x-2 bottom-0 h-0.5 rounded bg-blue-600" aria-hidden="true" />}
                            </button>
                        ))}
                    </div>
                    <div className="flex gap-2 border-b border-gray-100 p-2">
                        <select value={topic} onChange={(e) => setTopic(e.target.value)} aria-label={t("support.topic.filter")}
                            className="w-28 shrink-0 rounded-lg border border-gray-200 bg-white px-2 py-1.5 text-xs">
                            <option value="">{t("support.topic.all")}</option>
                            {SUPPORT_TOPICS.map((k) => <option key={k} value={k}>{t(`support.topic.${k}`)}</option>)}
                        </select>
                        <input type="search" value={search} onChange={(e) => setSearch(e.target.value)}
                            placeholder={t("support.desk.search")} aria-label={t("support.desk.search")}
                            className="min-w-0 flex-1 rounded-lg border border-gray-200 px-2 py-1.5 text-xs" />
                    </div>
                    <ul className="min-h-0 flex-1 divide-y divide-gray-100 overflow-y-auto">
                        {!isLoading && shown.length === 0 && (
                            <li className="p-6 text-center text-sm text-gray-400">{t("support.desk.empty")}</li>
                        )}
                        {shown.map((th) => (
                            <li key={th.threadId}>
                                <button type="button" onClick={() => pick(th)}
                                    className={`flex w-full gap-3 px-3 py-3 text-left ${
                                        selectedId === th.threadId ? "bg-blue-50" : "hover:bg-gray-50"}`}>
                                    <Avatar name={localizeChatName(t, th.customerName)} tone="gray" size="sm" />
                                    <span className="min-w-0 flex-1">
                                        <span className="flex items-center justify-between gap-2">
                                            <span className="truncate text-sm font-medium text-gray-900">{localizeChatName(t, th.customerName)}</span>
                                            <span className="shrink-0 text-[10px] text-gray-400">{formatTime(th.lastMessageAt || th.createdAt)}</span>
                                        </span>
                                        <span className="mt-0.5 block truncate text-xs text-gray-500">
                                            {localizeChatNotice(t, th.lastMessagePreview) || "—"}
                                        </span>
                                        <span className="mt-1 flex flex-wrap items-center gap-1">
                                            <TopicChip topic={th.topic} />
                                            {th.awaitingReply && th.status !== "CLOSED" && (
                                                <span className="rounded-full bg-red-100 px-1.5 py-0.5 text-[10px] font-semibold text-red-700">
                                                    {t("support.desk.awaiting_reply")}
                                                </span>
                                            )}
                                            {th.agentName && view !== "MINE" && (
                                                <span className="text-[10px] text-gray-400">{localizeChatName(t, th.agentName)}</span>
                                            )}
                                            <Stars value={th.rating} />
                                        </span>
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </aside>

                {/* ── 대화 ── */}
                <div className={`${selected ? "flex" : "hidden md:flex"} relative min-h-0 min-w-0 flex-col overflow-hidden rounded-2xl border border-gray-200 bg-gray-50 shadow-sm`}>
                    {!selected ? (
                        <p className="m-auto text-sm text-gray-400">{t("support.desk.select")}</p>
                    ) : (
                        <>
                            <header className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-200 bg-white px-4 py-3">
                                <div className="flex min-w-0 items-center gap-3">
                                    <button type="button" onClick={() => setSelected(null)} aria-label={t("support.desk.back")}
                                        className="rounded-md px-1.5 py-1 text-gray-500 hover:bg-gray-100 md:hidden">←</button>
                                    <Avatar name={localizeChatName(t, selected.customerName)} tone="gray" />
                                    <div className="min-w-0">
                                        <p className="truncate font-semibold text-gray-900">
                                            {t("support.desk.customer", { name: localizeChatName(t, selected.customerName) })}
                                        </p>
                                        <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                                            <StatusBadge status={selected.status} />
                                            <TopicChip topic={selected.topic} />
                                            {selected.status === "ACTIVE" && selected.agentName && (
                                                <span className="text-[11px] text-gray-500">
                                                    {t("support.agent", { name: localizeChatName(t, selected.agentName) })}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                </div>
                                <div className="flex flex-wrap gap-2">
                                    {selected.status === "WAITING" && (
                                        <button type="button" onClick={() => act(() => claimSupportThread(selectedId))}
                                            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-blue-700">
                                            {t("support.desk.claim")}
                                        </button>
                                    )}
                                    {canRelease && (
                                        <button type="button"
                                            onClick={() => window.confirm(t("support.desk.release_confirm")) && act(() => releaseSupportThread(selectedId))}
                                            className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50">
                                            {t("support.desk.release")}
                                        </button>
                                    )}
                                    {selected.status !== "CLOSED" && (
                                        <button type="button"
                                            onClick={() => window.confirm(t("support.close_confirm")) && act(() => closeSupportThread(selectedId))}
                                            className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50">
                                            {t("support.close")}
                                        </button>
                                    )}
                                    <button type="button" onClick={() => setShowInfo((s) => !s)} aria-expanded={showInfo}
                                        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 xl:hidden">
                                        {t("support.desk.info")}
                                    </button>
                                </div>
                            </header>
                            {error && <p role="alert" className="bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}
                            <div className="min-h-0 flex-1">
                                <SupportConversation
                                    messages={conversation.messages}
                                    myId={myId}
                                    canSend={canSend}
                                    readOnlyHint={selected.status === "CLOSED" ? t("support.status.CLOSED") : t("support.desk.readonly")}
                                    onSend={conversation.send}
                                    hasMore={conversation.hasMore}
                                    onLoadMore={conversation.loadMore}
                                    isConnected={conversation.isConnected}
                                    quickReplies={quickReplies}
                                />
                            </div>
                            {showInfo && (
                                <div className="absolute inset-y-0 right-0 z-10 w-80 max-w-full overflow-y-auto border-l border-gray-200 bg-white shadow-2xl xl:hidden">
                                    <InfoPanel thread={selected} onSaved={setSelected} onClose={() => setShowInfo(false)} />
                                </div>
                            )}
                        </>
                    )}
                </div>

                {/* ── 상담 정보 ── */}
                <aside className="hidden min-h-0 overflow-y-auto rounded-2xl border border-gray-200 bg-white shadow-sm xl:block">
                    {selected ? <InfoPanel thread={selected} onSaved={setSelected} />
                        : <p className="p-6 text-center text-sm text-gray-400">{t("support.desk.info_empty")}</p>}
                </aside>
            </div>
        </section>
    );
}

/** 상담 정보 — 고객 · 시각 · 평가 · 🔒 내부 메모. */
export function InfoPanel({ thread, onSaved, onClose }) {
    const { t } = useTranslation();
    const [note, setNote] = useState(thread.internalNote ?? "");
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [failed, setFailed] = useState(false);

    // 다른 상담을 고르면 칸 · 표시를 새로.
    useEffect(() => {
        setSaved(false);
        setFailed(false);
    }, [thread.threadId]);
    // 저장했거나 다른 상담원이 고쳐 다시 읽으면 칸을 서버 값에 맞춘다("저장했습니다" 는 지우지 않는다).
    useEffect(() => {
        setNote(thread.internalNote ?? "");
    }, [thread.threadId, thread.internalNote]);

    const dirty = note !== (thread.internalNote ?? "");
    const save = async () => {
        setSaving(true);
        setFailed(false);
        try {
            onSaved(await updateSupportNote(thread.threadId, note));
            setSaved(true);
        } catch {
            setFailed(true);
        } finally {
            setSaving(false);
        }
    };

    const row = (label, value) => (
        <div className="flex justify-between gap-3 py-1 text-xs">
            <dt className="text-gray-500">{label}</dt>
            <dd className="text-right text-gray-800">{value}</dd>
        </div>
    );

    return (
        <div className="space-y-5 p-4" data-testid="info-panel">
            <div className="flex items-center justify-between">
                <h2 className="text-sm font-semibold text-gray-900">{t("support.desk.info")}</h2>
                {onClose && (
                    <button type="button" onClick={onClose} aria-label={t("support.desk.info_close")}
                        className="rounded-md px-2 py-0.5 text-gray-500 hover:bg-gray-100">×</button>
                )}
            </div>

            <section>
                <h3 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{t("support.desk.section_customer")}</h3>
                <dl>
                    {row(t("support.desk.field_name"), localizeChatName(t, thread.customerName))}
                    {thread.customerPastThreads != null
                        && row(t("support.desk.field_past"), t("support.desk.past_n", { n: thread.customerPastThreads }))}
                </dl>
            </section>

            <section>
                <h3 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{t("support.desk.section_thread")}</h3>
                <dl>
                    {row(t("support.desk.field_topic"), <TopicChip topic={thread.topic} />)}
                    {row(t("support.desk.field_opened"), formatDateTime(thread.createdAt))}
                    {thread.assignedAt && row(t("support.desk.field_assigned"), formatDateTime(thread.assignedAt))}
                    {thread.closedAt && row(t("support.desk.field_closed"), formatDateTime(thread.closedAt))}
                </dl>
            </section>

            {thread.rating ? (
                <section data-testid="info-rating">
                    <h3 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{t("support.desk.section_rating")}</h3>
                    <Stars value={thread.rating} />
                    {thread.ratingComment && <p className="mt-1 whitespace-pre-wrap text-xs text-gray-700">{thread.ratingComment}</p>}
                </section>
            ) : null}

            <section>
                <h3 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-gray-400">{t("support.desk.section_note")}</h3>
                <p className="mb-2 text-[11px] text-amber-700">{t("support.desk.note_private")}</p>
                <textarea value={note} onChange={(e) => { setNote(e.target.value); setSaved(false); }}
                    maxLength={2000} rows={6} placeholder={t("support.desk.note_placeholder")}
                    aria-label={t("support.desk.section_note")}
                    className="w-full resize-y rounded-lg border border-gray-300 px-3 py-2 text-xs" />
                <div className="mt-1.5 flex items-center justify-between gap-2">
                    <span className="text-[10px] text-gray-400">
                        {thread.noteUpdatedAt ? t("support.desk.note_updated", { at: formatDateTime(thread.noteUpdatedAt) }) : ""}
                    </span>
                    <button type="button" onClick={save} disabled={!dirty || saving}
                        className="rounded-lg bg-gray-900 px-3 py-1 text-xs font-semibold text-white disabled:opacity-30">
                        {t("support.desk.note_save")}
                    </button>
                </div>
                {saved && !dirty && <p className="mt-1 text-[11px] text-emerald-600">{t("support.desk.note_saved")}</p>}
                {failed && <p role="alert" className="mt-1 text-[11px] text-red-600">{t("support.error")}</p>}
            </section>
        </div>
    );
}
InfoPanel.propTypes = { thread: PropTypes.object.isRequired, onSaved: PropTypes.func.isRequired, onClose: PropTypes.func };

/**
 * 데스크 실시간 알림 (docs/58 §9) — {@link DESK_TOPIC} 을 구독한다.
 *
 * <p>무엇이 오든 목록을 다시 읽는다. 그리고 사람이 알아야 할 것만 소리 내어 알린다:
 * <ul>
 *   <li>새 문의(NEW_THREAD) · 반납되어 대기열로 돌아온 상담(RELEASED) — 누구든 맡을 수 있으니 모든 상담원에게</li>
 *   <li>내가 맡은 상담에 고객이 말했다(MESSAGE) — 지금 그 상담을 보고 있지 않을 때만</li>
 * </ul>
 */
export function useDeskAlerts({ myId, selectedId, mineList, queryClient, t }) {
    const { isConnected, subscribe } = useStomp();
    // 구독 콜백은 한 번 걸고 둔다 — 최신 값은 ref 로 읽는다(목록이 바뀔 때마다 다시 구독하지 않게).
    const latest = useRef({});
    latest.current = { myId, selectedId, mineList, queryClient, t };

    useEffect(() => {
        if (!isConnected) return undefined;
        return subscribe(DESK_TOPIC, (frame) => {
            let event;
            try {
                event = JSON.parse(frame.body);
            } catch {
                return;
            }
            const { myId: me, selectedId: open, mineList: mine, queryClient: qc, t: tr } = latest.current;
            qc.invalidateQueries({ queryKey: ["support-desk"] });

            if (event.kind === "NEW_THREAD") {
                toast(tr("support.desk.new_thread"), { id: `support-new-${event.threadId}` });
                browserNotify(tr("support.desk.title"), tr("support.desk.new_thread"));
                return;
            }
            if (event.kind === "RELEASED" && String(event.senderId) !== String(me)) {
                toast(tr("support.desk.released_thread"), { id: `support-released-${event.threadId}` });
                browserNotify(tr("support.desk.title"), tr("support.desk.released_thread"));
                return;
            }
            if (event.kind === "MESSAGE" && String(event.senderId) !== String(me)
                    && String(event.threadId) !== String(open)) {
                const thread = (mine || []).find((th) => String(th.threadId) === String(event.threadId));
                if (thread && String(event.senderId) === String(thread.customerId)) {
                    const text = tr("support.desk.new_message", { name: localizeChatName(tr, thread.customerName) });
                    toast(text, { id: `support-msg-${event.threadId}` });
                    browserNotify(tr("support.desk.title"), text);
                }
            }
        });
    }, [isConnected, subscribe]);
}
