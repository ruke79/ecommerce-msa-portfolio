import { useTranslation } from "i18n/I18nProvider";
import  payment_methods from "assets/images/payment_methods.png";
import  buyer_protection from "assets/images/buyer_protection.png";

const PaymentMethods = () => {
    const { t } = useTranslation();

    return (
        <div className="mt-4  flex flex-col flex-grow h-fit bg-white rounded py-2 px-4 border">
            <h3 className="text-2xl my-2 font-semibold">{t("cart.payment_methods")}</h3>
            <img
                src={payment_methods}                
                alt={t("cart.payment_methods")}
                width={220}
                height={25}
            />
            <div className="my-4 w-full bg-slate-200 h-[1px]" />

            <div className="flex items-center space-x-1">
                <img
                    src={buyer_protection}
                    
                    alt={t("cart.buyer_protection")}
                    width={25}
                    height={25}
                />
                <h5 className="text-xs font-semibold">{t("cart.buyer_protection")}</h5>
            </div>
            <p className="text-sm">
                {t("cart.buyer_protection.detail")}
            </p>
        </div>
    );
};

export default PaymentMethods;
