import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

const MaterialsFilter = ({ materials, materialHandler, replaceQuery }) => {
    const { t } = useTranslation();
    const [show, setShow] = useState(true);
    return (
        <div className="w-full">
            <h3
                onClick={() => setShow((prev) => !prev)}
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.materials")}
                <span>
                    {show ? (
                        <MinusIcon className="w-5 h-5" />
                    ) : (
                        <PlusIcon className="w-5 h-5" />
                    )}
                </span>
            </h3>
            {show && (
                <div className="grid grid-cols-2 gap-1">
                    {materials.map((material, i) => {
                        const check = replaceQuery("material", material);
                        return (
                            <label
                            key={i}
                                onClick={() => materialHandler(check.result)}
                                className="flex items-center cursor-pointer"
                                htmlFor={material}
                            >
                                <input
                                    className="cursor-pointer  mr-2 w-4 h-4"
                                    type="checkbox"
                                    name="material"
                                    id={material}
                                    checked={check.active}
                                />
                                {material.length > 8
                                    ? `${material.substring(0, 8)}...`
                                    : material}
                            </label>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default MaterialsFilter;
