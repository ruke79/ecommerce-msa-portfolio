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
    private final RedisTemplate<String, Object> redisTemplate; // RedisTemplate 주입
    

    /**
     * 카트 상품 ID를 이용하여 상품 ID를 조회합니다.
     * @param cartProductId 카트 상품 ID
     * @return 상품 ID
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
     */
    static String cacheKey(Long userId) {
        return "carts::" + userId;
    }

    /**
     * 사용자의 장바구니. 캐시에서 먼저 조회하고, 없으면 DB에서 가져와 캐시에 저장합니다.
     * @param userId 게이트웨이가 검증한 토큰의 uid({@code User-Id} 헤더)
     * @return 카트 DTO, 없으면 null
     */
    @Transactional(readOnly = true)
    public CartDto getCart(Long userId) {
        String cacheKey = cacheKey(userId);
        CartDto cachedCart = (CartDto) redisTemplate.opsForValue().get(cacheKey);

        // SKU 코드가 빈 줄이 있으면 캐시를 쓰지 않는다 — 코드가 생기기 전에 만든 캐시다. 다시 만들면 catalog 에서
        // 채운다(fillMissingSkuCodes). 코드를 끝내 못 찾는 줄이 있으면 매번 다시 만들지만, 그런 줄은 드물다.
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
        CartDto cartDto = convertCartToDto(cart);
        redisTemplate.opsForValue().set(cacheKey, cartDto);
        log.debug("Cart data saved to cache for {}", cacheKey);

        return cartDto;
    }

    private static boolean hasMissingSkuCode(CartDto cart) {
        return cart.getProducts() != null
                && cart.getProducts().stream().anyMatch(p -> p != null && p.getSkuCode() == null);
    }

    /** 커밋 뒤에 캐시를 비운다. 롤백되면 그대로 둔다 — 커밋 전에 비우면 그 틈의 조회가 옛 값을 다시 넣는다. */
    private void evictAfterCommit(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.delete(cacheKey(userId));
                log.debug("Cache evicted for {}", cacheKey(userId));
            }
        });
    }

    /** 커밋 뒤에 캐시를 새 값으로 채운다. */
    private void putAfterCommit(Cart cart) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.opsForValue().set(cacheKey(cart.getUserId()), convertCartToDto(cart));
                log.debug("Cache updated for {}", cacheKey(cart.getUserId()));
            }
        });
    }

    /**
     * 카트의 상품 정보를 업데이트합니다.
     * @param request 상품 정보 요청 DTO
     * @return 업데이트된 상품 정보 DTO 리스트
     */
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
     */
    @Transactional(readOnly = true)
    public List<ProductInfoDto> loadCart(Long userId) {
        log.debug("Loading cart for user ID: {}", userId);

        Optional<Cart> optionalCart = cartRepository.findByUserId(userId);
        if (optionalCart.isEmpty()) {
            log.info("Cart not found for user ID: {}", userId);
            return null;
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
    private static String infoKey(CartProduct p, Map<String, ProductDto> products) {
        if (p.getProductId() == null) {
            return null;   // 옛 스키마의 줄 — 물을 id 가 없다(dropLinesWithoutProduct)
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
    private static int sizeIndexOf(ProductDto product, int styleIndex, String size) {
        ProductSkuDto sku = product.getSkus().size() > styleIndex ? product.getSkus().get(styleIndex) : null;
        if (sku == null) {
            throw new RuntimeException("Product SKU not found");
        }
        for (int i = 0; i < sku.getSizes().size(); i++) {
            if (sku.getSizes().get(i).getSize().equals(size)) {
                return i;
            }
        }
        throw new RuntimeException("Product size not found");
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
     */
    @Transactional
    public Cart saveCart(CartRequest request, Long userId) {
        log.debug("Saving cart selection for user ID: {}", userId);
        if (request.getProducts() == null || request.getProducts().isEmpty()) {
            log.warn("Attempted to save an empty cart. Returning null.");
            return null;
        }

        // 🔒 본문의 email 은 읽지 않는다 — 신원은 검증된 uid 다(CartController 주석).
        Cart cart = cartOf(userId);
        Map<String, ProductDto> catalog = fetchProducts(
                request.getProducts().stream().map(ProductInfoDto::getId).collect(Collectors.toList()));

        java.util.Set<CartProduct> chosen = new java.util.HashSet<>();
        for (ProductInfoDto item : request.getProducts()) {
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

    /**
     * 담는다 — 같은 줄(상품·색상·사이즈)이 있으면 수량을 이 값으로 바꾼다(화면의 "담기" 가 그렇게 동작한다). 새로 담은 줄은 고른 것이다.
     *
     * @return 지금 장바구니(loadCart 와 같은 모양)
     * @throws IllegalArgumentException 수량이 1 보다 작거나, 상품·색상·사이즈를 catalog 가 모른다
     */
    @Transactional
    public List<ProductInfoDto> putItem(Long userId, ProductInfoDto item) {
        if (item == null || item.getId() == null || item.getQty() < 1) {
            throw new IllegalArgumentException("담을 상품과 수량(1 이상)이 필요합니다.");
        }
        Cart cart = cartOf(userId);
        Map<String, ProductDto> catalog = fetchProducts(List.of(item.getId()));
        CartProduct line = upsertLine(cart, item, catalog);
        if (line == null) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }
        line.setSelected(true);
        saveRecomputed(cart);
        return loadCart(userId);
    }

    /** 수량을 바꾼다 — 0 이하면 뺀다. 없는 줄이면 그대로(여러 번 불러도 같다). */
    @Transactional
    public List<ProductInfoDto> setItemQty(Long userId, Long productId, Long colorId, String size, int qty) {
        Cart cart = getCartByUserId(userId);
        if (cart == null) {
            return List.of();
        }
        int before = cart.getCartProducts().size();
        dropLinesWithoutProduct(cart);
        boolean dropped = cart.getCartProducts().size() != before;
        CartProduct line = findLine(cart, productId, colorId, size);
        if (line != null) {
            if (qty <= 0) {
                // 동일성으로 뺀다 — 엔티티는 동일성으로만 비교한다(예전 BaseEntity @Data 의 equals 는 줄을 가리지 못했다).
                cart.getCartProducts().removeIf(l -> l == line);   // orphanRemoval 이 행을 지운다
            } else {
                line.setQty(qty);
            }
        }
        if (line != null || dropped) {
            finishChange(cart, userId);
        }
        return loadCart(userId);
    }

    /** 뺀다. 없는 줄이면 그대로. 마지막 줄이면 장바구니를 지운다. */
    @Transactional
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
     */
    private static void dropLinesWithoutProduct(Cart cart) {
        int before = cart.getCartProducts().size();
        cart.getCartProducts().removeIf(line -> line.getProductId() == null);   // orphanRemoval 이 행을 지운다
        if (cart.getCartProducts().size() != before) {
            log.warn("상품 id 없는 장바구니 줄 {}개를 지웠다 userId={}", before - cart.getCartProducts().size(), cart.getUserId());
        }
    }

    /** 같은 줄 — 상품·색상·사이즈(빈 사이즈는 같은 것으로). 결제 뒤 비우기(sameLine)와 같은 규칙이다. */
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

    /** 이 항목의 줄 — 있으면 수량·가격을 새로, 없으면 만든다. catalog 가 모르는 상품이면 null. */
    private CartProduct upsertLine(Cart cart, ProductInfoDto item, Map<String, ProductDto> catalog) {
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
        cartProductRepository.save(fresh);
        return fresh;
    }

    /** 총액은 고른 줄로(주문이 되는 것). 쿠폰 적용액은 바뀐 장바구니에 맞지 않으니 비운다. 커밋 뒤 캐시를 새로. */
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
     */
    public void cartEmpty(CartEmptyRequest request) {
        log.debug("Emptying cart for user ID: {}", request.getUserId());
        if (request.isEmptyCart()) {
            if (request.getItems().isEmpty()) {
                // 옛 발행자 — 품목이 없다. 예전처럼 전부 비운다.
                deleteCartByUserId(request.getUserId());
                log.info("Cart emptied successfully for user ID: {}", request.getUserId());
            } else {
                removeOrderedLines(request);
            }

            // 트랜잭션 커밋 성공 후 캐시 삭제
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
                    it.remove();                 // orphanRemoval 이 행을 지운다
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
     * 카트 상품 수량을 롤백합니다.
     * 트랜잭션 커밋 후 캐시를 업데이트합니다.
     * @param request 카트 롤백 요청 DTO
     * @return 롤백된 카트 엔티티
     */
    public Cart cartRollback(CartRollbackRequest request) {
        log.debug("Rolling back cart for user ID: {}", request.getUserId());
        Cart cart = getCartByUserId(request.getUserId());

        if (cart != null) {
            cart.getCartProducts().forEach(p -> updateQty(p, 1));
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
     */
    @Transactional(readOnly = true)
    private Cart getCartByUserId(long userId) {
        return cartRepository.findByUserId(userId).orElse(null);
    }

    /**
     * 사용자 ID를 통해 카트를 삭제합니다.
     */
    //@Transactional
    private void deleteCartByUserId(Long userId) {
        cartRepository.findByUserId(userId).ifPresent(cartRepository::delete);
    }

    /**
     * CartProduct를 CartProductDto로 매핑합니다.
     */
    private CartProductDto mapToCartProductDto(CartProduct item, Map<String, ProductColorDto> colors) {
        ProductColorDto color = item.getColorId() == null ? null : colors.get(Long.toString(item.getColorId()));
        return CartProductDto.builder()
                .id(Long.toString(item.getCartproductId()))
                // 상품 id 는 엔티티에 이미 있다 — 추가 조회 없이 실어 보낸다. 이게 없으면
                // 장바구니를 받은 쪽(쿠폰 제약 판정 등)이 "이 줄이 어떤 상품인가"를 알 수 없다.
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
                .shipping(item.getShipping())
                .qty(item.getQty())
                .size(item.getSize())
                .skuCode(item.getSkuCode())
                .build();
    }

    /** 스타일 안에서 사이즈 이름으로 그 줄의 SKU. 없거나 아직 코드가 없으면 null. */
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
            throw new RuntimeException("Product not found: " + productId);
        }
        return product;
    }

    /**
     * ProductInfoDto의 가격을 계산합니다.
     */
    private ProductInfoDto computeProductPrices(ProductInfoDto cartItem, Map<String, ProductDto> products) {
        ProductDto product = requireProduct(products, cartItem.getId());
        BigDecimal originalPrice = computeOriginalPriceFromSkusByStyleAndSize(
                product.getSkus(), cartItem.getStyle(), cartItem.getSize());
        BigDecimal price = discountedPrice(originalPrice, product.getSkus().get(cartItem.getStyle()).getDiscount());

        ProductInfoDto dto = new ProductInfoDto(cartItem);
        dto.setPriceBefore(originalPrice.toPlainString());
        dto.setQty(cartItem.getQty());
        dto.setPrice(price.toPlainString());
        dto.setShipping(product.getShipping());

        return dto;
    }

    /**
     * CartProduct를 ProductInfoDto로 매핑합니다.
     */
    private ProductInfoDto mapToProductInfoDto(CartProduct p, Map<String, ProductDto> products,
                                               Map<String, ProductInfoDto> cartInfos) {
        ProductDto product = requireProduct(products, Long.toString(p.getProductId()));
        int styleIndex = styleIndexOf(p, product);   // 저장된 위치가 아니라 색상으로 찾은 지금 위치(loadCart 의 infoKey 와 같다)

        // 배치 응답에서 이 항목의 (상품, 스타일, 사이즈) 정보. 없으면 품절·삭제·잘못된 인덱스다 —
        // 단건 API 가 null 을 돌려주던 경우와 같다.
        String key = CartProductInfoRequest.Item.key(p.getProductId(), styleIndex, sizeIndexOf(product, styleIndex, p.getSize()));
        ProductInfoDto found = cartInfos.get(key);
        if (found == null) {
            throw new RuntimeException("Cart product info not found: " + key);
        }
        ProductInfoDto dto = new ProductInfoDto(found);

        BigDecimal originalPrice = computeOriginalPriceFromSkusByStyleAndSize(product.getSkus(), styleIndex, p.getSize());
        dto.setPriceBefore(originalPrice.toPlainString());

        BigDecimal price = discountedPrice(originalPrice, product.getSkus().get(styleIndex).getDiscount());
        dto.setPrice(price.toPlainString());

        dto.setQty(p.getQty());
        // 🐛 줄의 식별자는 <b>줄 id</b> 다. 저장된 _uid 는 화면이 "상품명_스타일_사이즈" 로 만든 값이라, 이름이 같은 다른 상품(시드에
        // "Digital Camera" 가 여럿)이 같은 값을 가졌다 — 하나를 고르면 셋이 함께 선택되고, 목록의 React key 가 겹쳤다.
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
     */
    private BigDecimal computeOriginalPriceFromSkusByStyleAndSize(List<ProductSkuDto> skus, int style, String size) {
        return skus.get(style).getSizes().stream()
                .filter(i -> i.getSize().equals(size))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Product size not found")).getPrice();
    }

    /**
     * SKU와 사이즈에 따른 가격을 반환합니다.
     */
    private BigDecimal getPriceFromSkuBySize(ProductSkuDto sku, String size) {
        BigDecimal price = sku.getSizes().stream()
                .filter(p -> p.getSize().equals(size))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Product size not found")).getPrice();

        return discountedPrice(price, sku.getDiscount());
    }

    /**
     * 카트 총액을 계산합니다.
     */
    private BigDecimal computeCartTotal(List<CartProduct> products) {
        // 고른 줄만 — 주문이 되는 것의 총액이다(체크아웃이 고르지 않은 줄을 지우지 않게 된 뒤로, V3).
        return products.stream()
                .filter(CartProduct::isSelected)
                .filter(p -> p.getProductId() != null)
                .map(p -> p.getPrice().multiply(new BigDecimal(p.getQty())).add(p.getShipping()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * CartProduct 엔티티를 생성합니다.
     */
    private CartProduct createCartProduct(ProductInfoDto cartItem, Cart cart, Map<String, ProductDto> products) {
        ProductDto product = products.get(cartItem.getId());

        if (product != null) {
            ProductSkuDto sku = product.getSkus().get(cartItem.getStyle());
            BigDecimal price = getPriceFromSkuBySize(sku, cartItem.getSize());

            CartProduct cartProduct = CartProduct.builder()
                    .name(product.getName())
                    .colorId(sku.getColor() != null && sku.getColor().getId() != null
                            // 색상은 SKU(스타일)가 정한다 — 본문의 색상을 믿지 않는다(가격도 SKU 에서 온다)
                            ? Long.parseLong(sku.getColor().getId())
                            : Long.parseLong(cartItem.getColor().getId()))
                    .style(cartItem.getStyle())
                    .productId(Long.parseLong(product.getId()))
                    .image(sku.getImages().get(0))
                    .qty(cartItem.getQty())
                    .price(price)
                    .size(cartItem.getSize())
                    // SKU 는 catalog 의 상품 정보에서 — 본문(cartItem.skuCode)을 믿지 않는다. 주문 품목의 기록이 되는 값이다.
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
     */
    private CartDto convertCartToDto(Cart cart) {
        // 색상은 장바구니 단위로 한 번 — 항목마다 getColorInfo 를 부르던 것의 대체(docs/38 §1.13).
        Map<String, ProductColorDto> colors = fetchColors(cart.getCartProducts());
        // 주문의 근거(/checkout — order·coupon 이 읽는다)는 고른 줄만. 고르지 않은 줄은 장바구니에 남아 있을 뿐이다.
        List<CartProduct> selected = cart.getCartProducts().stream()
                .filter(CartProduct::isSelected)
                .filter(item -> item.getProductId() != null)   // 주문할 수 없는 옛 줄(dropLinesWithoutProduct)
                .collect(Collectors.toList());
        List<CartProductDto> products = selected.stream()
                .map(item -> mapToCartProductDto(item, colors))
                .collect(Collectors.toList());
        fillMissingSkuCodes(selected, products);

        return CartDto.builder()
                .userId(Long.toString(cart.getUserId()))
                // userImage 는 채우지 않는다 — 읽는 곳이 없고, 채우려면 user-service 를 불러야 한다.
                .products(products)
                .cartTotal(cart.getCartTotal())
                .totalAfterDiscount(cart.getTotalAfterDiscount())
                .build();
    }
    
    /**
     * 카트 상품 수량을 업데이트합니다.
     */
    void updateQty(CartProduct product, int qty) {
        log.info("Cart product rollback id: {} qty: {}", product.getCartproductId(), qty);
        product.setQty(qty);
        cartProductRepository.save(product);
    }
 
}