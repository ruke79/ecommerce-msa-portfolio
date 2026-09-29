import { useTranslation } from "i18n/I18nProvider";
import { useMoney } from "hook/useMoney";

const Checkout = ({ subtotal, shippingFee, total, selected, saveCartToDbHandler }) => {
    const { t } = useTranslation();
    const money = useMoney();
    // 🐛 예전에는 금액이 toFixed 의 **문자열**("0.00")로 와서 `shippingFee === 0` 이 늘 거짓이었다 — 무료 배송이
    //    "배송비 0.00$" 로 보였다. 숫자로 받는다(CartPage).
    const free = Number(shippingFee) === 0;

    return (
        <div className="flex flex-col h-fit bg-white rounded py-2 px-4 border">

            <h3 className="text-2xl my-2 font-semibold">{t("cart.summary.title")}</h3>
            <div className="my-1 flex items-center justify-between text-salte-600 text-sm">
                <span>{t("cart.summary.subtotal")}</span>
                <span>{money(subtotal)}</span>
            </div>

            <div className="my-1 flex items-center justify-between text-salte-600 text-sm">
                <span>{t("cart.summary.shipping")}</span>
                <span className={`${free ? "text-blue-500" : ""}`}>{free ? t("shipping.free") : t("shipping.fee", { amount: money(shippingFee) })}</span>
            </div>

            <div className="my-4 w-full bg-slate-200 h-[1px]" />
            <div className="my-1 flex items-center justify-between text-salte-600 font-semibold">
                <span>{t("cart.summary.total")}</span>
                <span>{money(total)}</span>
            </div>
            
            <button onClick={(e) => saveCartToDbHandler(e)} disabled={selected.length === 0} className={`${selected.length == 0 ? "bg-slate-300 text-slate-500 cursor-not-allowed" : "cursor-pointer text-amazon-blue_dark font-semibold bg-gradient-to-r from-amazon-orange to-yellow-300 text-amazon-blue_dark  hover:text-slate-100 hover:from-amazon-blue_light hover:to-slate-300"} w-full my-6 py-2 px-4  p-2 rounded-full space-x-2 transition duration-300`}>
                {t("cart.summary.continue")}
            </button>
        </div>
    );
};

export default Checkout;
