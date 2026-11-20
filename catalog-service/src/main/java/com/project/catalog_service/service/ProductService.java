package com.project.catalog_service.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Base64;
import org.checkerframework.checker.units.qual.s;
import org.springframework.beans.BeansException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.domain.Page;

import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import com.project.common.dto.catalog.request.CartProductInfoRequest;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductQADto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.catalog.request.ProductAddRequest;
import com.project.common.dto.catalog.request.ProductRequest;
import com.project.common.message.dto.request.OrderProductRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductDetails;
import com.project.catalog_service.model.ProductQA;
import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.model.ProductSubcategory;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductQARepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.util.CursorPagenation;
import com.project.common.util.FileUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.response.ProductResponse;
import com.project.catalog_service.mapper.ProductColorMapper;
import com.project.catalog_service.mapper.ProductDetailsMapper;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.mapper.ProductQAMapper;
import com.project.catalog_service.mapper.ProductSizeMapper;

//import jakarta.mail.Multipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService  {

    
    private final CategoryRepository categoryRepository;

    private final SubcategoryRepository subCategoryRepository;

    private final ProductRepository productRepository;

    private final ProductSizeRepository productSizeRepository;

    private final ProductSkuRepository productskuRepository;

    private final ProductColorRepository productColorRepository;

    private final ProductQARepository productQARepository;

    private final ImageService imageService;

    // 실시간 ES 반영. search.engine=es 가 아니면 내부에서 no-op 이다.
    // [JA] リアルタイム ES 反映。search.engine=es でなければ内部で no-op である。
    private final ProductIndexService productIndexService;

    /**
     * 진행 중인 기획전 할인을 조회 결과에 얹는다.
     *
     * <p>DB 의 {@code ProductSku.discount} 는 <b>기획전과 무관한 상시 할인</b> 하나의 뜻만
     * 갖는다. 기획전이 시작할 때 그 값을 덮어쓰면 종료 시 복원이 그 사이의 수정을 덮기
     * 때문이다(EventPricing 주석). 합성은 {@link #convertToDto} 한 곳에서만 한다 — 새
     * 조회 경로가 생겨도 그 funnel 을 지나면 자동으로 적용된다.
     *
     * <p>캐시에 합성된 값이 들어가지만, 기획전이 시작·종료할 때 {@code EventScheduler} 가
     * 상품 캐시를 비운다. 그래서 stale 구간은 상태 전환 시점 하나뿐이다.
     *
     * <p>[JA] 進行中の企画展割引を照会結果に載せる。
     * <p>DB の {@code ProductSku.discount} は<b>企画展と無関係な常時割引</b>一つの意味だけを持つ。企画展開始時にその値を上書きすると、終了時の復元がその間の修正を上書きするからだ(EventPricing のコメント)。
     * 合成は {@link #convertToDto} 一か所だけで行う — 新しい照会経路ができても、その funnel を通れば自動で適用される。
     * <p>キャッシュには合成済みの値が入るが、企画展の開始・終了時に {@code EventScheduler} が商品キャッシュを空にする。だから stale な区間は状態遷移の時点一つだけである。
     */
    private final EventPricing eventPricing;

    /** 재고가 0 을 건너면(품절·재입고) 알린다 — docs/53. */
    // [JA] 在庫が 0 をまたいだら(品切れ・再入荷)通知する — docs/53。
    private final com.project.catalog_service.message.producer.StockEventProducer stockEvents;

    // private final ObjectMapper objectMapper;

    // @Resource(name = "redisTemplate")
    // private ValueOperations<String, Object> productsOps;
    

    // 🔒 products 캐시의 키는 메서드마다 접두사를 단다 — 🐛 예전에는 slug · 이름 · 'warmup' · 'all' · 카테고리 키가 한 공간에 섞여,
    //    `GET /api/product/warmup`(slug 조회)이 관리자 목록의 'warmup' 키에 빈 목록을 영구로 박았다. 빈 결과는 캐시하지 않는다 —
    //    없는 slug · 카테고리마다 키가 쌓이지 않게(익명 요청으로 공유 Redis 를 채울 수 있었다).
    // [JA] キーにはメソッドごとの接頭辞を付ける。以前は slug と 'warmup' などが同じ空間で衝突した。空の結果はキャッシュしない。
    @Cacheable(value = "products", key = "'cat:' + #categoryName + '_' + #cursor + '_' + #pageSize",
            unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> getProductsByCategory(String categoryName, Long cursor, int pageSize) {
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);

        Page<Product> productsPage;
        if (cursor < 0) {
            productsPage = productRepository.findAllByCategory_CategoryNameOrderByProductIdDesc(categoryName,
                    pageRequest);
        } else {
            productsPage = productRepository.findAllByCategory_CategoryNameAndProductIdLessThanOrderByProductIdDesc(
                    categoryName, cursor, pageRequest);
        }
        
        CursorPagenation<Product> productCursor = CursorPagenation.of(productsPage.getContent(), pageSize);
        // ProductResponse 는 static 팩토리라 매퍼를 직접 쓴다 — convertToDto funnel 을 타지
        // 않으므로 여기서 기획전 할인을 얹는다.
        // [JA] ProductResponse は static ファクトリなのでマッパーを直接使う — convertToDto の funnel を通らないので、ここで企画展割引を載せる。
        return eventPricing.apply(
                ProductResponse.of(productCursor, productsPage.getContent().size()).getContents());
    }
    /**
     * 관리자 상품 목록 — 커서 페이징.
     *
     * <p>🐛 <b>예전에는 관리자 화면이 카탈로그 전체를 한 번에 받았다.</b>
     * {@code GET /api/product/products} → {@code warmUpProductCaches(20)} 인데, 이름과 달리
     * 이것은 전 카테고리 × 전 서브카테고리를 돌며 <b>모든 상품을 한 배열로</b> 만든다.
     * 게다가 {@code @Cacheable(key = "'warmup'")} 이라 <b>카탈로그 전체가 Redis 키 하나</b>에
     * 들어갔다. 상품이 250개라 견디고 있었을 뿐, 규모가 커지면
     *
     * <ul>
     *   <li>응답 JSON 이 수 MB → 게이트웨이 타임아웃, 브라우저 메모리</li>
     *   <li>Redis 키 하나가 수십 MB → maxmemory 정책에 따라 다른 키를 밀어내거나
     *       이 키가 evict 되어 매번 전 카탈로그를 다시 조회</li>
     * </ul>
     *
     * <p><b>캐시하지 않는다.</b> 관리자 목록은 방금 등록한 상품이 보여야 하는 화면이고,
     * 커서마다 키가 갈려 캐시 적중률도 낮다. 무엇보다 위의 Redis 문제가 캐시 때문이었다.
     *
     * @param cursor 마지막으로 받은 상품 id. 첫 페이지는 음수({@code -1}).
     *
     * <p>[JA] 管理者の商品一覧 — カーソルページング。
     * <p>🐛 <b>以前は管理者画面がカタログ全体を一度に受け取っていた。</b>{@code GET /api/product/products} → {@code warmUpProductCaches(20)} だが、名前と違い全カテゴリ × 全サブカテゴリを回って
     * <b>全商品を一つの配列に</b>していた。しかも {@code @Cacheable(key = "'warmup'")} なので<b>カタログ全体が Redis キー一つ</b>に入った。商品が 250 個だから耐えていただけで、
     * 規模が大きくなると応答 JSON が数 MB(ゲートウェイタイムアウト・ブラウザメモリ)、Redis キー一つが数十 MB(maxmemory ポリシーで他のキーを追い出すか、このキーが evict されて毎回全カタログを再照会)になる。
     * <p><b>キャッシュしない。</b>管理者一覧はいま登録した商品が見えるべき画面で、カーソルごとにキーが分かれてヒット率も低い。何より上の Redis 問題はキャッシュのせいだった。
     * cursor: 最後に受け取った商品 id。最初のページは負数({@code -1})。
     */
    @Transactional(readOnly = true)
    public ProductResponse getProductsPage(Long cursor, int pageSize) {
        // 상한을 둔다. pageSize=100000 이 오면 결국 전체 조회가 되어 고친 의미가 없다.
        // [JA] 上限を置く。pageSize=100000 が来れば結局全件照会になり、直した意味がない。
        int safeSize = Math.max(1, Math.min(pageSize, MAX_ADMIN_PAGE_SIZE));

        // +1 을 더 읽어 "다음이 있는가" 를 판정한다(CursorPagenation).
        // [JA] 1 件多く読んで「次があるか」を判定する(CursorPagenation)。
        PageRequest pageRequest = PageRequest.of(0, safeSize + 1);

        Page<Product> page = (cursor == null || cursor < 0)
                ? productRepository.findAllByOrderByProductIdDesc(pageRequest)
                : productRepository.findAllByProductIdLessThanOrderByProductIdDesc(cursor, pageRequest);

        CursorPagenation<Product> cursorPage = CursorPagenation.of(page.getContent(), safeSize);

        // 전체 건수는 목록에 "총 N개" 를 보여주기 위한 것이다. 커서 페이징은 총계를 몰라도
        // 동작하므로, 한 번만(첫 페이지에서만) 센다 — 페이지마다 count(*) 를 돌릴 이유가 없다.
        // [JA] 総件数は一覧に「全 N 件」を表示するためのもの。カーソルページングは総数を知らなくても動くので、一度だけ(最初のページでだけ)数える
        //      — ページごとに count(*) を回す理由はない。
        long total = (cursor == null || cursor < 0) ? productRepository.count() : -1;

        ProductResponse response = ProductResponse.of(cursorPage, total);
        // 관리자 목록도 고객이 보는 것과 같은 할인을 보여야 한다. 여기만 정가면 관리자는
        // 기획전이 걸린 줄 모르고 상품을 수정한다.
        // [JA] 管理者一覧も顧客が見るのと同じ割引を見せなければならない。ここだけ定価だと、管理者は企画展がかかっていると知らずに商品を修正する。
        eventPricing.apply(response.getContents());
        return response;
    }

    /** 관리자 목록 한 페이지의 상한. */
    // [JA] 管理者一覧 1 ページの上限。
    public static final int MAX_ADMIN_PAGE_SIZE = 100;

    /** 공개 카테고리 목록 한 페이지의 상한(ProductController). */
    // [JA] 公開カテゴリ一覧 1 ページの上限(ProductController)。
    public static final int MAX_PUBLIC_PAGE_SIZE = 100;

    @Cacheable(value = "products", key = "'name:' + #productName", unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> getProductsByName(String productName) {

        List<Product> products = productRepository.findByName(productName);

        List<ProductDto> result = new ArrayList<>();

        if (products != null) {

            for (Product p : products) {
                ProductDto dto = convertToDto(p);
                result.add(dto);
            }

            return result;
        }
        return null;
    }

    

    @Cacheable(value = "products", key = "'all'", unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> getProducts() {
        List<Product> products = productRepository.findAll();
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }


     
    // 키에 pageSize — 🐛 홈(4)과 관리자 목록(20)이 같은 'warmup' 키를 써서 먼저 부른 쪽의 개수가 둘 다에 나왔다(아래 메서드와 같은 버그).
    // [JA] キーに pageSize — 🐛 ホーム(4)と管理者一覧(20)が同じ 'warmup' キーを使い、先に呼んだ方の件数が両方に出ていた(下のメソッドと同じバグ)。
    @Cacheable(value = "products", key = "'warmup:' + #pageSize", unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> warmUpProductCaches(int pageSize) {
        
        log.info("Starting to warm up product caches...");        

        // 1. 모든 카테고리 목록을 가져옵니다.
        // [JA] 1. すべてのカテゴリ一覧を取得する。
        List<Category> allCategories = categoryRepository.findAll();
        List<ProductDto> products = new ArrayList<>();

        for (Category category : allCategories) {
            // 2. 현재 카테고리에 속한 모든 서브카테고리 목록을 가져옵니다.
            // [JA] 2. 現在のカテゴリに属するすべてのサブカテゴリ一覧を取得する。
            List<Subcategory> allSubcategories = subCategoryRepository.findByCategory(category);
            
            for (Subcategory subcategory : allSubcategories) {
                int page = 0;
                
                    log.info("Fetching products for Category: {}, Subcategory: {}, Page: {}",
                            category.getCategoryName(), subcategory.getSubcategoryName(), page);

                    // 3. getProductsByCategoryAndSubcategory 메소드를 호출하여 캐시를 채웁니다.
                    // [JA] 3. getProductsByCategoryAndSubcategory メソッドを呼んでキャッシュを満たす。
                    products.addAll( getProductsByCategoryAndSubcategory(
                            category.getCategoryName(), subcategory.getSubcategoryName(), page, pageSize));

                
                    page++; // 다음 페이지로 이동 ／ [JA] 次のページへ移動
                
            }
        }
        log.info("Product cache warm-up completed.");
        return products;
    }

     /**
     * 카테고리 및 서브카테고리별로 상품을 10개씩 페이지네이션하여 조회합니다.
     * @param categoryName
     * @param subcategoryName
     * @param page
     * @return
     *
     * <p>[JA] カテゴリおよびサブカテゴリ別に商品を 10 件ずつページネーションして照会する。
     */
    // ⚠️ 키에 pageSize 가 **빠져 있었다.** /api/product/frontpage 는 pageSize=4 로,
    //    /api/product/products 는 20 으로 같은 메서드를 부르는데 키가 같아 **한 엔트리를
    //    공유했다** — 먼저 호출된 쪽이 이겨서, 홈에 20개가 깔리거나 목록에 4개만 나왔다.
    //    캐시가 비어 있을 때만 재현되니 "가끔 개수가 다르다"로 보였다.
    //    같은 파일의 getProductsByCategory(114행)는 처음부터 pageSize 를 키에 넣고 있었다.
    // [JA] ⚠️ キーに pageSize が**抜けていた。**/api/product/frontpage は pageSize=4、/api/product/products は 20 で同じメソッドを呼ぶのにキーが同じで
    //      **一つのエントリを共有していた** — 先に呼ばれた方が勝ち、ホームに 20 件並ぶか一覧に 4 件しか出なかった。
    //      キャッシュが空のときだけ再現するので「たまに件数が違う」に見えた。同じファイルの getProductsByCategory(114 行)は最初から pageSize をキーに入れていた。
    @Cacheable(value = "products",
            key = "'sub:' + #categoryName + '_' + #subcategoryName + '_' + #page + '_' + #pageSize",
            unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> getProductsByCategoryAndSubcategory(String categoryName, String subcategoryName, int page, int pageSize) {
         // 기본 페이지 크기를 10으로 설정합니다.
        // 한 페이지에 1개씩 가져오도록 PageRequest 객체를 생성합니다.
         // [JA] 既定のページサイズを 10 にする。
         //      1 ページに 1 件ずつ取得するよう PageRequest オブジェクトを作る。
        PageRequest pageRequest = PageRequest.of(page, pageSize);

        // ProductRepository에 새로운 쿼리 메소드를 추가하여 사용해야 합니다.
        // 이 쿼리 메소드는 categoryName과 subcategoryName을 기준으로 결과를 필터링합니다.
        // 이 메소드명은 JPA 규칙을 따르며, ProductSubcategory 엔티티를 통해 조인됩니다.
        // [JA] ProductRepository に新しいクエリメソッドを追加して使う必要がある。
        //      このクエリメソッドは categoryName と subcategoryName を基準に結果を絞り込む。
        //      メソッド名は JPA の規則に従い、ProductSubcategory エンティティを通じて結合される。
        Page<Product> productsPage = productRepository
                .findAllByCategory_CategoryNameAndSubcategories_Subcategory_SubcategoryName(
                        categoryName, subcategoryName, pageRequest);

        return productsPage.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // 없는 id 는 캐시하지 않는다 — 🐛 null 을 막아 둔 캐시가 IllegalArgumentException 을 던져 404 대신 400 이 나갔다.
    // [JA] 存在しない id はキャッシュしない — 🐛 null を禁じたキャッシュが IllegalArgumentException を投げ、404 の代わりに 400 が出ていた。
    @Cacheable(value = "product", key = "#productId", unless = "#result == null")
    public ProductDto getProductById(Long productId) {
        return productRepository.findById(productId)
                .map(this::convertToDto)
                .orElse(null);
    }

    /**
     * 여러 상품을 <b>한 번에</b> 조회한다 — cart-service 가 장바구니 항목 수만큼
     * {@link #getProductById} 를 직렬로 부르던 것을 대체한다(docs/38 §1.13).
     *
     * <p>캐시를 타지 않는다. {@code @Cacheable} 은 id 하나에 DTO 하나를 묶는 구조라 목록에는
     * 맞지 않고, 여기서 항목별로 캐시를 뒤지면 결국 항목 수만큼 왕복이 되어 배치의 의미가
     * 없어진다. DB 한 번(IN 절) + {@code @BatchSize} 로 묶인 컬렉션 조회가 항목 수와 무관하게
     * 상수 번이라 그편이 낫다. 찾지 못한 id 는 결과에서 그냥 빠진다 — 호출자가 id 로 대조한다.
     * (DELETED 는 {@code @SQLRestriction} 이 거른다.)
     *
     * <p>[JA] 複数の商品を<b>一度に</b>照会する — cart-service がカート項目の数だけ {@link #getProductById} を直列に呼んでいたのを置き換える(docs/38 §1.13)。
     * <p>キャッシュを通らない。{@code @Cacheable} は id 一つに DTO 一つを結び付ける構造で一覧には合わず、ここで項目ごとにキャッシュを探せば結局項目数だけ往復になりバッチの意味がなくなる。
     * DB 一回(IN 句)+ {@code @BatchSize} でまとめたコレクション照会が項目数と無関係に定数回なので、その方がよい。見つからない id は結果から抜けるだけ — 呼び出し側が id で突き合わせる。
     * (DELETED は {@code @SQLRestriction} が除く。)
     */
    @Transactional(readOnly = true)
    public List<ProductDto> getProductsByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findAllById(productIds).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products", key = "'slug:' + #slug", unless = "#result == null || #result.isEmpty()")
    public List<ProductDto> getProductsBySlug(String slug) {
        List<Product> products = productRepository.findBySlug(slug);
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }
    
    /**
     * 별점 — user-service 가 리뷰가 바뀔 때마다(그리고 매일 평점 대사가 리뷰 있는 상품마다) 부른다.
     *
     * <p><b>그 상품의 캐시만</b> 맞춘다: id 상세(product)는 새 값으로 바꿔 넣고, slug 상세 · 이름 조회(products 의 'slug:' · 'name:')는
     * 지운다. 목록 · 검색 · 카테고리 쪽(products 의 다른 키 · search_cache · products_by_category)의 별점은 만료(30 · 60분)까지 옛 값이고,
     * "별점 N 이상" 필터도 그만큼 늦다 — 받아들인다. 필터 메뉴(colors · sizes · details · brands)는 별점과 상관없다.
     *
     * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5 · §2.2): 예전에는 위 일곱 캐시를 {@code allEntries} 로 통째로 비웠다 — 리뷰 초당
     * 1건에 홈 카드 · 목록 · 검색 · 메뉴가 늘 캐시 밖이 되어 모든 요청이 DB 커넥션을 기다렸고(대기 37 ~ 47, 받기까지 1 ~ 2초), 실패
     * 0.25% → 79.90%. 그 전에는 반대로 products 를 비우지 않아 상세 별점이 영구 캐시에 옛 값으로 남았다 — 상세는 지금도 바로 맞는다.
     *
     * <p>[JA] 評価 — user-service がレビューが変わるたびに(そして毎日の評価照合がレビューのある商品ごとに)呼ぶ。
     *
     * <p>[JA] <b>その商品のキャッシュだけ</b>を合わせる: id の詳細(product)は新しい値に置き換え、slug の詳細 · 名前の照会(products の 'slug:' · 'name:')は
     * 消す。一覧 · 検索 · カテゴリ側(products の他のキー · search_cache · products_by_category)の評価は期限(30 · 60 分)まで古い値で、
     * 「評価 N 以上」フィルターもその分遅れる — 受け入れる。フィルターメニュー(colors · sizes · details · brands)は評価と関係ない。
     *
     * <p>[JA] 2026-10-19 データ規模測定(docs/83 §5 · §2.2): 以前は上の七つのキャッシュを {@code allEntries} で丸ごと消していた — レビュー毎秒
     * 1 件でトップのカード · 一覧 · 検索 · メニューが常にキャッシュ外になり、すべてのリクエストが DB 接続を待ち(待ち 37 〜 47、取得まで 1 〜 2 秒)、失敗
     * 0.25% → 79.90%。その前は逆に products を消さず、詳細の評価が永続キャッシュに古い値で残っていた — 詳細は今もすぐ合う。
     */
    @Caching(
            put = @CachePut(value = "product", key = "#productId"),
            evict = {
                    @CacheEvict(value = "products", key = "'slug:' + #result.slug", condition = "#result != null"),
                    @CacheEvict(value = "products", key = "'name:' + #result.name", condition = "#result != null")
            })
    public ProductDto updateRating(Long productId, float rating) {
        productRepository.updateRating(productId, rating);
        Product product = productRepository.findById(productId).orElseThrow();
        return convertToDto(product);
    }

    
    @CacheEvict(value = {"products", "product", "search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    public List<ProductSku> load(List<ProductAddRequest> products, List<MultipartFile> images,
            List<MultipartFile> colorImages) throws IOException {

        List<ProductSku> result = new ArrayList<>();

        int i = 0;
        for (ProductAddRequest pr : products) {

            ProductSku sku = null;

            sku = load(pr, images.get(i), colorImages.get(i));
            result.add(sku);
            i++;
        }

        return result;
    }

    
    @CacheEvict(value = {"products", "product", "search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    public ProductSku load(ProductAddRequest request, MultipartFile images, MultipartFile colorImage)
            throws IOException {
        Product product = createOrFindProduct(request);
        return loadSku(request, product, images, colorImage);
    }

    // private 이라 @Transactional · @CachePut 이 돌지 않았다 — 그리고 "product" 캐시에 엔티티를 넣으려 했다. 둘 다 지웠다.
    // [JA] private なので @Transactional・@CachePut が効いていなかった — しかも "product" キャッシュにエンティティを入れようとしていた。両方消した。
    private Product createOrFindProduct(ProductAddRequest request) {
        if (request.getParent() != null && !request.getParent().isEmpty()) {
            return productRepository.findById(Long.parseLong(request.getParent()))
                    .orElseThrow(() -> new IllegalArgumentException("Parent product not found"));
        } else {
            Category category = createOrFindCategory(request.getCategory().getName(), request.getCategory().getSlug());
            List<Subcategory> subcategories = createOrFindSubcategories(category, request.getSubCategories());

            Product product = Product.builder()
                    .brand(request.getBrand())
                    .name(request.getName())
                    .description(request.getDescription())
                    .slug(request.getSlug())
                    .shipping(new BigDecimal(request.getShippingFee()))
                    .category(category)
                    .build();

            List<ProductSubcategory> productSubcategories = subcategories.stream()
                    .map(sub -> ProductSubcategory.builder().product(product).subcategory(sub).build())
                    .collect(Collectors.toList());
            product.setSubcategories(productSubcategories);

            productRepository.save(product);
            return product;
        }
    }

    private Category createOrFindCategory(String categoryName, String slug) {
        return categoryRepository.findByCategoryName(categoryName)
                .orElseGet(() -> categoryRepository.save(new Category(categoryName, slug)));
    }

    private List<Subcategory> createOrFindSubcategories(Category category, List<SubCategoryDto> subCategoryDtos) {
        List<String> subcategoryNames = subCategoryDtos.stream()
                .map(SubCategoryDto::getName)
                .collect(Collectors.toList());
        List<Subcategory> existingSubcategories = subCategoryRepository
                .findByCategory_CategoryNameAndSubcategoryNameIn(category.getCategoryName(), subcategoryNames);

        List<Subcategory> newSubcategories = subCategoryDtos.stream()
                .filter(dto -> existingSubcategories.stream()
                        .noneMatch(existing -> existing.getSubcategoryName().equals(dto.getName())))
                .map(dto -> new Subcategory(dto.getName(), dto.getSlug(), category))
                .collect(Collectors.toList());

        subCategoryRepository.saveAll(newSubcategories);
        existingSubcategories.addAll(newSubcategories);
        return existingSubcategories;
    }

    @Transactional
    // 신규 상품 등록 시 검색/필터 캐시를 무효화하지 않으면 최대 60분(search_cache TTL) 동안
    // 등록한 상품이 검색 결과에 나타나지 않는다.
    // 🐛 예전 @CachePut(value = "product", key = "#result.productId") — 돌려주는 ProductSku 에는 productId 가 없어 프록시에서
    //    SpEL 예외(EL1008E)로 등록이 500 이 됐고, 됐더라도 ProductDto 를 담는 "product" 캐시에 엔티티를 넣었다. 부모 상품의
    //    캐시를 <b>지운다</b> — 다음 조회가 새 변형까지 담아 다시 만든다(ProductCacheKeyTest).
    // [JA] 新規商品の登録時に検索/フィルタキャッシュを無効化しないと、最大 60 分(search_cache TTL)のあいだ登録した商品が検索結果に出ない。
    //      🐛 以前の @CachePut(value = "product", key = "#result.productId") — 返す ProductSku には productId がなくプロキシで SpEL 例外(EL1008E)となり登録が 500 になった。
    //      通ったとしても ProductDto を入れる "product" キャッシュにエンティティを入れていた。親商品のキャッシュを**消す** — 次の照会が新しいバリエーションまで含めて作り直す(ProductCacheKeyTest)。
    @org.springframework.cache.annotation.Caching(evict = {
            // products 도 — 🐛 예전에는 빠져 있어 새 상품이 홈 · 카테고리 목록에, 새 색상이 상세(slug)에 나오지 않았다.
            // [JA] products も — 🐛 以前は抜けていて、新商品がホーム・カテゴリ一覧に、新しい色が詳細(slug)に出なかった。
            @CacheEvict(value = {"products", "search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true),
            @CacheEvict(value = "product", key = "#result.product.productId")
    })
    // ⚠️ 이 메서드는 예전에 세 가지 방식으로 조용히 잘못됐다. 전부 실배포에서 확인된
    //    형태라 고친 이유를 남긴다:
    //
    //    · `Optional<Category> category` 를 `if (category != null)` 로 검사했다 —
    //      Optional 은 null 이 아니므로 **항상 참**이고, 없는 카테고리면 `category.get()`
    //      이 NoSuchElementException 으로 500 을 냈다. 검증이 아니라 폭발이었다.
    //    · 카테고리·부모를 못 찾으면 `return null` — 컨트롤러가 그걸 **200 OK 로**
    //      돌려줘서 화면은 "등록 완료"를 띄우는데 실제로는 아무것도 안 만들어졌다.
    //    · slug 에 아무 제약이 없어 같은 이름을 두 번 등록하면 상세 조회(`findBySlug`)가
    //      임의의 것을 돌려줬다.
    //
    //    이제 잘못된 입력은 IllegalArgumentException 으로 던진다(컨트롤러가 400 으로
    //    변환). null 반환 경로는 없다.
    // [JA] ⚠️ このメソッドは以前、三つの形で黙って間違っていた。すべて実配備で確認された形なので直した理由を残す:
    //      ・`Optional<Category> category` を `if (category != null)` で検査していた — Optional は null でないので**常に真**で、存在しないカテゴリだと `category.get()` が NoSuchElementException で 500 を出した。検証ではなく爆発だった。
    //      ・カテゴリ・親が見つからないと `return null` — コントローラがそれを**200 OK で**返し、画面は「登録完了」を出すのに実際には何も作られていなかった。
    //      ・slug に何の制約もなく、同じ名前を二回登録すると詳細照会(`findBySlug`)が任意のものを返した。
    //      今は不正な入力を IllegalArgumentException で投げる(コントローラが 400 に変換)。null を返す経路はない。
    public ProductSku addProduct(ProductRequest request, List<MultipartFile> images, MultipartFile colorImage)
            throws IOException {

        if (images == null || images.isEmpty()) {
            // createSku 가 images 를 그대로 순회하고 colorImage 를 무조건 업로드한다.
            // 시그니처의 required=false 는 거짓말이다 — 여기서 명확히 끊는다.
            // [JA] createSku は images をそのまま回し、colorImage を無条件にアップロードする。シグネチャの required=false は嘘である — ここではっきり断つ。
            throw new IllegalArgumentException("이미지가 없습니다 — 최소 1장이 필요합니다");
        }
        if (colorImage == null || colorImage.isEmpty()) {
            throw new IllegalArgumentException("색상(스타일) 이미지가 없습니다");
        }

        if (request.getParent() != null && request.getParent().length() > 0) {

            Product parent = productRepository.findById(parseId(request.getParent(), "parent"))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "부모 상품이 없습니다: id=" + request.getParent()));

            ProductSku newSku = createSku(request, parent, images, colorImage, false);   // 변형 — 상품 정보는 그대로 ／ [JA] バリエーション — 商品情報はそのまま
            productRepository.save(parent);
            productIndexService.index(parent);
            return newSku;
        }

        Product product = Product.builder()
                .brand(request.getBrand())
                .name(request.getName())
                .description(request.getDescription())
                .slug(uniqueSlug(request.getSlug(), request.getName()))
                .build();

        try {
            product.setShipping(new BigDecimal(request.getShippingFee()));
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "배송비가 숫자가 아닙니다: " + request.getShippingFee());
        }

        Category category = categoryRepository.findById(parseId(request.getCategory(), "category"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "카테고리가 없습니다: id=" + request.getCategory()));
        product.setCategory(category);

        List<Long> subcategoryIds = new ArrayList<>();
        if (request.getSubCategories() != null) {
            for (String subcategory : request.getSubCategories()) {
                subcategoryIds.add(parseId(subcategory, "subCategory"));
            }
        }

        List<Subcategory> subCategories = subCategoryRepository.findBySubcategoryIdIn(subcategoryIds);
        if (subCategories.size() != subcategoryIds.size()) {
            // 일부만 존재하면 "찾은 것만" 붙이고 조용히 넘어가는 편이 더 나쁘다 —
            // 화면에서 고른 분류와 실제 저장이 어긋난 채 성공한 것으로 보인다.
            // [JA] 一部だけ存在するとき「見つかったものだけ」付けて黙って進む方がもっと悪い — 画面で選んだ分類と実際の保存がずれたまま成功したように見える。
            throw new IllegalArgumentException(
                    "존재하지 않는 서브카테고리가 있습니다: 요청 " + subcategoryIds.size()
                            + "개 중 " + subCategories.size() + "개만 찾음");
        }
        // 🐛 하위 카테고리가 상품의 카테고리에 속하는지 보지 않았다. 시드 데이터의 연결 81% 가 다른 카테고리였다
        //    (올리브유가 "테이블 · 스마트폰" 에) — 검색 화면의 하위 카테고리 필터가 엉뚱한 상품을 보여 준 원인이다(V16).
        // [JA] 🐛 サブカテゴリが商品のカテゴリに属するかを見ていなかった。シードデータの紐付けの 81% が別カテゴリだった(オリーブオイルが「テーブル・スマートフォン」に)
        //      — 検索画面のサブカテゴリフィルタが見当違いの商品を見せた原因である(V16)。
        for (Subcategory sub : subCategories) {
            if (sub.getCategory() == null || !sub.getCategory().getCategoryId().equals(category.getCategoryId())) {
                throw new IllegalArgumentException("서브카테고리가 상품의 카테고리에 속하지 않습니다: " + sub.getSubcategoryId());
            }
        }

        List<ProductSubcategory> list = new ArrayList<>();
        for (Subcategory sub : subCategories) {
            list.add(ProductSubcategory.builder()
                    .product(product)
                    .subcategory(sub)
                    .build());
        }
        product.setSubcategories(list);

        productRepository.save(product);
        ProductSku created = createSku(request, product, images, colorImage, true);
        // 색인은 SKU(이미지·가격)까지 저장된 뒤에 — 문서에 SKU 목록이 들어간다.
        // [JA] 索引は SKU(画像・価格)まで保存された後に — ドキュメントに SKU 一覧が入る。
        productIndexService.index(product);
        return created;
    }

    /**
     * SKU 자동 생성: {@code <카테고리 접두사 3자>-<시퀀스 6자리>} — 예: {@code FOO-000123}.
     *
     * <p>접두사는 사람이 읽을 힌트일 뿐이다 — 카테고리가 바뀌어도 SKU 는 그대로 둔다.
     * 속성을 코드에 새기는 순간 속성이 변할 때 코드가 거짓말을 하게 되므로, 신뢰하는
     * 정보는 전부 DB 에 있고 코드는 식별만 한다.
     *
     * <p>번호는 DB 시퀀스라 레플리카가 몇 개든 충돌하지 않는다. 그래도 존재 검사를
     * 하는 이유: 관리자가 손으로 넣은 SKU 가 우연히 같은 모양(예: FOO-000200)일 수
     * 있다. 그 경우 다음 번호로 넘어간다.
     *
     * <p>[JA] SKU の自動生成: {@code <カテゴリ接頭辞 3 文字>-<シーケンス 6 桁>} — 例: {@code FOO-000123}。
     * <p>接頭辞は人が読むためのヒントにすぎない — カテゴリが変わっても SKU はそのままにする。属性をコードに刻んだ瞬間、属性が変わるとコードが嘘をつくので、
     * 信頼する情報はすべて DB にあり、コードは識別だけを担う。
     * <p>番号は DB シーケンスなのでレプリカがいくつでも衝突しない。それでも存在検査をする理由: 管理者が手で入れた SKU がたまたま同じ形(例: FOO-000200)のことがある。その場合は次の番号へ進む。
     */
    private String generateSku(Product product) {
        String prefix = skuPrefix(
                product.getCategory() != null ? product.getCategory().getCategoryName() : null);
        for (int attempt = 0; attempt < 10; attempt++) {
            Long n = productskuRepository.nextSkuNumber();
            String candidate = String.format("%s-%06d", prefix, n);
            if (!productskuRepository.existsBySku(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "SKU 생성이 10회 연속 충돌했습니다 — product_sku_seq 시퀀스 상태를 확인하세요");
    }

    /**
     * 카테고리 이름에서 접두사 3자를 뽑는다. ASCII 영숫자만 쓴다 — SKU 는 창고 라벨·
     * 전화 응대에서 불러 읽는 코드라 대문자 영숫자로 제한하는 것이 관례다. 한글 등
     * ASCII 밖 이름이라 3자를 못 채우면 중립 접두사 PRD 로 떨어진다.
     *
     * <p>[JA] カテゴリ名から接頭辞 3 文字を取る。ASCII 英数字だけを使う — SKU は倉庫ラベル・電話対応で読み上げるコードなので、大文字英数字に限るのが慣例である。
     * ハングルなど ASCII 外の名前で 3 文字を満たせなければ中立の接頭辞 PRD に落ちる。
     */
    public static String skuPrefix(String categoryName) {
        if (categoryName == null) {
            return "PRD";
        }
        String alnum = categoryName.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return alnum.length() >= 3 ? alnum.substring(0, 3) : "PRD";
    }

    /**
     * 상품 기본 정보 수정. 비어 있지 않은 필드만 반영한다(부분 수정).
     *
     * <p>slug 는 **바꾸지 않는다** — 상세 페이지 URL 이라 바꾸면 검색엔진·공유 링크·
     * 장바구니의 기존 참조가 전부 404 가 된다. 이름을 고쳐도 주소는 남는다.
     *
     * <p>[JA] 商品の基本情報の修正。空でないフィールドだけを反映する(部分修正)。
     * <p>slug は<b>変えない</b> — 詳細ページの URL なので、変えると検索エンジン・共有リンク・カートの既存参照がすべて 404 になる。名前を直してもアドレスは残る。
     */
    @org.springframework.cache.annotation.CacheEvict(
            value = { "products", "product", "search_cache", "products_by_category" }, allEntries = true)
    public ProductDto updateProduct(Long productId, com.project.common.dto.catalog.request.ProductEditRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품이 없습니다: id=" + productId));

        if (request.getName() != null && !request.getName().isBlank()) {
            product.setName(request.getName().trim());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            product.setDescription(request.getDescription().trim());
        }
        if (request.getBrand() != null && !request.getBrand().isBlank()) {
            product.setBrand(request.getBrand().trim());
        }
        if (request.getShippingFee() != null && !request.getShippingFee().isBlank()) {
            try {
                product.setShipping(new BigDecimal(request.getShippingFee().trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("배송비가 숫자가 아닙니다: " + request.getShippingFee());
            }
        }
        if (request.getRefundPolicy() != null && !request.getRefundPolicy().isBlank()) {
            product.setRefundPolicy(request.getRefundPolicy().trim());
        }

        Product saved = productRepository.save(product);
        productIndexService.index(saved);
        return ProductMapper.toDto(saved);
    }

    /**
     * 상태 변경 — 품절(SOLD_OUT)·복귀(ACTIVE)·삭제(DELETED)가 전부 이 경로다.
     *
     * <p>DELETED 로 바꾼 뒤에는 이 메서드로도 **되돌릴 수 없다** — @SQLRestriction 이
     * findById 에서 이미 걸러 "상품이 없습니다" 가 된다. 의도된 동작이다(V8 주석).
     *
     * <p>[JA] 状態変更 — 品切れ(SOLD_OUT)・復帰(ACTIVE)・削除(DELETED)はすべてこの経路である。
     * <p>DELETED にした後はこのメソッドでも<b>元に戻せない</b> — @SQLRestriction が findById で既に除き、「商品がありません」になる。意図した動作である(V8 のコメント)。
     */
    @org.springframework.cache.annotation.CacheEvict(
            value = { "products", "product", "search_cache", "products_by_category" }, allEntries = true)
    public ProductDto updateStatus(Long productId, String status) {
        if (!"ACTIVE".equals(status) && !"SOLD_OUT".equals(status) && !"DELETED".equals(status)) {
            throw new IllegalArgumentException(
                    "status 는 ACTIVE·SOLD_OUT·DELETED 중 하나여야 합니다: " + status);
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품이 없습니다: id=" + productId));

        product.setStatus(status);
        Product saved = productRepository.save(product);

        // 품절은 검색에 남긴다(목록과 같은 동작 — 보이되 담기지 않는다).
        // 삭제만 색인에서 뺀다 — DB 조회는 @SQLRestriction 이 걸러 주지만 ES 는
        // 별도 저장소라 직접 지워야 한다.
        // [JA] 品切れは検索に残す(一覧と同じ動作 — 見えるがカートに入らない)。
        //      削除だけ索引から外す — DB 照会は @SQLRestriction が除いてくれるが、ES は別ストレージなので直接消す必要がある。
        if ("DELETED".equals(status)) {
            productIndexService.remove(productId);
        } else {
            productIndexService.index(saved);
        }
        return ProductMapper.toDto(saved);
    }

    private static Long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(field + " id 가 숫자가 아닙니다: " + raw);
        }
    }

    /**
     * slug 를 정하고 **유일하게** 만든다.
     *
     * <p>클라이언트가 준 slug 를 신뢰하지 않는 이유: 비거나, 같은 이름의 상품이 이미
     * 있을 수 있다. slug 는 상세 페이지 URL 이라 중복이면 {@code findBySlug} 가 여러
     * 건을 돌려주고 어느 것이 보일지는 우연이 정한다. V6 마이그레이션의 UNIQUE 제약이
     * 최후 방어선이고, 여기서는 충돌 시 "-2", "-3" … 를 붙여 제약에 걸리기 전에 피한다.
     *
     * <p>[JA] slug を決め、<b>一意に</b>する。
     * <p>クライアントが渡す slug を信用しない理由: 空だったり、同名の商品がすでにあったりする。slug は詳細ページの URL なので、重複すると {@code findBySlug} が複数件を返し、
     * どれが見えるかは偶然が決める。V6 マイグレーションの UNIQUE 制約が最後の防衛線で、ここでは衝突時に "-2"、"-3" … を付けて制約に引っかかる前に避ける。
     */
    public String uniqueSlug(String requested, String name) {
        String base = (requested == null || requested.isBlank())
                ? String.valueOf(name).trim().toLowerCase().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
                        .replaceAll("(^-|-$)", "")
                : requested.trim();
        if (base.isBlank()) {
            throw new IllegalArgumentException("slug 를 만들 수 없습니다 — 상품 이름을 확인하세요");
        }
        String candidate = base;
        int n = 1;
        while (productRepository.slugTaken(candidate)) {   // 삭제된 상품의 slug 도 쓴 것이다(유니크 인덱스가 그 행에도 걸린다) ／ [JA] 削除された商品の slug も使用済みである(ユニークインデックスはその行にもかかる)
            n++;
            candidate = base + "-" + n;
            if (n > 100) {
                // 같은 이름이 100개면 데이터가 아니라 다른 문제다. 무한루프 방지.
                // [JA] 同じ名前が 100 個あるならデータではなく別の問題である。無限ループ防止。
                throw new IllegalStateException("slug 충돌이 100회를 넘었습니다: " + base);
            }
        }
        return candidate;
    }
    
    /**
     * 요청의 사이즈들을 엔티티로 — <b>부모(스타일)를 가리킨 채로.</b>
     *
     * <p>🐛 예전 코드는 이랬다.
     * <pre>
     * request.getSizes().forEach(size -&gt; ProductSizeMapper.toEntity(size).setSku(skuProject));  // ①
     * skuProject.setSizes(request.getSizes().stream().map(ProductSizeMapper::toEntity).toList()); // ②
     * </pre>
     * ①은 매번 <b>새 객체</b>를 만들어 부모를 걸고 그대로 버린다. 실제로 저장되는 것은 ②가 <b>다시</b> 만든 객체라
     * 부모가 비어 있다. {@code sizes} 는 {@code mappedBy="sku"} — 외래 키를 가진 쪽은 사이즈이므로, 사이즈의
     * {@code sku} 가 비면 {@code product_size.skuproduct_id} 가 NULL 로 저장된다. 그 사이즈는 어느 스타일에도
     * 속하지 않게 되어 <b>관리자가 등록한 상품은 사이즈가 없는 상품이 됐다</b> — 상세 화면에 가격이 없고 장바구니에
     * 담을 수 없다. 상세 · 질문도 같은 모양({@code mappedBy="product"})이라 함께 고친다.
     *
     * <p>[JA] リクエストのサイズ群をエンティティに — <b>親(スタイル)を指したまま。</b>
     * <p>🐛 以前のコードは ① で毎回<b>新しいオブジェクト</b>を作って親を設定し、そのまま捨てていた。実際に保存されるのは ② が<b>改めて</b>作ったオブジェクトで、親が空だった。
     * {@code sizes} は {@code mappedBy="sku"} — 外部キーを持つのはサイズ側なので、サイズの {@code sku} が空だと {@code product_size.skuproduct_id} が NULL で保存される。
     * そのサイズはどのスタイルにも属さなくなり、<b>管理者が登録した商品はサイズのない商品になった</b> — 詳細画面に価格がなく、カートに入れられない。
     * 詳細・質問も同じ形({@code mappedBy="product"})なので一緒に直す。
     */
    private static List<ProductSize> sizesOf(List<ProductSizeDto> requested, ProductSku parent) {
        if (requested == null) {
            return new ArrayList<>();
        }
        List<ProductSize> sizes = new ArrayList<>();
        for (ProductSizeDto dto : requested) {
            ProductSize size = ProductSizeMapper.toEntity(dto);
            if (size == null) {
                continue;
            }
            size.setSku(parent);
            sizes.add(size);
        }
        return sizes;
    }

    /**
     * {@link #sizesOf} 와 같은 이유 — 저장되는 객체에 부모를 건다. <b>새 상품에만</b> 쓴다.
     *
     * <p>🐛 예전: 변형(기존 상품에 스타일 추가) · 대량 적재의 부모에서도 불렀고, 컬렉션을 통째로 갈아 끼워 flush 가
     * "orphan deletion 컬렉션" 예외로 거절됐다 — 변형 등록이 늘 500 이었다. 설령 통과했어도 부모의 상세를 변형 양식의
     * 빈 줄로 덮어썼을 것이다. 변형은 스타일을 더할 뿐 상품 정보를 바꾸지 않는다. 내용은 {@link Product#replaceDetails} 로 바꾼다.
     *
     * <p>[JA] {@link #sizesOf} と同じ理由 — 保存されるオブジェクトに親を設定する。<b>新しい商品にだけ</b>使う。
     * <p>🐛 以前: バリエーション(既存商品へのスタイル追加)・大量投入の親でも呼び、コレクションを丸ごと差し替えたため flush が「orphan deletion コレクション」例外で拒否した
     * — バリエーション登録が常に 500 だった。仮に通っても、親の詳細をバリエーション様式の空行で上書きしていただろう。バリエーションはスタイルを足すだけで商品情報を変えない。
     * 内容は {@link Product#replaceDetails} で変える。
     */
    private static void attachDetailsAndQuestions(Product product, Collection<ProductDetailsDto> details,
            Collection<ProductQADto> questions) {
        if (details != null) {
            Set<ProductDetails> entities = new HashSet<>();
            for (ProductDetailsDto dto : details) {
                ProductDetails d = ProductDetailsMapper.toEntity(dto);
                if (d != null) {
                    d.setProduct(product);
                    entities.add(d);
                }
            }
            product.replaceDetails(entities);
        }
        if (questions != null) {
            Set<ProductQA> entities = new HashSet<>();
            for (ProductQADto dto : questions) {
                ProductQA q = ProductQAMapper.toEntity(dto);
                if (q != null) {
                    q.setProduct(product);
                    entities.add(q);
                }
            }
            product.replaceQuestions(entities);
        }
    }

    /**
     * 방금 저장한 스타일의 사이즈마다 SKU 코드를 채운다(V17 의 DB 함수 — 규칙은 거기에만 있다).
     *
     * <p>함수는 DB 의 행을 고치므로 메모리의 엔티티는 모른다. 이 엔티티로 곧바로 응답 · 색인 · 캐시를 만들기 때문에
     * 채운 값을 다시 읽어 옮겨 적는다.
     *
     * <p>[JA] いま保存したスタイルのサイズごとに SKU コードを埋める(V17 の DB 関数 — 規則はそこにだけある)。
     * <p>関数は DB の行を直すので、メモリ上のエンティティは知らない。このエンティティですぐ応答・索引・キャッシュを作るため、埋めた値を読み直して書き写す。
     */
    private ProductSku withSizeSkuCodes(ProductSku saved) {
        productSizeRepository.flush();   // 네이티브 함수가 방금 INSERT 한 행을 보도록 ／ [JA] ネイティブ関数がいま INSERT した行を見るように
        productSizeRepository.assignSkuCodes();
        if (saved.getSkuproductId() == null || saved.getSizes() == null) {
            return saved;
        }
        Map<Long, String> codes = new HashMap<>();
        for (Object[] row : productSizeRepository.skuCodesOf(saved.getSkuproductId())) {
            codes.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        for (ProductSize size : saved.getSizes()) {
            if (size.getSizeId() != null) {
                size.setSkuCode(codes.get(size.getSizeId()));
            }
        }
        return saved;
    }

    /**
     * 상품 이미지의 저장소 키 — {@code images/product/<이름>.<확장자>}.
     *
     * <p>🐛 예전에는 키가 무작위 이름 하나뿐이었다({@code images/} 도 확장자도 없음). 버킷의 공개 읽기 정책은 {@code images/*} 에만
     * 걸려 있어(ImageService 주석) 업로드는 성공하는데 화면에서는 403 — 관리자가 등록한 상품 이미지가 모두 깨졌다. 일괄 임포트는 CSV 의
     * {@code images/...} 경로를 그대로 써서 그쪽만 보였다. user-service 리뷰 이미지도 같은 버그였다.
     *
     * <p>[JA] 商品画像のキー — {@code images/product/<名前>.<拡張子>}。以前は {@code images/} も拡張子もなく、公開ポリシーの外で 403 だった。
     */
    static String productImageKey(MultipartFile file, String name) {
        return FileUtil.getFilePath(file, "product/" + name, "images");
    }

    private ProductSku loadSku(ProductAddRequest request, Product product, MultipartFile image,
            MultipartFile colorImage) throws IOException {

        ProductSku skuProject = ProductSku.builder()
                .sku(request.getSku())
                .build();

        if (request.getDiscount() != null)
            skuProject.setDiscount(Integer.parseInt(request.getDiscount()));

        // ArrayList<String> bytes = new ArrayList<String>();
        // for(MultipartFile image : images) {
        // bytes.add(encodeFileToBase64((image)));

        // }
        // if (bytes.size() > 0)
        // skuProject.setImages(bytes);
        // skuProject.getColor().setColorImage(encodeFileToBase64(colorImage));

        List<String> imageUrls = new ArrayList<String>();

        String filename = productImageKey(image, FileUtil.getRandomFilename());
        String filepath = imageService.upload(image, filename);
        imageUrls.add(filepath);

        skuProject.setImages(imageUrls);

        filename = productImageKey(colorImage, "color_" + FileUtil.getRandomFilename());
        String colorImageUrl = imageService.upload(colorImage, filename);

        skuProject.setColor(ProductColor.builder().colorImage(colorImageUrl)
                .color(request.getColor().getColor()).build());

        productColorRepository.save(skuProject.getColor());

        skuProject.setSizes(sizesOf(request.getSizes(), skuProject));
        // 부모에 붙이는 변형이면 상품 정보는 건드리지 않는다(createSku 와 같은 이유).
        // [JA] 親に付けるバリエーションなら商品情報には触れない(createSku と同じ理由)。
        if (request.getParent() == null || request.getParent().isEmpty()) {
            attachDetailsAndQuestions(product, request.getDetails(), request.getQuestions());
        }

        skuProject.setProduct(product);
        product.getSkus().add(skuProject);
        return withSizeSkuCodes(productskuRepository.save(skuProject));

    }

    private ProductSku createSku(ProductRequest request, Product product, List<MultipartFile> images,
            MultipartFile colorImage, boolean newProduct) throws IOException {

        // SKU: 비우면 생성하고, 주면 중복을 거절한다. 어느 쪽이든 유일성은 V7 의
        // UNIQUE 인덱스가 최후 방어선이다 — 여기 검사는 좋은 에러 메시지를 위한 것이다.
        // [JA] SKU: 空なら生成し、指定されたら重複を拒否する。どちらでも一意性は V7 の UNIQUE インデックスが最後の防衛線である — ここの検査は良いエラーメッセージのためである。
        String sku;
        if (request.getSku() == null || request.getSku().isBlank()) {
            sku = generateSku(product);
        } else {
            sku = request.getSku().trim();
            if (productskuRepository.existsBySku(sku)) {
                throw new IllegalArgumentException(
                        "이미 사용 중인 SKU 입니다: " + sku + " — 비워 두면 자동 생성됩니다");
            }
        }

        ProductSku skuProject = ProductSku.builder()
                .sku(sku)
                .build();

        if (request.getDiscount() != null && !request.getDiscount().isBlank()) {
            try {
                skuProject.setDiscount(Integer.parseInt(request.getDiscount()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("할인율이 숫자가 아닙니다: " + request.getDiscount());
            }
        }

        skuProject.setColor( ProductColorMapper.toEntity(request.getColor()));

        // ⚠️ 여기서부터는 S3 에 부수효과가 생긴다. 업로드 **뒤에** 어떤 단계든 실패하면
        //    (엔티티 매핑, DB 저장, 다음 업로드…) 이미 올라간 파일은 아무도 참조하지
        //    않는 고아가 된다 — 눈에 안 보이고 요금만 나간다. 그래서 올린 것을 전부
        //    기록해 두고, 실패하면 지운 뒤 원래 예외를 그대로 다시 던진다.
        // [JA] ⚠️ ここから S3 に副作用が生じる。アップロードの**後で**どの段階が失敗しても(エンティティのマッピング、DB 保存、次のアップロード…)、
        //      すでに上がったファイルは誰も参照しない孤児になる — 見えず料金だけかかる。だから上げたものをすべて記録しておき、失敗したら消してから元の例外をそのまま投げ直す。
        List<String> uploaded = new ArrayList<>();
        try {
            List<String> imageUrls = new ArrayList<String>();

            for (MultipartFile image : images) {
                String filename = productImageKey(image, FileUtil.getRandomFilename());
                String filepath = imageService.upload(image, filename);
                uploaded.add(filepath);
                imageUrls.add(filepath);
            }

            skuProject.setImages(imageUrls);

            String filename = productImageKey(colorImage, "color_" + FileUtil.getRandomFilename());
            String colorImageUrl = imageService.upload(colorImage, filename);
            uploaded.add(colorImageUrl);

            skuProject.getColor().setColorImage(colorImageUrl);

            skuProject.setSizes(sizesOf(request.getSizes(), skuProject));
            if (newProduct) {
                attachDetailsAndQuestions(product, request.getDetails(), request.getQuestions());
            }

            skuProject.setProduct(product);
            // 양쪽을 맞춘다 — 예전엔 상품의 skus 에 넣지 않아, 바로 뒤의 검색 색인이 SKU 없는 문서를 만들었다.
            // [JA] 両側を合わせる — 以前は商品の skus に入れておらず、直後の検索索引が SKU のないドキュメントを作っていた。
            product.getSkus().add(skuProject);

            return withSizeSkuCodes(productskuRepository.save(skuProject));

        } catch (Exception e) {
            for (String url : uploaded) {
                imageService.deleteByUrl(url);
            }
            throw e;
        }
    }

    private String encodeFileToBase64(MultipartFile file) {
        try {

            byte[] fileBytes = file.getBytes();
            return Base64.encodeBase64String(fileBytes);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] decodeBase64ToFile(String encodedString) {
        return Base64.decodeBase64(encodedString);
    }

    /**
     * 색상 여러 개를 <b>한 번에</b> — order-service 가 주문 상품마다, cart-service 가 장바구니
     * 항목마다 {@link #getColorAttributeInfo} 를 부르던 것의 대체다(docs/38 §1.13). 주문 목록
     * 한 페이지(주문 10건 × 상품 3개)가 색상 조회 30번이었다. 못 찾은 id 는 결과에서 빠진다.
     *
     * <p>[JA] 複数の色を<b>一度に</b> — order-service が注文商品ごと、cart-service がカート項目ごとに {@link #getColorAttributeInfo} を呼んでいたのの置き換えである(docs/38 §1.13)。
     * 注文一覧 1 ページ(注文 10 件 × 商品 3 個)で色の照会が 30 回だった。見つからない id は結果から抜ける。
     */
    @Transactional(readOnly = true)
    public List<ProductColorDto> getColorsByIds(Collection<Long> colorIds) {
        if (colorIds == null || colorIds.isEmpty()) {
            return List.of();
        }
        return productColorRepository.findAllById(colorIds).stream()
                .map(color -> ProductColorDto.builder()
                        .id(Long.toString(color.getColorId()))
                        .color(color.getColor())
                        .colorImage(color.getColorImage())
                        .build())
                .collect(Collectors.toList());
    }

    public ProductColorDto getColorAttributeInfo(Long colorId) {

        ProductColor color = productColorRepository.findById(colorId).orElse(null);

        if (null != color) {

            ProductColorDto dto = ProductColorDto.builder()
                    .id(Long.toString(color.getColorId()))
                    .color(color.getColor())
                    .colorImage(color.getColorImage())
                    .build();

            return dto;

        }

        log.info("color is null : " + Long.toString(colorId));

        return null;
    }

    public ProductInfoDto getCartProductInfo(Long productId, int style, int size) {
        Optional<Product> data = productRepository.findById(productId);
        if (data.isEmpty()) {
            log.info("product is null : " + Long.toString(productId));
            return null;
        }
        return toCartProductInfo(data.get(), style, size);
    }

    /**
     * 장바구니 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 <b>한 번에</b> — cart-service 의
     * loadCart 가 항목마다 {@link #getCartProductInfo} 를 부르던 것의 대체다(docs/38 §1.13).
     *
     * <p>상품은 중복 없는 id 로 한 번에 읽고({@code findAllById} + {@code @BatchSize} 컬렉션),
     * 항목별 계산은 단건과 같은 {@link #toCartProductInfo} 를 쓴다. 못 찾은 항목(없는 상품,
     * 품절, 잘못된 스타일/사이즈 인덱스)은 결과 맵에서 빠질 뿐이다 — 한 항목 때문에 장바구니
     * 전체가 실패하지 않게 하고, 호출자가 키로 대조한다.
     *
     * <p>[JA] カート項目複数の(商品、スタイル、サイズ)情報を<b>一度に</b> — cart-service の loadCart が項目ごとに {@link #getCartProductInfo} を呼んでいたのの置き換えである(docs/38 §1.13)。
     * <p>商品は重複のない id で一度に読み({@code findAllById} + {@code @BatchSize} コレクション)、項目ごとの計算は単件と同じ {@link #toCartProductInfo} を使う。
     * 見つからない項目(存在しない商品、品切れ、誤ったスタイル/サイズのインデックス)は結果マップから抜けるだけ — 一項目のためにカート全体が失敗しないようにし、呼び出し側がキーで突き合わせる。
     */
    @Transactional(readOnly = true)
    public Map<String, ProductInfoDto> getCartProductsInfo(List<CartProductInfoRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (CartProductInfoRequest.Item item : items) {
            if (item.getProductId() != null) {
                ids.add(item.getProductId());
            }
        }
        Map<Long, Product> products = new HashMap<>();
        for (Product product : productRepository.findAllById(ids)) {
            products.put(product.getProductId(), product);
        }

        Map<String, ProductInfoDto> result = new LinkedHashMap<>();
        for (CartProductInfoRequest.Item item : items) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                log.info("product is null : {}", item.getProductId());
                continue;
            }
            try {
                ProductInfoDto dto = toCartProductInfo(product, item.getStyle(), item.getSize());
                if (dto != null) {
                    result.put(item.key(), dto);
                }
            } catch (IndexOutOfBoundsException e) {
                // 단건 API 에서는 400 이 되던 경우. 배치에서는 그 항목만 뺀다.
                // [JA] 単件 API では 400 になっていたケース。バッチではその項目だけ外す。
                log.info("잘못된 스타일/사이즈 인덱스: {}", item.key());
            }
        }
        return result;
    }

    /** 단건·배치가 공유하는 항목 하나의 계산. 품절이거나 SKU 가 없으면 null. */
    // [JA] 単件・バッチが共有する項目一つの計算。品切れか SKU がなければ null。
    private ProductInfoDto toCartProductInfo(Product product, int style, int size) {
        // 품절(SOLD_OUT) 상품은 장바구니에 담기지 않아야 한다. 이 메서드는 cart-service
        // 가 담기 직전에 부르는 상품 정보 조회라, 여기서 null 을 돌려주면 담기가
        // "상품 없음"과 같은 경로로 실패한다 — cart 쪽 코드는 이미 null 을 처리한다.
        // (DELETED 는 @SQLRestriction 이 위 findById 에서 이미 걸렀다.)
        // [JA] 品切れ(SOLD_OUT)商品はカートに入ってはならない。このメソッドは cart-service がカートに入れる直前に呼ぶ商品情報照会なので、
        //      ここで null を返すとカート追加が「商品なし」と同じ経路で失敗する — cart 側のコードはすでに null を扱う。
        //      (DELETED は @SQLRestriction が上の findById で既に除いた。)
        if ("SOLD_OUT".equals(product.getStatus())) {
            log.info("품절 상품의 장바구니 담기 차단. productId={}", product.getProductId());
            return null;
        }

        Optional<ProductSku> optionalSku = product.getSkus().stream()
                .skip(style) // get(style)과 유사하게 특정 순서의 요소를 건너뛰지만, Set은 순서가 없으므로 정확한 결과를 보장하지 않습니다. ／ [JA] get(style) と同様に特定順の要素を飛ばすが、Set は順序がないので正確な結果は保証しない。
                .findFirst();

        if (optionalSku.isPresent()) {

            ProductSku sku = optionalSku.get();

            // 진행 중인 기획전이 있으면 그 할인율을 얹는다. 이 값이 **실제로 청구되는
            // 금액**을 정한다 — 화면과 다른 값을 쓰면 상세에서 본 가격과 결제 금액이
            // 어긋난다(EventPricing 은 두 경로 모두 같은 맵을 본다).
            // [JA] 進行中の企画展があればその割引率を載せる。この値が**実際に請求される金額**を決める — 画面と違う値を使うと、詳細で見た価格と決済金額がずれる
            //      (EventPricing は両経路とも同じマップを見る)。
            int discountPercent = eventPricing.effectiveDiscount(product.getProductId(), sku.getDiscount());
            BigDecimal priceBefore = sku.getSizes().get(size).getPrice();
            BigDecimal price = discounted(priceBefore, discountPercent);

            ProductColorDto color = ProductColorDto.builder()
                    .id(Long.toString(sku.getColor().getColorId()))
                    .color(sku.getColor().getColor())
                    .colorImage(sku.getColor().getColorImage())
                    .build();

            List<String> subcategoryIds = product.getSubcategories().stream().map(productSubcategory -> {
                return Long.toString(productSubcategory.getSubcategory().getSubcategoryId());
            })
                    .collect(Collectors.toList());

            List<ProductDetailsDto> details = product.getDetails().stream().map(detail -> {
                return ProductDetailsDto.builder()
                        .name(detail.getName())
                        .value(detail.getValue()).build();
            }).collect(Collectors.toList());

            List<ProductQADto> questions = product.getQuestions().stream().map(q -> {
                return ProductQADto.builder()
                        .question(q.getQuestion())
                        .answer(q.getAnswer())
                        .build();
            }).collect(Collectors.toList());

            ProductInfoDto dto = ProductInfoDto.builder()
                    .id(Long.toString(product.getProductId()))
                    .style(style)
                    .name(product.getName())
                    .description(product.getDescription())
                    .slug(product.getSlug())
                    .sku(sku.getSku())
                    // 사이즈 한 줄의 SKU(V17) — 장바구니 → 주문 품목이 이 값을 들고 간다.
                    // [JA] サイズ 1 行の SKU(V17) — カート → 注文品目がこの値を持っていく。
                    .skuCode(sku.getSizes().get(size).getSkuCode())
                    .brand(product.getBrand())
                    // ProductMapper 와 같은 무방비 역참조 — shipping 은 nullable 이라
                    // 배송비 없는 상품이 장바구니에 담기면 500 이 났다(docs/14 B-15).
                    // [JA] ProductMapper と同じ無防備な逆参照 — shipping は nullable なので、送料のない商品がカートに入ると 500 になっていた(docs/14 B-15)。
                    .shipping(product.getShipping() != null ? product.getShipping().toPlainString() : null)
                    .images(sku.getImages())
                    .color(color)
                    .size(sku.getSizes().get(size).getSize())
                    .price(price.toPlainString())
                    .priceBefore(priceBefore.toPlainString())
                    .qty(1)
                    .quantity(sku.getSizes().get(size).getQuantity())
                    .category(Long.toString(product.getCategory().getCategoryId()))
                    .subCategories(subcategoryIds)
                    .questions(questions)
                    .details(details)
                    .discount(discountPercent)
                    .build();

            return dto;
        }
        log.info("sku not found : productId={} style={}", product.getProductId(), style);
        return null;
    }

    /**
     * 할인가. {@code discountPercent} 는 <b>퍼센트</b>다(시드 데이터가 5~70 을 넣는다).
     *
     * <p>🐛 <b>예전 식은 {@code price - price / discount} 였다.</b> "정가에서 정가를 할인율로
     * 나눈 값을 뺀다"는 뜻이라 아무 의미가 없고, 결과적으로 <b>할인율이 클수록 할인 폭이
     * 작아졌다</b>: 10,000원에 50% 할인이면 9,800원(=2% 할인), 70% 할인이면 9,857원(=1.4%).
     *
     * <p>프론트엔드(`util/pricing.js`)는 이미 고쳐져 있었고 <b>여기만 남아 있었다.</b> 그래서
     * 상세 화면은 50% 할인가를 보여 주는데 장바구니에 담으면 2% 할인가가 담겼다 — 같은
     * 상품의 가격이 화면마다 달랐다. 기획전 할인도 이 경로를 지나므로 먼저 바로잡는다.
     *
     * <p>{@code RoundingMode.HALF_UP} · 소수점 2자리는 통화 금액의 관례다. 100 을 넘는 값이
     * 들어와도 가격이 음수가 되지 않게 자른다.
     *
     * <p>[JA] 割引価格。{@code discountPercent} は<b>パーセント</b>である(シードデータが 5〜70 を入れる)。
     * <p>🐛 <b>以前の式は {@code price - price / discount} だった。</b>「定価から、定価を割引率で割った値を引く」という意味で何の意味もなく、結果として
     * <b>割引率が大きいほど割引幅が小さくなった</b>: 10,000 ウォンに 50% 割引なら 9,800 ウォン(=2% 割引)、70% 割引なら 9,857 ウォン(=1.4%)。
     * <p>フロントエンド(`util/pricing.js`)はすでに直っていて<b>ここだけ残っていた。</b>そのため詳細画面は 50% 割引価格を見せるのに、カートに入れると 2% 割引価格が入った
     * — 同じ商品の価格が画面ごとに違った。企画展割引もこの経路を通るので先に正す。
     * <p>{@code RoundingMode.HALF_UP}・小数点 2 桁は通貨金額の慣例である。100 を超える値が来ても価格が負にならないよう切る。
     */
    static BigDecimal discounted(BigDecimal priceBefore, int discountPercent) {
        // 식은 common 의 하나뿐 — cart-service 가 옛 식을 따로 갖고 있다가 결제 금액이 달라졌다(Discount 주석).
        // [JA] 式は common の一つだけ — cart-service が古い式を別に持っていて決済金額が変わってしまった(Discount のコメント)。
        return com.project.common.pricing.Discount.apply(priceBefore, discountPercent);
    }

    /**
     * 엔티티 → DTO 의 <b>유일한</b> 통로.
     *
     * <p>기획전 할인을 여기서 얹는다. 예전에는 조회 경로마다 {@code ProductMapper::toDto} 를
     * 직접 불렀는데, 그러면 새 경로가 생길 때마다 합성을 한 줄씩 더해야 하고 빠뜨리면
     * <b>그 화면만 정가를 보여 준다</b> — 같은 상품이 목록에서는 30% 할인인데 상세에서는
     * 정가인 식이다. 통로를 하나로 두면 빠뜨릴 곳이 없다.
     *
     * <p>[JA] エンティティ → DTO の<b>唯一の</b>通り道。
     * <p>企画展割引をここで載せる。以前は照会経路ごとに {@code ProductMapper::toDto} を直接呼んでいたが、それだと新しい経路ができるたびに合成を一行ずつ足す必要があり、
     * 忘れると<b>その画面だけ定価を見せる</b> — 同じ商品が一覧では 30% 割引なのに詳細では定価、というように。通り道を一つにすれば忘れる場所がない。
     */
    private ProductDto convertToDto(Product product) {
        return eventPricing.apply(ProductMapper.toDto(product));
    }

    private Product getProductModel(Long productId) {
        Optional<Product> product = productRepository.findById(productId);
        if (product.isPresent()) {
            return product.get();
        }
        return null;
    }

    private ProductSize getProductSizeModel(Long skuId, String size) {
        Optional<ProductSize> productSize = productSizeRepository.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(skuId, size);
        if (productSize.isPresent()) {
            return productSize.get();
        }
        return null;
    }

    // @Transactional
    // private ProductSku updateSoldValue(Long productId, String slug, String size,
    // Long colorId, int qty) {

    // ProductSku sku = getProductSkuModel(productId, size, colorId);
    // if (sku != null) {
    // int sold = sku.getSold() + qty;
    // sku.setSold(sold);

    // List<ProductDto> cachedData =
    // objectMapper.convertValue(productsOps.get("products:"+slug),
    // new TypeReference<List<ProductDto>>() {});

    // if (!cachedData.isEmpty()) {

    // cachedData.get(0).getSkus().stream().forEach(
    // item -> {
    // if (item.hasSize(size) && item.hasColor(Long.toString(colorId))) {
    // item.setSold(sold);
    // }
    // }
    // );

    // productsOps.set("products:"+slug, cachedData);

    // }

    // sku = productskuRepository.save(sku);
    // return sku;
    // }

    // return null;
    // }

    // @Transactional
    // private ProductSize updateProductSize_Qty(String slug, Long skuId, String
    // size, int qty) {
    // ProductSize productSize = getProductSizeModel(skuId, size);

    // if (productSize != null) {

    // if (productSize.getQuantity() >= qty) {

    // int currQty = productSize.getQuantity() - qty;
    // productSize.setQuantity(currQty);

    // List<ProductDto> cachedData =
    // objectMapper.convertValue(productsOps.get("products:"+slug),
    // new TypeReference<List<ProductDto>>() {});

    // if (!cachedData.isEmpty()) {
    // cachedData.get(0).getSkus().stream().forEach(
    // item -> {
    // if (item.hasSize(size)) {
    // item.getSize(size).setQuantity(currQty);
    // }
    // }
    // );

    // productsOps.set("products:"+slug, cachedData);

    // }

    // productSize = productSizeRepository.save(productSize);
    // return productSize;
    // }
    // }

    // return null;
    // }

    // A transactional public method that orchestrates the update
    @Transactional
    // 판매량/재고 변경(주문 성공 Kafka 소비 경로)도 검색 필터(재고·판매량 기반)에 영향 → 캐시 무효화.
    // [JA] 販売数/在庫の変更(注文成功の Kafka 消費経路)も検索フィルタ(在庫・販売数ベース)に影響する → キャッシュ無効化。
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    // "product" 캐시는 ProductDto 를 담는다 — 여기 있던 @CachePut 은 ProductSku 를 넣으려 했다(자기 호출이라 실제로는 돌지 않았다).
    // [JA] "product" キャッシュは ProductDto を入れる — ここにあった @CachePut は ProductSku を入れようとしていた(自己呼び出しなので実際には動いていなかった)。
    public ProductSku updateSoldValue(Long productId, String slug, String size, Long colorId, int qty) {
        Optional<ProductSku> optionalSku = productskuRepository
                .findForStockUpdate(productId, size, colorId);

        if (optionalSku.isPresent()) {
            ProductSku sku = optionalSku.get();
            // qty 가 음수면 재고 복원(환불·반품 입고)이다 — 판매량을 뺀다. 0 아래로는 가지
            // 않는다: 복원 이벤트가 차감보다 먼저 도착하는 순서 역전은 이론상 가능하고,
            // 그때 판매량이 음수로 화면에 찍히는 것보다 0 이 낫다.
            // [JA] qty が負なら在庫復元(返金・返品入庫)である — 販売数を減らす。0 未満にはしない: 復元イベントが差し引きより先に届く順序逆転は理論上ありうるし、
            //      そのとき販売数が負で画面に出るより 0 の方がよい。
            int sold = Math.max(0, sku.getSold() + qty);
            sku.setSold(sold);

            // 1. Database Update
            productskuRepository.save(sku);

            // 2. Cache Update
            // This method call will trigger the @CachePut and update the cache.
            updateProductDtoCache(slug, sku);

            return sku;
        }
        return null;
    }

    // This method updates the cache with the latest data.
    // It is public so that the @CachePut annotation can be applied by Spring.
    @Transactional
    @CachePut(value = "products", key = "'slug:' + #slug")
    public List<ProductDto> updateProductDtoCache(String slug, ProductSku updatedSku) {
        // Here, we re-fetch the product from the DB or a separate method
        // to ensure the DTO list contains the latest data.
        List<Product> products = productRepository.findBySlug(slug);

        // Convert entities to DTOs and update the specific SKU's sold value
        List<ProductDto> updatedDtos = products.stream()
                .map(product -> {
                    ProductDto dto = convertToDto(product);
                    // Find and update the specific SKU in the DTO list
                    dto.getSkus().stream()
                            .filter(skuDto -> skuDto.getSku().equals(updatedSku.getSku()))
                            .findFirst()
                            .ifPresent(skuDto -> skuDto.setSold(updatedSku.getSold()));
                    return dto;
                })
                .collect(Collectors.toList());

        return updatedDtos;
    }

    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    // "product" 캐시에 ProductSize 를 넣으려던 @CachePut 을 지웠다(위와 같은 이유).
    // [JA] "product" キャッシュに ProductSize を入れようとしていた @CachePut を消した(上と同じ理由)。
    public ProductSize updateProductSize_Qty(String slug, Long skuId, String size, int qty) {
        ProductSize productSize = getProductSizeModel(skuId, size);

        if (productSize != null) {
            // qty 가 음수면 재고 복원이다 — 아래 조건은 언제나 참이고 수량이 |qty| 만큼 는다.
            // [JA] qty が負なら在庫復元である — 下の条件は常に真で、数量が |qty| だけ増える。
            if (productSize.getQuantity() >= qty) {
                int before = productSize.getQuantity();
                int currQty = productSize.getQuantity() - qty;
                productSize.setQuantity(currQty);

                // 데이터베이스 저장
                // [JA] データベースに保存
                productSize = productSizeRepository.save(productSize);
                // 0 을 건넜으면(품절·재입고) 커밋 뒤에 알린다(docs/53).
                // [JA] 0 をまたいだら(品切れ・再入荷)コミット後に通知する(docs/53)。
                stockEvents.onQuantityChanged(variantOf(productSize), before, currQty);

                // 데이터베이스 업데이트 후 캐시를 갱신하는 메서드 호출
                // [JA] データベース更新後にキャッシュを更新するメソッドの呼び出し
                refreshProductsCache(slug);

                return productSize;
            }
        }
        return null;
    }

    /** 이벤트에 실을 옵션 정보 — 커밋 전(영속성 컨텍스트 안)에 읽는다. */
    // [JA] イベントに載せるオプション情報 — コミット前(永続性コンテキスト内)に読む。
    private static com.project.catalog_service.message.producer.StockEventProducer.Variant variantOf(ProductSize size) {
        ProductSku sku = size.getSku();
        Product product = sku == null ? null : sku.getProduct();
        ProductColor color = sku == null ? null : sku.getColor();
        return new com.project.catalog_service.message.producer.StockEventProducer.Variant(
                product == null ? null : product.getProductId(),
                product == null ? null : product.getSlug(),
                product == null ? null : product.getName(),
                color == null ? null : color.getColorId(),
                color == null ? null : color.getColor(),
                size.getSize());
    }

    // ── 관리자 재고 (docs/53) ──────────────────────────────────────────────
    // [JA] ── 管理者在庫 (docs/53) ──

    /** 관리자 재고 목록 한 줄. */
    // [JA] 管理者在庫一覧の 1 行。
    public record StockRow(Long sizeId, Long productId, String name, String slug, Long colorId, String color,
            String size, String skuCode, int quantity) {
    }

    /** 한 번에 돌려주는 최대 줄 수 — 관리자 화면 한 장. */
    // [JA] 一度に返す最大行数 — 管理者画面 1 枚分。
    public static final int MAX_STOCK_ROWS = 200;

    /**
     * 재고 목록. {@code soldOutOnly} 면 품절(0 이하)만 — "채울 것" 목록이다. 상품 id·사이즈 id 순.
     *
     * <p>[JA] 在庫一覧。{@code soldOutOnly} なら品切れ(0 以下)だけ — 「補充すべきもの」の一覧である。商品 id・サイズ id 順。
     */
    @Transactional(readOnly = true)
    public List<StockRow> stockRows(boolean soldOutOnly, Long afterSizeId) {
        return productSizeRepository.findStockRows(soldOutOnly, afterSizeId == null ? -1L : afterSizeId,
                        org.springframework.data.domain.PageRequest.of(0, MAX_STOCK_ROWS))
                .stream().map(ProductService::rowOf).collect(Collectors.toList());
    }

    /**
     * 관리자가 재고를 <b>정한다</b>(더하기가 아니라 그 값으로). 0 을 건너면 품절·재입고가 나간다 — 채우면
     * 재입고 알림을 신청한 손님에게 알림이 간다.
     *
     * @return 바뀐 줄. 그런 사이즈가 없으면 null
     *
     * <p>[JA] 管理者が在庫を<b>決める</b>(加算ではなくその値に)。0 をまたぐと品切れ・再入荷が送られる — 補充すると再入荷通知を申し込んだ客に通知が行く。
     * 戻り値は変わった行。そのサイズがなければ null。
     */
    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    public StockRow setStock(Long sizeId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다: " + quantity);
        }
        ProductSize size = productSizeRepository.findById(sizeId).orElse(null);
        if (size == null) {
            return null;
        }
        int before = size.getQuantity();
        size.setQuantity(quantity);
        productSizeRepository.save(size);
        stockEvents.onQuantityChanged(variantOf(size), before, quantity);
        StockRow row = rowOf(size);
        log.info("관리자 재고 조정: sizeId={} productId={} {} → {}", sizeId, row.productId(), before, quantity);
        if (row.slug() != null) {
            refreshProductsCache(row.slug());
        }
        return row;
    }

    private static StockRow rowOf(ProductSize size) {
        var v = variantOf(size);
        return new StockRow(size.getSizeId(), v.productId(), v.name(), v.slug(), v.colorId(), v.color(),
                size.getSize(), size.getSkuCode(), size.getQuantity());
    }

    /**
     * @CachePut 어노테이션을 사용하여 Redis 캐시를 갱신합니다.
     *           이 메서드는 오직 캐시를 업데이트하는 역할만 담당합니다.
     *
     * <p>[JA] @CachePut アノテーションを使って Redis キャッシュを更新する。このメソッドはキャッシュの更新だけを担当する。
     */
    @Transactional
    @CachePut(value = "products", key = "'slug:' + #slug")
    public List<ProductDto> refreshProductsCache(String slug) {
        log.info("Refreshing cache for slug: {}", slug);
        List<Product> products = productRepository.findBySlug(slug);
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * 주문 결제 뒤의 재고 차감, 그리고 그 <b>역</b>인 재고 복원(환불·반품 입고).
     *
     * <p>{@code request.restock} 이 참이면 같은 항목 목록을 부호만 바꿔 적용한다 — 사이즈
     * 수량이 돌아오고 판매량이 빠진다. 별도 경로를 두지 않은 이유: 차감과 복원은 정확히
     * 같은 SKU·사이즈를 찾아 같은 두 필드를 만지는 일이고, 경로가 둘이면 한쪽만 고쳐지는
     * 날이 온다(CAT-13 이 그런 종류의 결함이었다). 멱등성은 리스너의 {@code eventId} 가
     * 지킨다 — 차감은 {@code order-<id>}, 복원은 {@code restock-<id>} 라 각각 한 번이다.
     *
     * <h3>🔴 재고가 모자랄 때 — 초과 판매</h3>
     *
     * <p>재고는 <b>결제 뒤에</b> 차감한다. 마지막 1개를 두 사람이 동시에 결제하면 뒤의 차감은 모자란다. 예전에는 그때
     * {@code updateProductSize_Qty} 가 조용히 null 을 돌려주고 리스너가 로그 한 줄을 남기는 것이 전부였다 — 주문은
     * 결제 완료로 진행되고, 환불도 경보도 없었다. 🐛 게다가 판매량은 수량을 확인하기 <b>전에</b> 올라가서, 차감이
     * 실패해도 판매량만 늘었다.
     *
     * <p>이제 줄마다 SKU 를 잠근 채({@code findForStockUpdate}) 남은 수량을 보고 <b>있는 만큼만</b> 배정한다. 판매량도
     * 배정한 만큼만 오른다. 모자란 수는 결과의 {@code shortages} 로 돌려주고, 리스너가 같은 트랜잭션에서 부족
     * 기록(아웃박스)을 남긴다 — order 가 그 수만큼 자동 환불한다(docs/53 §초과 판매). 상품·SKU·사이즈를 찾지 못한
     * 줄은 전부 모자란 것으로 본다(보낼 수 없다).
     *
     * <p>[JA] 注文決済後の在庫差し引き、そしてその<b>逆</b>である在庫復元(返金・返品入庫)。
     * <p>{@code request.restock} が真なら同じ項目リストを符号だけ変えて適用する — サイズ数量が戻り、販売数が減る。別経路を置かない理由: 差し引きと復元はまったく同じ SKU・サイズを探して
     * 同じ二つのフィールドを触る仕事で、経路が二つあると片方だけ直される日が来る(CAT-13 がその種の欠陥だった)。冪等性はリスナーの {@code eventId} が守る —
     * 差し引きは {@code order-<id>}、復元は {@code restock-<id>} でそれぞれ一回である。
     * <p>🔴 在庫が足りないとき — 売り越し: 在庫は<b>決済後に</b>差し引く。最後の 1 個を二人が同時に決済すると後の差し引きは足りない。以前はそのとき
     * {@code updateProductSize_Qty} が黙って null を返し、リスナーがログを一行残すだけだった — 注文は決済完了で進み、返金も警報もなかった。🐛 さらに販売数は数量を確かめる<b>前に</b>上がり、差し引きが失敗しても販売数だけ増えた。
     * <p>今は行ごとに SKU をロックしたまま({@code findForStockUpdate})残数を見て<b>ある分だけ</b>割り当てる。販売数も割り当てた分だけ上がる。足りない数は結果の {@code shortages} で返し、
     * リスナーが同じトランザクションで不足記録(アウトボックス)を残す — order がその数だけ自動返金する(docs/53 §売り越し)。商品・SKU・サイズが見つからない行はすべて不足とみなす(送れない)。
     */
    @Transactional
    // 주문 성공 시 판매량/재고가 바뀌므로 검색/필터 캐시를 무효화한다.
    // (updateSoldValue/updateProductSize_Qty는 여기서 this로 자기호출되어 그쪽 애노테이션은 프록시를 타지 않으므로,
    //  실제 외부 진입점인 이 메서드에 무효화를 건다.)
    // [JA] 注文成功時は販売数/在庫が変わるので検索/フィルタキャッシュを無効化する。
    //      (updateSoldValue/updateProductSize_Qty はここで this による自己呼び出しになり、そちらのアノテーションはプロキシを通らないので、実際の外部入口であるこのメソッドに無効化をかける。)
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    public StockApplyResult productUpdated(ProductUpdateRequest request) {
        return productUpdated(request, HeldQuantities.NONE);
    }

    /**
     * @param held 이 차감이 쓸 수 없는 수 — <b>다른</b> 주문이 결제 전에 잡아 둔 수(docs/59). 결제 확정 차감은 자기 주문의
     *             예약을 쓰므로 그것은 빼고 넘어온다(StockReservationService.heldByOthers).
     *
     * <p>[JA] held: この差し引きが使えない数 — <b>他の</b>注文が決済前に押さえた数(docs/59)。決済確定の差し引きは自分の注文の予約を使うので、それは除いて渡される(StockReservationService.heldByOthers)。
     */
    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category"}, allEntries = true)
    public StockApplyResult productUpdated(ProductUpdateRequest request, HeldQuantities held) {

        ProductSize lastUpdated = null;
        List<ShortLine> shortages = new ArrayList<>();

        if (request == null || request.getOrderProducts() == null || request.getOrderProducts().isEmpty()) {
            return new StockApplyResult(null, List.of());
        }

        for (OrderProductRequest item : inLockOrder(request.getOrderProducts())) {
            int qty = Math.abs(item.getQty());
            Product product = getProductModel(item.getProductId());

            if (request.isRestock()) {
                // 복원 — 수량이 돌아오고 판매량이 빠진다. 모자랄 일이 없다.
                // [JA] 復元 — 数量が戻り販売数が減る。足りなくなることはない。
                if (product == null) {
                    continue;
                }
                ProductSku sku = updateSoldValue(product.getProductId(), product.getSlug(), item.getSize(),
                        item.getColorId(), -qty);
                if (sku != null) {
                    lastUpdated = updateProductSize_Qty(product.getSlug(), sku.getSkuproductId(), item.getSize(), -qty);
                }
                continue;
            }

            // 차감 — 있는 만큼만 배정한다. 잠금은 이 트랜잭션이 끝날 때까지 쥔다(같은 SKU 의 동시 차감은 줄을 선다).
            // 남은 수 = 잠근 뒤 읽은 실물 − 다른 주문이 결제 전에 잡아 둔 수(docs/59).
            // [JA] 差し引き — ある分だけ割り当てる。ロックはこのトランザクションが終わるまで握る(同じ SKU の同時差し引きは順番待ちになる)。
            //      残数 = ロック後に読んだ実在庫 − 他の注文が決済前に押さえた数(docs/59)。
            int available = product == null ? 0
                    : Math.max(0, availableForUpdate(product.getProductId(), item.getSize(), item.getColorId())
                            - (held == null ? 0 : held.heldByOthers(product.getProductId(), item.getColorId(), item.getSize())));
            int allocated = Math.min(available, qty);
            if (allocated > 0) {
                ProductSku sku = updateSoldValue(product.getProductId(), product.getSlug(), item.getSize(),
                        item.getColorId(), allocated);
                if (sku != null) {
                    ProductSize updated = updateProductSize_Qty(product.getSlug(), sku.getSkuproductId(),
                            item.getSize(), allocated);
                    if (updated != null) {
                        lastUpdated = updated;
                    } else {
                        allocated = 0;   // 잠금 아래에서 확인했으므로 오지 않는다 — 와도 모자란 것으로 센다 ／ [JA] ロックの下で確認したので来ない — 来ても不足として数える
                    }
                } else {
                    allocated = 0;
                }
            }
            if (allocated < qty) {
                shortages.add(new ShortLine(item.getProductId(), item.getColorId(), item.getSize(), qty, qty - allocated));
            }
        }
        return new StockApplyResult(lastUpdated, List.copyOf(shortages));
    }

    /**
     * 잠금 순서 — 예약(StockReservationService.Option.ORDER)과 같은 (상품, 색상, 사이즈) 순서로 줄을 돈다.
     *
     * <p>🐛 예전에는 주문 줄 순서대로 SKU 를 잠갔다(예약만 정렬했다). [A, B] 주문과 [B, A] 주문의 차감 · 복원이 겹치면 서로의
     * 잠금을 기다리다 PostgreSQL 이 한쪽을 교착(40P01)으로 끊었고, 끊긴 결제 확정 차감은 재시도를 다 쓰면 DLT 로 갔다 —
     * 재고는 줄지 않은 채 예약이 만료되어 같은 수량이 다시 팔렸다.
     *
     * <p>[JA] ロック順序 — 仮押さえ(StockReservationService.Option.ORDER)と同じ (商品, 色, サイズ) 順で行を回る。
     *
     * <p>[JA] 以前は注文行の順で SKU をロックしていた(仮押さえだけ並べていた)。[A, B] 注文と [B, A] 注文の減算 · 復元が重なると互いの
     * ロックを待ち、PostgreSQL が片方をデッドロック(40P01)で切り、切られた決済確定の減算は再試行を使い切ると DLT へ行った —
     * 在庫は減らないまま仮押さえが期限切れになり、同じ数量が再び売れた。
     */
    static List<OrderProductRequest> inLockOrder(List<OrderProductRequest> lines) {
        List<OrderProductRequest> sorted = new ArrayList<>(lines);
        sorted.sort(java.util.Comparator
                .comparing(OrderProductRequest::getProductId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(OrderProductRequest::getColorId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(OrderProductRequest::getSize, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return sorted;
    }

    /**
     * 이 옵션의 남은 수량 — SKU 를 잠근 뒤에 읽는다. 없는 SKU·사이즈는 0. 음수(예전 초과 판매의 흔적)도 0 으로 본다.
     *
     * <p>[JA] このオプションの残数 — SKU をロックした後に読む。存在しない SKU・サイズは 0。負数(以前の売り越しの痕跡)も 0 とみなす。
     */
    int availableForUpdate(Long productId, String size, Long colorId) {
        Optional<ProductSku> sku = productskuRepository.findForStockUpdate(productId, size, colorId);
        if (sku.isEmpty()) {
            return 0;
        }
        ProductSize productSize = getProductSizeModel(sku.get().getSkuproductId(), size);
        return productSize == null ? 0 : Math.max(0, productSize.getQuantity());
    }

    /** 다른 주문이 결제 전에 잡아 둔 수 — 옵션(상품·색상·사이즈)별(docs/59). */
    // [JA] 他の注文が決済前に押さえた数 — オプション(商品・色・サイズ)別(docs/59)。
    @FunctionalInterface
    public interface HeldQuantities {
        HeldQuantities NONE = (productId, colorId, size) -> 0;

        int heldByOthers(Long productId, Long colorId, String size);
    }

    /** 재고 반영 결과 — 마지막으로 바뀐 사이즈 행(없으면 null)과 모자란 줄들. */
    // [JA] 在庫反映の結果 — 最後に変わったサイズ行(なければ null)と不足した行。
    public record StockApplyResult(ProductSize lastUpdated, List<ShortLine> shortages) {
        public boolean hasShortage() {
            return shortages != null && !shortages.isEmpty();
        }
    }

    /** 모자란 줄 — 주문한 수와 그중 보낼 수 없는 수. */
    // [JA] 不足した行 — 注文した数と、そのうち送れない数。
    public record ShortLine(Long productId, Long colorId, String size, int orderedQty, int shortQty) {
    }
}
