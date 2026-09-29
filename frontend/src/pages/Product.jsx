import Header from "./header/Header";
import ProductPage from "../components/product/ProductPage";
import { redirect, useLoaderData } from "react-router-dom";
import { getRequest } from "util/api";
import { discounted, skuPriceRange } from "util/pricing";
import React from "react";
import { translate } from "i18n/I18nProvider";
import useRevalidateWhileVisible from "hook/useRevalidateWhileVisible";
import { hasSizes, sizeLabel } from "util/sizes";

const SingleProduct = () => {
    
    const product = useLoaderData();
    // 남은 수량(다른 주문의 결제 전 예약을 뺀 값)이 연 순간에 굳지 않게 — 돌아올 때와 1분마다 다시 읽는다(docs/59 §8).
    useRevalidateWhileVisible(60_000);
        
    return (
        <React.Fragment>            
            <Header title={product.name} />
            <main className="bg-white w-full">
            <ProductPage product={product} />
            </main>                
        </React.Fragment>
    );
};

export default SingleProduct;


export async function loader({ request, params }) {
    const slug = params.slug;

    const searchParams = new URL(request.url).searchParams;
    // 쿼리스트링은 항상 문자열이다. 배열 인덱스로 쓰려면 숫자여야 한다 — 문자열 "1" 도
    // 배열 인덱싱은 되지만, 아래 size 비교/연산에서 문자열이 섞이면 조용히 어긋난다.
    const style = Number(searchParams.get("style")) || 0;
    const size = Number(searchParams.get("size")) || 0;

    // 오류를 삼키고 null 을 돌려주면 컴포넌트가 `product.name` 에서 죽는다. 그때 화면에
    // 뜨는 메시지는 진짜 원인(404·500·네트워크)과 아무 상관이 없다. 라우터의 errorElement
    // 가 처리하도록 그대로 던진다.
    const response = await getRequest(`/catalog-service/api/product/${slug}`);
    const product = response.data;

    // 상품 id 로 들어왔으면(체크아웃·주문 화면의 링크) 제 주소(slug)로 바꾼다 — 공유·즐겨찾기·다른 화면의 링크가 모두 slug 다.
    if (product?.slug && product.slug !== slug) {
        const search = new URL(request.url).search;
        return redirect(`/product/${product.slug}${search}`);
    }

    const subProduct = product.skus?.[style] ?? product.skus?.[0];
    if (!subProduct) {
        throw new Response(translate("product.not_found"), { status: 404 });
    }

    // sort 는 배열을 제자리에서 뒤집는다. subProduct.sizes 를 그대로 정렬하면 화면에
    // 보여 줄 사이즈 순서까지 가격순으로 바뀌어 버린다. 복사본을 정렬한다.
    const selectedSize = subProduct.sizes[size] ?? subProduct.sizes[0];

    return {
        ...product,
        style,
        images: subProduct.images,
        sizes: subProduct.sizes,
        // 사이즈 없는 상품 — 사이즈 고르기를 그리지 않고 첫 행을 쓴다(장바구니·catalog 도 같은 이름의 첫 행을 쓴다).
        sizeless: !hasSizes(subProduct.sizes),
        discount: subProduct.discount,
        // SKU 는 사이즈 한 줄의 코드다(catalog V17, 예: FAS-100042-XL) — 고른 사이즈의 것. 스타일(색상) 코드는 따로 둔다.
        // 코드가 생기기 전의 캐시라면 스타일 코드로 대신한다.
        sku: selectedSize.skuCode ?? subProduct.sku,
        styleCode: subProduct.sku,
        colors: product.skus.map((p) => p.color),
        // 가격은 **숫자**로 넘기고 통화 표기는 화면이 한다(useMoney) — 예전에는 여기서 "From 10$ to 20$" 같은
        // 문자열을 만들어 넘겼다. 할인 적용은 목록 카드와 같은 함수(skuPriceRange·discounted)다.
        // (예전 조건은 `prices.discount > 1` 이었다. prices 는 배열이라 늘 거짓 — 할인 상품인데도 정가 범위만 보였다.)
        priceRange: skuPriceRange(subProduct),
        price: discounted(selectedSize.price, subProduct.discount),
        priceBefore: Number(selectedSize.price),
        // 남은 수량은 서버가 이미 다른 주문의 결제 전 예약을 뺀 값이다. onHold 는 그중 잡혀 있는 수 — 0 이면 서버가 싣지 않는다
        // (docs/59 §5). "품절" 과 "누가 결제 중" 을 가르는 데 쓴다.
        quantity: selectedSize.quantity,
        onHold: selectedSize.onHold ?? 0,
        ratings: [],
        allSizes: product.skus
            .map((p) => p.sizes)
            .flat()
            .slice()
            .sort((a, b) => a.size - b.size)
            .filter(
                (element, index, array) =>
                    array.findIndex((el2) => el2.size === element.size) === index
            )
            // 이름 없는 사이즈는 고를 것이 아니다 — 리뷰의 사이즈 고르기·걸러 보기에서 뺀다.
            .filter((element) => sizeLabel(element) !== ""),
    };
}
