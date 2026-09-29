import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

const SizesFilter = ({ sizes, sizeHandler, replaceQuery }) => {
    const { t } = useTranslation();
    const [show, setShow] = useState(true);
    return (
        <div className="w-full">
            <h3
                onClick={() => setShow((prev) => !prev)}
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.sizes")}{" "}
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
                    {sizes.map((size, i) => {
                        const check = replaceQuery("size", size);
                        return (
                            <label
                            key={i}
                                onClick={() => sizeHandler(check.result)}
                                className="flex items-center cursor-pointer text-sm"
                                htmlFor={size}
                            >
                                <input
                                    className="mr-1.5 w-4 h-4"
                                    type="checkbox"
                                    name="size"
                                    id={size}
                                    defaultChecked={check.active}
                                />
                                {size}
                            </label>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default SizesFilter;
