import { ArrowSmallDownIcon } from "@heroicons/react/24/outline";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

/** 사이즈·스타일 목록 맨 앞의 "전체" 항목 — Table 이 넣는 코드 값이다. 화면에는 문구로 바꿔 그린다. */
const ALL = "All";

const TableSelect = ({ property, text, data, handleChange, selectedLabel, optionLabel }) => {
    const { t } = useTranslation();
    const [visible, setVisible] = useState(false);
    // text 는 "rating"·"size"·"style"·"order" — 코드 값이라 화면에는 문구로 바꿔 그린다.
    const placeholder = t(`product.review.select.${text}`);
    const sizeLabel = (size) => (size === ALL ? t("product.review.filter.all") : size);

    return (
        <div className="relative mr-1 max-md:mb-3">
            <div className="flex items-center">
                <span className="uppercase text-sm font-semibold">
                    {t(`product.review.label.${text}`)}:
                </span>
                <span
                    className="flex items-center min-w-[120px] text-sm mx-2 bg-slate-300 px-2 py-1 rounded cursor-pointer hover:bg-slate-400"
                    onClick={() => setVisible((prev) => !prev)}
                >
                    {text === "rating" || text === "order" ? (
                        (property && selectedLabel) || placeholder
                    ) : text === "size" ? (
                        (property && sizeLabel(property)) || placeholder
                    ) : text === "style" && property ? (
                        <>
                            <span
                                className="w-5 h-5 rounded-full mr-2"
                                style={{
                                    backgroundColor: `${property.color}`,
                                }}
                            ></span>
                            {property.image ? (
                                <img
                                    src={property.image}
                                    alt={property.color}
                                    width={13}
                                    height={13}
                                    className="rounded"
                                />
                            ) : (
                                t("product.review.filter.all_styles")
                            )}
                        </>
                    ) : (
                        placeholder
                    )}
                    <ArrowSmallDownIcon className="w-4 h-4 ml-auto" />
                </span>
            </div>
            {visible && (
                <ul
                    onMouseLeave={() => setVisible(false)}
                    className="flex z-50 flex-col items-center bg-white min-w-[120px] p-2 rounded border shadow-md absolute top-8 right-2"
                >
                    {data.map((item, i) => {
                        if (text === "rating") {
                            return (
                                <li
                                    onClick={() => handleChange(item.value || ALL)}
                                    className="w-full p-1 rounded hover:bg-slate-200 cursor-pointer"
                                    key={i}
                                >
                                    <span className="text-sm">{optionLabel(item)}</span>
                                </li>
                            );
                        }
                        if (text === "size") {
                            return (
                                <li
                                    onClick={() => handleChange(item.size)}
                                    className="w-full p-1 rounded hover:bg-slate-200 cursor-pointer"
                                    key={i}
                                >
                                    <span className="text-sm">{sizeLabel(item.size)}</span>
                                </li>
                            );
                        }
                        if (text === "style") {
                            return (
                                <li
                                    onClick={() => handleChange(item)}
                                    className="w-full flex items-center justify-center p-1 rounded hover:bg-slate-200 cursor-pointer"
                                    key={i}
                                >
                                    {item.color === ALL ? (
                                        ""
                                    ) : (
                                        <span
                                            className="w-7 h-7 rounded-full mr-2"
                                            style={{
                                                backgroundColor: `${item.color}`,
                                            }}
                                        ></span>
                                    )}
                                    {item.image ? (
                                        <img
                                            src={item.image}
                                            alt={item.color}
                                            width={30}
                                            height={30}
                                            className="rounded"
                                        />
                                    ) : (
                                        t("product.review.filter.all_styles")
                                    )}
                                </li>
                            );
                        }
                        if (text === "order") {
                            return (
                                <li
                                    onClick={() => handleChange(item.value)}
                                    className="w-full p-1 rounded hover:bg-slate-200 cursor-pointer"
                                    key={i}
                                >
                                    <span className="text-sm">{optionLabel(item)}</span>
                                </li>
                            );
                        }
                    })}
                </ul>
            )}
        </div>
    );
};

export default TableSelect;
