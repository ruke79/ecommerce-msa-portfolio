import { useCallback, useEffect, useMemo, useState } from "react";

import {
    adminCreateEvent,
    adminDeleteEvent,
    adminEndEvent,
    adminGetEvent,
    adminListEvents,
    adminUpdateEvent,
    getAdminProducts,
    getCategories,
} from "util/api";
import { formatDateTime, localInputToServer, serverToLocalInput } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const EMPTY = {
    title: "",
    slug: "",
    description: "",
    bannerImageUrl: "",
    linkUrl: "",
    startsAt: "",
    endsAt: "",
    publish: false,
    products: [],
    categories: [],
    audience: "ALL",
    couponName: "",
    couponDiscount: "",
    couponValidDays: "",
};

/** 대상에 담을 때의 기본 할인율. 0 으로 두면 "담았는데 가격이 그대로"가 된다. */
const DEFAULT_RATE = 10;

const formatDate = (value) => formatDateTime(value, "-");

/** 서버 시각(UTC) → 입력칸(현지). 보낼 때는 localInputToServer — 한쪽만 바꾸면 9시간 어긋난다(util/datetime). */
const toInput = serverToLocalInput;

/** 상태 이름표 — 사전 키를 두고 그릴 때 번역한다(모듈을 읽을 때 번역하면 언어가 그때로 굳는다). */
const STATUS_LABEL = {
    DRAFT: "admin.events.status.draft",
    SCHEDULED: "admin.events.status.scheduled",
    ACTIVE: "admin.events.status.active",
    ENDED: "admin.events.status.ended",
};

/** 사전 문구의 <b>…</b> 를 굵은 글씨로 그린다. 쪼갠 조각의 홀수 번째가 굵은 부분이다. */
const bold = (text) => text.split(/<b>(.*?)<\/b>/).map((part, i) => (i % 2 ? <b key={i}>{part}</b> : part));

const STATUS_CLASS = {
    DRAFT: "text-slate-400",
    SCHEDULED: "text-blue-600",
    ACTIVE: "text-green-700 font-semibold",
    ENDED: "text-slate-400",
};

/**
 * 이벤트 / 기획전 관리.
 *
 * <p><b>할인율은 여기에만 저장된다.</b> 기획전이 시작될 때 상품의 상시 할인을 덮어쓰고
 * 끝날 때 되돌리는 방식이 아니다 — 그러면 진행 중에 상품을 수정했을 때 종료 시 복원이
 * 그 수정을 덮는다. 상품 데이터는 그대로 두고 조회할 때 합성한다.
 *
 * <p>그래서 이 화면에서 무엇을 하든 상품은 손상되지 않는다. 기획전을 지우면 가격이
 * 원래대로 돌아온다.
 *
 * <p><b>상태는 직접 바꿀 수 없다.</b> 관리자는 "확정"까지만 하고, 시작·종료는 시각이
 * 결정한다(서버 스케줄러). 사람이 자정에 맞춰 버튼을 누르는 운영은 반드시 놓친다.
 */
const Events = () => {
    const { t } = useTranslation();
    const [events, setEvents] = useState([]);
    const [form, setForm] = useState(EMPTY);
    const [editingId, setEditingId] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

    // 대상 상품을 고르기 위한 카탈로그. 첫 묶음만 받아 이름으로 걸러 쓴다 —
    // 전체를 받아오면 상품이 늘수록 이 화면이 무거워진다.
    const [catalog, setCatalog] = useState([]);
    const [query, setQuery] = useState("");
    const [categories, setCategories] = useState([]);

    const load = useCallback(async () => {
        setError("");
        try {
            setEvents(await adminListEvents());
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.events.error.load"));
        }
    }, [t]);

    useEffect(() => {
        load();
    }, [load]);

    useEffect(() => {
        getAdminProducts({ cursor: -1, pageSize: 100 })
            .then((page) => setCatalog(page?.contents ?? []))
            // 상품 목록을 못 받아도 이 화면은 쓸 수 있어야 한다 — 기존 기획전의 기간이나
            // 배너를 고치는 데는 필요 없다.
            .catch(() => setCatalog([]));
    }, []);

    useEffect(() => {
        // 카테고리를 못 받아도 화면은 쓸 수 있어야 한다 — 상품 단위 지정만 하면 된다.
        getCategories().then((list) => setCategories(list ?? [])).catch(() => setCategories([]));
    }, []);

    const nameOf = useMemo(() => {
        const map = new Map(catalog.map((p) => [String(p.id), p.name]));
        return (productId) => map.get(String(productId)) ?? `#${productId}`;
    }, [catalog]);

    const candidates = useMemo(() => {
        const chosen = new Set(form.products.map((p) => String(p.productId)));
        const needle = query.trim().toLowerCase();
        return catalog
            .filter((p) => !chosen.has(String(p.id)))
            .filter((p) => !needle || (p.name ?? "").toLowerCase().includes(needle))
            .slice(0, 20);
    }, [catalog, form.products, query]);

    const set = (key) => (e) => {
        const value = e.target.type === "checkbox" ? e.target.checked : e.target.value;
        setForm((prev) => ({ ...prev, [key]: value }));
    };

    const addProduct = (product) => {
        setForm((prev) => ({
            ...prev,
            products: [...prev.products, { productId: Number(product.id), discountRate: DEFAULT_RATE }],
        }));
        setQuery("");
    };

    const setRate = (productId, raw) => {
        // 0~100 밖의 값은 서버가 400 으로 거절한다. 여기서 먼저 잡아 두면 관리자가
        // 저장 버튼을 누르고 나서야 알게 되지 않는다.
        const rate = Math.max(0, Math.min(100, Number(raw) || 0));
        setForm((prev) => ({
            ...prev,
            products: prev.products.map((p) =>
                p.productId === productId ? { ...p, discountRate: rate } : p),
        }));
    };

    const toggleCategory = (categoryId) => {
        setForm((prev) => {
            const has = prev.categories.some((c) => c.categoryId === categoryId);
            return {
                ...prev,
                categories: has
                    ? prev.categories.filter((c) => c.categoryId !== categoryId)
                    : [...prev.categories, { categoryId, discountRate: DEFAULT_RATE }],
            };
        });
    };

    const setCategoryRate = (categoryId, raw) => {
        const rate = Math.max(0, Math.min(100, Number(raw) || 0));
        setForm((prev) => ({
            ...prev,
            categories: prev.categories.map((c) =>
                c.categoryId === categoryId ? { ...c, discountRate: rate } : c),
        }));
    };

    const removeProduct = (productId) => {
        setForm((prev) => ({
            ...prev,
            products: prev.products.filter((p) => p.productId !== productId),
        }));
    };

    const reset = () => {
        setForm(EMPTY);
        setEditingId(null);
        setError("");
        setQuery("");
    };

    const submit = async () => {
        if (busy) return;

        if (form.startsAt && form.endsAt && form.endsAt <= form.startsAt) {
            setError(t("admin.events.error.end_before_start"));
            return;
        }
        // 확정하면 스케줄러가 시작시킨다. 시작 시각이 없으면 영영 시작되지 않는데,
        // 화면에는 "예약"이라고만 보여 무엇이 잘못됐는지 알 수 없다.
        if (form.publish && !form.startsAt) {
            setError(t("admin.events.error.publish_needs_start"));
            return;
        }

        // 이름만 있고 할인율이 없으면 0% 쿠폰이 발급되고, 사용자는 받아 놓고도 결제 때
        // 아무 일이 일어나지 않는 것을 본다. 서버도 막지만 여기서 먼저 잡는다.
        if (form.couponName.trim() && !(Number(form.couponDiscount) > 0)) {
            setError(t("admin.events.error.coupon_needs_discount"));
            return;
        }

        setBusy(true);
        setError("");
        try {
            const payload = {
                ...form,
                slug: form.slug || null,
                description: form.description || null,
                bannerImageUrl: form.bannerImageUrl || null,
                linkUrl: form.linkUrl || null,
                startsAt: localInputToServer(form.startsAt),
                endsAt: localInputToServer(form.endsAt),
                couponName: form.couponName.trim() || null,
                couponDiscount: form.couponName.trim() ? Number(form.couponDiscount) || 0 : null,
                couponValidDays: form.couponValidDays ? Number(form.couponValidDays) : null,
            };
            if (editingId) {
                await adminUpdateEvent(editingId, payload);
            } else {
                await adminCreateEvent(payload);
            }
            reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.events.error.save"));
        } finally {
            setBusy(false);
        }
    };

    const startEdit = async (id) => {
        setError("");
        try {
            const detail = await adminGetEvent(id);
            setEditingId(id);
            setForm({
                title: detail.title ?? "",
                slug: detail.slug ?? "",
                description: detail.description ?? "",
                bannerImageUrl: detail.bannerImageUrl ?? "",
                linkUrl: detail.linkUrl ?? "",
                startsAt: toInput(detail.startsAt),
                endsAt: toInput(detail.endsAt),
                // 진행 중인 것을 수정할 때 이 값이 false 면 저장하면서 취소된다.
                publish: detail.status !== "DRAFT",
                products: detail.products ?? [],
                categories: detail.categories ?? [],
                audience: detail.audience ?? "ALL",
                couponName: detail.couponName ?? "",
                couponDiscount: detail.couponDiscount ?? "",
                couponValidDays: detail.couponValidDays ?? "",
            });
        } catch {
            setError(t("admin.events.error.load_one"));
        }
    };

    const endNow = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await adminEndEvent(id);
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.events.error.end"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await adminDeleteEvent(id);
            if (editingId === id) reset();
            await load();
        } catch (e) {
            // 진행 중인 기획전은 서버가 409 로 거절한다 — 할인이 조용히 사라지고
            // 기록도 안 남기 때문이다.
            setError(e?.response?.data?.message ?? t("admin.events.error.delete"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="p-6">
            <h1 className="mb-2 text-2xl font-bold">{t("admin.events.title")}</h1>
            <p className="mb-6 text-sm text-slate-500">
                {t("admin.events.intro")}
            </p>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-8 rounded-md border border-slate-200 p-4">
                <h2 className="mb-3 font-semibold">
                    {editingId ? t("admin.events.form.edit_title", { id: editingId }) : t("admin.events.form.new_title")}
                </h2>

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.events.field.title")}</span>
                        <input
                            value={form.title}
                            onChange={set("title")}
                            maxLength={200}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">
                            {t("admin.events.field.slug")}
                        </span>
                        <input
                            value={form.slug}
                            onChange={set("slug")}
                            maxLength={200}
                            placeholder={t("admin.events.field.slug_placeholder")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                </div>

                <label className="mt-3 block">
                    <span className="text-sm text-slate-700">{t("admin.events.field.description")}</span>
                    <textarea
                        value={form.description}
                        onChange={set("description")}
                        rows={3}
                        maxLength={2000}
                        className="mt-1 w-full resize-none rounded border border-slate-300 p-2"
                    />
                </label>

                <label className="mt-3 block">
                    <span className="text-sm text-slate-700">{t("admin.events.field.banner")}</span>
                    <input
                        value={form.bannerImageUrl}
                        onChange={set("bannerImageUrl")}
                        maxLength={1000}
                        className="mt-1 w-full rounded border border-slate-300 p-2"
                    />
                </label>

                <div className="mt-3 grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.events.field.starts_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.startsAt}
                            onChange={set("startsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.events.field.ends_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.endsAt}
                            onChange={set("endsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>
                </div>

                {/* ── 대상 상품 ── */}
                <div className="mt-5 rounded border border-slate-200 p-3">
                    <h3 className="mb-2 text-sm font-semibold">
                        {t("admin.events.products.heading")} {form.products.length > 0 && `(${form.products.length})`}
                    </h3>

                    <input
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        placeholder={t("admin.events.products.search_placeholder")}
                        className="mb-2 w-full rounded border border-slate-300 p-2"
                    />

                    {query && (
                        <ul className="mb-3 max-h-40 overflow-y-auto rounded border border-slate-200">
                            {candidates.map((product) => (
                                <li key={product.id}>
                                    <button
                                        type="button"
                                        onClick={() => addProduct(product)}
                                        className="w-full px-3 py-1.5 text-left text-sm hover:bg-slate-100"
                                    >
                                        {product.name}
                                    </button>
                                </li>
                            ))}
                            {candidates.length === 0 && (
                                <li className="px-3 py-2 text-sm text-slate-400">
                                    {t("admin.events.products.no_match")}
                                </li>
                            )}
                        </ul>
                    )}

                    {form.products.length === 0 ? (
                        <p className="text-sm text-slate-400">
                            {t("admin.events.products.empty")}
                        </p>
                    ) : (
                        <ul className="divide-y divide-slate-100">
                            {form.products.map((item) => (
                                <li key={item.productId} className="flex items-center gap-3 py-1.5">
                                    <span className="flex-1 truncate text-sm">
                                        {nameOf(item.productId)}
                                    </span>
                                    <label className="flex items-center gap-1 text-sm">
                                        <input
                                            type="number"
                                            min={0}
                                            max={100}
                                            value={item.discountRate}
                                            onChange={(e) => setRate(item.productId, e.target.value)}
                                            aria-label={t("admin.events.rate_label", { name: nameOf(item.productId) })}
                                            className="w-20 rounded border border-slate-300 p-1 text-right"
                                        />
                                        %
                                    </label>
                                    <button
                                        type="button"
                                        onClick={() => removeProduct(item.productId)}
                                        aria-label={t("admin.events.products.remove")}
                                        className="px-1 text-red-600"
                                    >
                                        ×
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}

                    <p className="mt-2 text-xs text-slate-500">
                        {t("admin.events.products.note")}
                    </p>
                </div>

                {/* ── 대상 카테고리 ──
                    상품 단위와 달리 **나중에 등록된 상품도 자동으로 포함된다.** 상품 목록을
                    저장해 두면 그 순간 굳어, 기획전 중에 들어온 신상품만 정가로 남는다. */}
                <div className="mt-4 rounded border border-slate-200 p-3">
                    <h3 className="mb-2 text-sm font-semibold">
                        {t("admin.events.categories.heading")} {form.categories.length > 0 && `(${form.categories.length})`}
                    </h3>

                    {categories.length === 0 ? (
                        <p className="text-sm text-slate-400">{t("admin.events.categories.load_failed")}</p>
                    ) : (
                        <ul className="space-y-1">
                            {categories.map((category) => {
                                const picked = form.categories.find(
                                    (c) => String(c.categoryId) === String(category.id ?? category.categoryId));
                                const id = Number(category.id ?? category.categoryId);
                                return (
                                    <li key={id} className="flex items-center gap-3">
                                        <label className="flex flex-1 items-center gap-2 text-sm">
                                            <input
                                                type="checkbox"
                                                checked={Boolean(picked)}
                                                onChange={() => toggleCategory(id)}
                                            />
                                            <span>{category.name ?? category.categoryName}</span>
                                        </label>
                                        {picked && (
                                            <label className="flex items-center gap-1 text-sm">
                                                <input
                                                    type="number"
                                                    min={0}
                                                    max={100}
                                                    value={picked.discountRate}
                                                    onChange={(e) => setCategoryRate(id, e.target.value)}
                                                    aria-label={t("admin.events.rate_label", { name: category.name ?? category.categoryName })}
                                                    className="w-20 rounded border border-slate-300 p-1 text-right"
                                                />
                                                %
                                            </label>
                                        )}
                                    </li>
                                );
                            })}
                        </ul>
                    )}

                    <p className="mt-2 text-xs text-slate-500">
                        {bold(t("admin.events.categories.note"))}
                    </p>
                </div>

                {/* ── 딸린 쿠폰 ──
                    🔴 세그먼트(고객군) 할인은 진열가가 아니라 **쿠폰으로** 준다. 상품 조회에는
                    신원이 없고(공개 라우트), 목록은 캐시되며, 신원을 아는 화면과 모르는 화면이
                    섞이면 "목록에서 30% 를 보고 들어왔는데 상세는 정가" 가 되살아난다. */}
                <div className="mt-4 rounded border border-slate-200 p-3">
                    <h3 className="mb-2 text-sm font-semibold">{t("admin.events.coupon.heading")}</h3>

                    <div className="grid gap-3 sm:grid-cols-3">
                        <label className="block sm:col-span-2">
                            <span className="text-sm text-slate-700">{t("admin.events.coupon.name")}</span>
                            <input
                                value={form.couponName}
                                onChange={set("couponName")}
                                maxLength={120}
                                placeholder={t("admin.events.coupon.name_placeholder")}
                                className="mt-1 w-full rounded border border-slate-300 p-2"
                            />
                        </label>
                        <label className="block">
                            <span className="text-sm text-slate-700">{t("admin.events.coupon.discount")}</span>
                            <input
                                type="number"
                                min={0}
                                max={100}
                                value={form.couponDiscount}
                                onChange={set("couponDiscount")}
                                className="mt-1 w-full rounded border border-slate-300 p-2"
                            />
                        </label>
                    </div>

                    <div className="mt-3 grid gap-3 sm:grid-cols-2">
                        <label className="block">
                            <span className="text-sm text-slate-700">{t("admin.events.coupon.valid_days")}</span>
                            <input
                                type="number"
                                min={1}
                                max={365}
                                value={form.couponValidDays}
                                onChange={set("couponValidDays")}
                                placeholder={t("admin.events.coupon.valid_days_placeholder")}
                                className="mt-1 w-full rounded border border-slate-300 p-2"
                            />
                        </label>
                        <label className="block">
                            <span className="text-sm text-slate-700">{t("admin.events.coupon.audience")}</span>
                            <select
                                value={form.audience}
                                onChange={set("audience")}
                                className="mt-1 w-full rounded border border-slate-300 p-2"
                            >
                                <option value="ALL">{t("admin.events.audience.all")}</option>
                                <optgroup label={t("admin.events.audience.group.signup")}>
                                    <option value="NEW_USER">{t("admin.events.audience.new_user")}</option>
                                    <option value="EXISTING_USER">{t("admin.events.audience.existing_user")}</option>
                                </optgroup>
                                <optgroup label={t("admin.events.audience.group.purchase")}>
                                    <option value="PURCHASED">{t("admin.events.audience.purchased")}</option>
                                    <option value="NEVER_PURCHASED">{t("admin.events.audience.never_purchased")}</option>
                                </optgroup>
                                {/* 등급은 전부 "이상" 이다 — 골드 회원은 실버 조건에도 해당한다. */}
                                <optgroup label={t("admin.events.audience.group.tier")}>
                                    <option value="TIER_SILVER">{t("admin.events.audience.tier_silver")}</option>
                                    <option value="TIER_GOLD">{t("admin.events.audience.tier_gold")}</option>
                                    <option value="TIER_VIP">{t("admin.events.audience.tier_vip")}</option>
                                </optgroup>
                            </select>
                        </label>
                    </div>

                    <p className="mt-2 text-xs text-slate-500">
                        {bold(t("admin.events.coupon.note"))}
                    </p>
                    <p className="mt-1 text-xs text-slate-500">
                        {bold(t("admin.events.coupon.tier_note"))}
                    </p>
                </div>

                <label className="mt-4 flex items-center gap-2">
                    <input type="checkbox" checked={form.publish} onChange={set("publish")} />
                    <span className="text-slate-700">
                        {t("admin.events.publish")}
                    </span>
                </label>

                <div className="mt-4 flex gap-2">
                    <button
                        type="button"
                        onClick={submit}
                        disabled={busy || !form.title.trim()}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {editingId ? t("common.save") : t("admin.events.register")}
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
                        <th className="py-2">{t("admin.events.field.title")}</th>
                        <th>{t("admin.events.col.status")}</th>
                        <th>{t("admin.events.col.target")}</th>
                        <th>{t("admin.events.field.starts_at")}</th>
                        <th>{t("admin.events.col.ends_at")}</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {events.map((event) => (
                        <tr key={event.id} className="border-b border-slate-100">
                            <td className="max-w-xs truncate py-2">{event.title}</td>
                            <td className={STATUS_CLASS[event.status] ?? ""}>
                                {STATUS_LABEL[event.status] ? t(STATUS_LABEL[event.status]) : event.status}
                            </td>
                            <td>{t("admin.events.product_count", { count: event.productCount })}</td>
                            <td className="text-slate-500">{formatDate(event.startsAt)}</td>
                            <td className="text-slate-500">{formatDate(event.endsAt)}</td>
                            <td className="whitespace-nowrap">
                                {event.status !== "ENDED" && (
                                    <button
                                        type="button"
                                        onClick={() => startEdit(event.id)}
                                        className="mr-2 underline"
                                    >
                                        {t("admin.events.edit")}
                                    </button>
                                )}
                                {event.status === "ACTIVE" ? (
                                    // 진행 중인 것은 지울 수 없다 — 할인이 조용히 사라지고
                                    // "어제 30% 였는데요" 라는 문의에 답할 근거도 없어진다.
                                    <button
                                        type="button"
                                        onClick={() => endNow(event.id)}
                                        className="text-orange-700 underline"
                                    >
                                        {t("admin.events.end")}
                                    </button>
                                ) : (
                                    <button
                                        type="button"
                                        onClick={() => remove(event.id)}
                                        className="text-red-600 underline"
                                    >
                                        {t("admin.events.delete")}
                                    </button>
                                )}
                            </td>
                        </tr>
                    ))}
                    {events.length === 0 && (
                        <tr>
                            <td colSpan={6} className="py-6 text-center text-slate-400">
                                {t("admin.events.empty")}
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>
        </div>
    );
};

export default Events;
