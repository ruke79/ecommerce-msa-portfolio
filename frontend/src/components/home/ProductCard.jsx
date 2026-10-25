import { Link } from "react-router-dom"
import { useEffect, useState } from "react";
import ProductSwiper from "./ProductSwiper";
import { useMoney } from "hook/useMoney";
import { skuIndexInPriceRange, skuPriceRange } from "util/pricing";
import { rangeBeforeText, rangeText } from "util/priceText";
import { stockStateOf } from "util/stock";
import { useTranslation } from "i18n/I18nProvider";

// 재고 배지 — 품절은 회색, 결제 중은 호박색(곧 돌아올 수 있다), 얼마 안 남음은 빨간 글자(docs/59 §5).
const STOCK_BADGE = {
    sold_out: "bg-slate-600 text-white",
    on_hold: "bg-amber-100 text-amber-900 border border-amber-300",
    low: "text-red-600 font-semibold",
};

// 색상(sku)마다 가격이 다를 수 있어 활성 sku 기준으로 다시 계산한다.
// 사이즈가 비어 있으면 null — 색상 중 하나만 사이즈가 비어 있어도 TypeError 가 나면 React 가 렌더 트리를
// 버려 **홈 전체가 흰 화면**이 된다.
//
// 🔴 **할인이 반영된** 가격이다(skuPriceRange). 예전에는 사이즈의 정가를 그대로 보여 줘서, 검색·홈·기획전의
// 카드는 $100, 상품 화면은 $70 이었다 — 카드에 "-30%" 배지를 단 채로. 서버는 두 화면에 같은 값을 보낸다.
const pricesOf = (sku) => skuPriceRange(sku);

/**
 * ⚠️ 목록 응답(검색 · 첫 화면 · 기획전)은 카드가 읽는 필드만 온다 — 서버의 허용 목록(catalog `ProductCardResponses`):
 *    id · name · slug · status · skus(id · images · discount · color · sizes 의 size · price · quantity · onHold).
 *    여기서 새 필드를 읽으려면 그 목록에도 더한다(설명 · 분류 · 별점 등은 상품 상세에만 있다).
 *
 * @param priceFilter 검색의 가격대 `{ low, high }`(비면 열림). 있으면 그 범위에 드는 스타일부터 보여 준다 — 필터는 어느 스타일이든
 *                    맞으면 상품을 주는데, 첫 스타일만 보여 주면 "$50~$100" 결과에 "$15" 카드가 섞여 보였다.
 */
const ProductCard = ({ product, priceFilter }) => {
    const money = useMoney();
    const { t } = useTranslation();

    const matched = priceFilter ? skuIndexInPriceRange(product.skus, priceFilter.low, priceFilter.high) : -1;
    const [active, setActive] = useState(matched >= 0 ? matched : 0);
    const [images, setImages] = useState(product.skus[active]?.images);
    const [prices, setPrices] = useState(pricesOf(product.skus[active]));
    const [styles, setStyles] = useState(
        product.skus.map((p) => p.color)
    );

    useEffect(() => {
        setImages(product.skus[active]?.images);
        setPrices(pricesOf(product.skus[active]));

    }, [active]);

    // 고른 색상의 사이즈들을 합친 재고. 서버가 지금 수량 − 결제 전 예약을 보낸다.
    const stock = stockStateOf(product, product.skus[active]);

    return (
        <div className="flex flex-col relative w-[215px] rounded p-1">
            {/* 예전에는 가격이 여럿이면 `&size=${active}` 를 붙였다 — 색상 번호를 사이즈 번호로 넘긴 것이라, 상세가
                엉뚱한 사이즈를 고른 채 열렸다. 사이즈는 손님이 고른다. */}
            <Link to={`/product/${product.slug}?style=${active}`}
                  className={stock?.kind === "sold_out" ? "opacity-60" : undefined}>
                <ProductSwiper images={images} />
            </Link>
            {product.skus[active]?.discount > 0 && (
                <div className=" flex items-center justify-center absolute bg-yellow-400 w-9 h-9 rounded-full top-0 -right-1 text-sm z-50">
                    -{product.skus[active].discount}%
                </div>
            )}
            <div className=" mt-2">
                <Link to={`/product/${product.slug}?style=${active}`}>
                    <h3>
                        {product.name.length > 45
                            ? `${product.name.substring(0, 45)}`
                            : product.name}
                    </h3>
                </Link>
                <span className="text-xs text-red-500">
                    {rangeText(prices, money)}
                </span>
                {prices?.discount > 0 && (
                    <span className="ml-1 text-xs text-slate-400 line-through">
                        {rangeBeforeText(prices, money)}
                    </span>
                )}
                {stock && (
                    <div className="mt-1">
                        <span
                            data-testid="stock-badge"
                            data-kind={stock.kind}
                            className={`inline-block rounded px-1.5 py-0.5 text-xs ${STOCK_BADGE[stock.kind]}`}
                        >
                            {t(`card.stock.${stock.kind}`, { count: stock.count })}
                        </span>
                    </div>
                )}
                <div className="flex space-x-2 mt-1">
                    {styles &&
                        styles.map((style, i) =>
                            style?.image ? (
                                <img
                                    key={i}
                                    src={style.image}
                                    className={`cursor-pointer h-[25px] object-cover rounded-full hover:outline hover:outline-black active:outline active:outline-black outline-1 outline-offset-2 ${
                                        i === active
                                            ? "outline outline-black"
                                            : ""
                                    }`}
                                    width={25}
                                    height={25}
                                    onMouseOver={() => {
                                        setImages(
                                            product.skus[i].images
                                        );
                                        setActive(i);
                                    }}
                                    alt={product.name}
                                />
                            ) : (
                                <span
                                    key={i}
                                    className={`cursor-pointer w-[25px] h-[25px] rounded-full hover:outline hover:outline-black active:outline active:outline-black outline-1 outline-offset-2 ${
                                        i === active
                                            ? "outline outline-black"
                                            : ""
                                    }`}
                                    style={{
                                        backgroundColor: style?.color ?? "#e2e8f0",
                                    }}
                                    onMouseOver={() => {
                                        setImages(
                                            product.skus[i].images
                                        );
                                        setActive(i);
                                    }}
                                ></span>
                            )
                        )}
                </div>
            </div>
        </div>
    );
};

export default ProductCard;
