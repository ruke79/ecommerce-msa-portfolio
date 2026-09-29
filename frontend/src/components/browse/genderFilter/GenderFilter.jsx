import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

/** 값은 서버로 가는 질의 값이라 번역하지 않는다 — 이름표만 키로 번역한다. */
const GENDER_LABEL_KEYS = {
    Men: "browse.filter.gender.men",
    Women: "browse.filter.gender.women",
    Unisex: "browse.filter.gender.unisex",
};

const GenderFilter = ({ genderHandler, replaceQuery }) => {
    const { t } = useTranslation();
    const genders = Object.keys(GENDER_LABEL_KEYS);
    const [show, setShow] = useState(true);
    return (
        <div className="w-full">
            <h3
                onClick={() => setShow((prev) => !prev)}
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.gender")}
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
                    {genders.map((gender, i) => {
                        const check = replaceQuery("gender", gender);
                        return (
                            <label 
                            key={i}
                                onClick={() => genderHandler(check.result)}
                                className="flex items-center cursor-pointer"
                                htmlFor={gender}
                            >
                                <input
                                    className="cursor-pointer  mr-2 w-4 h-4"
                                    type="checkbox"
                                    name="gender"
                                    id={gender}
                                    defaultChecked={check.active}
                                />
                                {t(GENDER_LABEL_KEYS[gender])}
                            </label>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default GenderFilter;
