import ShippingPage from "components/checkout/ShippingPage";
import { useTranslation } from "i18n/I18nProvider";
import Layout from "components/profile/Layout";
import { useState } from "react";
import { useLoaderData } from "react-router-dom";
import { useAuthContext } from "store/AuthContext";
import DotLoaderSpinner from "components/loader/Loading";
import api, { getRequest } from "util/api";
import { useFetchAddresses } from "hook/hooks";
import { displayName } from "util/userDisplay";

const Address = () => {
    const { t } = useTranslation();

    const { tab } = useLoaderData();
    const { user } = useAuthContext();

    //const [addresses, setAddresses] = useState([]);
    //const { addressesData, isPendingAddress, isSuccessAddress, updateFetchAddresses } = useFetchAddresses(user.userId, addresses);
    const [selectedAddress, setSelectedAddress] = useState(null);
        

    return (
        <>
            <Layout user={user} tab={tab} title={t("profile.addresses.doc_title", { name: displayName(user) })}>
                <div className="text-center">
                    <h2 className="text-4xl font-bold mb-6">{t("profile.addresses.title")}</h2>
                </div>
                {<ShippingPage user={user} 
                    setSelectedAddress={setSelectedAddress} profile={true} />}
            </Layout>
        </>
    );
};

export default Address;


export const loader = async ({ params, request }) => {

    const searchParams = new URL(request.url).searchParams;
    const tab = Number(searchParams.get('tab')) || 0;

    return {
        tab: tab,
    }

};



