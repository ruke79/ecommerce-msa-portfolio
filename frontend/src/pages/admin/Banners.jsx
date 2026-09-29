import { useCallback, useEffect, useState } from "react";

import {
    adminCreateBanner,
    adminDeleteBanner,
    adminListBanners,
    adminUpdateBanner,
} from "util/api";
import { useTranslation } from "i18n/I18nProvider";
import { formatDateTime, localInputToServer, serverToLocalInput } from "util/datetime";

const EMPTY = {
    title: "",
    imageUrl: "",
    linkUrl: "",
    sortOrder: 0,
    published: false,
    startsAt: "",
    endsAt: "",
};

/**
 * 배너 관리.
 *
 * <p><b>이미지는 URL 로 받는다.</b> 콘텐츠 API 에 별도 업로드를 넣지 않은 것은 의도적이다 —
 * AWS 자격증명을 서비스 하나에 더 퍼뜨리지 않으려는 것이다. 상품 이미지 업로드로 올린 뒤
 * 그 URL 을 붙여 넣으면 된다. 업로드 UI 가 필요해지면 그때 붙인다.
 *
 * <p>목록에 <b>"지금 보임"</b> 칸이 있다. 공개로 해 뒀는데 화면에 안 나오는 이유는 대개
 * 기간이라, 그 판정을 서버가 계산해서 그대로 보여 준다.
 */
const Banners = () => {
    const { t } = useTranslation();
    const [banners, setBanners] = useState([]);
    const [form, setForm] = useState(EMPTY);
    const [editingId, setEditingId] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setError("");
        try {
            setBanners(await adminListBanners());
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.banners.load_failed"));
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

    const startEdit = (banner) => {
        setEditingId(banner.id);
        setForm({
            title: banner.title ?? "",
            imageUrl: banner.imageUrl ?? "",
            linkUrl: banner.linkUrl ?? "",
            sortOrder: banner.sortOrder ?? 0,
            published: Boolean(banner.published),
            // datetime-local 은 초 단위를 싫어한다. 서버가 주는 ISO 문자열에서 분까지만 쓴다.
            // 서버(UTC) ↔ 입력칸(현지) — 양쪽 모두 변환한다(util/datetime).
            startsAt: serverToLocalInput(banner.startsAt),
            endsAt: serverToLocalInput(banner.endsAt),
        });
    };

    const submit = async () => {
        if (busy) return;
        setBusy(true);
        setError("");

        const payload = {
            title: form.title,
            imageUrl: form.imageUrl,
            linkUrl: form.linkUrl || null,
            sortOrder: Number(form.sortOrder) || 0,
            published: form.published,
            // 빈 문자열은 null 로 보낸다 — 서버에서 "기간 제한 없음"을 뜻한다.
            startsAt: localInputToServer(form.startsAt),
            endsAt: localInputToServer(form.endsAt),
        };

        try {
            if (editingId) {
                await adminUpdateBanner(editingId, payload);
            } else {
                await adminCreateBanner(payload);
            }
            reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.banners.save_failed"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await adminDeleteBanner(id);
            if (editingId === id) reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.banners.delete_failed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.banners.title")}</h1>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-8 rounded-md border border-slate-200 p-4">
                <h2 className="mb-3 font-semibold">
                    {editingId ? t("admin.banners.edit_heading", { id: editingId }) : t("admin.banners.new_heading")}
                </h2>

                <div className="grid gap-3 md:grid-cols-2">
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.title")}</span>
                        <input
                            value={form.title}
                            onChange={set("title")}
                            maxLength={200}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.image_url")}</span>
                        <input
                            value={form.imageUrl}
                            onChange={set("imageUrl")}
                            maxLength={1000}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.link_url")}</span>
                        <input
                            value={form.linkUrl}
                            onChange={set("linkUrl")}
                            maxLength={1000}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.sort_order")}</span>
                        <input
                            type="number"
                            value={form.sortOrder}
                            onChange={set("sortOrder")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.starts_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.startsAt}
                            onChange={set("startsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.banners.field.ends_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.endsAt}
                            onChange={set("endsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                </div>

                <label className="mt-3 flex items-center gap-2">
                    <input type="checkbox" checked={form.published} onChange={set("published")} />
                    <span className="text-slate-700">{t("admin.banners.field.published")}</span>
                </label>

                {form.imageUrl && (
                    <img
                        src={form.imageUrl}
                        alt={t("admin.banners.preview_alt")}
                        className="mt-3 max-h-40 rounded border border-slate-200"
                    />
                )}

                <div className="mt-4 flex gap-2">
                    <button
                        type="button"
                        onClick={submit}
                        disabled={busy || !form.title || !form.imageUrl}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {editingId ? t("admin.banners.update") : t("admin.banners.create")}
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
                        <th className="py-2">{t("admin.banners.col.order")}</th>
                        <th>{t("admin.banners.col.title")}</th>
                        <th>{t("admin.banners.col.published")}</th>
                        <th>{t("admin.banners.col.visible_now")}</th>
                        <th>{t("admin.banners.col.period")}</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {banners.map((banner) => (
                        <tr key={banner.id} className="border-b border-slate-100">
                            <td className="py-2">{banner.sortOrder}</td>
                            <td className="max-w-xs truncate">{banner.title}</td>
                            <td>{banner.published ? "O" : "X"}</td>
                            <td className={banner.visibleNow ? "text-green-600" : "text-slate-400"}>
                                {banner.visibleNow ? t("admin.banners.visible") : t("admin.banners.hidden")}
                            </td>
                            <td className="text-slate-500">
                                {banner.startsAt || banner.endsAt
                                    ? `${formatDateTime(banner.startsAt, "")} ~ ${formatDateTime(banner.endsAt, "")}`
                                    : t("admin.banners.always")}
                            </td>
                            <td className="whitespace-nowrap">
                                <button
                                    type="button"
                                    onClick={() => startEdit(banner)}
                                    className="mr-2 underline"
                                >
                                    {t("admin.banners.edit")}
                                </button>
                                <button
                                    type="button"
                                    onClick={() => remove(banner.id)}
                                    className="text-red-600 underline"
                                >
                                    {t("admin.banners.delete")}
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

export default Banners;
