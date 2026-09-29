import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

const ColorsFilter = ({ colors, colorHandler, replaceQuery }) => {
    const { t } = useTranslation();
    const [show, setShow] = useState(true);

    //const existedColors = router.query.color || "";

    return (
        <div className="w-full">
            <h3
                onClick={() => setShow((prev) => !prev)}
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.colors")}
                <span>
                    {show ? (
                        <MinusIcon className="w-5 h-5" />
                    ) : (
                        <PlusIcon className="w-5 h-5" />
                    )}
                </span>
            </h3>
            {show && (
                <div className="flex flex-wrap gap-3">
                    {colors.map((color, i) => {
                        const check = replaceQuery("color", color);
                        return (
                            <button
                                onClick={() =>
                                    colorHandler(check.result)
                                }
                                key={i}
                                className={`shadow w-6 h-6 rounded-full hover:outline 
                                hover:outline-2 hover:outline-offset-4 hover:outline-slate-500
                                ${check.active ? 'outline outline-[3px] outline-offset-[3px] outline-slate-500' : ''}`}
                                style={{ background: `${color}` }}
                            ></button>
                        )
                    })}
                </div>
            )}
        </div>
    );
};

export default ColorsFilter;
