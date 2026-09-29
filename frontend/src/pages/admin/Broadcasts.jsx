import { useCallback, useEffect, useState } from "react";

import { createBroadcast, deleteBroadcast, fetchBroadcasts } from "api/notificationApi";
import { formatDateTime, localInputToServer } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const EMPTY = { title: "", content: "", url: "", startsAt: "", endsAt: "" };

const formatDate = (value) => formatDateTime(value, "-");

/**
 * 전체 알림 관리.
 *
 * <p><b>공지 관리(/admin/notice)와 무엇이 다른가.</b> 공지는 <b>읽을 글</b>이다 — 목록과
 * 상세 화면이 있고, 공개하면 그 글을 가리키는 전체 알림이 자동으로 나간다. 여기서 보내는
 * 것은 글이 없는 <b>안내</b>다: "오늘 02시부터 점검합니다" 한 줄처럼, 남겨 둘 문서가 아니라
 * 지금 눈에 띄어야 하는 문장.
 *
 * <p>그래서 이 화면에서 공지를 만들 수 없다. 두 경로를 한 화면에 합치면 "글도 만들고 알림도
 * 보낼까?" 라는 선택이 매번 생기고, 어느 쪽이 사용자에게 어떻게 보이는지 흐려진다.
 *
 * <p><b>기간.</b> 시작을 비우면 즉시, 종료를 비우면 만료 없음이다. 점검 예고처럼 지나면
 * 의미가 없는 안내는 종료를 넣어 두는 편이 낫다 — 사용자가 하나하나 지우게 하지 않는다.
 */
const Broadcasts = () => {
    const { t } = useTranslation();
    const [items, setItems] = useState([]);
    const [form, setForm] = useState(EMPTY);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setError("");
        try {
            setItems(await fetchBroadcasts(50));
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.broadcasts.load_failed"));
        }
    }, [t]);

    useEffect(() => {
        load();
    }, [load]);

    const set = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));

    const submit = async () => {
        if (busy) return;

        // 끝이 시작보다 앞서면 아무에게도 보이지 않는 알림이 만들어진다. 서버는 이것을
        // 막지 않는다(기간 판정은 조회 쪽이라 저장은 성공한다) — 여기서 걸러야 관리자가
        // "보냈는데 왜 안 보이지?" 를 겪지 않는다.
        if (form.startsAt && form.endsAt && form.endsAt <= form.startsAt) {
            setError(t("admin.broadcasts.end_before_start"));
            return;
        }

        setBusy(true);
        setError("");
        try {
            // 입력칸은 현지 시각, 서버는 UTC — 보낼 때 바꾼다(util/datetime).
            await createBroadcast({
                ...form,
                startsAt: localInputToServer(form.startsAt),
                endsAt: localInputToServer(form.endsAt),
            });
            setForm(EMPTY);
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.broadcasts.send_failed"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await deleteBroadcast(id);
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.broadcasts.delete_failed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="p-6">
            <h1 className="mb-2 text-2xl font-bold">{t("admin.broadcasts.title")}</h1>
            <p className="mb-6 text-sm text-slate-500">
                {/* 가운데 굵은 글자 때문에 문장을 셋으로 나눈다. 띄어쓰기는 사전 값에 들어 있다(일본어는 띄우지 않는다). */}
                {t("admin.broadcasts.intro_before")}
                <span className="font-semibold">{t("admin.broadcasts.intro_link")}</span>
                {t("admin.broadcasts.intro_after")}
            </p>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-8 rounded-md border border-slate-200 p-4">
                <h2 className="mb-3 font-semibold">{t("admin.broadcasts.new_heading")}</h2>

                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.broadcasts.field.title")}</span>
                    <input
                        value={form.title}
                        onChange={set("title")}
                        maxLength={255}
                        className="mt-1 w-full rounded border border-slate-300 p-2"
                    />
                </label>

                <label className="mt-3 block">
                    <span className="text-sm text-slate-700">{t("admin.broadcasts.field.content")}</span>
                    <textarea
                        value={form.content}
                        onChange={set("content")}
                        rows={4}
                        maxLength={1000}
                        className="mt-1 w-full resize-none rounded border border-slate-300 p-2"
                    />
                </label>

                <label className="mt-3 block">
                    <span className="text-sm text-slate-700">
                        {t("admin.broadcasts.field.link")}: <code>{"notice/12"}</code>
                    </span>
                    <input
                        value={form.url}
                        onChange={set("url")}
                        maxLength={512}
                        placeholder={t("admin.broadcasts.link_placeholder")}
                        className="mt-1 w-full rounded border border-slate-300 p-2"
                    />
                </label>

                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.broadcasts.field.starts_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.startsAt}
                            onChange={set("startsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.broadcasts.field.ends_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.endsAt}
                            onChange={set("endsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                </div>

                <button
                    type="button"
                    onClick={submit}
                    disabled={busy || !form.title.trim()}
                    className="mt-4 rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                >
                    {t("admin.broadcasts.send")}
                </button>
            </div>

            <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-300">
                    <tr>
                        <th className="py-2">{t("admin.broadcasts.col.title")}</th>
                        <th>{t("admin.broadcasts.col.type")}</th>
                        <th>{t("admin.broadcasts.col.status")}</th>
                        <th>{t("admin.broadcasts.col.starts_at")}</th>
                        <th>{t("admin.broadcasts.col.ends_at")}</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {items.map((item) => (
                        <tr key={item.id} className="border-b border-slate-100">
                            <td className="max-w-md truncate py-2">{item.title}</td>
                            <td className="text-slate-500">
                                {item.type === "NOTICE_PUBLISHED"
                                    ? t("admin.broadcasts.type.notice_published")
                                    : t("admin.broadcasts.type.admin")}
                            </td>
                            {/* 시작·종료 두 칸만 보여 주면 예약분과 만료분을 헷갈린다.
                                "지금 보이는가"는 서버가 계산해서 내려 준다. */}
                            <td className={item.active ? "text-green-700" : "text-slate-400"}>
                                {item.active ? t("admin.broadcasts.active") : t("admin.broadcasts.inactive")}
                            </td>
                            <td className="text-slate-500">{formatDate(item.startsAt)}</td>
                            <td className="text-slate-500">{formatDate(item.endsAt)}</td>
                            <td className="whitespace-nowrap">
                                <button
                                    type="button"
                                    onClick={() => remove(item.id)}
                                    className="text-red-600 underline"
                                >
                                    {t("admin.broadcasts.take_down")}
                                </button>
                            </td>
                        </tr>
                    ))}
                    {items.length === 0 && (
                        <tr>
                            <td colSpan={6} className="py-6 text-center text-slate-400">
                                {t("admin.broadcasts.empty")}
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>
        </div>
    );
};

export default Broadcasts;
