package com.project.order_service.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.order.OrderAddressDto;
import com.project.common.dto.order.OrderDto;
import com.project.common.dto.order.OrderedProductDto;
import com.project.common.dto.order.request.OrderRequest;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.client.CartServiceClient;
import com.project.order_service.client.UserServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.client.CatalogServiceClient;

import com.project.order_service.mapper.OrderAddressMapper;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderAddress;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;

@Transactional
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderCacheService cahcedOrderService;
    private final com.project.order_service.saga.OrderSaga orderSaga;
    private final com.project.order_service.ports.output.message.publisher.OrderRefundedKafkaPublisher orderRefundedPublisher;
    private final CartServiceClient cartServiceClient;
    /** 관리자 목록의 표시용 이메일만 — 신원 판정에는 쓰지 않는다(그건 User-Id 헤더다). */
    private final UserServiceClient userServiceClient;
    private final OrderPricing orderPricing;
    /** 결제 전 재고 예약(docs/59). */
    private final com.project.order_service.stock.OrderStockHold stockHold;
    private final ObjectMapper objectMapper;

        
    @Transactional(readOnly = true)
    public List<Order> getOrdersByCustomerId(Long customerId) {
         
         return cahcedOrderService.getOrdersByCustomerId(customerId);
    }

    
    
    /**
     * 주문을 만든다.
     *
     * <h3>🔴 원격 호출은 트랜잭션 <b>밖</b>에서 (docs/37 #5)</h3>
     *
     * <p>예전에는 {@code OrderDataService.createOrder} 가 트랜잭션을 연 직후
     * cart-service 를 불렀다. DB 커넥션을 쥔 채 다른 서비스를 기다리는 모양이라
     * <b>주문 생성 지연이 cart-service 지연에 정비례</b>했고, cart-service 가 멈추면
     * order-service 의 DB 풀이 먼저 말랐다.
     *
     * <p>docs/40 #10(금액을 서버가 정한다)을 고치면서 쿠폰 견적 호출이 하나 더 붙어
     * <b>그 창이 두 배가 됐다.</b> Feign 기본값이 연결 3초·읽기 5초이므로 최악의 경우
     * 커넥션 하나를 16초까지 붙들 수 있었다.
     *
     * <p>그래서 두 호출을 여기로 올렸다. 이 메서드는 {@code NOT_SUPPORTED} 라 트랜잭션이
     * 열리지 않는다 — 클래스에 붙은 {@code @Transactional} 을 이 메서드에서만 끄는 것이다.
     * DB 작업은 {@code cahcedOrderService.createOrder} 가 <b>짧게</b> 연다.
     *
     * <p>⚠️ 여기에 DB 작업을 추가하면 트랜잭션 없이 돈다. 저장은 아래 단계에 둘 것.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createOrder(OrderRequest request, Long customerId) {
        return createOrder(request, customerId, null);
    }

    /** 멱등 키로 쓸 수 있는 모양 — 화면은 UUID 를 보낸다. 그 밖은 키가 없는 것으로 본다(오래된 화면 · 지어낸 값). */
    static String usableCreateKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.strip();
        return k.length() >= 8 && k.length() <= 64 && k.matches("[A-Za-z0-9_-]+") ? k : null;
    }

    /**
     * @param createKey 멱등 키(Idempotency-Key 헤더, V26) — 같은 고객 · 같은 키로 이미 만든 주문이 있으면 새로 만들지 않고 그것을
     *                  돌려준다. 🐛 예전에는 없어서 게이트웨이 시간 초과 뒤 다시 보내면 주문이 둘 생겼고 둘 다 재고를 잡았다.
     *                  [JA] 冪等キー — 同じ顧客 · 同じキーで作った注文があれば新しく作らずそれを返す。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createOrder(OrderRequest request, Long customerId, String createKey) {
        String key = usableCreateKey(createKey);
        if (key != null) {
            Order existing = cahcedOrderService.findByCreateKey(customerId, key);
            if (existing != null) {
                log.info("같은 멱등 키의 주문 생성 재요청 — 이미 만든 주문을 돌려준다. orderId={}", existing.getOrderId());
                return existing;
            }
        }

        // ① 장바구니 — 무엇을 주문했는가의 근거다. 본문의 products 는 읽지 않는다.
        CartDto latestCart = objectMapper.convertValue(
                cartServiceClient.getCart(customerId).getBody(), CartDto.class);

        // 🐛 예전에는 여기서 null 을 돌려줬다. 컨트롤러는 그 null 에 대고
        //    newOrder.getOrderId() 를 불렀으므로 NPE 가 나고, 그것이
        //    catch (RuntimeException) 에 걸려 **본문이 JVM 의 NPE 문구인 400** 이 됐다
        //    ("Cannot invoke ... because \"newOrder\" is null"). 사용자는 원인을 알 수
        //    없고 로그에도 아무것도 남지 않았다. 실패는 이유를 달고 던진다.
        if (latestCart == null || latestCart.getProducts() == null || latestCart.getProducts().isEmpty()) {
            log.warn("주문 생성 실패: 장바구니가 비어 있다");
            throw new OrderDomainException("장바구니가 비어 있습니다. 상품을 담은 뒤 다시 시도해 주세요.");
        }
        fillMissingProductIds(latestCart);
        requireOrderableQuantities(latestCart);

        // ② 금액 — 서버가 정한다(docs/40 #10). 쿠폰 견적 Feign 호출이 여기 들어 있다.
        OrderPricing.Amounts amounts =
                orderPricing.priceOrder(latestCart, request.getCouponApplied(), customerId);

        // ③ 주문자는 게이트웨이가 검증한 토큰의 uid(User-Id 헤더)다. 예전에는 이메일로 고객 사본을 찾고, 없으면
        //    user-service 에 물어 사본을 메꿨다(백필) — 이미 아는 답을 얻으려고 복제·이벤트·동기 호출을 두고 있었다.
        //    docs/48 §6-①. 이메일은 장바구니·쿠폰 서비스가 그것을 키로 받으므로 위 두 호출에만 쓴다.

        // ④ 여기서부터 DB. 원격 호출이 끝난 뒤라 트랜잭션이 짧다.
        Order created;
        try {
            created = key == null ? cahcedOrderService.createOrder(request, latestCart, amounts, customerId)
                    : cahcedOrderService.createOrder(request, latestCart, amounts, customerId, key);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 왔다 — 앞선 쪽이 만들었다(부분 유니크, V26). 그 주문을 돌려준다.
            Order existing = key == null ? null : cahcedOrderService.findByCreateKey(customerId, key);
            if (existing == null) {
                throw e;
            }
            log.info("같은 멱등 키의 주문이 동시에 만들어졌다 — 앞선 주문을 돌려준다. orderId={}", existing.getOrderId());
            return existing;
        }

        // ⑤ 재고를 잡는다(docs/59) — 주문 id 가 예약의 키라 저장한 뒤다. 모자라면 방금 만든 주문을 버리고 이유를 돌려준다
        //    (결제 전이라 버릴 수 있다). catalog 에 닿지 못하면 예약 없이 간다 — 결제 뒤 부족분 환불이 받는다.
        //    장바구니는 결제 뒤에야 비워진다 — 주문하고 돌아가 다시 주문하면 앞 주문의 예약이 이 주문을 막는다. 같은 고객의 앞선
        //    결제 전 주문의 예약을 먼저 푼다(입금 대기는 그대로).
        for (Long earlier : cahcedOrderService.supersedeStockHolds(customerId, created.getOrderId(), java.time.LocalDateTime.now())) {
            stockHold.release(earlier);
        }
        com.project.order_service.stock.OrderStockHold.Outcome hold = stockHold.hold(created, stockHold.checkoutTtl());
        if (hold.isShort()) {
            cahcedOrderService.discardUnplaced(created.getOrderId());
            throw new OrderDomainException(shortageMessage(created, hold.shortages()));
        }
        if (hold.isRejected()) {
            // 요청이 잘못됐다 — 재고 검사를 건너 결제로 보내지 않는다(예전에는 "닿지 못함" 과 같이 예약 없이 진행했다).
            cahcedOrderService.discardUnplaced(created.getOrderId());
            throw new OrderDomainException(UNORDERABLE_MESSAGE);
        }
        if (hold.kind() == com.project.order_service.stock.OrderStockHold.Kind.UNAVAILABLE) {
            // 닿지 못했다(기한 초과 포함) — 예약 없이 간다(docs/59). 다만 기한 초과는 catalog 가 뒤늦게 잡았을 수 있다: 새 주문이라
            // 앞선 예약이 없으므로 한 번 풀어 둔다(여러 번 불러도 같다) — 🐛 예전에는 그 유령 예약이 TTL(30분) 동안 남의 구매를 막았다.
            // [JA] 到達できなかった — 予約なしで進める。ただし期限切れは catalog が遅れて押さえた可能性があるので、一度解放しておく。
            stockHold.release(created.getOrderId());
        }
        if (hold.isHeld() && hold.holdUntil() != null) {
            cahcedOrderService.recordStockHold(created.getOrderId(), hold.holdUntil());
            created.setStockHoldUntil(hold.holdUntil());
        }
        return created;
    }

    /** 한 줄의 수량 상한 — 장바구니(CartService.MAX_LINE_QTY)와 같다. */
    static final int MAX_LINE_QTY = 1000;

    static final String UNORDERABLE_MESSAGE = "주문할 수 없는 수량이 있습니다. 장바구니에서 수량(1 ~ 1000)을 조정해 주세요.";

    /**
     * 장바구니의 줄 수량은 1 ~ {@link #MAX_LINE_QTY} — 금액을 정하기 전에 본다.
     *
     * <p>🐛 예전에는 보지 않았다 — 장바구니의 {@code qty=-5} 줄이 그대로 주문 줄이 되어 주문 금액이 다른 줄의 값을 깎았고(가격 ×
     * 음수), 재고 예약의 거절도 "닿지 못함" 으로 읽혀 그대로 결제까지 갔다. 장바구니도 이제 막지만(checkedQty) 옛 장바구니 ·
     * 캐시 사본이 남아 있다.
     *
     * <p>[JA] カートの行数量は 1〜1000 — 金額を決める前に見る。以前は負の数量がそのまま注文行になり金額を減らした。
     */
    static void requireOrderableQuantities(CartDto cart) {
        for (com.project.common.dto.CartProductDto p : cart.getProducts()) {
            if (p == null || p.getQty() < 1 || p.getQty() > MAX_LINE_QTY) {
                throw new OrderDomainException(UNORDERABLE_MESSAGE);
            }
        }
    }

    /**
     * 상품 id 가 비어 있는 장바구니 줄을 채운다. cart-service 는 {@code productId} 를 싣지만, 그 필드가 생기기 전에 Redis 에
     * 들어간 장바구니 사본에는 없다. 비어 있으면 줄 id 로 cart 에 묻는다(트랜잭션 밖 — 이 메서드의 호출자가 그렇다).
     */
    void fillMissingProductIds(CartDto cart) {
        for (com.project.common.dto.CartProductDto p : cart.getProducts()) {
            if (p.getProductId() == null || p.getProductId().isBlank()) {
                p.setProductId(cartServiceClient.getProductId(p.getId()));
            }
        }
    }

    /**
     * 결제창을 열기 <b>직전에</b> 재고 예약을 새로 잡는다 (docs/59 §8).
     *
     * <p>주문을 만들 때 30분을 잡는다. 그 뒤 결제 화면에 오래 머물면 예약이 풀리고, 그새 다른 사람이 사 가면 결제 뒤 부족분
     * 환불로 떨어진다 — 카드면 자동 환불이지만 <b>포트원 가상계좌는 계좌가 PG 결제창 안에서 발급돼</b> 우리가 막을 틈이 없었다
     * (입금되면 "수동 환불 필요"). 그래서 결제창을 여는 순간 다시 잡는다: 모자라면 결제창을 열지 않고 이유를 말한다.
     *
     * <p>결제 전(NOT_PROCESSED) 주문만. 그 밖의 상태·예약 기능이 없거나 catalog 에 닿지 못하면 아무것도 막지 않는다(fail-open,
     * 주문 생성과 같다). 🔒 주문자만 — 아니면 null(404, 남의 주문인지 알리지 않는다).
     *
     * <p>쿠폰이 있으면 이 주문에 잡는다({@link OrderCouponClaim}) — 같은 쿠폰으로 다른 주문이 결제 중이면 던진다(fail-closed).
     *
     * @return {@code stockHoldUntil}(잡았으면) 을 담은 맵. 주문이 없거나 남의 것이면 null
     * @throws OrderDomainException 모자라다 — 문구는 주문 생성 때와 같은 모양("결제를 시작하지 못했습니다")
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public java.util.Map<String, Object> renewCheckoutHold(String trackingId, Long requesterId) {
        return renewCheckoutHold(trackingId, requesterId, null);
    }

    /**
     * @param attempt 화면이 창마다 만든 결제 시도 키(Payment-Attempt 헤더, V27) — 다른 창의 시도가 살아 있으면 던진다. 없으면(옛 화면)
     *                시도는 적지 않는다. 이미 결제가 붙은 주문은 키와 상관없이 던진다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public java.util.Map<String, Object> renewCheckoutHold(String trackingId, Long requesterId, String attempt) {
        Order order = cahcedOrderService.getOrderByTrackingId(trackingId);
        if (order == null || requesterId == null || !requesterId.equals(order.getCustomerId())) {
            return null;
        }
        // 주문 하나에 결제창 하나(V27) — 이미 결제됐거나 다른 창이 결제 중이면 여기서 멈춘다(재고 · 쿠폰을 건드리기 전에).
        cahcedOrderService.startPaymentAttempt(trackingId, usableCreateKey(attempt),
                java.time.Duration.ofMinutes(Math.max(1, paymentAttemptWindowMinutes)));
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (order.getOrderStatus() != com.project.common.constants.OrderStatus.NOT_PROCESSED) {
            body.put("renewed", false);
            return body;
        }
        com.project.order_service.stock.OrderStockHold.Outcome hold = stockHold.hold(order, stockHold.checkoutTtl());
        if (hold.isShort()) {
            throw new OrderDomainException(shortageMessage(order, hold.shortages())
                    .replace("주문하지 못했습니다", "결제를 시작하지 못했습니다"));
        }
        if (hold.isRejected()) {
            throw new OrderDomainException(UNORDERABLE_MESSAGE);
        }
        // 쿠폰을 이 주문에 잡는다(coupon V7) — 같은 쿠폰으로 다른 주문이 결제 중이면 결제창을 열지 않는다. 재고와 달리 fail-closed.
        if (couponClaim != null) {
            couponClaim.claim(order, stockHold.checkoutTtl());
        }
        body.put("renewed", hold.isHeld());
        if (hold.isHeld() && hold.holdUntil() != null) {
            cahcedOrderService.recordStockHold(order.getOrderId(), hold.holdUntil());
            body.put("stockHoldUntil", hold.holdUntil().toString());
        }
        return body;
    }

    /** 결제창 하나의 수명(분, V27) — 이 동안 다른 창은 결제를 시작하지 못한다. 결제창을 닫고 새 탭에서 다시 열면 이만큼 기다린다. */
    @org.springframework.beans.factory.annotation.Value("${order.payment-attempt.window-minutes:10}")
    private long paymentAttemptWindowMinutes = 10;

    /** 결제 시작 때 쿠폰 잡기(coupon V7). 세터로 받는다 — 생성자를 쓰는 곳을 늘리지 않으려고. 없으면(테스트) 건드리지 않는다. */
    private OrderCouponClaim couponClaim;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCouponClaim(OrderCouponClaim couponClaim) {
        this.couponClaim = couponClaim;
    }

    /**
     * 모자란 줄을 사람이 읽을 문장으로 — "티셔츠(M) 남은 수량 1개" 처럼. 상품 이름은 주문에 적힌 것(장바구니에서 온 것)을
     * 쓴다 — catalog 는 id 만 돌려준다.
     */
    static String shortageMessage(Order order, java.util.List<com.project.common.dto.stock.StockReservationResult.Shortage> shortages) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var s : shortages) {
            String name = order.getOrderedProducts().stream()
                    .filter(p -> java.util.Objects.equals(p.getProductId(), s.getProductId())
                            && java.util.Objects.equals(p.getColorId(), s.getColorId())
                            && java.util.Objects.equals(p.getSize(), s.getSize()))
                    .map(com.project.order_service.model.OrderProduct::getName)
                    .findFirst().orElse("상품");
            parts.add(s.getAvailable() <= 0
                    ? name + "(" + s.getSize() + ") 품절"
                    : name + "(" + s.getSize() + ") 남은 수량 " + s.getAvailable() + "개");
        }
        return "재고가 부족해 주문하지 못했습니다: " + String.join(", ", parts) + ". 장바구니에서 수량을 조정해 주세요.";
    }


    // @Cacheable: orderId와 email을 키로 사용하여 주문 상세 정보를 캐시합니다.    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만
    public OrderDto getOrder(Long orderId, Long requesterId, String filter) {
        log.debug("Fetching order details for order ID: {}, filter: {}", orderId, filter);
        return cahcedOrderService.getOrder(orderId, requesterId, filter);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만
    public List<OrderDto> getOrders(Long customerId, String filter) {
       
        return cahcedOrderService.getOrders(customerId, filter);
    }

    /**
     * 주문 목록 — 상태로 거른 커서 페이징(점진적 로딩).
     *
     * <p>{@link #getOrders} 의 세 가지 문제(무제한 · N+1 · 거른 결과에 null 이 섞임)를
     * 없앤 경로다. 자세한 설명은 {@code OrderDataService.getOrdersPage} 주석에 있다.
     *
     * <p><b>캐시하지 않는다.</b> 커서와 필터마다 키가 갈려 적중률이 낮고, 주문 목록은
     * 방금 한 주문이 보여야 하는 화면이다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만
    public Page<OrderDto> getOrdersPage(Long customerId, String filter, Long cursor, int size) {
        return cahcedOrderService.getOrdersPage(customerId, filter, cursor, size);
    }

     // 페이지별로 주문 목록을 조회하는 새로운 메서드    
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만
    public Page<OrderDto> getOrdersByCustomerIdWithPagination(Long customerId, int page, int size) {
        
        return cahcedOrderService.getOrdersByCustomerIdWithPagination(customerId, page, size);
    }

    // --- 점진적 로딩(커서 기반 페이징) 메서드 추가 ---
    /**
     * 고객 ID로 주문 목록을 커서 기반으로 조회합니다 (점진적 로딩).
     * @param customerId 조회할 고객의 ID
     * @param cursorId 마지막으로 조회한 주문의 ID (첫 페이지 조회 시 null)
     * @param size 페이지당 주문 개수
     * @return OrderDto 목록과 다음 커서 ID
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만
    public Page<OrderDto> getOrdersByCustomerIdWithCursor(Long customerId, Long cursorId, int size) {
        
        return cahcedOrderService.getOrdersByCustomerIdWithCursor(customerId, cursorId, size);
    }


   
    // 결제 완료 처리 메서드    
    /**
     * 확인된 결제를 주문에 반영한다 — PayPal · Stripe 공통.
     *
     * <p>제공자별 조회는 {@code PaymentGateway} 뒤에 있고, 여기 오는 것은 "어느 제공자의
     * 어떤 참조" 두 값뿐이다.
     */
    public void recordPayment(String trackingId, PaymentProvider provider, String providerPaymentId,
            Long requesterId) {
        cahcedOrderService.recordPayment(trackingId, provider, providerPaymentId, requesterId);
    }

    /**
     * 결제 취소. 결제와 <b>다른 연산</b>이므로 다른 메서드다 — 왜 그런지는
     * {@code OrderDataService.cancelPaypalPayment} 주석에 있다.
     */
    public void cancelPaypalPayment(PaypalPaymentRequest request, Long requesterId) {
        cahcedOrderService.cancelPaypalPayment(request, requesterId);
        // 결제를 접었다 — 잡아 둔 재고를 푼다(docs/59). 취소가 거절됐으면(결제된 주문 등) 상태가 CANCELED 가 아니라 풀지 않는다.
        Order after = cahcedOrderService.getOrderByTrackingId(request.getTrackingId());
        if (after != null && after.getOrderStatus() == com.project.common.constants.OrderStatus.CANCELED) {
            stockHold.releaseAfterCommit(after.getOrderId());
            // 결제 시작 때 이 주문에 잡은 쿠폰을 푼다(coupon V7 — coupon-rollback 이 이 주문의 표시만 지운다). 예전에는 보상이 없어도
            // 됐다(쿠폰은 결제 확정 때만 쓰였다). 이제는 없으면 PayPal 창을 닫고 다시 주문할 때 30분 동안 같은 쿠폰이 막힌다.
            orderSaga.compensateCancel(after);
        }
    }

    /**
     * 환불.
     *
     * <p>규칙과 제공자 호출은 {@code OrderDataService.refundOrder}(자기 트랜잭션) 가 한다.
     * 여기서는 그 뒤에 <b>보상</b>을 발행한다 — 쿠폰 복원. 트랜잭션 밖에서 하는 이유는
     * createOrder 와 같다(NOT_SUPPORTED 주석): 카프카 발행을 DB 트랜잭션 안에 두면
     * 커밋이 실패해도 메시지는 이미 나가 있다. 환불 자체는 아웃박스로 나가므로 안전하고,
     * 쿠폰 복원은 {@code OrderSaga} 의 다른 보상과 같은 등급(최선 노력)이다.
     */
    /**
     * 관리자 주문 목록. 권한은 컨트롤러가 이미 확인했다.
     *
     * <p>목록은 트랜잭션 안에서 만들고({@code OrderDataService}), 표시용 고객 이메일은 <b>그 밖에서</b> user-service 에
     * 한 번 물어 붙인다 — 예전에는 고객 사본(orders.customer)에서 읽었다(docs/48 §6-①). 트랜잭션 밖이어야 하는 이유는
     * createOrder 와 같다(원격 호출 동안 DB 커넥션을 쥐지 않는다, docs/37 #5).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Page<com.project.common.dto.order.AdminOrderSummaryDto> listOrdersForAdmin(
            String status, String trackingId, int page, int size) {
        Page<com.project.common.dto.order.AdminOrderSummaryDto> result =
                cahcedOrderService.listOrdersForAdmin(status, trackingId, page, size);
        attachCustomerEmails(userServiceClient, result.getContent());
        return result;
    }

    /** 고객 id 로 이메일을 한 번에 받아 붙인다. 실패하면 이메일 없이 둔다 — 관리자 목록이 user-service 때문에 멈추면 안 된다. */
    static void attachCustomerEmails(UserServiceClient users, List<com.project.common.dto.order.AdminOrderSummaryDto> rows) {
        List<Long> ids = rows.stream().map(com.project.common.dto.order.AdminOrderSummaryDto::getCustomerId)
                .filter(java.util.Objects::nonNull).map(Long::valueOf).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<String, String> emails;
        try {
            emails = users.findEmailsByIds(ids);
        } catch (RuntimeException e) {
            log.warn("관리자 주문 목록: 고객 이메일을 가져오지 못했다 — 이메일 없이 보여 준다. {}", e.toString());
            return;
        }
        if (emails == null) {
            return;
        }
        rows.forEach(r -> r.setCustomerEmail(r.getCustomerId() == null ? null : emails.get(r.getCustomerId())));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order refundOrder(String trackingId, Long requesterId, boolean admin, String reason,
            List<com.project.common.dto.request.OrderItemQtyRequest> items) {
        Order refunded = cahcedOrderService.refundOrder(trackingId, requesterId, admin, reason, items);
        orderSaga.compensateRefund(refunded);
        // 손님에게 "환불됐다" 를 알린다 — 부분 환불은 이것밖에 없다(OrderRefundedKafkaPublisher 주석).
        orderRefundedPublisher.publishLatest(refunded);
        return refunded;
    }

    /**
     * 반품 입고 (관리자). 기록은 {@code OrderDataService.receiveReturn}(자기 트랜잭션), 재고
     * 복원 이벤트는 그 뒤 트랜잭션 밖에서 — 환불과 같은 모양이다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order receiveReturn(String trackingId, boolean restock,
            List<com.project.common.dto.request.OrderItemQtyRequest> items) {
        Order received = cahcedOrderService.receiveReturn(trackingId, restock, items);
        if (restock) {
            orderSaga.restock(received);
        }
        return received;
    }

    /**
     * 주문 묶음에 나오는 색상을 catalog 에서 <b>한 번에</b> 가져온다.
     *
     * <p>예전에는 {@link #convertToOrderDto} 가 주문 상품마다 {@code getColorInfo} 를 불렀다 —
     * 주문 목록 한 페이지(주문 10건 × 상품 3개)가 Feign 왕복 30번. 페이지 단위로 색상 id 를
     * 모아 한 번에 받고, 변환은 그 맵을 받는다(docs/38 §1.13). 못 찾은 색상은 맵에 없고,
     * 그 상품의 color 는 예전처럼 null 이다.
     */
    static public Map<String, ProductColorDto> fetchColors(Collection<Order> orders, CatalogServiceClient catalogServiceClient) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Order order : orders) {
            for (OrderProduct product : order.getOrderedProducts()) {
                if (product.getColorId() != null) {
                    ids.add(product.getColorId());
                }
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        // 색상은 꾸밈이다 — catalog 가 흔들리면(재배포·일시 장애) 색상 없이 주문을 보여 준다. 예전에는 이 예외가 주문 조회를
        // 통째로 400 으로 만들었고, 컨트롤러가 로그 없이 삼켜 흔적도 없었다.
        List<ProductColorDto> found;
        try {
            found = catalogServiceClient.getColorsInfo(new ArrayList<>(ids)).getBody();
        } catch (RuntimeException e) {
            log.warn("주문 색상 조회 실패 — 색상 없이 보여 준다. colors={} ({})", ids.size(), e.getClass().getSimpleName());
            return Map.of();
        }
        if (found == null) {
            return Map.of();
        }
        Map<String, ProductColorDto> byId = new HashMap<>();
        for (ProductColorDto color : found) {
            byId.put(color.getId(), color);
        }
        return byId;
    }

    /** 주문 하나 — 색상 배치를 이 주문 하나에 대해 받는다. 목록은 페이지 단위로 {@link #fetchColors} 를 먼저 부를 것. */
    static public OrderDto convertToOrderDto(Order data, ObjectMapper objectMapper, CatalogServiceClient catalogServiceClient, OrderAddressRepository orderAddressRepository ) {
        return convertToOrderDto(data, fetchColors(List.of(data), catalogServiceClient));
    }

       // 도메인 엔티티를 DTO로 변환하는 공통 메서드
    static public OrderDto convertToOrderDto(Order data, Map<String, ProductColorDto> colors) {
        List<OrderedProductDto> dtos = data.getOrderedProducts().stream()
                .map(product -> {
                    ProductColorDto color = product.getColorId() == null ? null
                            : colors.get(Long.toString(product.getColorId()));
                    return OrderedProductDto.builder()
                            .color(color)
                            .id(Long.toString(product.getOrderProductId()))
                            .image(product.getImage())
                            .name(product.getName())
                            // 🐛 size 는 DTO 에 있었지만 채운 적이 없다 — 주문 내역에 사이즈가 비어 보였다.
                            .size(product.getSize())
                            .skuCode(product.getSkuCode())
                            .price(product.getPrice())
                            .qty(product.getQty())
                            .refundedQty(product.getRefundedQty())
                            .returnedQty(product.getReturnedQty())
                            .build();
                })
                .collect(Collectors.toList());

        OrderAddressDto address = OrderAddressMapper.toDto(data.getShippingAddress());

        return OrderDto.builder()
                .id(Long.toString(data.getOrderId()))
                .trackingId(data.getTrackingId())
                .couponApplied(data.getCouponApplied())
                .deliveredCreatedAt(data.getDeliveredCreatedAt())
                .completedAt(data.getCompletedAt())
                .autoCompleted(data.isAutoCompleted())
                .orderStatus(data.getOrderStatus().name())
                .paymentMethod(data.getPaymentMethod())
                .paymentStatus(PaymentStatus.COMPLETED.getStatus())
                .products(dtos)
                .shippingAddress(address)
                .totalBeforeDiscount(data.getTotalBeforeDiscount())
                // 🐛 shippingPrice 는 DTO 에 필드가 있는데 **옮기는 줄이 없었다.** 화면은
                //    언제나 0 을 받았다 — 엔티티 쪽도 채워진 적이 없어(항상 0) 눈에 띄지
                //    않았다. 두 구멍이 서로를 가려 주고 있었다.
                .shippingPrice(data.getShippingPrice())
                .total(data.getTotal())
                // 청구 통화·금액 — 결제 화면이 제공자에게 넘길 값이다(total 은 상점 통화).
                .currency(data.getCurrency())
                .chargedAmount(data.getChargedAmount())
                .refundedAmount(data.getRefundedAmount())
                .depositDeadline(data.getDepositDeadline())
                .stockHoldUntil(data.getStockHoldUntil())
                .depositorName(data.getDepositorName())
                .awaitingReturn(data.isAwaitingReturn())
                .returnReceivedAt(data.getReturnReceivedAt())
                .build();
    }
    
}