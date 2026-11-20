import { Link } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";
import { ChevronRightIcon } from "@heroicons/react/24/outline";

/**
 * 상품 상세의 위치 표시.
 *
 * <h3>🐛 두 링크가 아무 데도 가지 않았다</h3>
 *
 * <p>react-router 의 `<Link>` 는 `to` 를 받는다. 여기 있던 것은 `href` 였다 — 알 수 없는
 * prop 이라 렌더는 되고 클릭만 안 됐다. 앵커처럼 보이는데 눌러도 아무 일이 없다.
 *
 * <p>고치면서 <b>주소도 바꿨다.</b> 원래 가리키던 `/category/<slug>` 라우트는 이 앱에
 * 없다(`/category` 는 관리자 화면이다). `to` 로만 고쳤다면 이번엔 404 로 갔을 것이다.
 * 카테고리로 상품을 보는 실제 경로는 `/browse?category=<id>` 이고, 햄버거 메뉴도 같은
 * 주소를 쓴다.
 *
 * <h3>하위 카테고리는 일부러 링크가 아니다</h3>
 *
 * <p>파일에 `{/* have error for next subcategories , i will fix this *&#47;}` 가 남아
 * 있었다. 실제 사정은 <b>하위 카테고리로 거르는 경로가 아직 없다</b>는 것이다 — browse 의
 * 필터도 하위 카테고리는 이름만 보여 주고 클릭을 받지 않는다. 없는 곳으로 가는 링크를
 * 만드는 대신 글자로 둔다. 빵부스러기의 마지막 조각은 원래 현재 위치라 링크가 아닌 것이
 * 관례이기도 하다.
 *
 * <p>[JA] 商品詳細の位置表示。
 * 🐛 二つのリンクがどこにも行かなかった: react-router の `<Link>` は `to` を受ける。ここにあったのは `href` だった — 未知の
 * prop なのでレンダーはされクリックだけ効かなかった。アンカーに見えるのに押しても何も起きない。
 * 直しながら <b>アドレスも変えた。</b> 元々指していた `/category/<slug>` ルートはこのアプリに
 * ない(`/category` は管理者画面だ)。`to` だけ直していたら今度は 404 に行っていた。
 * カテゴリで商品を見る実際の経路は `/browse?category=<id>` で、ハンバーガーメニューも同じ
 * アドレスを使う。
 * サブカテゴリはわざとリンクにしていない: ファイルに古い TODO コメントが残っていたが、実際の事情は
 * <b>サブカテゴリで絞り込む経路がまだない</b>ことだ — browse のフィルターもサブカテゴリは名前だけ見せクリックを受けない。
 * 存在しない場所へのリンクを作る代わりに文字のまま置く。パンくずの最後の一片は元々現在位置なのでリンクでないのが
 * 慣例でもある。
 */
const BreadCrumb = ({ category, subCategories }) => {
    const { t } = useTranslation();
    const crumbs = Array.isArray(subCategories) ? subCategories : [];

    return (
        <nav aria-label={t("product.breadcrumb_label")} className="flex text-sm text-gray-600 items-center mx-2 my-4">
            <Link className="hover:underline" to="/">
                {t("browse.home")}
            </Link>

            {category && (
                <>
                    <ChevronRightIcon className="h-3 mx-1" />
                    <Link className="hover:underline" to={`/browse?category=${category.id}`}>
                        {category.name}
                    </Link>
                </>
            )}

            {crumbs.map((s) => (
                <span key={s.id ?? s.name} className="flex items-center">
                    <ChevronRightIcon className="h-3 mx-1" />
                    <span>{s.name}</span>
                </span>
            ))}
        </nav>
    );
};

export default BreadCrumb;
