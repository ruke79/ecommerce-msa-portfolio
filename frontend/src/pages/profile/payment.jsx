import PaymentCheckout from "components/checkout/Payment";
import { useTranslation } from "i18n/I18nProvider";
import Layout from "components/profile/Layout";

import { getRequest, putRequest } from "util/api";
import { useState } from "react";
import { useLoaderData } from "react-router-dom";
import toast from "react-hot-toast";
import { useAuthContext } from "store/AuthContext";

const Payment = () => {
    const { t } = useTranslation();

    const { tab } = useLoaderData();
    const {user} = useAuthContext();

    // user.defaultPaymentMethod 는 JWT 에서 만든 user 에 없는 필드다(checkout.jsx 주석 참조).
    const [dbPM, setDbPM] = useState("");
    const [paymentMethod, setPaymentMethod] = useState("");
    const [error, setErorr] = useState("");

    const handlePM = async () => {
        try {
            const { data } = await putRequest("/user-service/api/user/profile/changepm", null, {
                params : { paymentMethod: paymentMethod }
            });
            setErorr("");
            setDbPM(data);
            
        } catch (error) {
            setErorr(error.response?.data.message);
            
        }
    };

    return (
        <>
            <Layout
                user={user}
                tab={tab}
                title={t("profile.payment.doc_title", { email: user.email })}
            >
                <div className="text-center">
                    <h2 className="text-4xl font-bold mb-6">
                        {t("profile.payment.title")}
                    </h2>
                </div>
                <PaymentCheckout
                    paymentMethod={paymentMethod}
                    setPaymentMethod={setPaymentMethod}
                    profile={true}
                />
                <button
                    disabled={!paymentMethod || paymentMethod == dbPM}
                    className={`mt-4 w-52 rounded-xl  text-white p-4 font-semibold text-2xl  ${
                        !paymentMethod || paymentMethod == dbPM
                            ? "cursor-not-allowed bg-slate-400"
                            : "cursor-pointer  bg-amazon-blue_light hover:bg-amazon-blue_dark hover:scale-95 transition"
                    }`}
                    onClick={() => handlePM()}
                >
                    {t("profile.payment.save")}
                </button>
                {error && <span className="text-red-500">{error}</span>}
            </Layout>
        </>
    );
};

export default Payment;


export const loader = async ({params, request}) => {
    
        
        const searchParams = new URL(request.url).searchParams;
        const tab = Number(searchParams.get('tab')) || 0;

        return { tab: tab };        
       
};



