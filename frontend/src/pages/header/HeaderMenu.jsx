import {
    Bars3Icon,
    MapPinIcon,
} from "@heroicons/react/24/outline";
import {Link} from "react-router-dom"
import { useTranslation } from "i18n/I18nProvider";

const HeaderMenu = ({ handleOpenMenu }) => {
    const { t } = useTranslation();
    return ( 
        <>
        <div className="bg-amazon-blue_dark md:bg-amazon-blue_light flex items-center py-2 px-4 md:space-x-4 text-white max-md:-mt-1 max-md:pb-4">
            <div
                onClick={handleOpenMenu}
                className="hidden md:flex items-center cursor-pointer mr-2 text-lg"
            >
                <Bars3Icon className="h-7 mr-1" />
                <span className="font-bold text-sm">{t("header.menu.all")}</span>
            </div>
            <div className="flex flex-grow max-md:overflow-x-scroll scrollbar-hide text-sm whitespace-nowrap ">
                <ul className="flex space-x-4">
                    <li className=""><Link to="">{t("header.menu.deals")}</Link></li>
                    <li className=""><Link to="">{t("header.menu.customer_service")}</Link></li>
                    <li className=""><Link to="">{t("header.menu.registry")}</Link></li>
                    <li className=""><Link to="">{t("header.menu.gift_cards")}</Link></li>
                    <li className=""><Link to="">{t("header.menu.electronics")}</Link></li>
                </ul>
            </div>
            <div className="hidden md:inline text-sm">
                <a href="/">{t("header.menu.electronics_deals")}</a>
            </div>
        </div>

        <div className="flex md:hidden items-center p-2 bg-amazon-blue_light text-slate-200 max-md:-mt-1">
            <MapPinIcon className="h-6 mr-1" />
            <span className="text-sm">{t("header.deliver_to_country")}</span>
        </div>
    </>
    );
}
 
export default HeaderMenu;