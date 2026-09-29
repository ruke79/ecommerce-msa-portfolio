import { useEffect, useState } from "react";
import { putRequest, patchRequest, deleteRequest } from "../../util/api";
import toast from "react-hot-toast";
import { parseServerDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 관리자 상품 목록 — 수정·품절·삭제.
 *
 * 지금까지 상품은 만들 수만 있었다. 오타 하나 고치는 것도 DB 직접 수정이었다.
 * 세 액션 전부 서버에서 ROLE_ADMIN 을 요구한다(AdminWriteInterceptor).
 *
 * 삭제는 소프트다 — 행은 남고 모든 조회에서 사라진다. **화면에서도 서버에서도
 * 되돌릴 수 없으므로**(V8) confirm 문구가 그 말을 그대로 한다.
 */
// 이름표는 키로 둔다 — 모듈이 읽힐 때 번역하면 그때의 언어로 굳는다. 그릴 때 t 로 꺼낸다.
const STATUS_BADGE = {
    ACTIVE: null,
    SOLD_OUT: { labelKey: "admin.products.badge.sold_out", cls: "bg-amber-100 text-amber-700" },
    DELETED: { labelKey: "admin.products.badge.deleted", cls: "bg-red-100 text-red-700" },
};

const AllProduct = ({ products: initial }) => {
    const { t } = useTranslation();
    const [products, setProducts] = useState(initial ?? []);

    /**
     * 부모가 더 불러온 상품을 받아들인다.
     *
     * <p>목록이 무한 스크롤로 바뀌면서 `initial` 이 <b>시간이 지나며 늘어난다</b>.
     * `useState(initial)` 는 첫 렌더의 값만 쓰므로, 동기화하지 않으면 2페이지부터가
     * 화면에 영원히 나타나지 않는다.
     *
     * <p>통째로 갈아끼우지 않고 <b>합친다</b>. 이 컴포넌트는 수정·품절·삭제 결과를
     * 로컬 상태에 반영하는데(replaceProduct), 그냥 덮으면 방금 고친 내용이 서버에서
     * 다시 받아 온 옛 값으로 되돌아간다. 이미 아는 id 는 내 것을 남기고 새 id 만 붙인다.
     */
    useEffect(() => {
        if (!Array.isArray(initial)) return;
        setProducts((current) => {
            const known = new Set(current.map((p) => p.id));
            const added = initial.filter((p) => !known.has(p.id));
            return added.length === 0 ? current : [...current, ...added];
        });
    }, [initial]);
    const [editing, setEditing] = useState(null); // { id, name, brand, description, shippingFee }
    const [busyId, setBusyId] = useState(null);

    const replaceProduct = (updated) =>
        setProducts((list) => list.map((p) => (p.id === updated.id ? { ...p, ...updated } : p)));

    const errText = (error) =>
        error?.response?.data?.message || (error?.response ? `HTTP ${error.response.status}` : error.message);

    const saveEdit = async () => {
        setBusyId(editing.id);
        try {
            const { data } = await putRequest(
                `/catalog-service/api/product/${editing.id}`,
                {
                    name: editing.name,
                    brand: editing.brand,
                    description: editing.description,
                    shippingFee: editing.shippingFee,
                }
            );
            replaceProduct(data);
            setEditing(null);
            toast.success(t("admin.products.updated"));
        } catch (error) {
            toast.error(errText(error));
        } finally {
            setBusyId(null);
        }
    };

    const toggleSoldOut = async (product) => {
        const next = product.status === "SOLD_OUT" ? "ACTIVE" : "SOLD_OUT";
        setBusyId(product.id);
        try {
            const { data } = await patchRequest(
                `/catalog-service/api/product/${product.id}/status?status=${next}`,
                null
            );
            replaceProduct(data);
            toast.success(next === "SOLD_OUT" ? t("admin.products.marked_sold_out") : t("admin.products.resumed"));
        } catch (error) {
            toast.error(errText(error));
        } finally {
            setBusyId(null);
        }
    };

    const removeProduct = async (product) => {
        // eslint-disable-next-line no-restricted-globals
        if (!window.confirm(t("admin.products.delete_confirm", { name: product.name }))) {
            return;
        }
        setBusyId(product.id);
        try {
            await deleteRequest(`/catalog-service/api/product/${product.id}`);
            setProducts((list) => list.filter((p) => p.id !== product.id));
            toast.success(t("admin.products.deleted"));
        } catch (error) {
            toast.error(errText(error));
        } finally {
            setBusyId(null);
        }
    };

    return (
        <div className="my-4 ">
            <div className="flex p-2 border-b pb-1 font-semibold">
                {t("admin.products.title")}
            </div>
            <div className="flex flex-wrap">
                {products.map((product) => {
                    const created = parseServerDate(product.createdAt);
                    const badge = STATUS_BADGE[product.status];
                    const busy = busyId === product.id;
                    return (
                        <div
                            className="flex flex-col w-56 m-2 p-2 border rounded hover:shadow hover:bg-slate-100 transition"
                            key={product.id}
                        >
                            <div className="relative w-52 h-72">
                                {badge && (
                                    <span data-testid={`badge-${product.id}`}
                                        className={`absolute top-1 left-1 px-2 py-0.5 rounded text-xs font-semibold ${badge.cls}`}>
                                        {t(badge.labelKey)}
                                    </span>
                                )}
                                <img
                                    className="object-contained"
                                    src={product.skus?.[0]?.images?.[0]}
                                    alt={product.name}
                                />
                            </div>
                            <a href={`/product/${product.slug}`} target="_blank" rel="noreferrer">
                                <h3 className="text-sm font-semibold">{product.name}</h3>
                            </a>
                            <div className="mt-2 text-xs flex flex-col ">
                                <div>{t("admin.products.sku_line", { sku: product.skus?.[0]?.sku ?? "" })}</div>
                                <div>{t("admin.products.category_line", { name: product.category?.name ?? "" })}</div>
                                {created && (
                                    <div>{t("admin.products.created_line", {
                                        date: `${created.getFullYear()}-${created.getMonth() + 1}-${created.getDate()}`,
                                    })}</div>
                                )}
                            </div>
                            <div className="mt-2 flex space-x-1 text-xs">
                                <button
                                    type="button"
                                    disabled={busy}
                                    data-testid={`edit-${product.id}`}
                                    className="flex-1 py-1 border rounded hover:bg-white"
                                    onClick={() => setEditing({
                                        id: product.id,
                                        name: product.name ?? "",
                                        brand: product.brand ?? "",
                                        description: product.description ?? "",
                                        shippingFee: product.shipping ?? "",
                                    })}
                                >
                                    {t("admin.products.edit")}
                                </button>
                                <button
                                    type="button"
                                    disabled={busy}
                                    data-testid={`soldout-${product.id}`}
                                    className="flex-1 py-1 border rounded hover:bg-white"
                                    onClick={() => toggleSoldOut(product)}
                                >
                                    {product.status === "SOLD_OUT" ? t("admin.products.resume") : t("admin.products.sold_out")}
                                </button>
                                <button
                                    type="button"
                                    disabled={busy}
                                    data-testid={`delete-${product.id}`}
                                    className="flex-1 py-1 border rounded text-red-600 hover:bg-white"
                                    onClick={() => removeProduct(product)}
                                >
                                    {t("admin.products.delete")}
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>

            {editing && (
                <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
                    <div className="bg-white rounded p-4 w-96 space-y-2">
                        <h3 className="font-semibold">{t("admin.products.edit_title")}</h3>
                        <p className="text-xs text-slate-500">
                            {t("admin.products.slug_note")}
                        </p>
                        {[
                            ["name", "admin.products.field.name"],
                            ["brand", "admin.products.field.brand"],
                            ["shippingFee", "admin.products.field.shipping_fee"],
                        ].map(([field, labelKey]) => (
                            <label key={field} className="block text-sm">
                                {t(labelKey)}
                                <input
                                    className="mt-1 w-full border rounded px-2 py-1"
                                    data-testid={`edit-field-${field}`}
                                    value={editing[field]}
                                    onChange={(e) => setEditing({ ...editing, [field]: e.target.value })}
                                />
                            </label>
                        ))}
                        <label className="block text-sm">
                            {t("admin.products.field.description")}
                            <textarea
                                className="mt-1 w-full border rounded px-2 py-1"
                                rows={3}
                                value={editing.description}
                                onChange={(e) => setEditing({ ...editing, description: e.target.value })}
                            />
                        </label>
                        <div className="flex justify-end space-x-2 pt-2">
                            <button type="button" className="px-3 py-1 border rounded"
                                onClick={() => setEditing(null)}>{t("common.cancel")}</button>
                            <button type="button" data-testid="edit-save"
                                className="px-3 py-1 rounded bg-green-500 text-white disabled:bg-slate-300"
                                disabled={busyId === editing.id}
                                onClick={saveEdit}>{t("common.save")}</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AllProduct;
