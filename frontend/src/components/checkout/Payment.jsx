import { useTranslation } from "i18n/I18nProvider";
import { paymentMethods, paymentMethodDescription, paymentMethodName } from "./paymentMethods";
//import paypal from "assets/images/paypal.png"; 
//import credit_card from "assets/images/credit_card.png"; 
//import cash from "assets/images/cash.png"; 

/**
 * ⏸ 포트원(KG이니시스 일본결제)은 고를 수 없게 숨겨 둔다 — 2026-10-25 보류.
 *
 * <p>이유: 운영에서 결제창을 열면 이니시스 테스트 인증 서버(`devcbt.inicis.com/cbtauth`)로 넘어간 뒤 연결 시간 초과로 화면이
 * 깨진다. 한국 PC · 일본 OCI 두 곳 모두 `curl` 이 15초 안에 연결하지 못해 우리 쪽이 아니라 이니시스 쪽(서버 상태 또는 접속 허용 IP)으로
 * 보고 포트원에 문의 중이다. 요청 자체(이름 · 전화 · 상점 정보 · JPY · 리디렉션)는 포트원 문서의 예시와 같은 모양이고 포트원이 받아들였다
 * (docs/49 §6). 답이 와서 결제창이 열리면 아래 `HIDDEN_METHODS` 에서 "portone" 을 빼면 된다 — 결제 코드(order/Payment.jsx ·
 * PortOneReturn · 서버의 확인 · 웹훅 · 환불)는 그대로 두었다. 이미 포트원으로 만든 주문의 결제 화면도 그대로다.
 *
 * <p>[JA] PortOne(KG イニシス日本決済)は選べないよう隠す — 2026-10-25 保留。決済画面がイニシスのテスト認証サーバーへ移った後に
 * 接続タイムアウトになる(韓国 PC · 日本 OCI とも curl 15 秒で接続不可 — イニシス側とみて PortOne に問い合わせ中)。再開するときは
 * HIDDEN_METHODS から "portone" を外す。決済コード(注文画面 · サーバーの確認 · Webhook · 返金)はそのまま。
 */
export const HIDDEN_METHODS = ["portone"];

const PaymentCheckout = ({ paymentMethod, setPaymentMethod, profile }) => {
    const { t } = useTranslation();
    const selectable = paymentMethods.filter((m) => !HIDDEN_METHODS.includes(m.id));

    return (
        <>
            {!profile && (
                <h3 className=" pb-2 mb-4 border-b border-b-2  text-xl font-semibold">
                    {t("checkout.payment.title")}
                </h3>
            )}
            <div>
                {selectable.map((payment) => (
                    
                    <div
                        key={payment.id}
                        className={`cursor-pointer p-2 my-2 flex items-center rounded-xl ${
                            paymentMethod == payment.id && "bg-slate-200"
                        } hover:bg-slate-200 transition`}
                        onClick={() => setPaymentMethod(payment.id)}
                    >
                        <label htmlFor={payment.id} className="">
                            <input
                                type="radio"
                                name="payment"
                                id={payment.id}
                                readOnly
                                checked={paymentMethod == payment.id}
                            />
                        </label>
                        <div className="flex items-center disabled ">                            
                            {/* public/ 폴더의 내용은 **루트(/)** 로 서빙된다. 경로에 /public
                                을 넣으면 /public/assets/... 를 찾아 404 가 나고 결제수단
                                아이콘이 전부 깨진다. order/Payment.jsx 는 같은 이미지를
                                /assets/images/... 로 올바르게 참조하고 있었다. */}
                            <img
                                src={`${process.env.PUBLIC_URL}${payment.icon}`}
                                alt={paymentMethodName(payment, t)}
                                width={40}
                                height={40}
                                className="mx-3"
                            />
                            <div className="flex flex-col">
                                <span className="font-semibold">
                                    {paymentMethodName(payment, t)}
                                </span>
                                <p className="text-sm text-slate-600">
                                    {paymentMethodDescription(payment, t)}
                                </p>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </>
    );
};

export default PaymentCheckout;
