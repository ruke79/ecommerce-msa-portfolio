import { useState } from "react";
import TableSelect from "./TableSelect";
import { useTranslation } from "i18n/I18nProvider";

const TableHeader = ({ reviews, allSizes, colors }) => {
    const { t } = useTranslation();
    const [size, setSize] = useState("");
    const [style, setStyle] = useState("");
    const [fit, setFit] = useState("");
    const [review, setReview] = useState("");
    const [rating, setRating] = useState("");
    const [order, setOrder] = useState("");

    // 목록의 이름표는 키로 들고 있다가 그릴 때 번역한다 — 모듈에서 번역하면 그 순간의 언어로 굳는다.
    const optionLabel = (item) => (item ? t(item.textKey, { count: item.value }) : "");
    const ratingLabel = rating === "All"
        ? t("product.review.filter.all")
        : optionLabel(ratings.find((item) => item.value === rating));
    const orderLabel = optionLabel(orderOptions.find((item) => item.value === order));

    return (
        <div className="max-md:flex ">
            <div className="flex flex-wrap justify-end my-2">
                <TableSelect
                    property={rating}
                    text="rating"
                    selectedLabel={ratingLabel}
                    optionLabel={optionLabel}
                    data={ratings.filter((item) => item.value !== rating)}
                    handleChange={setRating}
                />
                {/* 사이즈 없는 상품이면 "All" 하나뿐이다 — 거를 것이 없다. */}
                {allSizes.some((item) => item.size && item.size !== "All") && (
                <TableSelect
                    property={size}
                    text="size"
                    data={allSizes.filter((item) => item.size !== size)}
                    handleChange={setSize}
                />
                )}
                <TableSelect
                    property={style}
                    text="style"
                    data={colors.filter((item) => item !== style)}
                    handleChange={setStyle}
                />
                <TableSelect
                    property={order}
                    text="order"
                    selectedLabel={orderLabel}
                    optionLabel={optionLabel}
                    data={orderOptions.filter(
                        (item) => item.value !== order
                    )}
                    handleChange={setOrder}
                />
            </div>
            <div className="md:hidden w-full"></div>
        </div>
    );
};

export default TableHeader;

// value 는 걸러 보기 상태의 코드 값이라 번역하지 않는다 — 이름표만 textKey 로 번역한다.
const ratings = [
    { textKey: "product.review.filter.all", value: "" },
    { textKey: "product.review.filter.stars", value: 5 },
    { textKey: "product.review.filter.stars", value: 4 },
    { textKey: "product.review.filter.stars", value: 3 },
    { textKey: "product.review.filter.stars", value: 2 },
    { textKey: "product.review.filter.stars", value: 1 },
];

const orderOptions = [
    { textKey: "product.review.filter.all", value: "All" },
    { textKey: "product.review.order.recommended", value: "Recomended" },
    { textKey: "product.review.order.newest", value: "Most Recent to Oldest" },
    { textKey: "product.review.order.oldest", value: "Oldest to Most Recent" },
];
