import { Link } from "react-router-dom";
import { Swiper, SwiperSlide } from "swiper/react";
import ProductCard from "./ProductCard";

// Import Swiper styles
import "swiper/css";
import "swiper/css/pagination";
import "swiper/css/navigation";
// import required modules
import { Navigation } from "swiper/modules";

import { isRenderable } from "util/productImage";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 가로 스와이퍼 섹션.
 *
 * <p>예전에는 전체 상품 배열과 카테고리 이름을 받아 스스로 걸러 냈다
 * ({@code products.filter(p => p.category.name === category)}). 이제 서버가 섹션별로
 * 그룹핑해서 보내 주므로 받은 대로 그린다.
 *
 * <p>{@code isRenderable} 로 한 번 더 거르는 이유: {@code ProductCard} 가
 * {@code product.skus[active].sizes.map(...)} 를 전제한다. sku 나 가격이 없는 상품이
 * 하나만 섞여도 TypeError 가 나고 <b>홈 전체가 흰 화면</b>이 된다.
 *
 * <p>[JA] 横スワイパーのセクション。
 * 以前は商品配列全体とカテゴリー名を受け取って自分で絞り込んでいた
 * (products.filter(p => p.category.name === category))。今はサーバーがセクションごとにグループ化して送るので、受け取ったとおりに描く。
 * isRenderable でもう一度絞る理由: ProductCard が product.skus[active].sizes.map(...) を前提にしている。
 * sku や価格のない商品が一つでも混じると TypeError が起きホーム全体が白画面になる。
 */
const HomeProductSwiper = ({ title, products, moreHref = "/browse" }) => {
    const { t } = useTranslation();
    const renderable = products.filter(isRenderable);

    if (renderable.length === 0) {
        return null;
    }

    return (
        <div className="z-50 flex flex-col rounded bg-white h-auto mb-4 mx-4 p-4 border">
            {/* 격자 카드와 같은 "더 보기" — 줄 끝까지 넘겨 본 뒤 갈 곳이 있어야 한다. */}
            {/* [JA] グリッドカードと同じ「もっと見る」 — 行の端までめくった後に行き先が必要である。 */}
            <div className="mb-4 flex items-baseline justify-between gap-4">
                <h4 className="min-w-0 truncate font-bold text-xl">{title}</h4>
                <Link to={moreHref} className="shrink-0 text-sm text-blue-500 hover:underline">
                    {t("home.grid.see_more")}
                </Link>
            </div>
            {/*
              * 🐛 `slidesPerView: 5` 였다. 스와이퍼는 슬라이드 폭을 컨테이너의 1/5 로
              *    계산하는데, 안에 든 ProductCard 는 **고정 215px** 이다. 둘이 맞는
              *    화면 폭에서만 멀쩡하고, 좁아지면(예: 1024px → 슬라이드 약 190px)
              *    카드가 슬라이드를 25px 씩 넘쳐 서로 겹쳤다.
              *
              * `slidesPerView="auto"` 는 반대로 **내용이 폭을 정하게** 한다. 고정 폭
              * 카드에 맞는 방식이고, 화면이 넓으면 더 많이, 좁으면 더 적게 보인다 —
              * 어느 폭에서도 넘치지 않는다.
              */}
            {/* [JA] 🐛 `slidesPerView: 5` だった。スワイパーはスライド幅をコンテナの 1/5 で計算するが、中の ProductCard は **固定 215px** である。
                二つが合う画面幅でだけ正常で、狭くなると(例: 1024px → スライド約 190px)カードがスライドを 25px ずつはみ出して互いに重なった。
                `slidesPerView="auto"` は逆に **内容が幅を決める** ようにする。固定幅カードに合う方式で、画面が広ければ多く、狭ければ少なく見える —
                どの幅でもはみ出さない。 */}
            <Swiper
                slidesPerView="auto"
                spaceBetween={10}
                slidesPerGroup={1}
                navigation={true}
                modules={[Navigation]}
                className="w-full products-swiper_home "
                breakpoints={{
                    640: {
                        slidesPerGroup: 2
                    },
                }}
            >
                {renderable.map((product, i) => (
                    // key 는 상품 id 다. 배열 인덱스를 쓰면 섹션이 갱신될 때 React 가
                    // 서로 다른 상품을 같은 요소로 취급해 이미지·선택 색상이 섞인다.
                    // !w-auto: 슬라이드 폭을 카드가 정한다. slidesPerView="auto" 일 때
                    // 스와이퍼가 넣는 값과 같지만, 초기화 **전에도** 넘치지 않게 한다.
                    // [JA] key は商品 id である。配列インデックスを使うとセクションが更新されたときに React が
                    //      別の商品を同じ要素として扱い、画像 · 選択色が混ざる。
                    //      !w-auto: スライド幅をカードが決める。slidesPerView="auto" のときに
                    //      スワイパーが入れる値と同じだが、初期化 **前にも** はみ出さないようにする。
                    <SwiperSlide className="!w-auto" key={product.id ?? i}>
                        <ProductCard product={product} />
                    </SwiperSlide>
                ))}
            </Swiper>
        </div>
    );
};

export default HomeProductSwiper;
