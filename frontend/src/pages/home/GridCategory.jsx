import { Link } from "react-router-dom";

import { firstImageOf } from "util/productImage";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 상단 격자 카드 한 장.
 *
 * <p>예전에는 전체 상품 배열과 카테고리 이름을 받아 <b>스스로 걸러 냈다</b>
 * ({@code products.filter(p => category === p.category.name).slice(0, gridCols*gridCols)}).
 * 이제 서버가 섹션별로 그룹핑해서 필요한 개수만 보내 주므로 받은 대로 그린다.
 *
 * <p>이미지 접근에 {@code firstImageOf} 를 쓰는 이유: 예전에는 {@code product.skus[0].images[0]}
 * 를 그대로 읽었다. sku 나 이미지가 없는 상품이 하나라도 섞이면 그 자리에서 TypeError 가 나고
 * <b>홈 전체가 흰 화면</b>이 됐다. 첫 화면에서 그건 너무 비싼 실패다.
 *
 * <h3>🐛 카드 안에 빈 공백이 크게 남았다</h3>
 *
 * <p>원인이 둘이다.
 *
 * <p><b>① 한 줄짜리 격자에 420px 타일을 쌓았다.</b> {@code grid-cols-1} + {@code h-[420px]}
 * 이었는데 서버가 내려 주는 개수는 {@code item_limit = 4} 다. 즉 카드 하나가 세로로 1700px
 * 넘게 자랐다. 그런데 카테고리마다 상품 수가 다르다 — 상품이 1개뿐인 카테고리 카드는
 * 420px 인데 <b>같은 줄의 다른 카드가 1700px</b> 이므로, 격자가 행 높이를 맞추면서 그 카드
 * 아래로 1200px 넘는 흰 공백이 생겼다. 이름이 "격자"인데 한 줄이었던 것이 화근이다.
 * 2열로 되돌리면(4개 → 2×2) 카드가 정사각형에 가까워지고 높이 편차도 1/2 로 줄어든다.
 *
 * <p><b>② {@code object-contain} 은 남는 자리를 비운다.</b> 상자 비율과 사진 비율이 다르면
 * 그 차이가 그대로 <b>레터박스</b>(위아래 또는 좌우 흰 띠)가 된다. 가로로 긴 사진을 세로로
 * 긴 상자에 넣으면 절반이 빈다. 타일을 {@code aspect-square} 로 고정하고
 * {@code object-cover} 로 채운다 — <b>상자가 절대 비지 않는다.</b>
 *
 * <p>홈에서 {@code cover} 를 쓰는 이유: 홈은 <b>썸네일</b>이다. 가장자리가 조금 잘리더라도
 * 타일이 꽉 찬 쪽이 낫다. 상품을 제대로 봐야 하는 <b>상세 화면</b>은 자르지 않는
 * {@code contain} 을 그대로 쓴다.
 *
 * <p>여기 타일이 정사각이고 카드 스와이퍼({@code ProductSwiper})가 4:5 인 것은 의도적이다.
 * 잘려 나가는 양은 상자 비율이 사진 비율에서 얼마나 먼가로 정해지는데, 상품 사진은 대개
 * 세로가 길다. 정사각은 그만큼 더 자르지만 격자를 가지런히 두는 값이 더 크고, 카드는
 * 상품을 보는 자리라 덜 자르는 쪽을 골랐다.
 */
const GridCategory = ({ title, products, moreHref = "/browse" }) => {
    const { t } = useTranslation();
    const items = Array.isArray(products) ? products : [];

    // 상품이 하나뿐이면 2열로 두면 오른쪽 칸이 통째로 빈다. 그때는 한 칸을 꽉 쓴다.
    const columns = items.length === 1 ? "grid-cols-1" : "grid-cols-2";

    return (
        // w-full: 부모(HomeSections 의 flex 항목)가 정해 준 폭을 그대로 쓴다.
        // 이게 없으면 내용 폭으로 줄어들어 카드 오른쪽이 빈다.
        <div className="flex w-full flex-col bg-white border rounded p-3">
            <h3 className="font-bold mb-2 uppercase truncate">
                {String(title ?? "").replace("-", " ")}
            </h3>
            <div className={`grid ${columns} gap-2`}>
                {items.map((product, i) => {
                    const image = firstImageOf(product);
                    return (
                        <Link
                            to={`/product/${product.slug}?index=${i}`}
                            key={product.id ?? i}
                            className="block"
                        >
                            {/*
                              * 타일이 크기를 정하고(aspect-square) 이미지는 그것을 채운다.
                              * overflow-hidden 은 cover 가 넘긴 부분을 잘라내는 쪽 —
                              * 이게 없으면 잘리지 않고 옆 칸을 침범한다.
                              */}
                            <div className="relative aspect-square overflow-hidden rounded bg-slate-100">
                                {image ? (
                                    <img
                                        src={image}
                                        alt={product.name}
                                        className="absolute inset-0 h-full w-full object-cover transition-transform duration-200 hover:scale-105"
                                        loading="lazy"
                                        decoding="async"
                                    />
                                ) : (
                                    // 이미지가 없는 상품도 자리는 지킨다 — 격자가 무너지면
                                    // 옆 카드까지 어긋나 보인다.
                                    <div className="absolute inset-0 flex items-center justify-center p-2 text-center text-xs text-slate-400">
                                        {product.name}
                                    </div>
                                )}
                            </div>
                        </Link>
                    );
                })}
            </div>
            {/*
              * mt-auto: 카드 높이가 남더라도 이 줄은 바닥에 붙는다.
              * 🐛 예전에는 <h4> 였다 — 눌리는 모양인데 링크가 아니었다(browseLink.js 참조).
              */}
            <Link
                to={moreHref}
                className="self-start text-xs hover:underline text-blue-500 mt-auto pt-2"
            >
                {items.length > 1 ? t("home.grid.see_more") : t("home.grid.shop_now")}
            </Link>
        </div>
    );
};

export default GridCategory;
