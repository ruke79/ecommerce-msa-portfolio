import { MapPinIcon } from "@heroicons/react/24/outline";
import { useTranslation } from "i18n/I18nProvider";

const DeliveryTo = () => {
    const { t } = useTranslation();
    return ( 
        <div className="hidden md:inline md:flex items-center link">
            <MapPinIcon className="h-5 mt-3" />
            <div className="ml-1">
                <p className="text-xs text-slate-300">{t("header.deliver_to")}</p>
                <p className="flex font-bold text-sm">{t("header.deliver_country")}</p>
            </div>
        </div>
     );
}
 
export default DeliveryTo;