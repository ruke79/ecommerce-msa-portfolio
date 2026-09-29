import { useCallback, useEffect, useMemo, useState } from "react";

import {
    adminCreateHomeSection,
    adminDeleteHomeSection,
    adminListHomeSections,
    adminUpdateHomeSection,
    getAllSubcategories,
    getCategories,
} from "util/api";
import { useTranslation } from "i18n/I18nProvider";
import { localInputToServer, serverToLocalInput } from "util/datetime";

const EMPTY = {
    title: "",
    kind: "CATEGORY",
    categoryName: "",
    subcategoryName: "",
    layout: "SWIPER",
    itemLimit: 10,
    sortOrder: 0,
    published: false,
    startsAt: "",
    endsAt: "",
};

// <input type="datetime-local"> 은 "YYYY-MM-DDTHH:mm" 만 받는다. 서버의 LocalDateTime 은
// 초까지 붙여 내려오므로 잘라 준다. 반대로 보낼 때 빈 문자열은 null 이어야 한다 —
// ""를 그대로 보내면 역직렬화에서 400 이 나고, null 은 "제한 없음"을 뜻한다.
// 그리고 입력칸은 현지 시각, 서버는 UTC 다 — 양쪽 모두 변환한다(util/datetime).
const toInput = serverToLocalInput;
const toPayload = localInputToServer;

/**
 * 홈 섹션 관리.
 *
 * <p>첫 화면에 무엇을 어떤 순서로 보여줄지를 정한다. 예전에는 이게 프론트 코드였다
 * ({@code CategoryProducts.jsx} 의 {@code category="fashion"} 다섯 줄). 기획전 하나
 * 올리는 데 배포가 필요했고, 카테고리 이름이 어긋나면 오류 없이 그 줄만 사라졌다.
 *
 * <p>목록의 <b>노출</b>·<b>카테고리</b> 두 칸이 "왜 홈에 안 나오지"의 답이다:
 * 기간 밖이거나 비공개면 노출이 X, 가리키는 카테고리가 사라졌으면 카테고리가 X 다.
 */
const HomeSections = () => {
    const { t } = useTranslation();
    const [sections, setSections] = useState([]);
    const [categories, setCategories] = useState([]);
    const [subcategories, setSubcategories] = useState([]);
    const [form, setForm] = useState(EMPTY);
    const [editingId, setEditingId] = useState(null);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setError("");
        try {
            setSections(await adminListHomeSections());
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.home_sections.error.load"));
        }
    }, [t]);

    useEffect(() => {
        load();
        getCategories()
            .then((list) => setCategories(Array.isArray(list) ? list : []))
            .catch(() => setCategories([]));
        getAllSubcategories().then(setSubcategories);
    }, [load]);

    const subcategoryOptions = useMemo(
        () => subcategories.filter((s) => s.parentName === form.categoryName),
        [subcategories, form.categoryName]
    );

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
            const payload = {
                ...form,
                // CATEGORY 섹션에 서브카테고리가 남아 있으면 화면에는 안 보이지만
                // 저장은 된다 — 나중에 kind 를 바꿨을 때 엉뚱한 값이 되살아난다.
                subcategoryName: form.kind === "SUBCATEGORY" ? form.subcategoryName : "",
                itemLimit: Number(form.itemLimit),
                sortOrder: Number(form.sortOrder),
                startsAt: toPayload(form.startsAt),
                endsAt: toPayload(form.endsAt),
            };
            if (editingId) {
                await adminUpdateHomeSection(editingId, payload);
            } else {
                await adminCreateHomeSection(payload);
            }
            reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.home_sections.error.save"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id) => {
        if (busy) return;
        setBusy(true);
        try {
            await adminDeleteHomeSection(id);
            if (editingId === id) reset();
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.home_sections.error.delete"));
        } finally {
            setBusy(false);
        }
    };

    // 목록 응답이 편집에 필요한 필드를 모두 갖고 있어서 상세 조회가 없다.
    const startEdit = (section) => {
        setError("");
        setEditingId(section.id);
        setForm({
            title: section.title ?? "",
            kind: section.kind ?? "CATEGORY",
            categoryName: section.categoryName ?? "",
            subcategoryName: section.subcategoryName ?? "",
            layout: section.layout ?? "SWIPER",
            itemLimit: section.itemLimit ?? 10,
            sortOrder: section.sortOrder ?? 0,
            published: Boolean(section.published),
            startsAt: toInput(section.startsAt),
            endsAt: toInput(section.endsAt),
        });
    };

    const incomplete =
        !form.title ||
        !form.categoryName ||
        (form.kind === "SUBCATEGORY" && !form.subcategoryName);

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.home_sections.title")}</h1>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-8 rounded-md border border-slate-200 p-4">
                <h2 className="mb-3 font-semibold">
                    {editingId ? t("admin.home_sections.form.edit_title", { id: editingId }) : t("admin.home_sections.form.new_title")}
                </h2>

                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.home_sections.field.title")}</span>
                    <input
                        value={form.title}
                        onChange={set("title")}
                        maxLength={200}
                        className="mt-1 w-full rounded border border-slate-300 p-2"
                    />
                </label>

                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.kind")}</span>
                        <select
                            value={form.kind}
                            onChange={set("kind")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        >
                            <option value="CATEGORY">{t("admin.home_sections.kind.category")}</option>
                            <option value="SUBCATEGORY">{t("admin.home_sections.kind.subcategory")}</option>
                        </select>
                    </label>

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.layout")}</span>
                        <select
                            value={form.layout}
                            onChange={set("layout")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        >
                            <option value="GRID">{t("admin.home_sections.layout.grid")}</option>
                            <option value="SWIPER">{t("admin.home_sections.layout.swiper")}</option>
                        </select>
                    </label>

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.category")}</span>
                        <select
                            value={form.categoryName}
                            onChange={set("categoryName")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        >
                            <option value="">{t("admin.home_sections.choose")}</option>
                            {categories.map((category) => (
                                <option key={category.id} value={category.name}>
                                    {category.name}
                                </option>
                            ))}
                        </select>
                    </label>

                    {form.kind === "SUBCATEGORY" && (
                        <label className="block">
                            <span className="text-sm text-slate-700">{t("admin.home_sections.field.subcategory")}</span>
                            <select
                                value={form.subcategoryName}
                                onChange={set("subcategoryName")}
                                className="mt-1 w-full rounded border border-slate-300 p-2"
                            >
                                <option value="">{t("admin.home_sections.choose")}</option>
                                {subcategoryOptions.map((sub) => (
                                    <option key={sub.id} value={sub.name}>
                                        {sub.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                    )}

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.item_limit")}</span>
                        <input
                            type="number"
                            min={1}
                            max={50}
                            value={form.itemLimit}
                            onChange={set("itemLimit")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.sort_order")}</span>
                        <input
                            type="number"
                            value={form.sortOrder}
                            onChange={set("sortOrder")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.starts_at")}</span>
                        <input
                            type="datetime-local"
                            value={form.startsAt}
                            onChange={set("startsAt")}
                            className="mt-1 w-full rounded border border-slate-300 p-2"
                        />
                    </label>

                    <label className="block">
                        <span className="text-sm text-slate-700">{t("admin.home_sections.field.ends_at")}</span>
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
                    <span className="text-slate-700">{t("admin.home_sections.field.published")}</span>
                </label>

                <div className="mt-4 flex gap-2">
                    <button
                        type="button"
                        onClick={submit}
                        disabled={busy || incomplete}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {editingId ? t("admin.home_sections.edit") : t("admin.home_sections.register")}
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
                        <th className="py-2">{t("admin.home_sections.col.order")}</th>
                        <th>{t("admin.home_sections.col.title")}</th>
                        <th>{t("admin.home_sections.col.target")}</th>
                        <th>{t("admin.home_sections.col.layout")}</th>
                        <th>{t("admin.home_sections.col.count")}</th>
                        <th>{t("admin.home_sections.col.published")}</th>
                        <th>{t("admin.home_sections.col.visible")}</th>
                        <th>{t("admin.home_sections.col.category")}</th>
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {sections.map((section) => (
                        <tr key={section.id} className="border-b border-slate-100">
                            <td className="py-2">{section.sortOrder}</td>
                            <td className="max-w-xs truncate">{section.title}</td>
                            <td className="text-slate-600">
                                {section.categoryName}
                                {section.subcategoryName ? ` / ${section.subcategoryName}` : ""}
                            </td>
                            <td>{section.layout === "GRID" ? t("admin.home_sections.layout.grid") : section.layout === "SWIPER" ? t("admin.home_sections.layout.swiper") : section.layout}</td>
                            <td>{section.itemLimit}</td>
                            <td>{section.published ? "O" : "X"}</td>
                            <td className={section.visibleNow ? "" : "text-slate-400"}>
                                {section.visibleNow ? "O" : "X"}
                            </td>
                            <td className={section.categoryExists ? "" : "text-red-600"}>
                                {section.categoryExists ? "O" : t("admin.home_sections.category_missing")}
                            </td>
                            <td className="whitespace-nowrap">
                                <button
                                    type="button"
                                    onClick={() => startEdit(section)}
                                    className="mr-2 underline"
                                >
                                    {t("admin.home_sections.edit")}
                                </button>
                                <button
                                    type="button"
                                    onClick={() => remove(section.id)}
                                    className="text-red-600 underline"
                                >
                                    {t("admin.home_sections.delete")}
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

export default HomeSections;
