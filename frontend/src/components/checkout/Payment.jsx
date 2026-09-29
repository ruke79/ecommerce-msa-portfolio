import { useTranslation } from "i18n/I18nProvider";
import { paymentMethods, paymentMethodDescription, paymentMethodName } from "./paymentMethods";
//import paypal from "assets/images/paypal.png"; 
//import credit_card from "assets/images/credit_card.png"; 
//import cash from "assets/images/cash.png"; 

const PaymentCheckout = ({ paymentMethod, setPaymentMethod, profile }) => {
    const { t } = useTranslation();

    return (
        <>
            {!profile && (
                <h3 className=" pb-2 mb-4 border-b border-b-2  text-xl font-semibold">
                    {t("checkout.payment.title")}
                </h3>
            )}
            <div>
                {paymentMethods.map((payment) => (
                    
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
