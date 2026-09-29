import { useCallback, useEffect, useState } from "react";

import {
    adminCreateNotice,
    adminGetNotice,
    adminDeleteNotice,
    adminListNotices,
    adminUpdateNotice,
} from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const EMPTY = { title: "", body: "", pinned: false, published: false };

const formatDate = (value) => formatDateTime(value, "-");

/**
 * 공지 관리.
 *
 * <p>목록은 <b>비공개 초안까지</b> 보여 준다. 미리 써 두고 나중에 공개하는 흐름이
 * 기본이라(점검 공지), 초안이 목록에 없으면 그 흐름 자체를 쓸 수 없다.
 *
 * <p>본문은 일반 텍스트다. 서식을 지원하려면 마크다운 + sanitize 가 필요한데, 그 전까지는
 * 줄바꿈만 살린다(상세 화면의 whitespace-pre-wrap).
 */
const Notices = () => {
    const { t } = useTranslation();
    const [notices, setNotices] = useState([]);
    const [form, setForm] = useState(EMPTY);
    const [editingId, setEditingId] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setError("");
        try {
            const paged = await adminListNotices({ page: 0, size: 50 });
            setNotices(paged?.items ?? []);
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.notices.load_failed"));
        }
    }, [t]);

    useEffect(() => {
        load();
    }, [load]);

    const set = (key) => (e) => {
        const value = e.target.type === "checkbox" ? e.target.checked : e.target.value;
        setForm((prev) => ({ ...prev, [key]: value }));
    };

    const reset = () => {
        setForm(EMPTY);
        setEditingId(null);
        setError("");
    };

    const submit = async () => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            if (editingId) {
                await adminUpdateNotice(editingId, form);
            } else {
                await adminCreateNotice(form);
            }
            reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.notices.save_failed"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await adminDeleteNotice(id);
            if (editingId === id) reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.notices.delete_failed"));
        } finally {
            setBusy(false);
        }
    };

    // 목록은 요약(제목·고정·공개)만 준다. 수정하려면 본문이 필요하므로 상세를 받아 온다.
    const startEdit = async (id) => {
        setError("");
        try {
            const detail = await adminGetNotice(id);
            setEditingId(id);
            setForm({
                title: detail.title ?? "",
                body: detail.body ?? "",
                pinned: Boolean(detail.pinned),
                published: Boolean(detail.published),
            });
        } catch (e) {
            setError(t("admin.notices.detail_failed"));
        }
    };

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.notices.title")}</h1>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-8 rounded-md border border-slate-200 p-4">
                <h2 className="mb-3 font-semibold">
                    {editingId ? t("admin.notices.edit_heading", { id: editingId }) : t("admin.notices.new_heading")}
                </h2>

                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.notices.field.title")}</span>
                    <input
                        value={form.title}
                        onChange={set("title")}
                        maxLength={200}
                        className="mt-1 w-full rounded border border-slate-300 p-2"
                    />
                </label>

                <label className="mt-3 block">
                    <span className="text-sm text-slate-700">{t("admin.notices.field.body")}</span>
                    <textarea
                        value={form.body}
                        onChange={set("body")}
                        rows={10}
                        maxLength={20000}
                        className="mt-1 w-full resize-none rounded border border-slate-300 p-2"
                    />
                </label>

                <div className="mt-3 flex gap-6">
                    <label className="flex items-center gap-2">
                        <input type="checkbox" checked={form.pinned} onChange={set("pinned")} />
                        <span className="text-slate-700">{t("admin.notices.field.pinned")}</span>
                    </label>
                    <label className="flex items-center gap-2">
                        <input
                            type="checkbox"
                            checked={form.published}
                            onChange={set("published")}
                        />
                        <span className="text-slate-700">{t("admin.notices.field.published")}</span>
                    </label>
                </div>

                <div className="mt-4 flex gap-2">
                    <button
                        type="button"
                        onClick={submit}
                        disabled={busy || !form.title || !form.body}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {editingId ? t("admin.notices.update") : t("admin.notices.create")}
                    </button>
                    {editingId && (
                        <button
                            type="button"
                            onClick={reset}
                            className="rounded border border-slate-300 px-4 py-2"
                        >
                            {t("common.cancel")}
                        </button>
                    )}
                </div>
            </div>

            <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-300">
                    <tr>
                        <th className="py-2">{t("admin.notices.col.title")}</th>
                        <th>{t("admin.notices.col.pinned")}</th>
                        <th>{t("admin.notices.col.published")}</th>
                        <th>{t("admin.notices.col.published_at")}</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {notices.map((notice) => (
                        <tr key={notice.id} className="border-b border-slate-100">
                            <td className="max-w-md truncate py-2">{notice.title}</td>
                            <td>{notice.pinned ? "O" : "X"}</td>
                            <td>{notice.published ? "O" : "X"}</td>
                            <td className="text-slate-500">{formatDate(notice.publishedAt)}</td>
                            <td className="whitespace-nowrap">
                                <button
                                    type="button"
                                    onClick={() => startEdit(notice.id)}
                                    className="mr-2 underline"
                                >
                                    {t("admin.notices.edit")}
                                </button>
                                <button
                                    type="button"
                                    onClick={() => remove(notice.id)}
                                    className="text-red-600 underline"
                                >
                                    {t("admin.notices.delete")}
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

export default Notices;
