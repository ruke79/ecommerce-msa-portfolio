import { Link } from "react-router-dom";
import { useMoney } from "hook/useMoney";
import { useTranslation } from "i18n/I18nProvider";


/**
 * 결제 화면 아래의 장바구니 요약 — 서버의 checkout 응답({@code CartProductDto[]})을 그린다.
 *
 * <p>🐛 색상 칩이 {@code product.color.image} 를 읽고 있었다. DTO 의 필드는
 * {@code colorImage} 다(장바구니 화면은 맞게 읽고 있었다). {@code src={undefined}} 인
 * {@code <img>} 는 빈 칸이 아니라 <b>alt 를 글자로 그린다</b> — 그래서 칩마다 상품 이름이
 * 배경처럼 겹쳐 보였다. 상품 이미지 쪽에는 Next.js 의 {@code fill} 이 남아 있었고
 * ({@code <img>} 에는 없는 속성), {@code object-contained} 는 Tailwind 에 없는 클래스다.
 *
 * <p>없는 이미지는 그리지 않는다. 글자로 대신 그리는 {@code <img>} 보다 빈 자리가 낫다.
 */
/**
 * 상품 상세로 가는 주소 — 상품 **id** 로 간다(catalog 가 slug 로 못 찾으면 숫자를 id 로 보고, 화면이 slug 주소로 바꾼다).
 *
 * <p>🐛 예전에는 이름을 slug 자리에 넣었다(`Italian Olive Oil` → `/product/Italian-Olive-Oil`). slug 는 이름과 무관한 값이라
 * ("event-much-mention") 그런 상품은 전부 "商品が見つかりません" 400 이었다. 스타일도 늘 0, 사이즈도 늘 첫 번째였다 — 담은
 * 스타일로 가고, 사이즈는 이름만 알아 인덱스를 모르므로 고르지 않는다.
 */
export function productHref(product) {
    const style = Number(product?.style) || 0;
    const key = product?.productId ?? String(product?.name ?? "").replaceAll(" ", "-");
    return `/product/${key}?style=${style}`;
}

const Product = ({ cart }) => {
    const { t } = useTranslation();
    const money = useMoney();
    const products = cart?.products ?? [];

    return (
        <div>
            <div className="flex items-center justify-between  pb-2 mb-4 border-b border-b-2 ">
                <h3 className="text-xl font-semibold">{t("checkout.cart.title")}</h3>
                <span className="text-sm text-slate-600">
                    {products.length === 1
                        ? t("checkout.cart.one_item")
                        : t("checkout.cart.items", { count: products.length })}
                </span>
            </div>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                {products.map((product) => (
                    <div
                        key={product.id}
                        className="p-1.5 flex flex-col outline outline-1 outline-offset-1 outline-slate-300 rounded-md"
                    >
                        <Link
                            target="_blank"
                            to={productHref(product)}
                            className="block w-full h-[180px] overflow-hidden rounded-md bg-slate-50"
                        >
                            {product.image ? (
                                <img
                                    src={product.image}
                                    alt={product.name}
                                    loading="lazy"
                                    className="w-full h-full object-contain cursor-pointer"
                                />
                            ) : null}
                        </Link>
                        <div className="w-fit text-sm flex items-center space-x-3 pr-5 my-2 bg-slate-100 rounded-full">
                            {product.color?.colorImage ? (
                                <img
                                    src={product.color.colorImage}
                                    alt={product.color.color ?? ""}
                                    width={30}
                                    height={30}
                                    className="rounded-full object-cover"
                                />
                            ) : (
                                <span className="inline-block w-[30px] h-[30px] rounded-full bg-slate-300" aria-hidden="true" />
                            )}
                            <span>{product.size}</span>
                            <span>
                                <b className="text-xs mr-1">X</b>
                                {product.qty}
                            </span>
                        </div>
                        <div className="mb-2 text-xs font-semibold" title={product.name}>
                            {product.name?.length > 18
                                ? `${product.name.substring(0, 18)}...`
                                : product.name}
                        </div>
                        <div className="text-sm">
                            {money(Number(product.price) * product.qty)}
                        </div>
                    </div>
                ))}
            </div>
            <div className="my-4 border-t pt-2">
                {t("checkout.cart.subtotal")}: <span className="font-bold">{money(cart?.cartTotal ?? 0)}</span>
            </div>
        </div>
    );
};

export default Product;
