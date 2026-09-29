import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useState } from "react";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 브랜드 한 칸.
 *
 * <h3>🐛 대부분의 브랜드에서 아이콘이 깨져 있었다</h3>
 *
 * <p>예전에는 조건 없이 이렇게 그렸다:
 *
 * <pre>{@code
 * <img src={`/assets/images/${brand.toLowerCase()}.png`} width={50} height={50} alt={brand} />
 * }</pre>
 *
 * <p>그런데 {@code public/assets/images/} 에 있는 로고는 <b>열 몇 개뿐</b>이고(adidas·nike
 * ·puma·zara …), 브랜드 목록은 <b>카탈로그에 실제로 등록된 값</b>이다. 파일이 없는
 * 브랜드는 404 → 브라우저가 <b>깨진 이미지 아이콘</b>을 그린다. 이름에 공백이나 대문자가
 * 섞인 브랜드는 무조건 그렇게 된다.
 *
 * <p>게다가 칸에 <b>글자가 하나도 없다.</b> alt 는 이미지가 깨졌을 때 자리를 채우라고 있는
 * 것이지만 브라우저마다 다르게 그리고, 여기서는 깨진 아이콘 옆에 작게 붙을 뿐이다 —
 * 사용자는 <b>어느 브랜드를 고르는지 알 수 없다.</b>
 *
 * <p>고친 방식: <b>이름을 먼저 보장하고</b>, 로고는 있으면 얹는다. 로고 파일을 새로 넣으면
 * 코드를 고치지 않아도 그대로 뜬다 — 알려진 브랜드 목록을 코드에 박지 않은 이유다.
 */
const BrandOption = ({ brand, active, onSelect }) => {
    // 로고가 없는 브랜드가 정상이다. 그래서 '실패'가 아니라 '없음'으로 다룬다.
    const [hasLogo, setHasLogo] = useState(true);

    return (
        <button
            type="button"
            onClick={onSelect}
            title={brand}
            className={`${active ? "border-slate-500 font-semibold" : ""} flex flex-col items-center justify-center gap-1 rounded border bg-white px-1 py-2 hover:border-slate-500`}
        >
            {hasLogo && (
                <img
                    src={`${process.env.PUBLIC_URL}/assets/images/${String(brand).toLowerCase()}.png`}
                    width={50}
                    height={50}
                    alt=""
                    // 이 한 줄이 핵심이다. 없으면 깨진 아이콘이 그대로 남는다.
                    onError={() => setHasLogo(false)}
                />
            )}
            {/* 로고가 있어도 이름을 지우지 않는다 — 로고만으로 브랜드를 알아보지 못하는
                사용자가 있고, 무엇보다 이 버튼은 필터라 무엇이 선택됐는지 읽혀야 한다. */}
            <span className="w-full truncate text-center text-xs">{brand}</span>
        </button>
    );
};

const BrandsFilter = ({ brands, brandHandler, replaceQuery }) => {
    const { t } = useTranslation();
    const [show, setShow] = useState(true);
    return (
        <div className="w-full">
            <h3
                onClick={() => setShow((prev) => !prev)}
                className={`cursor-pointer my-4 flex items-center justify-between font-semibold `}
            >
                {t("browse.filter.brands")}
                <span>
                    {show ? (
                        <MinusIcon className="w-5 h-5" />
                    ) : (
                        <PlusIcon className="w-5 h-5" />
                    )}
                </span>
            </h3>
            {show && (
                <div className="grid grid-cols-2 gap-3">
                    {brands.map((brand, i) => {
                        const check = replaceQuery("brand", brand);
                        return (
                            <BrandOption
                                key={i}
                                brand={brand}
                                active={check.active}
                                onSelect={() => brandHandler(check.result)}
                            />
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default BrandsFilter;
