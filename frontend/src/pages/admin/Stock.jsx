import { useCallback, useEffect, useState } from "react";

import { adminListStock, adminSetStock } from "api/restockApi";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 관리자 재고 — 품절 옵션을 보고 채운다 (docs/53).
 *
 * <p>품절 경보({@code ProductSoldOut}, docs/51 §4i)를 받은 운영자가 여는 화면이다. 예전에는 재고를 채울 방법이
 * 아예 없었다 — 재고는 주문으로 줄고 환불·반품 입고로만 돌아왔다.
 *
 * <p>입력한 값으로 <b>정한다</b>(더하기가 아니다). 0 에서 채우면 catalog 가 재입고를 알리고, 그 옵션에 재입고 알림을
 * 신청한 손님에게 알림이 간다. 저장 뒤에는 목록을 서버에서 다시 읽는다 — 서버가 거절했는데 화면만 바뀌지 않게.
 */
const Stock = () => {
    const { t } = useTranslation();
    const [soldOutOnly, setSoldOutOnly] = useState(true);
    const [rows, setRows] = useState([]);
    const [drafts, setDrafts] = useState({});      // sizeId → 입력 중인 수량
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setError("");
        try {
            setRows(await adminListStock({ soldOutOnly }));
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.stock.load_failed"));
        }
    }, [soldOutOnly, t]);

    useEffect(() => { load(); }, [load]);

    const save = async (row) => {
        const raw = drafts[row.sizeId];
        const quantity = Number(raw);
        if (raw === undefined || raw === "" || !Number.isInteger(quantity) || quantity < 0) {
            setError(t("admin.stock.invalid_quantity"));
            return;
        }
        if (busy) return;
        setBusy(true);
        setError("");
        setNotice("");
        try {
            const saved = await adminSetStock(row.sizeId, quantity);
            const vars = { name: row.name, option: option(row), quantity: saved.quantity };
            setNotice(row.quantity <= 0 && saved.quantity > 0
                ? t("admin.stock.restocked", vars)
                : t("admin.stock.set", vars));
            setDrafts((d) => { const next = { ...d }; delete next[row.sizeId]; return next; });
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.stock.save_failed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.stock.title")}</h1>

            {error && <p className="mb-4 text-red-600" role="alert">{error}</p>}
            {notice && <p className="mb-4 text-emerald-700" role="status">{notice}</p>}

            <div className="mb-4 flex flex-wrap items-end gap-3">
                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.stock.view")}</span>
                    <select
                        aria-label={t("admin.stock.view")}
                        value={soldOutOnly ? "soldout" : "all"}
                        onChange={(e) => setSoldOutOnly(e.target.value === "soldout")}
                        className="mt-1 block rounded border border-slate-300 p-2"
                    >
                        <option value="soldout">{t("admin.stock.view.sold_out_only")}</option>
                        <option value="all">{t("admin.stock.view.all")}</option>
                    </select>
                </label>
                <span className="pb-2 text-sm text-slate-500">{t("admin.stock.count", { count: rows.length })}</span>
            </div>

            <table className="w-full border-collapse text-sm">
                <thead>
                    <tr className="border-b bg-slate-50 text-left">
                        <th className="p-2">{t("admin.stock.col.product")}</th>
                        <th className="p-2">{t("admin.stock.col.option")}</th>
                        <th className="p-2">{t("admin.stock.col.current")}</th>
                        <th className="p-2">{t("admin.stock.col.new")}</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={4} className="p-6 text-center text-slate-500">
                            {soldOutOnly ? t("admin.stock.empty_sold_out") : t("admin.stock.empty")}
                        </td></tr>
                    )}
                    {rows.map((row) => (
                        <tr key={row.sizeId} className="border-b">
                            <td className="p-2">
                                <div>{row.name ?? "-"}</div>
                                <div className="text-xs text-slate-500">#{row.productId}</div>
                            </td>
                            <td className="p-2">
                                <div>{option(row)}</div>
                                {/* 사이즈 한 줄의 SKU — 이 행이 곧 재고 단위다(catalog V17) */}
                                {row.skuCode && <div className="text-xs font-mono text-slate-500">{row.skuCode}</div>}
                            </td>
                            <td className={`p-2 font-mono ${row.quantity <= 0 ? "text-red-600 font-semibold" : ""}`}>
                                {row.quantity <= 0 ? t("admin.stock.sold_out") : row.quantity}
                            </td>
                            <td className="p-2">
                                <div className="flex items-center gap-2">
                                    <input
                                        aria-label={t("admin.stock.new_stock_label", { name: row.name, option: option(row) })}
                                        type="number"
                                        min="0"
                                        value={drafts[row.sizeId] ?? ""}
                                        onChange={(e) => setDrafts((d) => ({ ...d, [row.sizeId]: e.target.value }))}
                                        className="w-24 rounded border border-slate-300 p-1 font-mono"
                                    />
                                    <button
                                        type="button"
                                        disabled={busy}
                                        onClick={() => save(row)}
                                        className="rounded border border-slate-600 px-3 py-1 text-slate-700 hover:bg-slate-600 hover:text-white disabled:opacity-40"
                                    >
                                        {t("common.save")}
                                    </button>
                                </div>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

const option = (row) => (row.color ? `${row.color} / ${row.size}` : row.size);

export default Stock;
