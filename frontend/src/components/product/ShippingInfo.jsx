import { LockClosedIcon } from "@heroicons/react/24/outline";
import { ChevronDownIcon } from "@heroicons/react/24/solid";
import { BRAND_NAME } from "constants/brand";
import { useTranslation } from "i18n/I18nProvider";
import { useMoney } from "hook/useMoney";

/**
 * 배송·재고 안내.
 *
 * <p>🐛 <b>여기에 사실이 아닌 정보가 박혀 있었다.</b> Amazon 클론에서 딸려 온 것들이다:
 *
 * <ul>
 *   <li>{@code Delivery <b>Thursday, March 23</b>. Order within 23 hrs 53 mins}
 *       — 고정된 날짜와 가짜 카운트다운. 이 저장소에는 배송일을 계산하는 코드가 없다.</li>
 *   <li>{@code Deliver to Germany} — 이 서비스는 한국·일본으로 배송한다.</li>
 *   <li>{@code Sold by ATUAT} — 존재하지 않는 판매자 이름.</li>
 * </ul>
 *
 * <p>번역하지 않고 <b>지웠다.</b> 거짓 정보를 일본어로도 보여 줄 이유가 없고, 없는 것보다
 * 나쁘다 — 사용자는 그 날짜를 믿고 주문한다. 배송일 추정이 생기면 그때 실제 값으로 되살린다.
 */
const ShippingInfo = ({ product }) => {
    const { t } = useTranslation();
    const money = useMoney();

    return (
        <div className="max-h-96 rounded-lg border border-gray-300 p-3 flex flex-col row-span-2 md:col-span-2">
            <div className="mb-3 font-semibold">{money(product.price)}</div>

            <div className="text-slate-600 text-sm">
                <p>{t("shipping.no_import_fees")}</p>
                <div className="flex">
                    <p className="my-1 text-blue-500">
                        {product.shipping
                            ? t("shipping.fee", { amount: money(product.shipping) })
                            : t("shipping.free")}
                    </p>
                    <div className="ml-2 flex items-center text-blue-500 cursor-pointer hover:text-amazon-orange">
                        {t("product.details")}
                        <ChevronDownIcon className="w-4 h-4 ml-1" />
                    </div>
                </div>
            </div>

            <div className="my-2">
                <p className="font-md font-semibold text-green-700">
                    {/* 🐛 예전에는 `> 1` 이라 마지막 1개가 "품절" 로 보였다. */}
                    {/* 관리자가 품절로 내린 상품은 수량이 남아 있어도 품절이다. */}
                    {product.quantity > 0 && product.status !== "SOLD_OUT" ? t("shipping.in_stock") : t("shipping.sold_out")}
                </p>
            </div>

            {/* <button className="py-2 px-4 bg-amazon-orange text-black rounded-full shadow">
                Add to Cart
            </button> */}

            <div className="flex items-center mt-2 cursor-pointer">
                <LockClosedIcon className="h-4 mr-1" />
                <p className="text-blue-600 hover:text-amazon-orange">
                    {t("shipping.secure")}
                </p>
            </div>

            <table className="my-3 text-sm whitespace-nowrap grid">
                <tbody>
                    {/* "Sold by ATUAT" 는 지웠다 — 존재하지 않는 판매자였다.
                        판매도 발송도 이 서비스가 한다. */}
                    <tr className="grid grid-cols-3">
                        <td className="text-slate-500">{t("shipping.ships_from")}</td>
                        <td className="col-span-2">{BRAND_NAME}</td>
                    </tr>
                    <tr className="grid grid-cols-3">
                        <td className="text-slate-500">{t("shipping.returns")}</td>
                        <td className="col-span-2 truncate">
                            {t("shipping.returns_policy")}
                        </td>
                    </tr>
                </tbody>
            </table>

            {/* <div className="flex w-full bg-slate-200 h-0.5" />

            <div>add to list</div> */}
        </div>
    );
};

export default ShippingInfo;
