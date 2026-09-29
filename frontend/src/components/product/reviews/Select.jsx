import { ArrowSmallDownIcon } from "@heroicons/react/24/outline";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 착용감 값 → 문구 키. 값("Small" 등)은 서버에 저장되는 코드라 번역하지 않고, 화면에 그릴 때만 바꾼다.
 * 리뷰 카드도 이 표로 그린다 — 모르는 값(예전에 저장된 다른 값)은 그대로 보여 준다.
 */
export const FIT_LABEL_KEYS = {
    Small: "product.review.fit.small",
    "True to size": "product.review.fit.true_to_size",
    Large: "product.review.fit.large",
};

export const fitLabel = (t, fit) => (FIT_LABEL_KEYS[fit] ? t(FIT_LABEL_KEYS[fit]) : fit);

/** 스타일 한 칸의 그림 — 이미지가 있으면 이미지, 없으면 색 동그라미. 빈 src 는 깨진 아이콘이 된다. */
const StyleSwatch = ({ style, size = 20 }) => {
    const image = style?.image ?? style?.colorImage;
    if (image) {
        return <img src={image} alt={style?.color ?? ""} width={size} height={size} className="rounded-full object-cover" />;
    }
    return (
        <span
            className="inline-block rounded-full border border-slate-300"
            style={{ width: size, height: size, backgroundColor: style?.color }}
        />
    );
};

/**
 * 리뷰 작성의 선택 칸(사이즈·스타일·착용감).
 *
 * <p>🐛 예전 모양의 문제:
 * <ul>
 *   <li>골라도 목록이 닫히지 않았다 — 마우스가 목록을 벗어날 때만 닫혀서, 휴대폰(마우스 없음)에서는 영영 열려 있었다.
 *       고르면 닫고, 바깥을 누르면 닫는다.</li>
 *   <li>목록이 칸 오른쪽 끝에 붙어(right-2) 칸과 어긋났다 — 칸 바로 아래 왼쪽 정렬로.</li>
 *   <li>스타일이 {@code item.image} 를 그렸는데 상품의 색상 정보에는 그 필드가 없다({@code colorImage}) — 깨진 그림이었다.</li>
 *   <li>사이즈가 많으면 목록이 화면 밖으로 길어졌다 — 높이를 제한하고 스크롤.</li>
 * </ul>
 */
const Select = ({ property, text, data, handleChange }) => {
    const { t } = useTranslation();
    const [visible, setVisible] = useState(false);
    const box = useRef(null);
    // text 는 "size"·"style"·"fit" — 코드 값이라 화면에는 문구로 바꿔 그린다.
    const placeholder = t(`product.review.select.${text}`);

    useEffect(() => {
        if (!visible) return undefined;
        const close = (e) => {
            if (box.current && !box.current.contains(e.target)) setVisible(false);
        };
        document.addEventListener("mousedown", close);
        return () => document.removeEventListener("mousedown", close);
    }, [visible]);

    const choose = (value) => {
        handleChange(value);
        setVisible(false);
    };

    const current = !property ? (
        <span className="text-slate-600">{placeholder}</span>
    ) : text === "size" ? (
        property
    ) : text === "style" ? (
        <span className="flex items-center gap-2">
            <StyleSwatch style={property} />
            <span className="truncate">{property.color}</span>
        </span>
    ) : (
        fitLabel(t, property)
    );

    return (
        <div className="relative mr-3 mb-2" ref={box}>
            <div className="flex items-center">
                <span className="text-sm font-semibold whitespace-nowrap">
                    {t(`product.review.label.${text}`)}
                </span>
                <button
                    type="button"
                    aria-haspopup="listbox"
                    aria-expanded={visible}
                    data-testid={`review-select-${text}`}
                    className="flex items-center gap-2 min-w-[140px] max-w-[220px] text-sm mx-2 bg-white border border-slate-300 px-2 py-1 rounded hover:border-slate-500"
                    onClick={() => setVisible((prev) => !prev)}
                >
                    <span className="flex-1 text-left truncate">{current}</span>
                    <ArrowSmallDownIcon className="w-4 h-4 shrink-0" />
                </button>
            </div>
            {visible && (
                <ul
                    role="listbox"
                    className="absolute left-0 top-full mt-1 z-50 flex flex-col bg-white min-w-[160px] max-h-60 overflow-auto p-1 rounded border shadow-md"
                >
                    {data.length === 0 && (
                        <li className="p-2 text-sm text-slate-500">{t("product.review.select.empty")}</li>
                    )}
                    {data.map((item, i) => (
                        <li
                            key={i}
                            role="option"
                            aria-selected={false}
                            // 사이즈는 이름만 값으로(서버가 문자열로 받는다), 스타일·착용감은 항목 그대로
                            onClick={() => choose(text === "size" ? item.size : item)}
                            className="flex items-center gap-2 w-full px-2 py-1.5 rounded text-sm hover:bg-slate-100 cursor-pointer"
                        >
                            {text === "size" && item.size}
                            {text === "style" && (
                                <>
                                    <StyleSwatch style={item} size={24} />
                                    <span className="truncate">{item.color}</span>
                                </>
                            )}
                            {text === "fit" && fitLabel(t, item)}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
};

export default Select;
