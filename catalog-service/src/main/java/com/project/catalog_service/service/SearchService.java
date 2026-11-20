package com.project.catalog_service.service;

import com.project.catalog_service.dto.SearchResultDto;

import com.project.catalog_service.mapper.CategoryMapper;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.mapper.SubcategoryMapper;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.QProduct;
import com.project.catalog_service.model.QProductColor;
import com.project.catalog_service.model.QProductDetails;
import com.project.catalog_service.model.QProductSize;
import com.project.catalog_service.model.QProductSku;
import com.project.catalog_service.model.QProductSubcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subCategoryRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 검색 결과에도 기획전 할인을 얹는다.
     *
     * <p>여기를 빠뜨리면 같은 상품이 <b>검색 결과에서는 정가, 상세 화면에서는 할인가</b>로
     * 보인다. 사용자는 목록에서 보고 들어왔는데 가격이 달라진 것을 본다.
     *
     * <p>[JA] 検索結果にも企画展割引を載せる。
     * <p>ここを漏らすと同じ商品が<b>検索結果では定価、詳細画面では割引価格</b>で見える。ユーザーは一覧で見て入ってきたのに価格が変わっているのを見ることになる。
     */
    private final EventPricing eventPricing;

    /** 검색의 DB 작업 격벽 — 자리를 얻은 뒤에 시간 상한 트랜잭션을 연다(캐시가 맞으면 오지 않는다). */
    // [JA] 検索の DB 作業のバルクヘッド — 枠を得た後で時間上限付きトランザクションを開く(キャッシュがヒットすれば来ない)。
    private final SearchBulkhead searchBulkhead;

    // 첫 쪽의 필터 메뉴(브랜드 · 색상 · 사이즈 · 상세)는 여기서 만들지 않는다 — 결과 캐시 밖에서 SearchFilterFacets.attach 가 붙인다
    // (SearchController). 🐛 2026-10-20 전에는 이 캐시된 계산 안에서 돌아 1,000만 상품에서 질의 하나가 1분 넘게 커넥션을 쥐었다.
    // [JA] 最初のページのフィルタメニュー(ブランド・色・サイズ・詳細)はここでは作らない — 結果キャッシュの外で SearchFilterFacets.attach が付ける(SearchController)。
    //      🐛 2026-10-20 より前はこのキャッシュされる計算の中で回っており、1,000 万商品でクエリ一つが 1 分以上コネクションを握っていた。

    // @RequiredArgsConstructor가 있으므로 별도의 @Autowired 생성자는 필요 없습니다.
    // private final 필드들에 대한 생성자를 자동으로 생성합니다.
    // [JA] @RequiredArgsConstructor があるので別途 @Autowired コンストラクタは不要である。private final フィールドのコンストラクタを自動生成する。

    private Pageable createPageRequest(int page, int size) {
        return PageRequest.of(page, size);
    }

    /**
     * ES 관련도 검색으로 후보 productId 를 조회한다.
     * @return 이 검색이 ES 로 가지 않음(엔진 POSTGRES · ES 빈 없음) 또는 조회 실패 시 null (호출자가 LIKE 경로로 폴백),
     *         정상 조회 시 id 목록(비어 있을 수 있음 — "매칭 없음" 의미)
     *
     * <p>[JA] ES の関連度検索で候補の productId を照会する。
     * 戻り値: この検索が ES に行かない(エンジン POSTGRES・ES Bean なし)か照会失敗なら null(呼び出し側が LIKE 経路へフォールバック)、正常なら id リスト(空もありうる — 「一致なし」の意味)。
     */
    private List<Long> searchIdsViaEs(String term, SearchEngine engine) {
        if (engine != SearchEngine.ES || esProductSearchService == null) {
            return null;
        }
        try {
            return esProductSearchService.searchProductIds(term, ES_CANDIDATE_LIMIT);
        } catch (Exception e) {
            log.warn("ES 검색 실패 — Postgres LIKE 경로로 폴백합니다: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 가격·사이즈·색상 조건 — 그 조건에 맞는 SKU(사이즈 줄)가 하나라도 있는 상품. 조건이 없으면 null(조건 없음).
     *
     * <p>가격은 <b>화면에 보이는 가격</b>으로 본다 — 사이즈 정가에 상시 할인과 기획전 할인 중 큰 쪽을 적용한 값
     * (상품 화면·목록 카드·장바구니와 같은 {@link ProductService#discounted} 의 식: {@code 정가 × (100 − 율) / 100}, 소수 2자리 반올림).
     * 🐛 예전에는 정가로 걸러, 카드에 $70 으로 보이는 상품이 "$80 이하" 에서 빠지고 "$90 이상" 에 나왔다(docs/40 §22).
     *
     * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 예전에는 조건에 맞는 후보를 <b>카테고리와 상관없이 전부</b> 앱으로
     * 읽었다 — 가격대는 하한 이상인 사이즈 줄 전부(수백만 줄)를 읽어 할인가를 앱에서 따졌고, 맞는 상품 id 를 {@code IN (…)} 으로 다시
     * 보냈다. 필터 검색을 초당 3번 섞자 catalog 3개가 20초 만에 {@code OutOfMemoryError: Java heap space} 로 쓰러졌다(실패 93.76%,
     * 힙 512MB). 이제 상관 서브쿼리({@code EXISTS})로 DB 안에서 판정한다 — 바깥 질의의 카테고리 조건과 함께 돌고, 앱으로 오는 것은
     * 그 쪽의 상품 10개와 개수뿐이다. 맞는 것이 없으면 0건이다("없음"이 "전부"로 뒤집히지 않는다).
     *
     * <p>기획전 할인은 SQL 이 모르는 메모리의 맵이라, 펼치기 전의 규칙({@link EventPricing#rules()} — 상품 지정 · 카테고리 지정)을
     * 그 상품의 율을 고르는 CASE 로 싣는다. 높은 율부터 보므로 겹치면 높은 쪽 — {@code EventPricing.refresh} 와 같은 규칙이다.
     * 사이즈 · 색상은 값마다 앞부분 일치(대소문자 무시, 글자 그대로 — {@link #likeLiteral}).
     *
     * <p>[JA] 価格 · サイズ · 色の条件 — その条件に合う SKU(サイズ行)が一つでもある商品。条件がなければ null(条件なし)。
     *
     * <p>[JA] 価格は<b>画面に見える価格</b>で見る — サイズ定価に常時割引と企画展割引の大きい方を適用した値(商品画面 · 一覧カード · カートと
     * 同じ {@link ProductService#discounted} の式: {@code 定価 × (100 − 率) / 100}、小数 2 桁で四捨五入)。🐛 以前は定価で絞り込み、カードに
     * $70 と見える商品が「$80 以下」から抜けて「$90 以上」に出た(docs/40 §22)。
     *
     * <p>[JA] 🐛 2026-10-19 データ規模測定(商品 100 万、docs/83 §5): 以前は条件に合う候補を<b>カテゴリに関係なく全部</b>アプリに読んでいた —
     * 価格帯は下限以上のサイズ行全部(数百万行)を読んで割引価格をアプリで判定し、合う商品 id を {@code IN (…)} で送り直していた。
     * フィルター検索を毎秒 3 回混ぜると catalog 3 個が 20 秒で {@code OutOfMemoryError: Java heap space} で倒れた(失敗 93.76%、ヒープ 512MB)。
     * 今は相関サブクエリ({@code EXISTS})で DB の中で判定する — 外側クエリのカテゴリ条件と一緒に動き、アプリに来るのはそのページの
     * 商品 10 個と件数だけだ。合うものがなければ 0 件である(「なし」が「全部」に反転しない)。
     *
     * <p>[JA] 企画展割引は SQL が知らないメモリ上のマップなので、展開前のルール({@link EventPricing#rules()} — 商品指定 · カテゴリ指定)を
     * その商品の率を選ぶ CASE として載せる。高い率から見るので重なれば高い方 — {@code EventPricing.refresh} と同じルールだ。
     * サイズ · 色は値ごとに前方一致(大文字小文字無視、文字どおり — {@link #likeLiteral})。
     */
    com.querydsl.core.types.Predicate skuFilter(SearchParamsRequest params) {
        if (!hasSkuFilter(params)) {
            return null;
        }
        java.math.BigDecimal low = params.getLowPrice();
        java.math.BigDecimal high = params.getHighPrice();
        List<String> sizes = filterValues(params.getSize());
        List<String> colors = filterValues(params.getColor());
        boolean priced = low != null || high != null;

        QProduct product = QProduct.product;
        QProductSku sku = new QProductSku("filterSku");
        QProductSize size = new QProductSize("filterSize");
        QProductColor color = new QProductColor("filterColor");
        com.querydsl.jpa.JPQLQuery<Integer> sub = JPAExpressions.selectOne().from(sku);
        BooleanBuilder where = new BooleanBuilder(sku.product.productId.eq(product.productId));
        if (priced || !sizes.isEmpty()) {
            sub.join(sku.sizes, size);
            where.and(startsWithAny(size.size, sizes));
        }
        if (!colors.isEmpty()) {
            sub.join(sku.color, color);
            where.and(startsWithAny(color.color, colors));
        }
        if (priced) {
            com.querydsl.core.types.dsl.NumberExpression<java.math.BigDecimal> shown =
                    shownPrice(size.price, sku.discount, eventRate(product));
            if (low != null) {
                // 할인가는 정가를 넘지 않는다 — 정가 조건은 늘 참이고, 할인가를 셈하기 전에 줄을 거른다
                // [JA] 割引価格は定価を超えない — 定価の条件は常に真で、割引価格を計算する前に行を絞る
                where.and(size.price.goe(low)).and(shown.goe(low));
            }
            if (high != null) {
                where.and(shown.loe(high));
            }
        }
        return sub.where(where).exists();
    }

    /** 화면의 할인가 — {@code round(정가 × (100 − least(greatest(상시, 기획전), 100)) / 100, 2)}(common Discount.apply 와 같은 값). */
    // [JA] 画面の割引価格 — {@code round(定価 × (100 − least(greatest(常時, 企画展), 100)) / 100, 2)}(common Discount.apply と同じ値)。
    private static com.querydsl.core.types.dsl.NumberExpression<java.math.BigDecimal> shownPrice(
            com.querydsl.core.types.dsl.NumberPath<java.math.BigDecimal> price,
            com.querydsl.core.types.dsl.NumberPath<Integer> skuDiscount,
            com.querydsl.core.types.dsl.NumberExpression<Integer> eventRate) {
        return com.querydsl.core.types.dsl.Expressions.numberTemplate(java.math.BigDecimal.class,
                "round({0} * (100 - least(greatest(coalesce({1}, 0), {2}), 100)) / 100, 2)", price, skuDiscount, eventRate);
    }

    /**
     * 그 상품의 기획전 할인율 — 상품 지정 · 카테고리 지정 중 높은 쪽, 없으면 0. 규칙이 없으면(대부분의 때) 상수 0.
     * 상품 지정은 많아야 수천 건이고 카테고리 지정은 카테고리 수만큼이라 바인드 변수 한도(65,535) 안이다 — 펼친 맵을 싣지 않는 이유.
     *
     * <p>[JA] その商品の企画展割引率 — 商品指定・カテゴリ指定のうち高い方、なければ 0。ルールがなければ(ほとんどの時)定数 0。
     * 商品指定は多くても数千件、カテゴリ指定はカテゴリ数だけなのでバインド変数の上限(65,535)内である — 展開したマップを載せない理由。
     */
    private com.querydsl.core.types.dsl.NumberExpression<Integer> eventRate(QProduct product) {
        EventPricing.Rules rules = eventPricing.rules();
        if (rules == null || rules.isEmpty()) {
            return com.querydsl.core.types.dsl.Expressions.numberTemplate(Integer.class, "0");
        }
        java.util.TreeMap<Integer, List<Long>> productsByRate = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        java.util.TreeMap<Integer, List<Long>> categoriesByRate = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        rules.products().forEach((id, rate) -> productsByRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(id));
        rules.categories().forEach((id, rate) -> categoriesByRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(id));
        java.util.TreeSet<Integer> rates = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        rates.addAll(productsByRate.keySet());
        rates.addAll(categoriesByRate.keySet());

        com.querydsl.core.types.dsl.CaseBuilder.Cases<Integer, com.querydsl.core.types.dsl.NumberExpression<Integer>> cases = null;
        for (Integer rate : rates) {
            BooleanBuilder applies = new BooleanBuilder();
            List<Long> ids = productsByRate.get(rate);
            if (ids != null) {
                applies.or(product.productId.in(ids));
            }
            List<Long> categoryIds = categoriesByRate.get(rate);
            if (categoryIds != null) {
                applies.or(product.category.categoryId.in(categoryIds));
            }
            cases = cases == null ? new com.querydsl.core.types.dsl.CaseBuilder().when(applies).then(rate) : cases.when(applies).then(rate);
        }
        return cases.otherwise(0);
    }

    /** 값 중 하나로 시작하면 참(대소문자 무시, 글자 그대로). 값이 없으면 null(조건 없음). */
    // [JA] 値のどれかで始まれば真(大文字小文字無視、文字どおり)。値がなければ null(条件なし)。
    private static com.querydsl.core.types.Predicate startsWithAny(com.querydsl.core.types.dsl.StringPath path, List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        BooleanBuilder any = new BooleanBuilder();
        for (String v : prefixLikes(values)) {
            any.or(path.likeIgnoreCase(v, '!'));
        }
        return any;
    }

    /** 앞부분 일치 LIKE 패턴 — {@code 값%}, 값 안의 {@code %} · {@code _} · {@code !} 는 글자({@link #likeLiteral}, 이스케이프 {@code !}). */
    // [JA] 前方一致の LIKE パターン — {@code 値%}、値の中の {@code %}・{@code _}・{@code !} は文字として扱う({@link #likeLiteral}、エスケープは {@code !})。
    static List<String> prefixLikes(List<String> values) {
        return values.stream().map(v -> likeLiteral(v) + "%").toList();
    }

    /**
     * 가격·사이즈·색상 중 하나라도 걸었는가.
     *
     * <p>걸지 않았으면 SKU 조건을 <b>만들지 않는다.</b> 조건 없는 SKU 질의는 상품 전량을
     * 훑는데, 그 결과는 "모든 상품" 이라 필터로서 아무 의미가 없다.
     *
     * <p>[JA] 価格・サイズ・色のどれか一つでも指定したか。
     * <p>指定していなければ SKU 条件を<b>作らない。</b>条件のない SKU クエリは商品全量を走査するが、その結果は「すべての商品」でフィルタとして何の意味もない。
     */
    /**
     * 하위 카테고리가 고른 카테고리에 속하지 않는다(없는 하위 포함) — 두 조건을 AND 로 묶으면 0건이 확실하다.
     * 카테고리가 이름이거나 둘 중 하나가 없으면 묻지 않는다.
     *
     * <p>[JA] サブカテゴリが選んだカテゴリに属さない(存在しないサブを含む) — 二つの条件を AND で結べば 0 件が確実である。
     * カテゴリが名前か、二つのどちらかがなければ問い合わせない。
     */
    boolean subcategoryOutsideCategory(Long categoryId, SearchParamsRequest params) {
        Long subCategoryId = parseCategoryId(params.getSubCategory());
        if (categoryId == null || subCategoryId == null) {
            return false;
        }
        return subCategoryRepository.findParentCategoryId(subCategoryId)
                .map(parent -> !parent.equals(categoryId))
                .orElse(true);
    }

    /** 어떤 값도 들 수 없는 가격 범위 — 하한 > 상한, 또는 상한 < 0. */
    // [JA] どんな値も入りえない価格範囲 — 下限 > 上限、または上限 < 0。
    static boolean emptyPriceRange(SearchParamsRequest params) {
        java.math.BigDecimal low = params.getLowPrice();
        java.math.BigDecimal high = params.getHighPrice();
        return high != null && (high.signum() < 0 || (low != null && low.compareTo(high) > 0));
    }

    private static boolean hasSkuFilter(SearchParamsRequest params) {
        return params.getLowPrice() != null
                || params.getHighPrice() != null
                || !filterValues(params.getSize()).isEmpty()
                || !filterValues(params.getColor()).isEmpty();
    }

    /** 다중 선택 필터 하나에 받는 값의 상한 · 값 하나의 길이 상한. */
    // [JA] 複数選択フィルタ一つで受け取る値の上限・値一つの長さの上限。
    static final int MAX_FILTER_VALUES = 20;
    static final int MAX_FILTER_VALUE_LENGTH = 100;

    /**
     * 화면이 보내는 다중 선택 값({@code ^Red|^Blue} — browse.jsx 의 createRegex)을 <b>글자 그대로의 값 목록</b>으로 푼다.
     * 앞의 {@code ^} 를 떼고 {@code |} 로 나눈다. 정규식으로 읽지 않는다.
     *
     * <p>🐛 예전에는 이 문자열을 그대로 썼다:
     * <ul>
     *   <li>사이즈 · 색상은 SQL {@code ~*} 의 <b>정규식</b>이 됐다 — 익명 요청이 정규식을 고른다. {@code size=(} 는
     *       "invalid regular expression" 500, 역참조 · 중첩 반복은 그 SKU 표 전체에 대한 비싼 매칭이었다</li>
     *   <li>브랜드 · 스타일 · 소재 · 성별은 LIKE {@code '%^Nike|^Adidas%'} 가 됐다 — {@code ^} · {@code |} 를 글자로 찾으니
     *       <b>화면에서 고른 필터가 한 번도 맞지 않았다</b>(언제나 0건)</li>
     * </ul>
     *
     * <p>[JA] 画面が送る複数選択値({@code ^Red|^Blue} — browse.jsx の createRegex)を<b>文字どおりの値リスト</b>にする。
     * 先頭の {@code ^} を外して {@code |} で分ける。正規表現としては読まない。
     *
     * <p>[JA] 🐛 以前はこの文字列をそのまま使っていた:
     * <ul>
     *   <li>サイズ · 色は SQL {@code ~*} の<b>正規表現</b>になった — 匿名リクエストが正規表現を選べる。{@code size=(} は
     *       "invalid regular expression" の 500、後方参照 · 入れ子の繰り返しはその SKU 表全体に対する高コストなマッチングだった</li>
     *   <li>ブランド · スタイル · 素材 · 性別は LIKE {@code '%^Nike|^Adidas%'} になった — {@code ^} · {@code |} を文字として探すので
     *       <b>画面で選んだフィルターが一度も合わなかった</b>(常に 0 件)</li>
     * </ul>
     */
    static List<String> filterValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String part : raw.split("\\|")) {
            String v = part.strip();
            while (v.startsWith("^")) {
                v = v.substring(1).strip();
            }
            if (!v.isEmpty() && v.length() <= MAX_FILTER_VALUE_LENGTH) {
                values.add(v);
            }
            if (values.size() >= MAX_FILTER_VALUES) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /** LIKE 의 {@code %} · {@code _} · 이스케이프 문자를 글자로 — 값 안의 {@code _} 가 "아무 한 글자" 가 되지 않게. */
    // [JA] LIKE の {@code %}・{@code _}・エスケープ文字を文字として扱う — 値の中の {@code _} が「任意の一文字」にならないように。
    static String likeLiteral(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * 상품 이름에 검색어가 들어 있는가 — 양쪽을 DB 함수 {@code product.search_key}(NFKC + 소문자, catalog V23)로 맞춰 비교한다.
     * 그 식 인덱스(idx_product_name_search_key_trgm)를 쓴다. 검색어의 {@code %} · {@code _} 는 글자로(예전에는 {@code %} 하나가 전부를 맞혔다).
     * [JA] 商品名に検索語が含まれるか — 両側を product.search_key(NFKC + 小文字)で揃えて比較する(式インデックスを使う)。
     */
    static com.querydsl.core.types.Predicate nameMatches(com.querydsl.core.types.dsl.StringPath name, String search) {
        return com.querydsl.core.types.dsl.Expressions.booleanTemplate(
                "search_key({0}) like concat('%', search_key({1}), '%') escape '!'",
                name, likeLiteral(search));
    }

    /** 값 중 하나라도 포함하면 참. 값이 없으면 null(조건 없음). */
    // [JA] 値のどれか一つでも含めば真。値がなければ null(条件なし)。
    private static com.querydsl.core.types.Predicate containsAny(com.querydsl.core.types.dsl.StringPath path, List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        BooleanBuilder any = new BooleanBuilder();
        for (String v : values) {
            any.or(path.likeIgnoreCase("%" + likeLiteral(v) + "%", '!'));
        }
        return any;
    }

    /** 스타일 · 소재 · 성별 중 하나라도 값이 있는가 — {@link #buildSearchPredicate} 의 5번 조건이 생기는가와 같다. */
    // [JA] スタイル・素材・性別のどれか一つでも値があるか — {@link #buildSearchPredicate} の 5 番の条件ができるかと同じ。
    static boolean hasDetailsFilter(SearchParamsRequest params) {
        return !filterValues(params.getStyle()).isEmpty()
                || !filterValues(params.getMaterial()).isEmpty()
                || !filterValues(params.getGender()).isEmpty();
    }

    /** category 파라미터가 숫자 id면 Long으로, 이름이거나 비어있으면 null을 반환한다(이름 검색은 별도 경로). */
    // [JA] category パラメータが数値 id なら Long で、名前か空なら null を返す(名前検索は別経路)。
    static Long parseCategoryId(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(category.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    
    // 페이지 크기 상한: 무제한 pageSize로 인한 대량 조회/deep pagination(DoS) 방지.
    // [JA] ページサイズの上限: 無制限の pageSize による大量照会/deep pagination(DoS)を防ぐ。
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    // ES 관련도 검색으로 가져올 후보 productId 상한 (파셋/필터가 이후 Postgres 에서 더 걸러냄)
    // [JA] ES の関連度検索で持ってくる候補 productId の上限(ファセット/フィルタがその後 Postgres でさらに絞る)
    private static final int ES_CANDIDATE_LIMIT = 1000;

    // (docs/07 R-C1) search.engine=es 일 때만 존재하는 선택적 빈.
    // 존재하고 이 검색의 엔진이 ES 면(docs/74 — 기능 플래그) 텍스트 검색을 "ES 관련도 검색 → id IN 조건" 으로 전환하고,
    // ES 장애 시에는 기존 LIKE 경로로 폴백한다. 테스트 주입을 위해 package-private.
    // [JA] (docs/07 R-C1) search.engine=es のときだけ存在する任意の Bean。
    //      存在し、この検索のエンジンが ES なら(docs/74 — 機能フラグ)テキスト検索を「ES 関連度検索 → id IN 条件」に切り替え、ES 障害時は既存の LIKE 経路へフォールバックする。
    //      テストで注入するため package-private。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    EsProductSearchService esProductSearchService;

    /**
     * @param engine 텍스트 검색 엔진 — {@link SearchEngineSelector} 가 정한다(docs/74). <b>캐시 키에 들어간다.</b>
     *               같은 검색이라도 ES 와 LIKE 의 결과는 다르다. 키에 없으면 비율을 바꾼 뒤 최대 60분(search_cache TTL)
     *               동안 반대쪽 엔진의 결과가 나가고, 추적의 변형 태그와 실제 결과가 어긋난다.
     *               [JA] キャッシュキーに含まれる。同じ検索でも ES と LIKE の結果は違う。キーになければ比率を変えた後
     *               最大 60 分間、反対側のエンジンの結果が返り、トレースの変形タグと実際の結果が食い違う。
     *
     * <p>{@code sync = true} — 만료 순간 같은 검색을 파드마다 한 번만 계산한다({@code SingleFlightCache}). 🐛 2026-10-19 mixed
     * {@code 160904Z}(docs/83 §5): 한꺼번에 채워진 키 48개가 같은 초에 만료되자 키마다 들어온 요청이 모두 DB 에서 다시 계산해 20초 동안
     * 커넥션 대기 22 · 서버 p95 1.5초.
     *
     * <p>트랜잭션(= 커넥션)은 여기서 열지 않는다({@code NOT_SUPPORTED}) — 캐시를 본 뒤 {@link SearchBulkhead} 가 자리를 얻고 나서 연다
     * (읽기 전용 · 시간 상한 {@code catalog.search.query-timeout-seconds}). 🐛 2026-10-20 실습: 필터 검색이 커넥션을 1 ~ 4초씩 쥐어 풀이
     * 늘 차고 home · 상품까지 5xx 였다.
     *
     * <p>[JA] {@code sync = true} — 期限切れの瞬間、同じ検索を Pod ごとに一度だけ計算する({@code SingleFlightCache})。🐛 2026-10-19 mixed
     * {@code 160904Z}(docs/83 §5): 一斉に埋められたキー 48 個が同じ秒に期限切れになると、キーごとに来たリクエストがすべて DB で計算し直し、
     * 20 秒間接続待ち 22 · サーバー p95 1.5 秒。
     *
     * <p>[JA] トランザクション(= 接続)はここでは開かない({@code NOT_SUPPORTED})— キャッシュを見た後 {@link SearchBulkhead} が席を得てから開く
     * (読み取り専用 · 時間上限 {@code catalog.search.query-timeout-seconds})。🐛 2026-10-20 実習: フィルター検索が接続を 1 ~ 4 秒ずつ握り、
     * プールが常に埋まって home · 商品まで 5xx だった。
     */
    @Cacheable(value = "search_cache", cacheManager = "redisCacheManager", sync = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SearchResultDto searchProducts(SearchParamsRequest params, SearchEngine engine) {
        return searchBulkhead.run(hasSkuFilter(params), () -> search(params, engine));
    }

    private SearchResultDto search(SearchParamsRequest params, SearchEngine engine) {
        // category는 숫자 id 또는 이름(name)일 수 있다. 숫자가 아니면 id로 파싱하지 않고 null 처리
        // (기존 Long.parseLong은 이름 검색 시 NumberFormatException → 500을 유발했다).
        // [JA] category は数値 id か名前(name)でありうる。数値でなければ id として解析せず null とする
        //      (以前の Long.parseLong は名前検索時に NumberFormatException → 500 を引き起こしていた)。
        Long categoryId = parseCategoryId(params.getCategory());

        // 1. SKU(가격·색상·사이즈) 필터를 **건 경우에만** 조건(EXISTS)을 만든다 — null 은 "이 필터를 걸지 않았다"는 뜻이다.
        //
        // 🔴 예전에는 조건이 없어도 매번 후보 id 질의를 돌렸다(상품 전량의 id 를 읽어 `in (...)` 에 넣었다).
        //    건 경우에도 후보를 앱으로 읽었다 — 100만 상품에서 힙 부족(skuFilter 주석).
        // [JA] 1. SKU(価格・色・サイズ)フィルタを**指定した場合だけ**条件(EXISTS)を作る — null は「このフィルタを指定していない」という意味である。
        //      🔴 以前は条件がなくても毎回候補 id のクエリを回していた(商品全量の id を読んで `in (...)` に入れていた)。
        //      指定した場合も候補をアプリへ読み込んでいた — 100 万商品でヒープ不足(skuFilter のコメント)。
        com.querydsl.core.types.Predicate skuFilter = skuFilter(params);

        // 2. Querydsl을 사용하여 모든 검색 조건(상품명, 브랜드, 상세 정보, 평점)을 조합하여 최종 상품 목록 조회
        // SKU 조건은 상관 서브쿼리(EXISTS)로 같은 WHERE 에 들어간다.
        // [JA] 2. Querydsl ですべての検索条件(商品名、ブランド、詳細情報、評価)を組み合わせて最終的な商品一覧を照会する。
        //      SKU 条件は相関サブクエリ(EXISTS)として同じ WHERE に入る。
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = buildSearchPredicate(params, categoryId, skuFilter, engine);

        // 상세(스타일 · 소재 · 성별) 필터가 있을 때만 상세 표를 조인한다 — 🐛 예전에는 늘 조인해 상품마다 상세 줄 수만큼 불린 뒤
        // count(distinct) · select distinct 로 다시 접었다(100만 상품의 큰 카테고리에서 개수 세기가 그만큼 무거웠다). 결과는 같다.
        // [JA] 詳細フィルターがあるときだけ詳細テーブルを結合する。以前は常に結合して distinct で畳み直していた。
        boolean joinDetails = hasDetailsFilter(params);

        // page는 1-기반 입력. 0/음수 입력 시 PageRequest.of(-1,..)가 예외를 던지므로 안전하게 클램프.
        // [JA] page は 1 始まりの入力。0/負の入力だと PageRequest.of(-1,..) が例外を投げるので安全にクランプする。
        int safePage = Math.max(1, params.getPage()) - 1;
        int safeSize = params.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(params.getPageSize(), MAX_PAGE_SIZE);
        Pageable pageRequest = createPageRequest(safePage, safeSize);

        /*
         * 개수를 끝까지 세지 않는다 — 한 쪽보다 하나 더 가져와 "더 있는가" 만 본다. 더 있으면 totalProducts = 받은 수 + 1(하한),
         * 없으면 정확한 수. 화면은 이 수를 보여 주지 않고 무한 스크롤의 "더 있는가"(browse.jsx nextPageParam: 받은 수 < total)에만 쓴다.
         *
         * 🐛 2026-10-25 운영(상품 200만 · 카테고리 16 에 55,395개): 가격 필터의 개수 세기가 카테고리의 상품마다 EXISTS 를 돌려 42초 —
         * 검색 상한 5초에 걸려 늘 503 이었다. 그래서 SKU 조건이 있을 때만 세지 않게 했는데(61f546a8b125), 같은 날 하위 카테고리
         * (lab equipment — 상품 6,073개)만 고른 검색도 늘 503 이었다: 목록 10개는 16ms 인데 개수가 200만 상품을 상품 번호 순으로
         * 훑어 하위 카테고리 연결과 맞춰 봤다. 개수는 조건마다 계획이 달라 어느 조건이 안전한지 미리 알 수 없다 — 모든 검색에서 세지 않는다.
         * 맞는 것이 아주 드문 조건은 목록도 끝까지 훑는다(상한 5초 → 503).
         * [JA] 件数を最後まで数えない — 1 件多く取って「まだあるか」だけを見る。本番(200 万商品)で価格フィルターの件数が 42 秒、
         *      同じ日にサブカテゴリだけの検索も件数で 5 秒を超えて常に 503 だった(一覧 10 件は 16ms)。条件ごとに計画が違い安全な条件を
         *      前もって決められないので、すべての検索で数えない。
         */
        long totalProducts = 0;
        /*
         * 🐛 2026-10-25 운영: 가격 칸이 "4 ~ 0"(하한 > 상한 — 상한 칸의 스피너를 누르면 빈 칸이 0 이 된다)이면 맞는 SKU 가 하나도 없어
         * 목록 질의가 카테고리의 상품을 끝까지 훑었다(55,395개 · 상한 5초 → 503, 하한을 1씩 올려도 같다). 그런 범위는 DB 에 묻지 않고
         * 0건이다 — 화면은 "조건에 맞는 상품이 없습니다".
         * [JA] 価格の下限 > 上限(「4 ~ 0」)は合う SKU がなく、一覧がカテゴリを最後まで走査して 503 だった。DB に聞かず 0 件。
         */
        //
        // 🐛 2026-10-26 운영: 카테고리(consumer electronics)를 고른 채 옆 메뉴에서 다른 카테고리의 하위(food › dairy products)를
        // 누르면 두 조건이 AND 로 묶여 맞는 상품이 0 — 목록 질의가 그 카테고리를 끝까지 훑어 5초 상한 503 이었다(화면은 고쳤다 —
        // 하위를 누르면 그 상위로 바꾼다; 옛 화면 · 북마크 URL 은 그대로 온다). 이것도 DB 에 묻지 않고 0건이다.
        // [JA] カテゴリを選んだまま別カテゴリのサブカテゴリを押すと AND で 0 件確定なのに一覧がカテゴリを最後まで走査して 503 だった。DB に聞かず 0 件。
        boolean emptyResult = emptyPriceRange(params) || subcategoryOutsideCategory(categoryId, params);

        com.querydsl.jpa.impl.JPAQuery<Product> listQuery = queryFactory.selectFrom(product);
        if (joinDetails) {
            listQuery.distinct().leftJoin(product.details, productDetails);
        }
        List<Product> products = emptyResult ? List.of() : listQuery
                .where(builder)
                // 🔒 순서를 고정한다 — 🐛 예전에는 ORDER BY 없이 OFFSET/LIMIT 이라, 무한 스크롤의 2페이지가 1페이지와 겹치거나
                //    상품을 건너뛸 수 있었다(PostgreSQL 은 순서 없는 결과의 순서를 보장하지 않는다 — 계획 · 갱신에 따라 바뀐다).
                //    최신 등록 순. [JA] 順序を固定する — 以前は ORDER BY なしの OFFSET/LIMIT で、ページが重複 · 欠落し得た。
                .orderBy(product.productId.desc())
                .offset(pageRequest.getOffset())
                .limit(pageRequest.getPageSize() + 1)
                .fetch();
        boolean more = products.size() > pageRequest.getPageSize();
        if (more) {
            products = products.subList(0, pageRequest.getPageSize());
        }
        totalProducts = pageRequest.getOffset() + products.size() + (more ? 1 : 0);

        List<ProductDto> pageContent = eventPricing.apply(products.stream()
                .map(ProductMapper::toDto)
                .collect(Collectors.toList()));

        Page<ProductDto> productPage = new PageImpl<>(pageContent, pageRequest, totalProducts);

        /*
         * 3. 필터링 메뉴에 표시할 정보 조회
         *
         * 🐛 예전에는 **조건 없이 매번** 이 여섯 개를 다 읽었다. 그중 둘은
         * `findAll()` — 카테고리 전체와 하위 카테고리 전체다. 그런데 화면은 첫 페이지의
         * 것만 쓰고 나머지는 버린다(browse.jsx 가 `pages[0]` 에서만 읽는다).
         *
         * 무한 스크롤을 붙인 뒤로 이게 더 도드라졌다 — 2·3·4페이지를 받을 때마다 쓰지도
         * 않을 카테고리 전체가 따라왔다. 상품 20개를 더 받자고 카테고리·하위카테고리·
         * 색상·사이즈·상세·브랜드 여섯 질의를 더 돌린 셈이다.
         *
         * 필요 없다고 말한 요청에는 만들지 않는다. 기본값은 true 라 이 값을 모르는
         * 호출부의 동작은 그대로다.
         *
         * <p>[JA] 3. フィルタメニューに表示する情報の照会
         * 🐛 以前は<b>条件なしに毎回</b>この六つをすべて読んでいた。うち二つは `findAll()` — カテゴリ全体とサブカテゴリ全体である。だが画面は最初のページのものだけ使い、残りは捨てる(browse.jsx は `pages[0]` からだけ読む)。
         * 無限スクロールを付けてからこれがより目立った — 2・3・4 ページを受け取るたびに使いもしないカテゴリ全体が付いてきた。商品 20 件を追加で受け取るために、カテゴリ・サブカテゴリ・色・サイズ・詳細・ブランドの六クエリを余分に回していたことになる。
         * 不要と言ったリクエストには作らない。既定値は true なので、この値を知らない呼び出し側の動作はそのままである。
         */
        SearchResultDto.SearchResultDtoBuilder result = SearchResultDto.builder()
                .product(productPage)
                .totalProducts((int) totalProducts);

        // 메뉴 넷(색상 · 사이즈 · 상세 · 브랜드)은 빈 목록으로 두고 SearchFilterFacets.attach 가 채운다 — 캐시된 이 결과에 넣으면
        // 60분 동안 그 메뉴가 그대로고, 계산이 늦으면 검색 전체가 그만큼 늦다.
        // [JA] メニュー四つ(色・サイズ・詳細・ブランド)は空リストにしておき SearchFilterFacets.attach が埋める — キャッシュされるこの結果に入れると
        //      60 分間そのメニューが変わらず、計算が遅れれば検索全体がその分遅れる。
        if (params.isIncludeFilters()) {
            List<CategoryDto> categoryDtos = categoryRepository.findAll().stream().map(CategoryMapper::toDto).collect(Collectors.toList());
            List<SubCategoryDto> subCategoryDtos = subCategoryRepository.findAll().stream().map(SubcategoryMapper::toDto).collect(Collectors.toList());

            result.categories(categoryDtos)
                  .subCategories(subCategoryDtos)
                  .colors(List.of())
                  .sizes(List.of())
                  .details(List.of())
                  .brandsDB(List.of());
        } else {
            // null 이 아니라 빈 목록을 준다. 받는 쪽이 길이를 재거나 순회할 때
            // null 검사를 빠뜨려도 터지지 않는다.
            // [JA] null ではなく空リストを渡す。受け取る側が長さを測ったり回したりするとき、null 検査を忘れても落ちない。
            result.categories(List.of())
                  .subCategories(List.of())
                  .colors(List.of())
                  .sizes(List.of())
                  .details(List.of())
                  .brandsDB(List.of());
        }

        // 4. 결과 DTO 구성 및 반환
        // [JA] 4. 結果 DTO の構成と返却
        return result.build();
    }

    private BooleanBuilder buildSearchPredicate(SearchParamsRequest params, Long categoryId, com.querydsl.core.types.Predicate skuFilter,
            SearchEngine engine) {
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = new BooleanBuilder();

        // 1. SKU 조건 (Price, Color, Size — skuFilter)
        //
        // 🔴 예전 조건은 `!= null && !isEmpty()` 였다. 즉 <b>필터가 아무것도 못 찾으면
        //    조건 자체를 버렸다</b> — "맞는 게 없다" 가 "제한이 없다" 로 뒤집힌다.
        //    사용자에게는 이렇게 보인다: 가격대를 좁힐수록 상품이 줄다가, 맞는 게 하나도
        //    없는 구간을 고르는 순간 <b>카탈로그 전체</b>가 돌아온다. 색상·사이즈도 같다.
        //
        //    null(필터를 걸지 않음)과 빈 목록(걸었는데 매칭 없음)을 반드시 갈라야 한다.
        //
        //    지금은 EXISTS 라 맞는 SKU 가 없으면 그 상품이 빠질 뿐이다 — 조건이 사라질 수 없다(skuFilter).
        // [JA] 1. SKU 条件(Price、Color、Size — skuFilter)
        //      🔴 以前の条件は `!= null && !isEmpty()` だった。つまり**フィルタが何も見つけられないと条件自体を捨てていた** — 「一致なし」が「制限なし」に反転する。
        //      ユーザーにはこう見える: 価格帯を狭めるほど商品が減るが、一致が一つもない区間を選んだ瞬間**カタログ全体**が返ってくる。色・サイズも同じ。
        //      null(フィルタ指定なし)と空リスト(指定したが一致なし)は必ず区別しなければならない。
        //      今は EXISTS なので一致する SKU がなければその商品が抜けるだけ — 条件が消えることはない(skuFilter)。
        if (skuFilter != null) {
            builder.and(skuFilter);
        }

        // 2. categoryId 조건
        // [JA] 2. categoryId 条件
        if (categoryId != null) {
            builder.and(product.category.categoryId.eq(categoryId));
        } else if (params.getCategory() != null && !params.getCategory().isBlank()) {
            // 🐛 예전에는 여기서 **아무 일도 하지 않았다.** parseCategoryId 가 숫자가
            //    아니면 null 을 돌려주고, 그 다음 이름으로 거르는 코드는 어디에도 없었다
            //    (주석에는 "이름 검색은 별도 경로" 라고 적혀 있지만 그런 경로가 없다).
            //    그래서 category=shoes 처럼 이름으로 들어오면 **필터가 조용히 사라지고
            //    전체 상품이 나왔다** — 사용자에게는 "카테고리를 골랐는데 다 나온다"다.
            // [JA] 🐛 以前はここで**何もしていなかった。**parseCategoryId は数値でなければ null を返し、その後名前で絞るコードはどこにもなかった
            //      (コメントには「名前検索は別経路」とあるが、そんな経路はない)。
            //      そのため category=shoes のように名前で来ると**フィルタが黙って消えて全商品が出た** — ユーザーには「カテゴリを選んだのに全部出る」である。
            builder.and(product.category.categoryName.equalsIgnoreCase(params.getCategory().trim()));
        }

        // 2-1. subCategory 조건
        //
        // 🐛 하위 카테고리는 **화면에 그려지기만 했다.** 누를 수도 없었고(onClick 없음),
        //    누를 수 있었다 해도 보낼 파라미터도 여기 조건도 없었다.
        //
        // category 와 AND 로 묶인다 — 상위를 고른 뒤 그 안에서 좁히는 것이 화면의 모양이다.
        // [JA] 2-1. subCategory 条件
        //      🐛 サブカテゴリは**画面に描かれるだけだった。**押すこともできず(onClick なし)、押せたとしても送るパラメータもここの条件もなかった。
        //      category と AND で結ばれる — 上位を選んだ後でその中を絞るのが画面の形である。
        Long subCategoryId = parseCategoryId(params.getSubCategory());
        if (subCategoryId != null) {
            // exists 서브쿼리가 아니라 조인이면 상품이 하위 카테고리 수만큼 중복된다.
            // [JA] exists サブクエリではなく結合だと、商品がサブカテゴリの数だけ重複する。
            QProductSubcategory link = QProductSubcategory.productSubcategory;
            builder.and(JPAExpressions.selectOne()
                    .from(link)
                    .where(link.product.eq(product)
                            .and(link.subcategory.subcategoryId.eq(subCategoryId)))
                    .exists());
        }

        // 3. name 조건 — 이 검색의 엔진이 ES 면 관련도 검색으로 후보 id 를 구해 IN 으로 좁힌다 (docs/07 R-C1, docs/74).
        //    POSTGRES · ES 장애 시에는 기존 LIKE 경로 유지(폴백).
        // [JA] 3. name 条件 — この検索のエンジンが ES なら関連度検索で候補 id を求めて IN で絞る(docs/07 R-C1、docs/74)。
        //      POSTGRES・ES 障害時は既存の LIKE 経路を維持(フォールバック)。
        if (params.getSearch() != null && !params.getSearch().isEmpty()) {
            List<Long> esIds = searchIdsViaEs(params.getSearch(), engine);
            if (esIds == null) {
                // ES 비활성 또는 실패 → 선행 와일드카드 LIKE. 양쪽을 product.search_key(NFKC + 소문자, V23)로 비교한다 —
                // 전각 · 반각으로 친 검색어가 원문 그대로 저장된 이름을 찾는다(검색어는 SearchParamsRequest 가 이미 NFKC).
                // [JA] 両側を product.search_key(NFKC + 小文字)で比較 — 全角 · 半角の検索語が原文の名前を見つける。
                builder.and(nameMatches(product.name, params.getSearch()));
            } else if (esIds.isEmpty()) {
                // ES 가 "매칭 없음"이라고 답함 → 빈 결과 (IN () 는 SQL 오류이므로 불가능 조건 사용)
                // [JA] ES が「一致なし」と答えた → 空の結果(IN () は SQL エラーなので不可能な条件を使う)
                builder.and(product.productId.eq(-1L));
            } else {
                builder.and(product.productId.in(esIds));
            }
        }

        // 4. brand 조건
        // [JA] 4. brand 条件
        com.querydsl.core.types.Predicate brand = containsAny(product.brand, filterValues(params.getBrand()));
        if (brand != null) {
            builder.and(brand);
        }

        // 5. style, material, gender 조건 (OR 로직) — 값마다 글자 그대로 포함(filterValues)
        // [JA] 5. style、material、gender 条件(OR ロジック) — 値ごとに文字どおり含む(filterValues)
        BooleanBuilder detailsBuilder = new BooleanBuilder();
        for (String raw : new String[] { params.getStyle(), params.getMaterial(), params.getGender() }) {
            com.querydsl.core.types.Predicate any = containsAny(productDetails.value, filterValues(raw));
            if (any != null) {
                detailsBuilder.or(any);
            }
        }
        if (detailsBuilder.hasValue()) {
            builder.and(detailsBuilder);
        }

        // 6. rating 조건
        // [JA] 6. rating 条件
        if (params.getRating() != 0.0f) {
            builder.and(product.rating.goe(params.getRating()));
        }

        // 7. shipping 조건
        //
        // 🐛 `SearchParamsRequest.shipping` 은 <b>받기만 하고 한 번도 쓰이지 않았다.</b>
        //    화면의 "무료배송" 체크박스는 URL 을 바꾸고 요청에도 실려 갔지만, 검색 조건에는
        //    들어간 적이 없다 — 별점 필터와 같은 모양의 결함이다(docs/40).
        //
        //    화면이 보내는 값은 0(무료)뿐이지만 조건은 "그 값 이하"로 둔다. 나중에
        //    "배송비 3,000원 이하" 같은 것을 붙일 때 서버를 다시 고치지 않아도 된다.
        //    배송비가 null 인 상품은 제외된다 — 모르는 값을 무료라고 말하지 않는다.
        // [JA] 7. shipping 条件
        //      🐛 `SearchParamsRequest.shipping` は**受け取るだけで一度も使われていなかった。**画面の「送料無料」チェックボックスは URL を変えリクエストにも載っていたが、検索条件に入ったことはない
        //      — 評価フィルタと同じ形の欠陥である(docs/40)。
        //      画面が送る値は 0(無料)だけだが、条件は「その値以下」にしておく。後で「送料 3,000 ウォン以下」のようなものを付けるときにサーバーを直さずに済む。
        //      送料が null の商品は除外される — 分からない値を無料とは言わない。
        if (params.getShipping() != null) {
            builder.and(product.shipping.loe(params.getShipping()));
        }

        return builder;
    }

    @Cacheable(value = "products_by_category", key = "#category + '_' + #pageRequest.pageNumber", cacheManager = "redisCacheManager", sync = true)
    public List<Product> findAllByCategory(Long category, Pageable pageRequest) {

        Page<Product> productPage = productRepository.findAllByCategory_CategoryId(category, pageRequest);

        return productPage.getContent();
    }
}