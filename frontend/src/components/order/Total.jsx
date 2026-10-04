import { useTranslation } from "i18n/I18nProvider";
import { useMoney } from "hook/useMoney";

/**
 * 주문 상세의 금액 세 줄 — 상점 통화다.
 *
 * <p>서버의 {@code total · totalBeforeDiscount · taxPrice} 는 V7 부터 소수 둘째 자리까지
 * 온다($59.98 이 예전에는 59 로 왔다). 통화 기호는 하드코딩하지 않는다 — 상점 통화는
 * 빌드 인자({@code REACT_APP_STORE_CURRENCY})고, {@code Intl} 이 자릿수와 기호를 안다.
 *
 * <p>여기는 <b>상점 통화</b>다. 제공자에게 실제 청구한 통화·금액(KRW·JPY)은 결제 화면과
 * 환불 버튼이 {@code chargeOf(order)} 로 따로 보여 준다.
 */

const Total = ({ order }) => {
    const { t } = useTranslation();
    const money = useMoney();
    const discount = Number(order.totalBeforeDiscount ?? 0) - Number(order.total ?? 0);

    return (
        <div className="mt-4 space-y-2 ">
            <div className="flex items-center  justify-between font-semibold text-slate-800">
                <span>{t("order.total.subtotal")}</span>
                <span>{money(order.totalBeforeDiscount)}</span>
            </div>
            {order.couponApplied && (
                <div className="flex items-center justify-between font-semibold text-slate-800">
                    <span>
                        {t("order.total.coupon")}{" "}
                        <em className="text-green-600">
                            ({order.couponApplied})
                        </em>
                    </span>
                    <span>
                        - {money(discount)}
                    </span>
                </div>
            )}
            <div className="flex items-center justify-between font-semibold text-slate-800">
                <span>{t("order.total.tax")}</span>
                {/* 가격은 세금 포함이다. 예전에는 서버에 없는 order.tax 를 보고 늘 "무료" 라고 적었다(docs/62 §18). */}
                <span>{t("order.total.included")}</span>
            </div>
            <div className="flex items-center justify-between font-bold text-slate-800 border-t pt-2">
                <span>{t("order.total.to_pay")}</span>
                <span> {money(order.total)}</span>
            </div>
            {Number(order.refundedAmount ?? 0) > 0 && (
                <div className="flex items-center justify-between font-semibold text-red-600">
                    <span>{t("order.total.refunded")}</span>
                    <span>- {money(order.refundedAmount)}</span>
                </div>
            )}
        </div>
    );
};

export default Total;
