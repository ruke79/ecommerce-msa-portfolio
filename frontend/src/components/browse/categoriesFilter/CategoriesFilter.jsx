import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";
import ParentCategory from "./ParentCategory";

const CategoriesFilter = ({
    categories,
    subCategories,
    categoryHandler,
    subCategoryHandler,
    replaceQuery
}) => {
    const { t } = useTranslation();
    const [show, setShow] = useState(true);
    return (
        <div className="w-full">
            <h3
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.category")}
                <span>
                    {show ? (
                        <MinusIcon
                            className="w-5 h-5"
                            onClick={() => setShow((prev) => !prev)}
                        />
                    ) : (
                        <PlusIcon
                            className="w-5 h-5"
                            onClick={() => setShow((prev) => !prev)}
                        />
                    )}
                </span>
            </h3>
            {show &&
                categories.map((c) => (
                    <ParentCategory
                        key={c.id}
                        category={c}
                        subCategories={subCategories}
                        categoryHandler={categoryHandler}
                        subCategoryHandler={subCategoryHandler}
                        replaceQuery={replaceQuery}
                    />
                ))}
        </div>
    );
};

export default CategoriesFilter;
