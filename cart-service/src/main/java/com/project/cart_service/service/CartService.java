package com.project.cart_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.cart.request.CartRequest;
import com.project.common.dto.catalog.request.CartProductInfoRequest;
import com.project.common.dto.catalog.request.ProductInfoRequest;
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.request.CartRollbackRequest;
import com.project.common.message.dto.request.CouponUseRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Transactional
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartProductRepository cartProductRepository;
    private final CatalogServiceClient catalogServiceClient;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate; // RedisTemplate 주입 ／ [JA] RedisTemplate の注入
    

    /**
     * 카트 상품 ID를 이용하여 상품 ID를 조회합니다.
     * @param cartProductId 카트 상품 ID
     * @return 상품 ID
     *
     * <p>[JA] カート商品 ID を使って商品 ID を照会する。
     */
    @Transactional(readOnly = true)
    public Long getProductId(String cartProductId) {
        log.debug("Fetching product ID for cartProductId: {}", cartProductId);
        return cartProductRepository.findById(Long.parseLong(cartProductId))
                .orElseThrow(() -> new RuntimeException("Cart product not found")).getProductId();
    }

    /**
     * 장바구니 캐시의 키 — <b>사용자 id</b>.
     *
     * <p>예전에는 {@code carts::<이메일>} 이었다. 장바구니는 DB 에 id 로 저장되는데 캐시만 이메일이라, 캐시를 갱신·삭제하는
     * 모든 경로(Kafka 로 오는 cart-empty·coupon-use·cart-rollback 포함)가 <b>user-service 에 id → 이메일을 물어야</b>
     * 했다. 그 호출에 쓸 토큰이 없어 사용자 액세스 토큰을 Kafka 메시지에 실어 날랐고(docs/54 §10.1), user-service 가
     * 멈추면 커밋이 끝난 뒤에 예외가 나 메시지가 재처리됐다. 이메일이 캐시 키(= Redis 키 이름)에 평문으로 남기도 했다.
     * id 면 그 호출이 전부 필요 없다(docs/48 §6-⑥).
     *
     * <p>[JA] カートキャッシュのキー — <b>ユーザー id</b>。
     *      以前は {@code carts::<メール>} だった。カートは DB に id で保存されるのにキャッシュだけメールだったため、キャッシュを更新・削除する
     *      すべての経路(Kafka で来る cart-empty・coupon-use・cart-rollback を含む)が <b>user-service に id → メールを問い合わせる</b>
     *      必要があった。その呼び出しに使うトークンがなく、ユーザーのアクセストークンを Kafka メッセージに載せて運び(docs/54 §10.1)、user-service が
     *      止まるとコミットが終わった後に例外が出てメッセージが再処理された。メールがキャッシュキー(= Redis キー名)に平文で残ってもいた。
     *      id ならその呼び出しはすべて不要である(docs/48 §6-⑥)。
     */
    static String cacheKey(Long userId) {
        return "carts::" + userId;
    }

    /**
     * 사용자의 장바구니. 캐시에서 먼저 조회하고, 없으면 DB에서 가져와 캐시에 저장합니다.
     * @param userId 게이트웨이가 검증한 토큰의 uid({@code User-Id} 헤더)
     * @return 카트 DTO, 없으면 null
     *
     * <p>[JA] ユーザーのカート。まずキャッシュから照会し、なければ DB から取得してキャッシュに保存する。
     */
    // catalog(색상 · SKU)를 부른다 — 트랜잭션 밖에서(B-87). 장바구니는 줄과 함께 짧게 읽는다(findByUserId 의 @EntityGraph).
    // [JA] catalog(色 · SKU)を呼ぶ — トランザクションの外で(B-87)。カートは行と一緒に短く読む(findByUserId の @EntityGraph)。
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CartDto getCart(Long userId) {
        String cacheKey = cacheKey(userId);
        CartDto cachedCart = (CartDto) redisTemplate.opsForValue().get(cacheKey);

        // SKU 코드가 빈 줄이 있으면 캐시를 쓰지 않는다 — 코드가 생기기 전에 만든 캐시다. 다시 만들면 catalog 에서
        // 채운다(fillMissingSkuCodes). 코드를 끝내 못 찾는 줄이 있으면 매번 다시 만들지만, 그런 줄은 드물다.
        // [JA] SKU コードが空の行があればキャッシュを使わない — コードができる前に作ったキャッシュである。作り直すと catalog から
        //      埋める(fillMissingSkuCodes)。コードを最後まで見つけられない行があれば毎回作り直すが、そういう行はまれである。
        if (cachedCart != null && !hasMissingSkuCode(cachedCart)) {
            log.debug("Cache hit for {}", cacheKey);
            return cachedCart;
        }

        log.debug("Cache miss for {}, fetching from DB.", cacheKey);
        Cart cart = getCartByUserId(userId);

        if (cart == null) {
            log.info("Cart not found for user ID: {}", userId);
            return null;
        }

        // DTO로 변환하여 캐시에 저장
        // [JA] DTO に変換してキャッシュに保存
        CartDto cartDto = convertCartToDto(cart);
        redisTemplate.opsForValue().set(cacheKey, cartDto, CACHE_TTL);
        log.debug("Cart data saved to cache for {}", cacheKey);

        return cartDto;
    }

    /** 재계산의 트랜잭션. 세터 — 생성자를 쓰는 테스트가 많다. 없으면(단위 테스트) 트랜잭션 없이 돈다. */
    // [JA] 再計算のトランザクション。セッター — コンストラクタを使うテストが多い。なければ(単体テスト)トランザクションなしで動く。
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * 체크아웃 — 주문의 근거(order · coupon 이 {@code /checkout} 으로 읽는다). 고른 줄의 가격 · 배송비를 <b>catalog 의 지금 값</b>으로
     * 다시 맞춘 뒤 돌려준다.
     *
     * <p>🐛 예전에는 담을 때의 가격을 그대로 썼다 — 할인 중에 담아 두면 할인이 끝난 뒤에도 할인가로 결제됐다(반대로 값이 내렸어도 옛
     * 값). order 는 이 합계를 결제 금액으로 쓴다(OrderPricing).
     *
     * <p>catalog 는 트랜잭션 밖에서 부르고, 바뀐 줄이 있을 때만 짧은 트랜잭션으로 저장한다. catalog 에 닿지 못하면 저장된 가격 그대로 —
     * 주문을 막지는 않는다(경고를 남긴다).
     *
     * <p>[JA] チェックアウト — 注文の根拠(order · coupon が {@code /checkout} で読む)。選んだ行の価格 · 送料を <b>catalog の現在値</b>に
     * 合わせ直してから返す。
     *
     * <p>🐛 以前はカートに入れた時の価格をそのまま使っていた — セール中に入れておくと、セール終了後もセール価格で決済された(逆に値下がりしても
     * 古い値)。order はこの合計を決済金額として使う(OrderPricing)。
     *
     * <p>catalog はトランザクションの外で呼び、変わった行があるときだけ短いトランザクションで保存する。catalog に届かなければ保存済みの価格のまま
     * — 注文は止めない(警告を残す)。
     *
     * <p>[JA] チェックアウト — 選んだ行の価格 · 送料を catalog の現在値に合わせて返す。以前は入れた時の価格のままで、セール終了後も
     * セール価格で決済された。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CartDto getCartForCheckout(Long userId) {
        Cart cart = getCartByUserId(userId);
        if (cart == null) {
            return null;
        }
        Map<String, BigDecimal[]> fresh = currentPrices(cart);
        if (!fresh.isEmpty()) {
            if (transactions == null) {
                applyPrices(userId, fresh);
            } else {
                transactions.executeWithoutResult(status -> applyPrices(userId, fresh));
            }
        }
        return getCart(userId);
    }

    private static String lineKey(Long productId, Long colorId, String size) {
        return productId + "|" + colorId + "|" + normalize(size);
    }

    /** 고른 줄 중 catalog 의 지금 가격 · 배송비가 저장된 것과 다른 줄 → [가격, 배송비]. catalog 에 닿지 못하면 빈 맵. */
    // [JA] 選んだ行のうち catalog の今の価格 · 送料が保存値と違う行 → [価格, 送料]。catalog に届かなければ空のマップ。
    private Map<String, BigDecimal[]> currentPrices(Cart cart) {
        List<CartProduct> selected = cart.getCartProducts().stream()
                .filter(CartProduct::isSelected).filter(l -> l.getProductId() != null).collect(Collectors.toList());
        if (selected.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductDto> catalog;
        try {
            catalog = fetchProducts(selected.stream().map(l -> Long.toString(l.getProductId())).collect(Collectors.toList()));
        } catch (RuntimeException e) {
            log.warn("체크아웃 가격 재확인 실패 — 저장된 가격을 쓴다. userId={} ({})", cart.getUserId(), e.getClass().getSimpleName());
            return Map.of();
        }
        Map<String, BigDecimal[]> changed = new java.util.HashMap<>();
        for (CartProduct line : selected) {
            ProductDto product = catalog.get(Long.toString(line.getProductId()));
            ProductSkuDto sku = product == null || product.getSkus() == null || line.getColorId() == null ? null
                    : product.getSkus().stream()
                            .filter(s -> s.getColor() != null && Long.toString(line.getColorId()).equals(s.getColor().getId()))
                            .findFirst().orElse(null);
            if (sku == null || sku.getSizes() == null) {
                continue;   // 상품 · 스타일이 없어졌다 — 가격을 지어내지 않는다(주문 쪽 재고 예약이 막는다) ／ [JA] 商品 · スタイルがなくなった — 価格をでっち上げない(注文側の在庫予約が止める)
            }
            BigDecimal list = sku.getSizes().stream()
                    .filter(z -> java.util.Objects.equals(normalize(z.getSize()), normalize(line.getSize())))
                    .map(com.project.common.dto.ProductSizeDto::getPrice).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            if (list == null) {
                continue;
            }
            BigDecimal price = discountedPrice(list, sku.getDiscount());
            BigDecimal shipping = product.getShipping() == null ? line.getShipping() : new BigDecimal(product.getShipping());
            if (line.getPrice() == null || price.compareTo(line.getPrice()) != 0
                    || (shipping != null && (line.getShipping() == null || shipping.compareTo(line.getShipping()) != 0))) {
                changed.put(lineKey(line.getProductId(), line.getColorId(), line.getSize()), new BigDecimal[] { price, shipping });
            }
        }
        return changed;
    }

    private void applyPrices(Long userId, Map<String, BigDecimal[]> fresh) {
        Cart cart = getCartByUserId(userId);
        if (cart == null) {
            return;
        }
        int updated = 0;
        for (CartProduct line : cart.getCartProducts()) {
            BigDecimal[] p = fresh.get(lineKey(line.getProductId(), line.getColorId(), line.getSize()));
            if (p != null && line.isSelected()) {
                line.setPrice(p[0]);
                if (p[1] != null) {
                    line.setShipping(p[1]);
                }
                updated++;
            }
        }
        if (updated > 0) {
            log.info("체크아웃 가격 재확인 — {}줄의 가격을 지금 값으로 맞췄다. userId={}", updated, userId);
            saveRecomputed(cart);
        }
    }

    private static boolean hasMissingSkuCode(CartDto cart) {
        return cart.getProducts() != null
                && cart.getProducts().stream().anyMatch(p -> p != null && p.getSkuCode() == null);
    }

    /** 커밋 뒤에 캐시를 비운다. 롤백되면 그대로 둔다 — 커밋 전에 비우면 그 틈의 조회가 옛 값을 다시 넣는다. */
    // [JA] コミット後にキャッシュを消す。ロールバックされればそのまま — コミット前に消すとその隙間の照会が古い値を入れ直す。
    private void evictAfterCommit(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.delete(cacheKey(userId));
                log.debug("Cache evicted for {}", cacheKey(userId));
            }
        });
    }

    /**
     * 장바구니 캐시의 만료 — 커밋 뒤 갱신 · 삭제가 실패해도 옛 값이 이 시간 뒤에는 사라진다.
     *
     * <p>🐛 예전에는 만료 없이 썼다. 커밋 뒤 캐시 갱신이 실패하면(Redis 순간 장애 · DTO 변환 실패) 옛 장바구니가 영영 남아, 주문이 옛 줄 ·
     * 옛 합계로 만들어졌다(order 가 /checkout 으로 이 캐시를 읽는다).
     * [JA] カートキャッシュの有効期限 — 以前は期限なしで、コミット後の更新が失敗すると古いカートが残り、注文が古い行と合計で作られた。
     */
    static final java.time.Duration CACHE_TTL = java.time.Duration.ofMinutes(30);

    /**
     * 바꾼 장바구니 — 커밋 뒤에 캐시를 <b>지운다</b>. 다음 조회(getCart)가 트랜잭션 밖에서 다시 만든다.
     *
     * <p>🐛 예전에는 커밋 뒤에 새 DTO 를 만들어 넣었다 — 그 변환이 catalog(색상 · SKU)를 부르는데, afterCommit 은 트랜잭션 자원을 정리하기
     * 전이라 DB 커넥션을 쥔 채 catalog 를 기다렸다(풀 5개, B-87).
     * [JA] 変更したカート — コミット後にキャッシュを消す。以前は afterCommit で catalog を呼び、接続を持ったまま待っていた。
     */
    private void putAfterCommit(Cart cart) {
        evictAfterCommit(cart.getUserId());
    }

    /** 트랜잭션 하나로 — 원격 호출을 앞뒤로 뺀 쓰기 경로가 쓴다. 트랜잭션 관리자가 없으면(단위 테스트) 그대로 돈다. */
    // [JA] 1 つのトランザクションで — リモート呼び出しを前後に出した書き込み経路が使う。トランザクションマネージャがなければ(単体テスト)そのまま動く。
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(status -> work.get());
    }

    /**
     * 카트의 상품 정보를 업데이트합니다.
     * @param request 상품 정보 요청 DTO
     * @return 업데이트된 상품 정보 DTO 리스트
     *
     * <p>[JA] カートの商品情報を更新する。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // DB 를 쓰지 않고 catalog 만 부른다 ／ [JA] DB を使わず catalog だけを呼ぶ
    public List<ProductInfoDto> updateCart(ProductInfoRequest request) {
        log.debug("Updating cart with new product information.");
        if (request.getProducts() == null) {
            return null;
        }

        Map<String, ProductDto> products = fetchProducts(
                request.getProducts().stream().map(ProductInfoDto::getId).collect(Collectors.toList()));

        return request.getProducts().stream()
                .map(item -> computeProductPrices(item, products))
                .collect(Collectors.toList());
    }

    /**
     * 사용자 ID를 이용하여 카트 정보를 불러옵니다.
     * @param userId 게이트웨이가 검증한 토큰의 uid
     * @return 상품 정보 DTO 리스트
     *
     * <p>[JA] ユーザー ID を使ってカート情報を読み込む。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // catalog 를 부른다 — 장바구니는 줄과 함께 짧게 읽는다 ／ [JA] catalog を呼ぶ — カートは行と一緒に短く読む
    public List<ProductInfoDto> loadCart(Long userId) {
        log.debug("Loading cart for user ID: {}", userId);

        Optional<Cart> optionalCart = cartRepository.findByUserId(userId);
        if (optionalCart.isEmpty()) {
            // 장바구니가 없으면 빈 목록 — null 이 아니다. 🐛 2026-10-24 운영: null 이면 200 에 본문이 비고, 화면(axios)은 그것을 빈
            // 문자열로 받아 `(items ?? []).map` 에서 TypeError — 장바구니 화면이 오류 화면이 됐다. 장바구니 행이 없는 것은 흔하다:
            // 처음 온 사용자, 주문 뒤(장바구니 비우기가 행을 지운다), 마지막 줄을 뺀 직후(removeItem 이 이 값을 돌려준다).
            // [JA] カートがなければ空のリスト(null ではない)。null だと 200 で本文が空になり、画面はそれを空文字列として受けて
            //      TypeError — カート画面がエラー画面になった。注文後 · 最後の行を消した直後など、カートの行がないのはよくある。
            log.debug("Cart not found for user ID: {}", userId);
            return List.of();
        }

        Cart cart = optionalCart.get();
        Map<String, ProductDto> products = fetchProducts(
                cart.getCartProducts().stream().map(CartProduct::getProductId).filter(java.util.Objects::nonNull)
                        .map(String::valueOf).collect(Collectors.toList()));

        // 두 번째 왕복 — 항목별 (상품, 스타일, 사이즈) 정보. 사이즈 인덱스는 첫 왕복의 SKU 목록에서
        // 구하므로 순서가 이렇다. 예전에는 이것도 항목마다였다(항목 N 개 = 2N 왕복, 지금은 2).
        //
        // 🐛 catalog 가 더는 모르는 줄(삭제·사이즈 변경·판매 중지)이 하나라도 있으면 예전에는 장바구니 전체가 예외였다. 담기·수량·
        // 삭제가 이 응답을 돌려주게 된 뒤로는 그 줄을 <b>빼는 요청까지</b> 같은 트랜잭션에서 롤백된다 — 장바구니가 영영 막힌다.
        // 그런 줄은 응답에서만 빼고(경고 로그) 남은 줄을 돌려준다. 체크아웃이 고르지 못하니 주문(/checkout)에도 들어가지 않는다.
        // [JA] 2 回目の往復 — 項目ごとの(商品, スタイル, サイズ)情報。サイズのインデックスは 1 回目の往復の SKU 一覧から
        //      求めるのでこの順序になる。以前はこれも項目ごとだった(項目 N 個 = 2N 往復、今は 2)。
        //      🐛 catalog がもう知らない行(削除・サイズ変更・販売停止)が 1 つでもあると、以前はカート全体が例外だった。追加・数量・
        //      削除がこの応答を返すようになってからは、その行を <b>外すリクエストまで</b> 同じトランザクションでロールバックされる — カートが永久に詰まる。
        //      そういう行は応答からだけ外し(警告ログ)、残りの行を返す。チェックアウトで選べないので注文(/checkout)にも入らない。
        List<CartProduct> resolvable = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (CartProduct p : cart.getCartProducts()) {
            String key = infoKey(p, products);
            if (key == null) {
                log.warn("Cart line no longer in catalog, hidden: cartProductId={} productId={}", p.getCartproductId(), p.getProductId());
                continue;
            }
            resolvable.add(p);
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        Map<String, ProductInfoDto> cartInfos = fetchCartProductInfos(keys);

        List<ProductInfoDto> result = new ArrayList<>();
        for (CartProduct p : resolvable) {
            if (cartInfos.containsKey(infoKey(p, products))) {
                result.add(mapToProductInfoDto(p, products, cartInfos));
            } else {
                log.warn("Cart line info unavailable, hidden: cartProductId={} productId={}", p.getCartproductId(), p.getProductId());
            }
        }
        return result;
    }

    /** 이 줄의 catalog 배치 키(상품:스타일:사이즈 인덱스). catalog 가 상품·스타일·사이즈를 모르면 null. */
    // [JA] この行の catalog バッチキー(商品:スタイル:サイズインデックス)。catalog が商品・スタイル・サイズを知らなければ null。
    private static String infoKey(CartProduct p, Map<String, ProductDto> products) {
        if (p.getProductId() == null) {
            return null;   // 옛 스키마의 줄 — 물을 id 가 없다(dropLinesWithoutProduct) ／ [JA] 旧スキーマの行 — 問い合わせる id がない(dropLinesWithoutProduct)
        }
        ProductDto product = products.get(Long.toString(p.getProductId()));
        int style = product == null ? -1 : styleIndexOf(p, product);
        if (style < 0) {
            return null;
        }
        try {
            return CartProductInfoRequest.Item.key(p.getProductId(), style, sizeIndexOf(product, style, p.getSize()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 이 줄이 가리키는 SKU(스타일)가 지금 상품의 SKU 목록에서 몇 번째인지. 없으면 -1.
     *
     * <p>🐛 줄은 담을 때의 <b>목록 위치</b>({@code style})만 믿었다. 위치는 catalog 의 SKU 목록이 바뀌면(변형이 빠지거나 순서가
     * 달라지면) 다른 색상 · 가격을 가리킨다 — 장바구니가 조용히 다른 변형을 보여 주고 그 값으로 청구한다. 줄에는 담을 때 SKU 가
     * 정한 색상 id 가 있으므로 그것으로 찾고, 저장된 위치는 그 위치의 색상이 같을 때만 쓴다. 색상 id 가 없는 옛 줄만 위치를 그대로 쓴다.
     */
    // [JA] 行は追加時の一覧上の位置(style)だけを信じていた。位置は SKU 一覧が変わると別の色・価格を指すので、保存した色 id で探す。
    static int styleIndexOf(CartProduct p, ProductDto product) {
        List<ProductSkuDto> skus = product.getSkus();
        if (skus == null) {
            return -1;
        }
        int stored = p.getStyle();
        if (p.getColorId() == null) {
            return stored >= 0 && stored < skus.size() ? stored : -1;
        }
        String color = Long.toString(p.getColorId());
        if (stored >= 0 && stored < skus.size() && color.equals(colorIdOf(skus.get(stored)))) {
            return stored;
        }
        for (int i = 0; i < skus.size(); i++) {
            if (color.equals(colorIdOf(skus.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String colorIdOf(ProductSkuDto sku) {
        return sku.getColor() == null ? null : sku.getColor().getId();
    }

    /** SKU 의 사이즈 목록에서 이 항목의 사이즈가 몇 번째인지 — catalog 의 단건·배치 API 가 인덱스를 받는다. */
    // [JA] SKU のサイズ一覧でこの項目のサイズが何番目か — catalog の単件・バッチ API がインデックスを受け取る。
    private static int sizeIndexOf(ProductDto product, int styleIndex, String size) {
        ProductSkuDto sku = product.getSkus().size() > styleIndex ? product.getSkus().get(styleIndex) : null;
        if (sku == null) {
            throw new IllegalArgumentException("Product SKU not found");
        }
        for (int i = 0; i < sku.getSizes().size(); i++) {
            if (java.util.Objects.equals(normalize(sku.getSizes().get(i).getSize()), normalize(size))) {
                return i;
            }
        }
        throw new IllegalArgumentException("Product size not found");
    }

    private Map<String, ProductInfoDto> fetchCartProductInfos(List<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductInfoDto> found = catalogServiceClient.getCartProductsInfo(keys).getBody();
        return found == null ? Map.of() : found;
    }

    /**
     * 체크아웃 — 고른 줄을 표시한다(담긴 줄은 지우지 않는다).
     *
     * <p>🐛 예전에는 서버 장바구니를 <b>고른 줄로 통째로 바꿨다</b>(지우고 새로 만들기). 고르지 않은 줄은 서버에서 사라졌고, 담기·수량·삭제는
     * 브라우저에만 있어 다른 기기에서는 옛 장바구니가 보였다(docs/40 0-b). 이제 담기·수량·삭제는 {@link #putItem}·{@link #setItemQty}·
     * {@link #removeItem} 이 바로 서버에 반영하고, 체크아웃은 고른 줄을 (없으면 더해) selected 로, 나머지는 selected=false 로 둔다.
     * 주문(order-service 가 읽는 /checkout)은 고른 줄로만 만든다.
     *
     * @return 장바구니, 고른 줄이 없으면 null(예전과 같다)
     *
     * <p>[JA] チェックアウト — 選んだ行に印を付ける(カートに入った行は消さない)。
     *      🐛 以前はサーバーのカートを <b>選んだ行で丸ごと置き換えていた</b>(消して作り直す)。選ばなかった行はサーバーから消え、追加・数量・削除は
     *      ブラウザにしかなく、別の端末では古いカートが見えた(docs/40 0-b)。今は追加・数量・削除は {@link #putItem}・{@link #setItemQty}・
     *      {@link #removeItem} がすぐサーバーに反映し、チェックアウトは選んだ行を(なければ足して)selected に、残りを selected=false にする。
     *      注文(order-service が読む /checkout)は選んだ行だけで作る。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // catalog 는 앞에서, 저장은 짧은 트랜잭션으로(B-87) ／ [JA] catalog は前で、保存は短いトランザクションで(B-87)
    public Cart saveCart(CartRequest request, Long userId) {
        log.debug("Saving cart selection for user ID: {}", userId);
        if (request.getProducts() == null || request.getProducts().isEmpty()) {
            log.warn("Attempted to save an empty cart. Returning null.");
            return null;
        }
        Map<String, ProductDto> catalog = fetchProducts(
                request.getProducts().stream().map(ProductInfoDto::getId).collect(Collectors.toList()));
        return inTransaction(() -> saveSelection(request, userId, catalog));
    }

    private Cart saveSelection(CartRequest request, Long userId, Map<String, ProductDto> catalog) {
        // 🔒 본문의 email 은 읽지 않는다 — 신원은 검증된 uid 다(CartController 주석).
        // [JA] 🔒 本文の email は読まない — アイデンティティは検証済みの uid である(CartController のコメント)。
        Cart cart = cartOf(userId);

        java.util.Set<CartProduct> chosen = new java.util.HashSet<>();
        for (ProductInfoDto item : request.getProducts()) {
            if (item == null) {
                throw new IllegalArgumentException(QTY_MESSAGE);
            }
            CartProduct line = upsertLine(cart, item, catalog);
            if (line != null) {
                chosen.add(line);
            }
        }
        for (CartProduct line : cart.getCartProducts()) {
            line.setSelected(chosen.contains(line));
        }

        Cart result = saveRecomputed(cart);
        log.info("Saved cart selection for user ID: {} ({} of {} lines selected)", userId, chosen.size(), cart.getCartProducts().size());
        return result;
    }

    // ── 서버가 장바구니의 기준 — 담기·수량·삭제 (docs/40 0-b 2단계) ──────────────────────────
    // [JA] ── サーバーがカートの基準 — 追加・数量・削除(docs/40 0-b 2 段階) ──

    /**
     * 담는다 — 같은 줄(상품·색상·사이즈)이 있으면 수량을 이 값으로 바꾼다(화면의 "담기" 가 그렇게 동작한다). 새로 담은 줄은 고른 것이다.
     *
     * @return 지금 장바구니(loadCart 와 같은 모양)
     * @throws IllegalArgumentException 수량이 1 보다 작거나, 상품·색상·사이즈를 catalog 가 모른다
     *
     * <p>[JA] 追加する — 同じ行(商品・色・サイズ)があれば数量をこの値に変える(画面の「カートに入れる」がそう動く)。新しく入れた行は選択済みである。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // catalog 는 앞뒤로, 저장만 짧은 트랜잭션(B-87) ／ [JA] catalog は前後で、保存だけ短いトランザクション(B-87)
    public List<ProductInfoDto> putItem(Long userId, ProductInfoDto item) {
        if (item == null || item.getId() == null || item.getQty() < 1) {
            throw new IllegalArgumentException("담을 상품과 수량(1 이상)이 필요합니다.");
        }
        Map<String, ProductDto> catalog = fetchProducts(List.of(item.getId()));
        inTransaction(() -> {
            Cart cart = cartOf(userId);
            CartProduct line = upsertLine(cart, item, catalog);
            if (line == null) {
                throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
            }
            line.setSelected(true);
            return saveRecomputed(cart);
        });
        return loadCart(userId);
    }

    /** 수량을 바꾼다 — 0 이하면 뺀다. 없는 줄이면 그대로(여러 번 불러도 같다). */
    // [JA] 数量を変える — 0 以下なら外す。ない行ならそのまま(何度呼んでも同じ)。
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // 바꾸기는 짧은 트랜잭션, 응답(catalog)은 그 뒤(B-87) ／ [JA] 変更は短いトランザクション、応答(catalog)はその後(B-87)
    public List<ProductInfoDto> setItemQty(Long userId, Long productId, Long colorId, String size, int qty) {
        if (qty > MAX_LINE_QTY) {
            throw new IllegalArgumentException(QTY_MESSAGE);
        }
        boolean found = inTransaction(() -> changeQty(userId, productId, colorId, size, qty));
        return found ? loadCart(userId) : List.of();
    }

    /** @return 장바구니가 있었는가 */
    // [JA] @return カートがあったか
    private boolean changeQty(Long userId, Long productId, Long colorId, String size, int qty) {
        Cart cart = getCartByUserId(userId);
        if (cart == null) {
            return false;
        }
        int before = cart.getCartProducts().size();
        dropLinesWithoutProduct(cart);
        boolean dropped = cart.getCartProducts().size() != before;
        CartProduct line = findLine(cart, productId, colorId, size);
        if (line != null) {
            if (qty <= 0) {
                // 동일성으로 뺀다 — 엔티티는 동일성으로만 비교한다(예전 BaseEntity @Data 의 equals 는 줄을 가리지 못했다).
                // [JA] 同一性で外す — エンティティは同一性でのみ比較する(以前の BaseEntity @Data の equals は行を区別できなかった)。
                cart.getCartProducts().removeIf(l -> l == line);   // orphanRemoval 이 행을 지운다 ／ [JA] orphanRemoval が行を消す
            } else {
                line.setQty(qty);
            }
        }
        if (line != null || dropped) {
            finishChange(cart, userId);
        }
        return true;
    }

    /** 뺀다. 없는 줄이면 그대로. 마지막 줄이면 장바구니를 지운다. */
    // [JA] 外す。ない行ならそのまま。最後の行ならカートを消す。
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<ProductInfoDto> removeItem(Long userId, Long productId, Long colorId, String size) {
        return setItemQty(userId, productId, colorId, size, 0);
    }

    private void finishChange(Cart cart, Long userId) {
        if (cart.getCartProducts().isEmpty()) {
            cartRepository.delete(cart);
            evictAfterCommit(userId);
        } else {
            saveRecomputed(cart);
        }
    }

    /**
     * 사용자의 장바구니 — 없으면 만든다.
     *
     * <p>🐛 예전에는 "읽고 없으면 save" 였다. 처음 담기가 두 탭에서 동시에 오면 장바구니가 둘 생겼고, 그 뒤로 이 사용자의
     * {@code findByUserId} 는 결과가 둘이라 예외 — 장바구니가 영영 열리지 않았다. 이제 user_id 는 유니크(V5)이고, 만들기는
     * {@link CartRepository#insertIfAbsent} 로 해서 늦은 쪽도 실패하지 않고 같은 장바구니를 쓴다.
     */
    // [JA] 以前は「読んで無ければ save」で、同時の初回追加でカートが二つでき、以後そのユーザーのカートが開けなくなっていた。
    private Cart cartOf(Long userId) {
        Cart cart = getCartByUserId(userId);
        if (cart == null) {
            cartRepository.insertIfAbsent(io.hypersistence.tsid.TSID.Factory.getTsid().toLong(), userId);
            return getCartByUserId(userId);
        }
        dropLinesWithoutProduct(cart);
        return cart;
    }

    /**
     * 상품 id 가 없는 줄을 지운다 — 쓰는 경로(담기·수량·삭제·체크아웃)마다.
     *
     * <p>🐛 옛 스키마 때 만든 줄은 product_id 가 비어 있다. 이런 줄은 보여 줄 수도(catalog 에 물을 id 가 없다) 주문할 수도 없고,
     * 결제 뒤 비우기(상품·색상·사이즈로 맞춘다)에도 걸리지 않아 장바구니에 영영 남았다. 서버가 장바구니의 기준이 된 뒤로는 담기 응답이
     * 장바구니 전체를 읽다가 이 줄에서 NPE 가 나 <b>담기가 전부 400</b> 이 됐다.
     *
     * <p>[JA] 商品 id のない行を消す — 書き込み経路(追加・数量・削除・チェックアウト)ごとに。
     *      🐛 旧スキーマ時代に作った行は product_id が空である。こういう行は表示も(catalog に問い合わせる id がない)注文もできず、
     *      決済後の削除(商品・色・サイズで照合する)にもかからず、カートに永久に残った。サーバーがカートの基準になってからは、追加の応答が
     *      カート全体を読む途中でこの行で NPE になり、<b>追加がすべて 400</b> になった。
     */
    private static void dropLinesWithoutProduct(Cart cart) {
        int before = cart.getCartProducts().size();
        cart.getCartProducts().removeIf(line -> line.getProductId() == null);   // orphanRemoval 이 행을 지운다 ／ [JA] orphanRemoval が行を消す
        if (cart.getCartProducts().size() != before) {
            log.warn("상품 id 없는 장바구니 줄 {}개를 지웠다 userId={}", before - cart.getCartProducts().size(), cart.getUserId());
        }
    }

    /** 같은 줄 — 상품·색상·사이즈(빈 사이즈는 같은 것으로). 결제 뒤 비우기(sameLine)와 같은 규칙이다. */
    // [JA] 同じ行 — 商品・色・サイズ(空のサイズは同じものとみなす)。決済後の削除(sameLine)と同じ規則である。
    private static CartProduct findLine(Cart cart, Long productId, Long colorId, String size) {
        for (CartProduct line : cart.getCartProducts()) {
            if (java.util.Objects.equals(line.getProductId(), productId)
                    && java.util.Objects.equals(line.getColorId(), colorId)
                    && java.util.Objects.equals(normalize(line.getSize()), normalize(size))) {
                return line;
            }
        }
        return null;
    }

    /** 한 줄의 수량 상한 — 총액 계산(가격 × 수량)이 터무니없어지지 않게. */
    // [JA] 1 行の数量上限 — 合計額の計算(価格 × 数量)がとんでもない値にならないように。
    static final int MAX_LINE_QTY = 1000;

    static final String QTY_MESSAGE = "수량은 1 ~ 1000 사이여야 합니다.";

    /**
     * 한 줄의 수량은 1 ~ {@link #MAX_LINE_QTY}.
     *
     * <p>🐛 예전에는 체크아웃 저장(savecart)이 본문의 수량을 그대로 저장했다 — {@code qty=-5} 인 줄이 고른 줄이 되어 장바구니 총액이
     * 음수가 됐고, 그 장바구니로 만든 주문의 금액이 다른 줄의 값을 깎았다(담기 · 수량 변경은 하한을 봤지만 저장은 보지 않았다).
     *
     * <p>[JA] 一行の数量は 1〜1000。以前は savecart が本文の数量をそのまま保存し、負の数量で合計が減った。
     */
    static int checkedQty(int qty) {
        if (qty < 1 || qty > MAX_LINE_QTY) {
            throw new IllegalArgumentException(QTY_MESSAGE);
        }
        return qty;
    }

    /** 이 항목의 줄 — 있으면 수량·가격을 새로, 없으면 만든다. catalog 가 모르는 상품이면 null. */
    // [JA] この項目の行 — あれば数量・価格を新しく、なければ作る。catalog が知らない商品なら null。
    private CartProduct upsertLine(Cart cart, ProductInfoDto item, Map<String, ProductDto> catalog) {
        checkedQty(item.getQty());
        CartProduct fresh = createCartProduct(item, cart, catalog);
        if (fresh == null) {
            return null;
        }
        CartProduct existing = findLine(cart, fresh.getProductId(), fresh.getColorId(), fresh.getSize());
        if (existing != null) {
            existing.setQty(fresh.getQty());
            existing.setPrice(fresh.getPrice());
            existing.setShipping(fresh.getShipping());
            existing.setStyle(fresh.getStyle());
            existing.setSkuCode(fresh.getSkuCode());
            return existing;
        }
        cart.getCartProducts().add(fresh);
        // 🐛 새 줄은 <b>직접 저장한다.</b> 컬렉션에 더하기만 하고 연쇄(cascade = PERSIST)에 맡겼더니, 이미 저장된 장바구니에서는
        // 저장이 merge 로 가고(PERSIST 는 merge 에 연쇄되지 않는다) 줄이 DB 에 남지 않았다. 응답(같은 트랜잭션의 메모리 상태)에는
        // 보이다가 다음 요청에서 사라졌다 — "에러 없이 담기가 안 된다"(CartPersistenceJpaTest).
        // [JA] 🐛 新しい行は <b>直接保存する。</b> コレクションに足すだけでカスケード(cascade = PERSIST)に任せたら、既に保存済みのカートでは
        //      保存が merge になり(PERSIST は merge にカスケードしない)、行が DB に残らなかった。応答(同じトランザクションのメモリ状態)には
        //      見えていたのに次のリクエストで消えた — 「エラーなしで追加できない」(CartPersistenceJpaTest)。
        cartProductRepository.save(fresh);
        return fresh;
    }

    /** 총액은 고른 줄로(주문이 되는 것). 쿠폰 적용액은 바뀐 장바구니에 맞지 않으니 비운다. 커밋 뒤 캐시를 새로. */
    // [JA] 合計額は選んだ行で(注文になるもの)。クーポン適用額は変わったカートに合わないので空にする。コミット後にキャッシュを新しくする。
    private Cart saveRecomputed(Cart cart) {
        cart.setCartTotal(computeCartTotal(cart.getCartProducts()));
        cart.setTotalAfterDiscount(BigDecimal.ZERO);
        Cart result = cartRepository.save(cart);
        putAfterCommit(result);
        return result;
    }

    /**
     * 쿠폰 사용 후의 카트 총액을 업데이트합니다.
     * 트랜잭션 커밋 후 캐시를 갱신합니다.
     * @param request 쿠폰 사용 요청 DTO
     * @return 업데이트된 카트 엔티티
     *
     * <p>[JA] クーポン使用後のカート合計額を更新する。
     *      トランザクションのコミット後にキャッシュを更新する。
     */
    public Cart couponUsed(CouponUseRequest request) {
        log.debug("Applying coupon for user ID: {}", request.getUserId());
        Cart cart = getCartByUserId(request.getUserId());

        if (cart != null) {
            cart.setTotalAfterDiscount(request.getTotalAfterDiscount());
            log.info("Cart total after discount updated for user ID: {}", request.getUserId());
            Cart result = cartRepository.save(cart);

            putAfterCommit(result);

            return result;
        }

        log.warn("Cart not found for coupon use, user ID: {}", request.getUserId());
        return null;
    }

    /**
     * 카트의 모든 항목을 비웁니다.
     * 트랜잭션 커밋 후 캐시를 삭제합니다.
     * @param request 카트 비우기 요청 DTO
     *
     * <p>[JA] カートのすべての項目を空にする。
     *      トランザクションのコミット後にキャッシュを削除する。
     */
    public void cartEmpty(CartEmptyRequest request) {
        log.debug("Emptying cart for user ID: {}", request.getUserId());
        // 주문당 한 번 — 🐛 예전에는 재전달되면 주문한 줄을 한 번 더 뺐다(그 사이 다시 담은 같은 상품까지). 옛 발행자(orderId 없음)는 그대로.
        // [JA] 注文ごとに一度 — 以前は再配信で注文した行をもう一度取り除いた。
        if (request.getOrderId() != null && cartRepository.markProcessed("cart-empty-" + request.getOrderId()) == 0) {
            log.info("이미 반영한 장바구니 비우기 — 건너뛴다. orderId={} userId={}", request.getOrderId(), request.getUserId());
            return;
        }
        if (request.isEmptyCart()) {
            if (request.getItems().isEmpty()) {
                // 옛 발행자 — 품목이 없다. 예전처럼 전부 비운다.
                // [JA] 旧発行者 — 品目がない。以前どおりすべて空にする。
                deleteCartByUserId(request.getUserId());
                log.info("Cart emptied successfully for user ID: {}", request.getUserId());
            } else {
                removeOrderedLines(request);
            }

            // 트랜잭션 커밋 성공 후 캐시 삭제
            // [JA] トランザクションのコミット成功後にキャッシュを削除
            evictAfterCommit(request.getUserId());
        }
    }

    /**
     * 주문한 품목만 장바구니에서 뺀다.
     *
     * <h3>🐛 환불했더니 환불과 무관한 물건까지 사라졌다</h3>
     *
     * <p>사라진 것은 환불 때문이 아니라 <b>그 전 결제의 cart-empty</b> 때문이었다. 이 이벤트는
     * 결제 응답 뒤 사가가 비동기로 보내고, 예전에는 "이 사용자의 장바구니를 통째로 지워라" 였다.
     * 확정이 늦어지는 동안 손님이 새로 담은 물건이 있으면 그것까지 같이 지워졌다 — 늦어질수록
     * 자주. 이제 발행자가 주문의 품목(상품·사이즈·색상·수량)을 싣고, 여기서는 그 줄만 뺀다.
     *
     * <p>같은 상품·사이즈·색상이 같은 줄이다(담기 규칙과 같다). 주문 수량만큼 줄이고, 0 이 되면
     * 줄을 지운다. 줄이 하나도 안 남으면 장바구니 자체를 지운다(예전과 같은 끝 상태).
     * 주문에는 있는데 장바구니에 없는 줄은 이미 뺀 것이다(재전달) — 조용히 넘어간다.
     *
     * <p>[JA] 注文した品目だけをカートから外す。
     *      🐛 返金したら返金と無関係な品物まで消えた
     *      消えたのは返金のせいではなく <b>その前の決済の cart-empty</b> のせいだった。このイベントは
     *      決済応答の後にサガが非同期で送り、以前は「このユーザーのカートを丸ごと消せ」だった。
     *      確定が遅れている間に客が新しく入れた品物があれば、それまで一緒に消された — 遅れるほど
     *      頻繁に。今は発行者が注文の品目(商品・サイズ・色・数量)を載せ、ここではその行だけを外す。
     *      同じ商品・サイズ・色が同じ行である(追加の規則と同じ)。注文数量だけ減らし、0 になれば
     *      行を消す。行が 1 つも残らなければカート自体を消す(以前と同じ最終状態)。
     *      注文にはあるのにカートにない行は既に外したもの(再配信) — 黙って通過する。
     */
    private void removeOrderedLines(CartEmptyRequest request) {
        Cart cart = getCartByUserId(request.getUserId());
        if (cart == null) {
            log.info("Cart already gone for user ID: {} — nothing to remove", request.getUserId());
            return;
        }
        int removed = 0;
        for (com.project.common.message.dto.request.CartEmptyItem item : request.getItems()) {
            int remaining = item.getQty();
            java.util.Iterator<CartProduct> it = cart.getCartProducts().iterator();
            while (it.hasNext() && remaining > 0) {
                CartProduct line = it.next();
                if (!sameLine(line, item)) {
                    continue;
                }
                int take = Math.min(remaining, line.getQty());
                remaining -= take;
                removed += take;
                if (line.getQty() - take <= 0) {
                    it.remove();                 // orphanRemoval 이 행을 지운다 ／ [JA] orphanRemoval が行を消す
                } else {
                    line.setQty(line.getQty() - take);
                }
            }
        }
        if (cart.getCartProducts().isEmpty()) {
            cartRepository.delete(cart);
            log.info("Cart emptied (all {} ordered units removed) for user ID: {}", removed, request.getUserId());
            return;
        }
        cart.setCartTotal(computeCartTotal(cart.getCartProducts()));
        cart.setTotalAfterDiscount(BigDecimal.ZERO);
        cartRepository.save(cart);
        log.info("Removed {} ordered units from cart, {} lines remain, user ID: {}",
                removed, cart.getCartProducts().size(), request.getUserId());
    }

    private static boolean sameLine(CartProduct line, com.project.common.message.dto.request.CartEmptyItem item) {
        return java.util.Objects.equals(line.getProductId(), item.getProductId())
                && java.util.Objects.equals(normalize(line.getSize()), normalize(item.getSize()))
                && java.util.Objects.equals(line.getColorId(), item.getColorId());
    }

    private static String normalize(String size) {
        return size == null || size.isBlank() ? null : size.trim();
    }

    /**
     * 결제 실패의 보상 — 쿠폰 적용액을 지우고 총액을 다시 센다. <b>줄의 수량은 건드리지 않는다.</b>
     *
     * <p>🐛 예전에는 모든 줄의 수량을 1 로 바꿨다 — 3개를 담고 결제가 실패하면 장바구니가 1개가 됐다. 장바구니는 결제가 <b>성공한 뒤에만</b>
     * 비워지므로(cart-empty) 결제 실패로 되돌릴 수량이 애초에 없다.
     *
     * <p>[JA] 決済失敗の補償 — クーポン適用額を消し、合計を数え直す。行の数量には触れない。以前はすべての行を 1 にしていた。
     */
    public Cart cartRollback(CartRollbackRequest request) {
        log.debug("Rolling back cart for user ID: {}", request.getUserId());
        Cart cart = getCartByUserId(request.getUserId());

        if (cart != null) {
            BigDecimal cartTotal = computeCartTotal(cart.getCartProducts());
            cart.setCartTotal(cartTotal);
            cart.setTotalAfterDiscount(BigDecimal.ZERO);
            log.info("Cart rollback complete for user ID: {}", request.getUserId());
            Cart result = cartRepository.save(cart);

            putAfterCommit(result);

            return result;
        }

        log.warn("Cart not found for rollback, user ID: {}", request.getUserId());
        return null;
    }

    // --- Private Helper Methods ---

    /**
     * 사용자 ID를 통해 카트를 조회합니다. 없으면 null을 반환합니다.
     *
     * <p>트랜잭션은 부르는 쪽의 것이다 — private 이라 프록시를 지나지 않는다(B-108). 예전의 {@code @Transactional(readOnly = true)}
     * 는 한 번도 적용되지 않았다: NOT_SUPPORTED 경로(getCart · getCartForCheckout)에서는 저장소 호출 하나가 자기 트랜잭션으로 돌고,
     * 줄 · 연관은 {@code findByUserId} 가 함께 읽는다.
     * <p>[JA] ユーザー ID でカートを照会する。なければ null を返す。
     * トランザクションは呼び出し側のもの — private なのでプロキシを通らない(B-108)。以前の {@code @Transactional(readOnly = true)}
     * は一度も適用されていなかった: NOT_SUPPORTED の経路(getCart · getCartForCheckout)ではリポジトリ呼び出し一つが自分のトランザクションで動き、
     * 行 · 関連は {@code findByUserId} が一緒に読む。
     * <p>[JA] トランザクションは呼び出し側のもの — private なのでプロキシを通らない。以前の readOnly は一度も効いていなかった。
     */
    private Cart getCartByUserId(long userId) {
        return cartRepository.findByUserId(userId).orElse(null);
    }

    /**
     * 공개 데모 계정의 장바구니를 통째로 비운다 — 매일 초기화(DemoCartReset, docs/100). 캐시는 커밋 뒤에 지운다.
     * [JA] 公開デモアカウントのカートを丸ごと空にする — 毎日の初期化。キャッシュはコミット後に消す。
     */
    public void resetDemoCart(Long userId) {
        deleteCartByUserId(userId);
        evictAfterCommit(userId);
    }

    /**
     * 사용자 ID를 통해 카트를 삭제합니다.
     *
     * <p>[JA] ユーザー ID でカートを削除する。
     */
    //@Transactional
    private void deleteCartByUserId(Long userId) {
        cartRepository.findByUserId(userId).ifPresent(cartRepository::delete);
    }

    /**
     * CartProduct를 CartProductDto로 매핑합니다.
     *
     * <p>[JA] CartProduct を CartProductDto にマッピングする。
     */
    private CartProductDto mapToCartProductDto(CartProduct item, Map<String, ProductColorDto> colors) {
        ProductColorDto color = item.getColorId() == null ? null : colors.get(Long.toString(item.getColorId()));
        return CartProductDto.builder()
                .id(Long.toString(item.getCartproductId()))
                // 상품 id 는 엔티티에 이미 있다 — 추가 조회 없이 실어 보낸다. 이게 없으면
                // 장바구니를 받은 쪽(쿠폰 제약 판정 등)이 "이 줄이 어떤 상품인가"를 알 수 없다.
                // [JA] 商品 id はエンティティに既にある — 追加照会なしに載せて送る。これがないと
                //      カートを受け取った側(クーポン制約の判定など)が「この行がどの商品か」を知ることができない。
                .productId(item.getProductId() == null ? null : Long.toString(item.getProductId()))
                .color(color == null ? null : ProductColorDto.builder()
                        .id(color.getId())
                        .color(color.getColor())
                        .colorImage(color.getColorImage()).build())
                .image(item.getImage())
                .name(item.getName())
                .price(item.getPrice())
                // 배송비를 항목에 실어 보낸다. 총액(cartTotal)에 녹아 있을 뿐이라
                // 받는 쪽이 분리할 수 없었다(CartProductDto.shipping 주석).
                // [JA] 送料を項目に載せて送る。合計額(cartTotal)に溶け込んでいるだけなので
                //      受け取る側が分離できなかった(CartProductDto.shipping のコメント)。
                .shipping(item.getShipping())
                .qty(item.getQty())
                .size(item.getSize())
                .skuCode(item.getSkuCode())
                .build();
    }

    /** 스타일 안에서 사이즈 이름으로 그 줄의 SKU. 없거나 아직 코드가 없으면 null. */
    // [JA] スタイルの中でサイズ名からその行の SKU。ないか、まだコードがなければ null。
    private static String skuCodeOf(ProductSkuDto sku, String size) {
        if (sku == null || sku.getSizes() == null) {
            return null;
        }
        return sku.getSizes().stream()
                .filter(s -> java.util.Objects.equals(normalize(s.getSize()), normalize(size)))
                .map(com.project.common.dto.ProductSizeDto::getSkuCode)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 줄의 SKU 를 상품 정보에서 찾는다 — 스타일은 <b>색상 id</b> 로 고른다. 저장된 스타일 순번은 그사이 색상이 추가되면
     * 다른 스타일을 가리킬 수 있지만, 색상 id 는 줄을 식별하는 값이라 흔들리지 않는다.
     *
     * <p>[JA] 行の SKU を商品情報から探す — スタイルは <b>色 id</b> で選ぶ。保存されたスタイルの順番はその間に色が追加されると
     *      別のスタイルを指しうるが、色 id は行を識別する値なので揺るがない。
     */
    private static String skuCodeOf(ProductDto product, Long colorId, String size) {
        if (product == null || product.getSkus() == null || colorId == null) {
            return null;
        }
        String color = Long.toString(colorId);
        return product.getSkus().stream()
                .filter(s -> s.getColor() != null && color.equals(s.getColor().getId()))
                .map(s -> skuCodeOf(s, size))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * SKU 코드가 없는 고른 줄(코드가 생기기 전에 담긴 것)을 catalog 에서 채운다 — 주문 품목이 빈 SKU 로 남지 않게.
     * 전부 있으면 catalog 를 부르지 않는다. 조회가 실패해도 장바구니는 그대로 돌려준다(코드는 기록일 뿐 주문을 막을 이유가 아니다).
     *
     * <p>[JA] SKU コードのない選択行(コードができる前に入れたもの)を catalog から埋める — 注文品目が空の SKU で残らないように。
     *      すべてあれば catalog を呼ばない。照会が失敗してもカートはそのまま返す(コードは記録にすぎず注文を止める理由ではない)。
     */
    private void fillMissingSkuCodes(List<CartProduct> lines, List<CartProductDto> dtos) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (dtos.get(i).getSkuCode() == null && lines.get(i).getProductId() != null) {
                missing.add(Long.toString(lines.get(i).getProductId()));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, ProductDto> products;
        try {
            products = fetchProducts(missing);
        } catch (RuntimeException e) {
            log.warn("SKU 코드 보충 실패 — 빈 채로 둔다: lines={}, cause={}", missing.size(), e.getMessage());
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            CartProductDto dto = dtos.get(i);
            CartProduct line = lines.get(i);
            if (dto.getSkuCode() == null && line.getProductId() != null) {
                dto.setSkuCode(skuCodeOf(products.get(Long.toString(line.getProductId())), line.getColorId(), line.getSize()));
            }
        }
    }

    /**
     * 장바구니에 든 상품들을 catalog-service 에서 <b>한 번에</b> 가져온다.
     *
     * <p>예전에는 항목마다 {@code getProductInfo} 를 직렬로 불렀다 — 항목 N 개면 Feign 왕복 N 번,
     * 각각 연결 3초·읽기 5초 타임아웃을 안고서. 스레드 풀로 병렬화하는 길은 막혀 있다:
     * {@code FeignConfig} 가 {@code Authorization} 을 요청 스레드의 컨텍스트에서 복사하므로
     * 워커 스레드에서는 헤더가 빠진다. 그래서 배치 엔드포인트다(docs/38 §1.13).
     *
     * <p>같은 상품이 여러 줄(색상·사이즈만 다른)이면 id 는 한 번만 보낸다. 결과는 id 로 찾을 수
     * 있게 맵으로 돌려주고, catalog 가 모르는 id 는 맵에 없다.
     *
     * <p>[JA] カートに入った商品を catalog-service から <b>一度に</b> 取得する。
     *      以前は項目ごとに {@code getProductInfo} を直列に呼んでいた — 項目 N 個なら Feign 往復 N 回、
     *      それぞれ接続 3 秒・読み取り 5 秒のタイムアウトを抱えて。スレッドプールで並列化する道は塞がっている:
     *      {@code FeignConfig} が {@code Authorization} をリクエストスレッドのコンテキストからコピーするので、
     *      ワーカースレッドではヘッダが抜ける。だからバッチエンドポイントである(docs/38 §1.13)。
     *      同じ商品が複数行(色・サイズだけ違う)なら id は 1 回だけ送る。結果は id で探せる
     *      ようにマップで返し、catalog が知らない id はマップにない。
     */
    private Map<String, ProductDto> fetchProducts(Collection<String> productIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String id : productIds) {
            ids.add(Long.parseLong(id));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ProductDto> found = catalogServiceClient.getProductsInfo(new ArrayList<>(ids)).getBody();
        if (found == null) {
            return Map.of();
        }
        return found.stream().collect(Collectors.toMap(ProductDto::getId, Function.identity(), (a, b) -> a));
    }

    /** 장바구니 항목들의 색상을 한 번에. 못 찾은 색상은 맵에 없다. */
    // [JA] カート項目の色を一度に。見つからない色はマップにない。
    private Map<String, ProductColorDto> fetchColors(Collection<CartProduct> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CartProduct item : items) {
            if (item.getColorId() != null) {
                ids.add(item.getColorId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ProductColorDto> found = catalogServiceClient.getColorsInfo(new ArrayList<>(ids)).getBody();
        if (found == null) {
            return Map.of();
        }
        return found.stream().collect(Collectors.toMap(ProductColorDto::getId, Function.identity(), (a, b) -> a));
    }

    private static ProductDto requireProduct(Map<String, ProductDto> products, String productId) {
        ProductDto product = products.get(productId);
        if (product == null) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
        return product;
    }

    /**
     * ProductInfoDto의 가격을 계산합니다.
     *
     * <p>[JA] ProductInfoDto の価格を計算する。
     */
    private ProductInfoDto computeProductPrices(ProductInfoDto cartItem, Map<String, ProductDto> products) {
        ProductDto product = requireProduct(products, cartItem.getId());
        BigDecimal originalPrice = computeOriginalPriceFromSkusByStyleAndSize(
                product.getSkus(), cartItem.getStyle(), cartItem.getSize());
        BigDecimal price = discountedPrice(originalPrice, skuAt(product.getSkus(), cartItem.getStyle()).getDiscount());

        ProductInfoDto dto = cartItem.toBuilder().build();
        dto.setPriceBefore(originalPrice.toPlainString());
        dto.setQty(cartItem.getQty());
        dto.setPrice(price.toPlainString());
        dto.setShipping(product.getShipping());

        return dto;
    }

    /**
     * CartProduct를 ProductInfoDto로 매핑합니다.
     *
     * <p>[JA] CartProduct を ProductInfoDto にマッピングする。
     */
    private ProductInfoDto mapToProductInfoDto(CartProduct p, Map<String, ProductDto> products,
                                               Map<String, ProductInfoDto> cartInfos) {
        ProductDto product = requireProduct(products, Long.toString(p.getProductId()));
        int styleIndex = styleIndexOf(p, product);   // 저장된 위치가 아니라 색상으로 찾은 지금 위치(loadCart 의 infoKey 와 같다) ／ [JA] 保存された位置ではなく色で探した今の位置(loadCart の infoKey と同じ)

        // 배치 응답에서 이 항목의 (상품, 스타일, 사이즈) 정보. 없으면 품절·삭제·잘못된 인덱스다 —
        // 단건 API 가 null 을 돌려주던 경우와 같다.
        // [JA] バッチ応答でのこの項目の(商品, スタイル, サイズ)情報。なければ品切れ・削除・誤ったインデックスである —
        //      単件 API が null を返していた場合と同じ。
        String key = CartProductInfoRequest.Item.key(p.getProductId(), styleIndex, sizeIndexOf(product, styleIndex, p.getSize()));
        ProductInfoDto found = cartInfos.get(key);
        if (found == null) {
            throw new IllegalArgumentException("Cart product info not found: " + key);
        }
        ProductInfoDto dto = found.toBuilder().build();

        BigDecimal originalPrice = computeOriginalPriceFromSkusByStyleAndSize(product.getSkus(), styleIndex, p.getSize());
        dto.setPriceBefore(originalPrice.toPlainString());

        BigDecimal price = discountedPrice(originalPrice, product.getSkus().get(styleIndex).getDiscount());
        dto.setPrice(price.toPlainString());

        dto.setQty(p.getQty());
        // 🐛 줄의 식별자는 <b>줄 id</b> 다. 저장된 _uid 는 화면이 "상품명_스타일_사이즈" 로 만든 값이라, 이름이 같은 다른 상품(시드에
        // "Digital Camera" 가 여럿)이 같은 값을 가졌다 — 하나를 고르면 셋이 함께 선택되고, 목록의 React key 가 겹쳤다.
        // [JA] 🐛 行の識別子は <b>行 id</b> である。保存された _uid は画面が「商品名_スタイル_サイズ」で作った値なので、名前が同じ別の商品(シードに
        //      "Digital Camera" が複数)が同じ値を持っていた — 1 つを選ぶと 3 つが一緒に選択され、一覧の React key が重複した。
        dto.set_uid(p.getCartproductId() != null ? Long.toString(p.getCartproductId()) : p.get_uid());
        dto.setShipping(product.getShipping());

        return dto;
    }

    /**
     * 할인가 — 할인 값은 퍼센트다. 식은 common 의 {@link com.project.common.pricing.Discount} 하나뿐이다.
     *
     * <p>🐛 예전 식은 {@code price − price / discount} 였다 — catalog · 화면은 9월에 퍼센트 식으로 고쳤는데 여기만 남아,
     * 50% 할인 상품이 장바구니 · 결제에서 정가의 98% 로 청구됐다(주문 금액은 이 장바구니 합계에서 온다 — order OrderPricing).
     */
    // [JA] 割引価格 — 割引値はパーセント。旧式(price − price / discount)がここだけ残り、50% 割引の商品が定価の 98% で請求されていた。
    private static BigDecimal discountedPrice(BigDecimal originalPrice, int discountPercent) {
        return com.project.common.pricing.Discount.apply(originalPrice, discountPercent);
    }

    /**
     * 스타일과 사이즈에 따른 원가를 계산합니다.
     *
     * <p>[JA] スタイルとサイズに応じた原価を計算する。
     */
    private BigDecimal computeOriginalPriceFromSkusByStyleAndSize(List<ProductSkuDto> skus, int style, String size) {
        return skuAt(skus, style).getSizes().stream()
                .filter(i -> java.util.Objects.equals(normalize(i.getSize()), normalize(size)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product size not found")).getPrice();
    }

    /**
     * SKU와 사이즈에 따른 가격을 반환합니다. 사이즈는 {@link #normalize} 로 비교한다 — 빈 사이즈(단일 사이즈 상품)는 null · "" 가 같다.
     *
     * <p>🐛 2026-10-18 order 부하 측정: 담기 실패 536건이 모두 여기의 {@code NullPointerException} 이었다. 사이즈가 하나뿐인 상품은
     * 사이즈 이름이 비어 있고(시드 1,290줄 중 1,025줄), catalog DB 에는 {@code ''} 또는 NULL 로 있다(데모 생성기의 CSV COPY 는
     * 빈 칸을 NULL 로 넣었다). NULL 인 상품은 {@code getSize().equals(...)} 에서 터져 <b>담기가 모두 실패</b>했다.
     */
    // [JA] サイズは normalize で比較する — 単一サイズ商品の空のサイズ(null と "")は同じ。以前は NULL のサイズで NPE になり、カート追加が全て失敗した。
    private BigDecimal getPriceFromSkuBySize(ProductSkuDto sku, String size) {
        BigDecimal price = sku.getSizes().stream()
                .filter(p -> java.util.Objects.equals(normalize(p.getSize()), normalize(size)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product size not found")).getPrice();

        return discountedPrice(price, sku.getDiscount());
    }

    /**
     * 요청의 스타일(SKU 목록 위치)이 가리키는 SKU — 범위 밖이면 고칠 수 있는 요청 오류(400).
     *
     * <p>🐛 예전: 본문의 {@code style} 로 바로 {@code List.get()} 해 {@code IndexOutOfBoundsException} 이 났다 — {@code
     * IllegalArgumentException} 이 아니라 {@code CartController.failure()} 가 <b>503</b> 으로 내렸다. 낡은 화면 하나가 보낸 {@code
     * style: 99} 가 서버 장애로 기록되고 5xx 오류율 · 경보에 들어갔다. 사이즈를 못 찾음도 같았다({@code RuntimeException}).
     * [JA] リクエストの style が指す SKU — 範囲外なら直せる要求エラー(400)。以前は List.get() の IndexOutOfBounds で 503 になり、
     *      古い画面一つが 5xx 率 · 警報に入った。サイズが見つからない場合も同じだった。
     */
    static ProductSkuDto skuAt(List<ProductSkuDto> skus, int style) {
        if (skus == null || style < 0 || style >= skus.size()) {
            throw new IllegalArgumentException("Product SKU not found");
        }
        return skus.get(style);
    }

    /** SKU 에 색상이 없을 때만 요청의 색상 — 없거나 숫자가 아니면 400(예전에는 NPE · NumberFormatException 이 503). */
    // [JA] SKU に色がないときだけリクエストの色 — ないか数字でなければ 400(以前は NPE · NumberFormatException で 503)。
    private static long requestColorId(ProductInfoDto cartItem) {
        if (cartItem.getColor() == null || cartItem.getColor().getId() == null) {
            throw new IllegalArgumentException("Product color not found");
        }
        try {
            return Long.parseLong(cartItem.getColor().getId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Product color not found");
        }
    }

    /**
     * 카트 총액을 계산합니다.
     *
     * <p>[JA] カートの合計額を計算する。
     */
    private BigDecimal computeCartTotal(List<CartProduct> products) {
        // 고른 줄만 — 주문이 되는 것의 총액이다(체크아웃이 고르지 않은 줄을 지우지 않게 된 뒤로, V3).
        // [JA] 選んだ行だけ — 注文になるものの合計額である(チェックアウトが選ばなかった行を消さなくなって以降、V3)。
        return products.stream()
                .filter(CartProduct::isSelected)
                .filter(p -> p.getProductId() != null)
                .map(p -> p.getPrice().multiply(new BigDecimal(p.getQty())).add(p.getShipping()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * CartProduct 엔티티를 생성합니다.
     *
     * <p>[JA] CartProduct エンティティを生成する。
     */
    private CartProduct createCartProduct(ProductInfoDto cartItem, Cart cart, Map<String, ProductDto> products) {
        ProductDto product = products.get(cartItem.getId());

        if (product != null) {
            ProductSkuDto sku = skuAt(product.getSkus(), cartItem.getStyle());
            BigDecimal price = getPriceFromSkuBySize(sku, cartItem.getSize());

            CartProduct cartProduct = CartProduct.builder()
                    .name(product.getName())
                    .colorId(sku.getColor() != null && sku.getColor().getId() != null
                            // 색상은 SKU(스타일)가 정한다 — 본문의 색상을 믿지 않는다(가격도 SKU 에서 온다)
                            // [JA] 色は SKU(スタイル)が決める — 本文の色を信じない(価格も SKU から来る)
                            ? Long.parseLong(sku.getColor().getId())
                            : requestColorId(cartItem))
                    .style(cartItem.getStyle())
                    .productId(Long.parseLong(product.getId()))
                    .image(sku.getImages().get(0))
                    .qty(cartItem.getQty())
                    .price(price)
                    .size(cartItem.getSize())
                    // SKU 는 catalog 의 상품 정보에서 — 본문(cartItem.skuCode)을 믿지 않는다. 주문 품목의 기록이 되는 값이다.
                    // [JA] SKU は catalog の商品情報から — 本文(cartItem.skuCode)を信じない。注文品目の記録になる値である。
                    .skuCode(skuCodeOf(sku, cartItem.getSize()))
                    ._uid(cartItem.get_uid())
                    .shipping(new BigDecimal(product.getShipping()))
                    .build();
            cartProduct.setCart(cart);
            return cartProduct;
        }
        return null;
    }

    /**
     * Cart 엔티티를 CartDto로 변환하는 도우미 메서드 (캐시용)
     *
     * <p>[JA] Cart エンティティを CartDto に変換するヘルパーメソッド(キャッシュ用)
     */
    private CartDto convertCartToDto(Cart cart) {
        // 색상은 장바구니 단위로 한 번 — 항목마다 getColorInfo 를 부르던 것의 대체(docs/38 §1.13).
        // [JA] 色はカート単位で 1 回 — 項目ごとに getColorInfo を呼んでいたものの代替(docs/38 §1.13)。
        Map<String, ProductColorDto> colors = fetchColors(cart.getCartProducts());
        // 주문의 근거(/checkout — order·coupon 이 읽는다)는 고른 줄만. 고르지 않은 줄은 장바구니에 남아 있을 뿐이다.
        // [JA] 注文の根拠(/checkout — order・coupon が読む)は選んだ行だけ。選ばなかった行はカートに残っているだけである。
        List<CartProduct> selected = cart.getCartProducts().stream()
                .filter(CartProduct::isSelected)
                .filter(item -> item.getProductId() != null)   // 주문할 수 없는 옛 줄(dropLinesWithoutProduct) ／ [JA] 注文できない旧行(dropLinesWithoutProduct)
                .collect(Collectors.toList());
        List<CartProductDto> products = selected.stream()
                .map(item -> mapToCartProductDto(item, colors))
                .collect(Collectors.toList());
        fillMissingSkuCodes(selected, products);

        return CartDto.builder()
                .userId(Long.toString(cart.getUserId()))
                // userImage 는 채우지 않는다 — 읽는 곳이 없고, 채우려면 user-service 를 불러야 한다.
                // [JA] userImage は埋めない — 読む場所がなく、埋めるには user-service を呼ぶ必要がある。
                .products(products)
                .cartTotal(cart.getCartTotal())
                .totalAfterDiscount(cart.getTotalAfterDiscount())
                .build();
    }
    
 
}