import { useEffect, useState } from "react";
import { compareArrays } from "util/array_utils";
import { useTranslation } from "i18n/I18nProvider";

const CartHeader = ({ cartItems, selected, setSelected }) => {
    const { t } = useTranslation();
    const [allActive, setAllActive] = useState(false);

    useEffect(() => {
        const check = compareArrays(cartItems, selected);
        setAllActive(check);
    },[selected]);

    const handleSelect = (e) => {
        e.preventDefault();
        if(selected.length !== cartItems.length) {
            setSelected(cartItems);
        } else {
            setSelected([])
        }
    }

    return (
        <div className="mb-4 md:col-span-5 bg-white rounded py-2 px-4 border">
            <h2 className="font-bold text-3xl my-2">
                {t("cart.item_summary", { count: selected.length })}
            </h2>
            <div className="w-full bg-slate-200 h-[1px]" />
            <div className="mt-3 flex items-center space-x-3">
                <input
                    type="checkbox"
                    name="product"
                    className="w-5 h-5 cursor-pointer"
                    checked={allActive}
                    onChange={(e) => handleSelect(e)}
                />
                <span>{t("cart.select_items")}</span>
            </div>
        </div>
    );
};

export default CartHeader;
