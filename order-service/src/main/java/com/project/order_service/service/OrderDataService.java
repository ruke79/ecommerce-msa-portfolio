package com.project.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.dto.CartDto;
import com.project.common.dto.order.OrderAddressDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.order.OrderDto;
import com.project.common.dto.order.request.OrderRequest;
import com.project.common.dto.request.OrderItemQtyRequest;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.mapper.OrderAddressMapper;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderAddress;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.model.OrderRefund;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.payment.PaymentCurrencyPolicy;
import com.project.order_service.payment.PaymentGateways;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.VerifiedPayment;
import com.project.order_service.payment.PaypalVerificationException;
import com.project.order_service.payment.VerifiedPaypalOrder;
import com.project.order_service.repository.jpa.OrderRepository;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDataService {

    private final OrderRepository orderRepository;
    private final CatalogServiceClient catalogServiceClient;
    // 🔒 cartServiceClient · orderPricing 을 여기서 **뺐다**(docs/37 #5).
    //    이 클래스의 메서드는 전부 트랜잭션 안에서 도므로, 원격 호출 클라이언트를 손에
    //    쥐고 있으면 누군가 "한 줄이면 되는데" 하고 다시 부른다. 필드가 없으면 부를 수가
    //    없다 — 주석보다 확실하다. 원격 호출은 OrderService.createOrder 가 한다.
    private final ObjectMapper objectMapper;
    private final OrderAddressRepository orderAddressRepository;
    private final PaymentOutboxHelper paymentOutboxHelper;
    /** 제공자별 결제 확인. 규칙은 이 클래스가 갖고, 조회만 게이트웨이에 맡긴다. */
    private final PaymentGateways paymentGateways;

    /**
     * 결제수단별 청구 통화와 환율. 예전에는 {@code paypal.currency} 하나가 "모든 결제가
     * 대조될 통화" 였다 — 포트원(KRW)이 USD 상점에 붙을 수 없던 이유다.
     */
    private final PaymentCurrencyPolicy currencyPolicy;

    /** 상태 변경 + 이벤트(docs/76). 세터 — 생성자를 쓰는 테스트가 많다. 기본값은 이벤트 없이 상태만 바꾼다(단위 테스트). */
    // [JA] 状態変更 + イベント(docs/76)。セッター — コンストラクタを使うテストが多い。デフォルトはイベントなしで状態だけ変える。
    private OrderStatusChanger statusChanger = OrderStatusChanger.withoutEvents();

    @org.springframework.beans.factory.annotation.Autowired
    public void setStatusChanger(OrderStatusChanger statusChanger) {
        this.statusChanger = statusChanger;
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByCustomerId(Long customerId) {
        log.debug("Data Service: Fetching orders for customer ID: {}", customerId);
        return orderRepository.findByCustomerId(customerId).orElse(null);
    }
    
    @Transactional(readOnly = true)
    /**
     * 주문 상세를 읽는다 — <b>요청자 소유의 주문만</b>.
     *
     * <p>🔒 예전에는 {@code email} 인자를 받아 놓고 <b>쓰지 않았다.</b> 그래서
     * {@code findById(orderId)} 가 주문 번호만으로 아무 주문이나 돌려줬고, 컨트롤러에도
     * 소유권 검사가 없어 주문 번호를 1부터 훑는 것만으로 전 고객의 주문·주소·연락처를
     * 읽을 수 있었다(IDOR).
     *
     * <p>이제 주문의 {@code customerId} 와 요청자의 customerId 를 대조한다. 어긋나면
     * {@code null} — 컨트롤러는 이것을 404 로 바꾼다. 403 이 아니라 404 인 이유는, 403 은
     * "그 주문은 존재한다"는 사실을 알려줘 번호를 훑는 것 자체를 막지 못하기 때문이다.
     */
    public OrderDto getOrder(Long orderId, Long requesterId, String filter) {
        log.debug("Data Service: Fetching order details for order ID: {}", orderId);
        Order data = filter.isEmpty()
                ? orderRepository.findById(orderId).orElse(null)
                : orderRepository.findByOrderIdAndOrderStatus(orderId, OrderStatus.getStatus(filter)).orElse(null);

        if (data == null) {
            return null;
        }

        if (requesterId == null || !requesterId.equals(data.getCustomerId())) {
            log.warn("다른 고객의 주문 조회 시도를 차단: orderId={}", orderId);
            return null;
        }

        return OrderService.convertToOrderDto(data, objectMapper, catalogServiceClient, orderAddressRepository);
    }

    @Transactional(readOnly = true)    
    public List<OrderDto> getOrders(Long customerId, String filter) {
        List<Order> orders = getOrdersByCustomerId(customerId);

        if (orders != null && !orders.isEmpty()) {
            return orders.stream()
                    .map(o -> getOrder(o.getOrderId(), customerId, filter))
                    .collect(Collectors.toList());
        }
        return null;
    }

    /**
     * 주문 목록 — 상태로 거른 커서 페이징.
     *
     * <p>🐛 <b>기존 {@link #getOrders} 가 하던 일이 세 가지 면에서 나빴다.</b>
     *
     * <ol>
     *   <li><b>무제한이다.</b> 고객의 주문을 전부 가져온다. 주문이 쌓이는 만큼 응답이 커진다.</li>
     *   <li><b>N+1 이다.</b> 전부 가져온 뒤 주문마다 {@code getOrder(orderId, email, filter)}
     *       를 다시 부른다. 주문 하나마다 DB 조회 + <b>user-service Feign 호출</b>
     *       (이메일 → 고객 id) + catalog 호출이 일어난다. 주문 50건이면
     *       서비스 간 왕복이 100번을 넘는다.</li>
     *   <li><b>거른 결과에 null 이 섞인다.</b> 상태가 맞지 않으면 {@code getOrder} 가
     *       {@code null} 을 돌려주는데 그것을 그대로 {@code collect} 한다. "Paid" 를 골라도
     *       행 수는 그대로이고 대부분이 빈 줄이다 — 화면이 {@code order?.id} 로 물음표를
     *       붙여 가며 그리던 이유다.</li>
     * </ol>
     *
     * <p>세 가지를 한 번에 없앤다: 상태는 SQL 에서 거르고, 신원 조회는 <b>한 번만</b> 하고,
     * 색상은 페이지 단위로 <b>한 번에</b> 받는다.
     *
     * @param cursor 마지막으로 받은 주문 id. 첫 페이지는 null 또는 0.
     * @param filter 비어 있으면 상태로 거르지 않는다(화면의 "All Orders").
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersPage(Long customerId, String filter, Long cursor, int size) {
        if (customerId == null) {
            return Page.empty();
        }

        int safeSize = Math.max(1, Math.min(size, MAX_ORDER_PAGE_SIZE));
        PageRequest pageRequest = PageRequest.of(0, safeSize, Sort.by("orderId").descending());

        boolean first = (cursor == null || cursor <= 0);
        boolean noFilter = (filter == null || filter.isBlank());

        Page<Order> page;
        if (noFilter) {
            page = first
                    ? orderRepository.findByCustomerIdOrderByOrderIdDesc(customerId, pageRequest)
                    : orderRepository.findByCustomerIdAndOrderIdLessThanOrderByOrderIdDesc(
                            customerId, cursor, pageRequest);
        } else {
            // 모르는 상태 문자열이면 OrderStatus.getStatus 가 IllegalStateException 을 던진다.
            // 컨트롤러가 400 으로 바꾼다 — 목록 조회가 500 으로 죽을 일은 아니다.
            OrderStatus status = OrderStatus.getStatus(filter);
            page = first
                    ? orderRepository.findByCustomerIdAndOrderStatusOrderByOrderIdDesc(
                            customerId, status, pageRequest)
                    : orderRepository.findByCustomerIdAndOrderStatusAndOrderIdLessThanOrderByOrderIdDesc(
                            customerId, status, cursor, pageRequest);
        }

        // 색상은 페이지 단위로 한 번 — 주문마다 부르면 페이지당 (주문 × 상품) 번이다.
        Map<String, ProductColorDto> colors =
                OrderService.fetchColors(page.getContent(), catalogServiceClient);

        return page.map(order -> OrderService.convertToOrderDto(order, colors));
    }

    /** 주문 목록 한 페이지의 상한. */
    public static final int MAX_ORDER_PAGE_SIZE = 50;

    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByCustomerIdWithPagination(Long customerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Order> ordersPage = orderRepository.findByCustomerId(customerId, pageRequest);
        // 색상은 페이지 단위로 한 번 — 주문마다 부르면 페이지당 (주문 × 상품) 번이다.
        Map<String, ProductColorDto> colors = OrderService.fetchColors(ordersPage.getContent(), catalogServiceClient);
        return ordersPage.map(order -> OrderService.convertToOrderDto(order, colors));
    }
    
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByCustomerIdWithCursor(Long customerId, Long cursorId, int size) {
        PageRequest pageRequest = PageRequest.of(0, size, Sort.by("orderId").descending());
        Page<Order> ordersPage;

        if (cursorId == null || cursorId == 0) {
            ordersPage = orderRepository.findByCustomerIdOrderByOrderIdDesc(customerId, pageRequest);
        } else {
            ordersPage = orderRepository.findByCustomerIdAndOrderIdLessThanOrderByOrderIdDesc(customerId, cursorId, pageRequest);
        }
        Map<String, ProductColorDto> colors = OrderService.fetchColors(ordersPage.getContent(), catalogServiceClient);
        return ordersPage.map(order -> OrderService.convertToOrderDto(order, colors));
    }

    /**
     * 주문을 저장한다 — <b>DB 작업만</b>.
     *
     * <h3>🔴 원격 호출이 여기 있으면 안 된다 (docs/37 #5)</h3>
     *
     * <p>예전에는 이 메서드가 트랜잭션을 연 직후 {@code cartServiceClient.getCart} 를
     * 불렀다. DB 커넥션을 쥔 채로 다른 서비스를 기다리는 모양이라 <b>주문 생성 지연이
     * cart-service 지연에 정비례</b>했고, cart-service 가 멈추면 order-service 의 DB 풀이
     * 먼저 말랐다.
     *
     * <p>docs/40 #10 을 고치면서 쿠폰 견적 호출이 하나 더 붙어 <b>그 창이 두 배가 됐다</b>.
     * Feign 기본 타임아웃이 연결 3초·읽기 5초이므로 최악의 경우 커넥션 하나를 16초까지
     * 붙들 수 있었다.
     *
     * <p>그래서 원격 호출을 전부 <b>트랜잭션 밖</b>({@code OrderService.createOrder})으로
     * 옮기고, 이 메서드는 이미 받아 온 값만 쓴다. 인자가 늘어난 것은 그 대가다 —
     * "이 메서드는 원격 호출을 하지 않는다" 가 시그니처에 드러나는 편이 낫다.
     *
     * @param latestCart 서버가 읽은 장바구니. 주문 품목과 금액의 근거다
     * @param amounts    서버가 정한 금액(docs/40 #10)
     */
    /**
     * 장바구니 줄 하나 → 주문 품목. 이름 · 가격 · 사이즈 · SKU 는 <b>주문 시점의 값을 복사</b>한다 — 뒤에 상품이 바뀌어도 이 주문에서
     * 나간 것은 바뀌지 않는다. SKU(catalog V17)는 식별자가 아니라 기록이다: 재고 이동은 (상품, 색상, 사이즈 이름)으로 한다.
     */
    static OrderProduct toOrderProduct(CartProductDto p, Order order) {
        return OrderProduct.builder()
                .name(p.getName())
                .colorId(Long.parseLong(p.getColor().getId()))
                .image(p.getImage())
                .price(p.getPrice())
                .qty(p.getQty())
                .size(p.getSize())
                .skuCode(p.getSkuCode())
                // 🐛 예전에는 p.getId() 였다 — 그것은 **장바구니 줄 id**(TSID)다. 상품 id 가 아니다. 그래서 주문 품목의
                //    product_id 는 어느 상품도 가리키지 않았고, 그 값으로 하는 일이 전부 헛돌았다: 재고 예약 "품절",
                //    결제 뒤 차감 "상품 없음 → 전량 부족 → 자동 환불", 장바구니 비우기 불일치, 리뷰 "구매하지 않음".
                //    OrderService.createOrder 가 비어 있는 값을 미리 채운다(옛 캐시).
                .productId(requireProductId(p))
                .order(order)
                .build();
    }

    /** 장바구니 줄이 가리키는 상품 id. 없으면 주문을 만들지 않는다 — 틀린 id 로 만든 주문은 재고·환불·리뷰를 전부 어긋낸다. */
    static Long requireProductId(CartProductDto p) {
        String id = p.getProductId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("장바구니 줄에 상품 id 가 없다: cartProductId=" + p.getId());
        }
        return Long.parseLong(id.trim());
    }

    @Transactional
    public Order createOrder(OrderRequest request, CartDto latestCart, OrderPricing.Amounts amounts,
            Long customerId) {

        // 🐛 예전에는 이 세 갈래가 전부 `return null` 이었다. 컨트롤러는 그 null 에 대고
        //    newOrder.getOrderId() 를 불렀으므로 NPE → catch (RuntimeException) → **본문이
        //    JVM 의 NPE 문구인 400**. 세 가지 다른 원인이 하나의 알 수 없는 400 으로
        //    뭉개졌고, 원인별 로그도 남지 않았다(아래 warn 은 있었지만 NPE 가 그 뒤를
        //    덮었다). 이유를 달고 던진다.
        if (latestCart == null || latestCart.getProducts() == null || latestCart.getProducts().isEmpty()) {
            log.warn("주문 생성 실패: 장바구니가 비어 있다");
            throw new OrderDomainException("장바구니가 비어 있습니다. 상품을 담은 뒤 다시 시도해 주세요.");
        }

        // 여기 오기 전에 OrderService.resolveCustomerId 가 채워 준다(없으면 백필). 그래도
        // 남는 방어선이다 — 이 값이 null 이면 아래 save 가 제약 위반으로 죽는다.
        if (customerId == null) {
            log.warn("주문 생성 실패: 주문자 id 가 없다");
            throw new OrderDomainException("주문자 정보를 찾지 못했습니다. 다시 로그인해 주세요.");
        }

        // 주소가 없으면 아래 아홉 줄이 전부 NPE 다. 같은 이유로 먼저 막는다.
        OrderAddressDto shipTo = request.getShippingAddress();
        if (shipTo == null) {
            log.warn("주문 생성 실패: 배송지가 없다");
            throw new OrderDomainException("배송지를 선택해 주세요.");
        }

        // 청구 통화·금액을 **지금** 정한다. 결제수단은 체크아웃에서 골랐고(paymentMethod)
        // 그 제공자의 통화가 상점 통화와 다르면 환율로 바꾼다. 결제 확인·환불은 이 값과
        // 대조하므로, 이 뒤에 환율이 바뀌어도 이 주문에는 영향이 없다.
        PaymentCurrencyPolicy.Charge charge =
                currencyPolicy.quote(amounts.total(), PaymentProvider.from(request.getPaymentMethod()));

        Order order = Order.builder()
                    .trackingId(UUID.randomUUID().toString())
                    .paymentMethod(request.getPaymentMethod())
                    .currency(charge.currency())
                    .chargedAmount(charge.amount())
                    .fxRate(charge.rate())
                    .couponApplied(amounts.couponApplied())
                    .total(amounts.total())
                    .totalBeforeDiscount(amounts.totalBeforeDiscount())
                    // 🐛 이 컬럼은 선언만 돼 있고 한 번도 채워진 적이 없었다(항상 0).
                    //    장바구니가 배송비를 총액에 합산해 버려 채울 값이 없었다.
                    .shippingPrice(amounts.shippingPrice())
                    .orderStatus(OrderStatus.NOT_PROCESSED)
                    .customerId(customerId)
                    .build();

            List<OrderProduct> ordered = new ArrayList<>();

                for (CartProductDto p : latestCart.getProducts()) { // 최신 장바구니 데이터의 products 사용
            ordered.add(toOrderProduct(p, order));
        }

            order.setOrderedProducts(ordered);

            // 주소를 먼저 저장하고 주문이 가리킨다 — INSERT 두 번. 예전에는 주문 → 주소 → 주문 UPDATE 세 번이었다(주소도 주문을
            // 가리키는 외래 키가 있어서, V25).
            OrderAddress address = OrderAddress.builder()
                    .firstname(shipTo.getFirstname())
                    .lastname(shipTo.getLastname())
                    .address1(shipTo.getAddress1())
                    .address2(shipTo.getAddress2())
                    .city(shipTo.getCity())
                    .state(shipTo.getState())
                    .zipCode(shipTo.getZipCode())
                    .country(shipTo.getCountry())
                    .phoneNumber(shipTo.getPhoneNumber())
                    .build();
            orderAddressRepository.save(address);
            order.setShippingAddress(address);
            Order result = orderRepository.save(order);
            // 타임라인의 첫 항목(docs/76) — 저장 뒤여야 주문 id 가 있다.
            statusChanger.created(result);

            log.info("주문 저장 완료: orderId={}", result.getOrderId());
            return result;
    }


    /**
     * 재고가 모자라 성립하지 않은 주문을 지운다(docs/59). 결제 전·결제 참조 없음이 조건이라, 잘못 불러도 결제된 주문은
     * 지워지지 않는다(0행). 주문과 주소가 서로를 가리켜 순서대로 지운다.
     *
     * @return 지웠으면 true
     */
    @Transactional
    public boolean discardUnplaced(Long orderId) {
        Long addressId = orderRepository.findAddressIdOfUnplaced(orderId);
        orderRepository.detachAddressOfUnplaced(orderId);
        if (addressId != null) {
            orderRepository.deleteDetachedAddress(addressId);
        }
        orderRepository.deleteProductsOfUnplaced(orderId);
        boolean deleted = orderRepository.deleteUnplaced(orderId) == 1;
        log.info("재고 부족으로 주문을 버렸다: orderId={} deleted={}", orderId, deleted);
        return deleted;
    }

    /** 재고 예약의 만료 시각을 적는다(docs/59) — 결제 화면이 안내한다. */
    @Transactional
    public void recordStockHold(Long orderId, LocalDateTime holdUntil) {
        orderRepository.findById(orderId).ifPresent(o -> o.setStockHoldUntil(holdUntil));
    }

    /**
     * 같은 고객의 앞선 결제 전 주문들이 잡은 재고를 새 주문에 넘긴다(docs/59 §4) — 그 주문들의 보관 시각을 지금으로 당기고 id 를
     * 돌려준다. catalog 의 예약은 부르는 쪽이 푼다(원격 호출을 트랜잭션 안에 두지 않는다). 앞 주문은 그대로 남는다 — 결제할 수는
     * 있지만 결제 화면이 "재고가 보장되지 않습니다" 라고 말한다.
     */
    @Transactional
    public List<Long> supersedeStockHolds(Long customerId, Long keepOrderId, LocalDateTime now) {
        if (customerId == null || keepOrderId == null) {
            return List.of();
        }
        List<Long> ids = orderRepository.findHeldUnplacedOrderIds(customerId, keepOrderId, now, OrderStatus.NOT_PROCESSED);
        if (!ids.isEmpty()) {
            orderRepository.findAllById(ids).forEach(o -> o.setStockHoldUntil(now));
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Order getOrderByTrackingId(String trackingId) {
         log.debug("Fetching order by tracking ID: {}", trackingId);
         return orderRepository.findByTrackingId(trackingId).orElseGet(() -> {
            log.debug("Order with tracking ID {} not found.", trackingId);
            return null;
        });
    }

   
    /**
     * PayPal 결제를 주문에 반영한다.
     *
     * <p>⚠️ 예전에는 <b>요청 본문을 그대로 믿었다.</b> 금액도, 결제 상태도 브라우저가 정했고
     * 서버는 아무것도 확인하지 않았다. 즉 아래 한 줄로 결제 없이 주문을 완료시킬 수 있었다:
     *
     * <pre>
     *   curl -X POST .../api/order/payment/paypal -H 'Authorization: ...' \
     *     -d '{"trackingId":"...","paypalOrderId":"아무거나","amounts":0.01,
     *          "paymentStatus":"COMPLETED","orderStatus":"NOT_PROCESSED"}'
     * </pre>
     *
     * 이제 네 가지를 서버가 정한다(docs/35):
     * <ol>
     *   <li>PayPal 에 직접 조회해 <b>정말 캡처됐는지</b> 확인한다</li>
     *   <li>금액을 <b>DB 의 주문 총액</b>과 대조한다 — 요청 본문의 amounts 는 쓰지 않는다</li>
     *   <li>통화를 대조한다 — 금액만 보면 59.98 USD 를 59.98 KRW 로 바꿔치기할 수 있다</li>
     *   <li>그 PayPal 주문이 <b>이미 다른 주문에 쓰였는지</b> DB 유니크 제약으로 막는다</li>
     * </ol>
     */
    /**
     * 결제를 <b>취소</b>한다 — PayPal 창에서 사용자가 그냥 닫은 경우.
     *
     * <h3>🐛 취소가 결제와 같은 엔드포인트로 갔다</h3>
     *
     * <p>프론트의 {@code onApproveHandler} 와 {@code onCancelHandler} 가 <b>둘 다</b>
     * {@code POST /api/order/payment/paypal} 을 불렀고, 구분은 본문의
     * {@code paymentStatus: "CANCELED"} 한 필드뿐이었다.
     *
     * <p>그런데 서버는 그 필드를 <b>일부러 읽지 않는다</b> — 브라우저가 결제 상태를 정하게
     * 두는 것이 docs/35 결함의 출발점이었기 때문이다. 그래서 취소 요청도 결제로 취급돼
     * PayPal 캡처 검증을 통과해야 했고, 승인조차 하지 않은 주문은 당연히 {@code CREATED}
     * 라서 이렇게 끝났다:
     *
     * <pre>
     * 400 {"message":"Payment transaction failed: PayPal 결제가 완료되지 않았습니다:
     *      status=CREATED, paypalOrderId=..."}
     * </pre>
     *
     * <p><b>두 수정이 서로 부딪힌 자리다.</b> "본문의 결제 상태를 믿지 않는다" 는 옳고,
     * "취소를 본문의 결제 상태로 알린다" 도 그 전까지는 동작했다. 둘을 같은 엔드포인트에
     * 두었기 때문에 한쪽을 고치자 다른 쪽이 조용히 깨졌다. 취소는 <b>다른 연산</b>이므로
     * 다른 문을 쓴다.
     *
     * <h3>취소의 규칙은 결제와 다르다</h3>
     *
     * <ul>
     *   <li><b>PayPal 캡처를 요구하지 않는다.</b> 취소는 캡처가 <b>없었다</b>는 뜻이다.</li>
     *   <li><b>결제 아웃박스에 쓰지 않는다.</b> 돈이 오가지 않았으므로 알릴 결제가 없다.</li>
     *   <li>🔒 <b>이미 결제된 주문은 취소하지 않는다.</b> 이게 없으면 결제 직후 늦게
     *       도착한 취소 한 번으로 <b>돈은 받았는데 주문은 취소됨</b>이 된다. 환불은
     *       별도 절차이지 이 경로가 할 일이 아니다.</li>
     *   <li>멱등하다 — 이미 취소된 주문에 또 와도 조용히 성공이다. 사용자가 두 번 닫는
     *       일은 흔하고, 그때 오류를 띄우면 멀쩡히 취소된 주문이 실패로 보인다.</li>
     * </ul>
     *
     * @param requesterId 게이트웨이가 검증한 요청자(토큰의 uid → User-Id). 본문의 값이 아니다
     */
    @Transactional
    public void cancelPaypalPayment(PaypalPaymentRequest request, Long requesterId) {

        Order order = getOrderByTrackingId(request.getTrackingId());
        if (order == null) {
            throw new PaypalVerificationException(
                    "주문을 찾을 수 없습니다: trackingId=" + request.getTrackingId());
        }

        // 🔒 남의 주문을 취소할 수 없다. 조회 API 들이 이미 같은 규칙을 쓴다(getOrder 주석).
        if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
            log.warn("다른 고객의 주문 취소 시도를 차단: orderId={}", order.getOrderId());
            // 404 와 같은 문구다 — 403 은 "그 주문은 존재한다"를 알려 준다.
            throw new PaypalVerificationException(
                    "주문을 찾을 수 없습니다: trackingId=" + request.getTrackingId());
        }

        if (OrderStatus.CANCELED == order.getOrderStatus()) {
            log.info("이미 취소된 주문입니다 — 중복 요청을 무시합니다. orderId={}", order.getOrderId());
            return;
        }

        // 🔒 결제가 붙어 있으면 취소가 아니라 환불의 영역이다.
        if (OrderStatus.PAID_STATUSES.contains(order.getOrderStatus())
                || order.getProviderPaymentId() != null) {
            log.warn("결제된 주문에 취소 요청이 왔다 — 거절한다. orderId={}, status={}",
                    order.getOrderId(), order.getOrderStatus());
            throw new PaypalVerificationException(
                    "이미 결제된 주문은 이 경로로 취소할 수 없습니다. 고객센터로 문의해 주세요.");
        }

        statusChanger.change(order, OrderStatus.CANCELED, "customer");
        orderRepository.save(order);
        log.info("주문 취소: orderId={}", order.getOrderId());
    }

    /**
     * 확인된 결제를 주문에 반영한다 — <b>제공자와 무관하게</b>.
     *
     * <h3>여기 있는 것이 docs/35 의 규칙 전부다</h3>
     *
     * <p>예전에는 이 메서드가 {@code persisitPaypalPayment} 였고, PayPal REST 호출과
     * 아래 규칙들이 <b>한 덩어리로 섞여</b> 있었다. 그 상태에서 결제수단을 추가하면
     * 규칙이 통째로 복사되고, 복사본 하나에서 금액 대조를 빠뜨리면 <b>그 수단으로만</b>
     * 결제 없이 주문이 완료된다.
     *
     * <p>제공자별 조회는 {@link PaymentGateway} 뒤로 갔다. 여기 남은 것은 모든 결제에
     * 공통인 것뿐이고, <b>새 제공자는 이 규칙을 자동으로 상속한다.</b>
     *
     * <ol>
     *   <li>주문이 실재하는가, <b>요청자의 것인가</b></li>
     *   <li>제공자에게 물어 <b>정말 결제됐는가</b></li>
     *   <li>확인된 금액이 <b>DB 의 주문 총액</b>과 같은가 — 요청 본문의 금액은 읽지 않는다</li>
     *   <li>통화가 같은가 — 금액만 보면 59.98 USD 를 59.98 KRW 로 바꿔치기할 수 있다</li>
     *   <li>그 결제가 <b>이미 다른 주문에 쓰였는가</b> — DB 유니크 제약으로 막는다</li>
     * </ol>
     *
     * @param requesterId 게이트웨이가 검증한 요청자(토큰의 uid). 웹훅이면 주문의 고객 id. <b>본문의 값이 아니다</b> — 예전에는
     *                       {@code request.getUserEmail()} 을 그대로 썼다
     */
    @Transactional
    public void recordPayment(String trackingId, PaymentProvider provider,
            String providerPaymentId, Long requesterId) {

        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            // 존재하지 않는 주문에 결제를 붙이려는 시도. 조용히 넘어가면 안 된다 —
            // 예전에는 if(order != null) 안에서 아무 일도 하지 않고 200 을 돌려줬다.
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
            log.warn("다른 고객의 주문에 결제를 붙이려는 시도를 차단: orderId={}", order.getOrderId());
            // 404 와 같은 문구다 — 403 은 "그 주문은 존재한다"를 알려 준다.
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        // 이미 이 주문에 붙어 있으면 같은 결제의 재전송이다(새로고침·웹훅과 복귀 검증이
        // 겹치는 경우). 멱등하게 끝낸다 — 두 경로로 확인하는 설계라 반드시 겹친다.
        //
        // 예전에는 옛 컬럼(paypal_order_id)도 함께 봤다 — V4 이전 이미지가 반영한 결제는 그쪽에만 값이 있었고, 새
        // 컬럼만 보면 결제 이벤트를 한 번 더 발행했다. V17 이 그 값을 새 컬럼으로 옮긴 뒤로는 새 컬럼만 보면 된다
        // (docs/49 §3).
        boolean alreadyRecorded = provider == order.getPaymentProvider()
                && providerPaymentId.equals(order.getProviderPaymentId());
        if (alreadyRecorded) {
            log.info("이미 반영된 결제입니다 — 중복 요청을 무시합니다. orderId={}", order.getOrderId());
            return;
        }

        // 🔴 접힌 주문에 돈이 도착했다 — 가상계좌 기한이 지나 우리가 접은 뒤 입금됐거나, 취소된
        //    주문에 뒤늦게 캡처가 붙은 경우다. 조용히 기록하면 사가가 상태를 못 바꿔 "돈은
        //    왔는데 주문은 취소" 가 아무 데도 안 보인다. 거절하고 error 로 남긴다 — 수동 환불.
        if (order.getOrderStatus() == OrderStatus.CANCELED) {
            log.error("취소된 주문에 결제가 도착했다 — 수동 환불 필요. orderId={} provider={} ref={}",
                    order.getOrderId(), provider, providerPaymentId);
            throw new PaymentVerificationException(
                    "취소된 주문입니다 — 결제는 돌려드려야 합니다: orderId=" + order.getOrderId() + ", ref=" + providerPaymentId);
        }

        VerifiedPayment verified = paymentGateways.require(provider).verify(providerPaymentId);

        if (!verified.completed()) {
            // 승인만 된 상태(PayPal APPROVED · Stripe unpaid)는 돈이 오지 않았다는 뜻이다.
            throw new PaymentVerificationException(
                    "결제가 완료되지 않았습니다: provider=" + provider
                            + ", status=" + verified.rawStatus());
        }

        // amount 가 null 이면 검증이 꺼진 모드다(DisabledPaypalOrderVerifier).
        // 그때는 기동 시점에 이미 경고를 찍었으므로 여기서 다시 막지는 않는다.
        if (verified.amount() != null) {
            // 대조 기준은 **주문에 저장된 청구**(통화·금액)다. 상점 통화의 total 이 아니다 —
            // 포트원은 KRW 로, 일본 테스트는 JPY 로 청구하고, 그 금액은 주문 생성 시점의
            // 환율로 이미 정해져 있다.
            PaymentCurrencyPolicy.Charge expected = expectedCharge(order);
            if (verified.amount().compareTo(expected.amount()) != 0) {
                throw new PaymentVerificationException(
                        "결제 금액이 청구액과 다릅니다: paid=" + verified.amount()
                                + ", expected=" + expected.amount() + " " + expected.currency()
                                + ", orderId=" + order.getOrderId());
            }
            if (!expected.currency().equalsIgnoreCase(verified.currency())) {
                throw new PaymentVerificationException(
                        "결제 통화가 다릅니다: paid=" + verified.currency()
                                + ", expected=" + expected.currency());
            }
        }

        order.setPaymentProvider(provider);
        order.setProviderPaymentId(providerPaymentId);

        try {
            // flush 해서 유니크 제약 위반을 **여기서** 잡는다. 트랜잭션 커밋까지 미루면
            // 아웃박스에 이미 결제가 들어간 뒤에 터진다.
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new PaymentVerificationException(
                    "이미 다른 주문에 사용된 결제입니다: " + providerPaymentId, e);
        }

        // ⚠️ 페이로드는 **서버가 확인한 값**으로 만든다. 요청 본문의 amounts·paymentStatus 는
        //    쓰지 않는다 — 그게 이 결함의 출발점이었다.
        PaymentRequest payload = PaymentRequest.builder()
                .orderId(order.getOrderId())
                .customerId(requesterId)
                .trackingId(trackingId)
                // 하류(pay-service · 매출 집계)는 **상점 통화**로 산다. 청구 통화의 금액을
                // 보내면 KRW 80,000 과 USD 59 가 한 컬럼에 섞인다. 제공자가 확인해 준 금액은
                // 바로 위에서 청구액과 대조됐으므로, 여기 싣는 것은 그에 대응하는 상점 통화 총액이다.
                .amounts(order.getTotal())
                .paymentProvider(provider)
                .providerPaymentId(providerPaymentId)
                .couponName(order.getCouponApplied())
                .orderStatus(order.getOrderStatus())
                .paymentType(paymentTypeOf(provider))
                .paymentStatus(PaymentStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                // 사용자 토큰은 싣지 않는다 — 아웃박스 행·토픽에 평문으로 남았고 pay 는 쓰지 않았다(docs/54 §1.7).
                .build();

        paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
    }

    /**
     * 결제된 주문을 환불한다 — <b>제공자와 무관하게</b>, 전액이든 일부든.
     *
     * <h3>{@link #recordPayment} 의 거울상이다</h3>
     *
     * <p>결제 확인의 규칙이 한 곳에 있듯, 환불의 규칙도 여기 하나다. 제공자별 호출은
     * {@link com.project.order_service.payment.RefundingGateway} 뒤에 있고, 새 제공자는
     * 아래 규칙을 자동으로 상속한다.
     *
     * <ol>
     *   <li>주문이 실재하는가, <b>요청자의 것인가</b>(관리자는 예외)</li>
     *   <li>이미 다 환불됐는가 — 멱등하게 끝낸다. 제공자를 <b>다시 부르지 않는다</b></li>
     *   <li>결제된 주문인가 — 결제 전 주문은 취소({@link #cancelPaypalPayment})의 영역이다</li>
     *   <li>배송 전인가 — 사용자 스스로는 {@code PAID}·{@code PROCESSING} 까지만. 발송된
     *       뒤에는 물건이 돌아와야 하므로 관리자가 판단한다</li>
     *   <li>품목·수량이 이 주문의 남은 수량 안인가 — 금액은 {@link RefundPricing} 이 정한다.
     *       마지막 환불은 언제나 남은 전부라 부분 환불의 합은 정확히 총액이다</li>
     *   <li>제공자가 <b>받아들였는가</b>, 그리고 환불액·통화가 <b>서버가 정한 값</b>과 같은가</li>
     * </ol>
     *
     * <p><b>순서가 중요하다.</b> 제공자를 부르기 전에 거부할 수 있는 것은 전부 먼저
     * 거부한다. 제공자가 돈을 돌려준 뒤 우리가 거부하면 "돈은 나갔는데 주문은 결제 상태"
     * 가 된다 — 그래서 제공자 호출 뒤에 남는 검사는 응답 대조뿐이고, 그것이 실패하면
     * <b>예외에 환불 참조를 실어</b> 운영자가 손으로 맞출 수 있게 한다.
     *
     * <p>부분 환불(V9)은 주문 상태를 바꾸지 않는다 — 돈이 일부 남아 있으니 여전히 결제된
     * 주문이다. 매출 집계는 {@code total − refundedAmount} 를 본다. 남은 수량이 0 이 되는
     * 환불만 {@code REFUNDED} 로 바꾸고 pay-service 에 CANCELED 를 알린다.
     *
     * @param admin 게이트웨이의 {@code User-Role} 로 판정한 값. 본문에서 오지 않는다
     * @param items 환불할 품목·수량. 비어 있으면 아직 환불되지 않은 전부
     * @return 환불이 반영된 주문(보상 이벤트 발행에 쓴다). 이미 다 환불돼 있었으면 그 주문
     */
    @Transactional
    public Order refundOrder(String trackingId, Long requesterId, boolean admin, String reason,
            List<OrderItemQtyRequest> items) {

        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        if (!admin) {
            if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
                log.warn("다른 고객의 주문 환불 시도를 차단: orderId={}", order.getOrderId());
                // 404 와 같은 문구 — 403 은 "그 주문은 존재한다"를 알려 준다.
                throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
            }
        }

        if (order.getOrderStatus() == OrderStatus.REFUNDED || order.remainingRefundable().signum() == 0) {
            log.info("이미 전부 환불된 주문입니다 — 중복 요청을 무시합니다. orderId={}", order.getOrderId());
            return order;
        }

        // 🔒 결제가 붙어 있지 않으면 돌려줄 돈이 없다. 취소의 영역이다.
        if (!OrderStatus.PAID_STATUSES.contains(order.getOrderStatus())
                || order.getPaymentProvider() == null || order.getProviderPaymentId() == null) {
            throw new PaymentVerificationException(
                    "결제되지 않은 주문은 환불할 수 없습니다: status=" + order.getOrderStatus());
        }

        if (!admin && !SELF_SERVICE_REFUNDABLE.contains(order.getOrderStatus())) {
            throw new PaymentVerificationException(
                    "배송이 시작된 주문은 여기서 환불할 수 없습니다. 고객센터로 문의해 주세요.");
        }
        // 🔒 무통장입금 환불은 계좌로 송금하는 사람(관리자)이 기록한다. 고객이 스스로 부르면
        //    돈은 가지 않았는데 주문만 REFUNDED 가 된다 — 게이트웨이가 언제나 "받아들였다" 고
        //    답하기 때문이다(BankTransferGateway).
        if (!admin && order.getPaymentProvider() == PaymentProvider.BANK_TRANSFER) {
            throw new PaymentVerificationException(
                    "무통장입금 주문의 환불은 환불 계좌를 확인한 뒤 처리됩니다. 고객센터로 문의해 주세요.");
        }

        // 금액은 서버가 정한다 — 품목 단가에서 할인을 나눠 빼고, 마지막 환불은 남은 전부.
        RefundPricing.Quote quote;
        try {
            quote = RefundPricing.quote(order.getTotal(), order.getTotalBeforeDiscount(),
                    order.getShippingPrice(), order.getRefundedAmount(), pricingLines(order), pricingItems(items));
        } catch (IllegalArgumentException e) {
            throw new PaymentVerificationException(e.getMessage());
        }
        if (quote.storeAmount().signum() <= 0) {
            throw new PaymentVerificationException("환불할 금액이 없습니다: orderId=" + order.getOrderId());
        }

        // 환불도 **청구된 통화·금액**으로 — KRW 로 받았으면 KRW 로 돌려준다. 청구액은 주문에
        // 고정된 환율로 바꾸고, 마지막 환불은 청구 잔액 전부라 환율 반올림 찌꺼기가 정리된다.
        PaymentCurrencyPolicy.Charge charge = expectedCharge(order);
        String expectedCurrency = charge.currency();
        BigDecimal chargeRemainder = charge.amount().subtract(order.getRefundedChargedAmount()).max(BigDecimal.ZERO);
        BigDecimal expected = quote.completesOrder()
                ? chargeRemainder
                : PaymentCurrencyPolicy.round(quote.storeAmount().multiply(charge.rate()), expectedCurrency)
                        .min(chargeRemainder);
        if (expected.signum() <= 0) {
            throw new PaymentVerificationException("청구 통화로 환불할 금액이 없습니다: orderId=" + order.getOrderId());
        }
        String safeReason = sanitizeReason(reason);
        String refundKey = refundKey(order, quote);

        RefundResult result = paymentGateways.requireRefunding(order.getPaymentProvider())
                .refund(order.getProviderPaymentId(), expected, expectedCurrency, safeReason, refundKey, chargeRemainder);

        if (!result.accepted()) {
            throw new PaymentVerificationException(
                    "제공자가 환불을 받아들이지 않았습니다: provider=" + order.getPaymentProvider()
                            + ", status=" + result.rawStatus());
        }
        // ⚠️ 여기서부터는 돈이 이미 나갔다. 거부하되 운영자가 찾을 수 있게 참조를 남긴다.
        if (result.amount() != null && result.amount().compareTo(expected) != 0) {
            log.error("환불액이 요청과 다르다 — 수동 확인 필요. orderId={} refundId={} refunded={} expected={}",
                    order.getOrderId(), result.refundId(), result.amount(), expected);
            throw new PaymentVerificationException(
                    "환불액이 주문 총액과 다릅니다: refunded=" + result.amount()
                            + ", expected=" + expected + ", refundId=" + result.refundId());
        }
        if (result.currency() != null && !result.currency().isBlank()
                && !expectedCurrency.equalsIgnoreCase(result.currency())) {
            log.error("환불 통화가 다르다 — 수동 확인 필요. orderId={} refundId={} currency={}",
                    order.getOrderId(), result.refundId(), result.currency());
            throw new PaymentVerificationException(
                    "환불 통화가 다릅니다: refunded=" + result.currency()
                            + ", expected=" + expectedCurrency + ", refundId=" + result.refundId());
        }

        // 물건이 이미 나갔는가 — 발송 전(PAID·PROCESSING)이면 환불된 수량은 창고에 그대로라
        // "돌아온 것" 으로 세고 재고가 바로 돌아온다. 발송된 뒤면 반품이 **입고된 때** 돌아온다.
        boolean shipped = SHIPPED_STATUSES.contains(order.getOrderStatus());
        Map<Long, OrderProduct> lines = linesById(order);
        for (RefundPricing.Item item : quote.items()) {
            OrderProduct line = lines.get(item.orderProductId());
            int allocatedBefore = line.refundedAllocatedQty();
            line.setRefundedQty(line.getRefundedQty() + item.qty());
            if (!shipped) {
                // 창고에 그대로 있는 것은 **배정된** 몫뿐이다 — 재고 부족으로 배정되지 못한 수(shortQty)는 차감된 적이
                // 없으니 "돌아왔다" 로 세면 재고 복원이 없는 재고를 만든다(docs/53). 환불은 부족분을 먼저 소진한다.
                int allocatedNow = line.refundedAllocatedQty() - allocatedBefore;
                line.setReturnedQty(Math.min(line.allocatedQty(), line.getReturnedQty() + allocatedNow));
            }
        }

        String refundRef = result.refundId() == null || result.refundId().isBlank()
                ? order.getProviderPaymentId() : result.refundId();
        order.setRefundedAmount(order.getRefundedAmount().add(quote.storeAmount()));
        order.setRefundedChargedAmount(order.getRefundedChargedAmount().add(expected));
        order.setRefundId(refundRef);
        order.setRefundedAt(LocalDateTime.now());
        order.setAwaitingReturn(awaitingReturn(order));
        order.getRefunds().add(OrderRefund.builder()
                .order(order).refundRef(refundRef)
                .amount(quote.storeAmount()).chargedAmount(expected).currency(expectedCurrency)
                .reason(safeReason.isBlank() ? null : safeReason)
                .items(itemsJson(quote.items()))
                .build());
        if (quote.completesOrder()) {
            statusChanger.change(order, OrderStatus.REFUNDED, admin ? "admin" : "customer");
        }
        orderRepository.saveAndFlush(order);
        log.info("환불 반영: orderId={} provider={} refundId={} by={} store={} charged={} {} complete={} awaitingReturn={}",
                order.getOrderId(), order.getPaymentProvider(), refundRef, admin ? "admin" : "customer",
                quote.storeAmount(), expected, expectedCurrency, quote.completesOrder(), order.isAwaitingReturn());

        // pay-service 가 결제 행을 CANCELED 로 바꾸도록 같은 아웃박스로 알린다 — **전액이 다
        // 돌아갔을 때만.** 부분 환불은 결제가 아직 살아 있다(pay-service 의 상태는 둘뿐이다).
        // 결제 확인과 같은 경로라 CDC·릴레이·멱등 처리가 그대로 적용된다.
        if (quote.completesOrder()) {
            PaymentRequest payload = PaymentRequest.builder()
                    .orderId(order.getOrderId())
                    .customerId(order.getCustomerId())
                    .trackingId(trackingId)
                    .amounts(order.getRefundedChargedAmount())
                    .paymentProvider(order.getPaymentProvider())
                    .providerPaymentId(order.getProviderPaymentId())
                    .couponName(order.getCouponApplied())
                    .orderStatus(OrderStatus.REFUNDED)
                    .paymentType(paymentTypeOf(order.getPaymentProvider()))
                    .paymentStatus(PaymentStatus.CANCELED)
                    .createdAt(LocalDateTime.now())
                    .build();
            paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
        }

        return order;
    }

    /** 옛 호출 — 남은 전부. */
    @Transactional
    public Order refundOrder(String trackingId, Long requesterId, boolean admin, String reason) {
        return refundOrder(trackingId, requesterId, admin, reason, null);
    }

    private static List<RefundPricing.Line> pricingLines(Order order) {
        List<RefundPricing.Line> lines = new ArrayList<>();
        for (OrderProduct p : order.getOrderedProducts()) {
            lines.add(new RefundPricing.Line(p.getOrderProductId(), p.getPrice(), p.getQty(), p.getRefundedQty()));
        }
        return lines;
    }

    private static List<RefundPricing.Item> pricingItems(List<OrderItemQtyRequest> items) {
        if (items == null) {
            return null;
        }
        List<RefundPricing.Item> out = new ArrayList<>();
        for (OrderItemQtyRequest i : items) {
            out.add(new RefundPricing.Item(i == null ? null : i.getOrderProductId(), i == null ? 0 : i.getQty()));
        }
        return out;
    }

    private static Map<Long, OrderProduct> linesById(Order order) {
        Map<Long, OrderProduct> map = new java.util.HashMap<>();
        for (OrderProduct p : order.getOrderedProducts()) {
            map.put(p.getOrderProductId(), p);
        }
        return map;
    }

    /** 환불된 수량이 돌아온 수량보다 많으면 물건이 아직 밖에 있다. */
    static boolean awaitingReturn(Order order) {
        int refunded = 0;
        int returned = 0;
        for (OrderProduct p : order.getOrderedProducts()) {
            // 배정되지 못한 수(재고 부족)는 나간 적이 없으니 돌아올 것도 없다(docs/53).
            refunded += p.refundedAllocatedQty();
            returned += p.getReturnedQty();
        }
        return refunded > returned;
    }

    /**
     * 이 환불의 멱등 키 — "같은 상태에서 같은 품목·수량" 이면 같은 키. 더블클릭·재시도가
     * 제공자에서 한 번으로 접히고, 다음 부분 환불(상태가 바뀌었다)은 다른 키를 받는다.
     */
    static String refundKey(Order order, RefundPricing.Quote quote) {
        StringBuilder sb = new StringBuilder().append(order.getOrderId()).append('|')
                .append(order.getRefundedAmount().toPlainString());
        quote.items().stream()
                .sorted(java.util.Comparator.comparing(RefundPricing.Item::orderProductId))
                .forEach(i -> sb.append('|').append(i.orderProductId()).append(':').append(i.qty()));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String itemsJson(List<RefundPricing.Item> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 무통장입금 (docs/49 §11)

    /**
     * 입금 안내를 시작한다 — {@code NOT_PROCESSED → UNPAID}.
     *
     * <p>돈이 오지 않았으니 여기서 바뀌는 것은 상태·기한·입금자명뿐이다. 재고 차감·장바구니
     * 비우기는 결제 확인 뒤 사가가 한다(카드와 같다). 이미 {@code UNPAID} 면 멱등 — 입금자명만
     * 갱신한다(손님이 고쳐 쓸 수 있어야 한다).
     */
    @Transactional
    public Order startBankTransfer(String trackingId, Long requesterId, String depositorName, int deadlineHours) {
        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
            log.warn("다른 고객의 주문에 입금 안내 시도를 차단: orderId={}", order.getOrderId());
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (!"cash".equalsIgnoreCase(order.getPaymentMethod())) {
            throw new PaymentVerificationException("무통장입금으로 주문한 건이 아닙니다: paymentMethod=" + order.getPaymentMethod());
        }
        String name = sanitizeDepositor(depositorName);
        if (name.isEmpty()) {
            throw new PaymentVerificationException("입금자명을 입력해 주세요.");
        }
        if (order.getOrderStatus() == OrderStatus.UNPAID) {
            order.setDepositorName(name);
            orderRepository.save(order);
            return order;
        }
        if (order.getOrderStatus() != OrderStatus.NOT_PROCESSED) {
            throw new PaymentVerificationException(
                    "입금 안내를 받을 수 있는 주문이 아닙니다: status=" + order.getOrderStatus());
        }
        statusChanger.change(order, OrderStatus.UNPAID, "bank-transfer");
        order.setDepositorName(name);
        order.setDepositDeadline(LocalDateTime.now().plusHours(Math.max(1, deadlineHours)));
        orderRepository.save(order);
        log.info("무통장입금 안내: orderId={} deadline={}", order.getOrderId(), order.getDepositDeadline());
        return order;
    }

    /**
     * 관리자가 입금을 확인했다 — {@code UNPAID} 에 결제를 붙이고 아웃박스로 알린다.
     *
     * <p>{@link #recordPayment} 의 무통장입금 판이다. 다른 점은 "제공자에게 물어본다" 가
     * "관리자가 봤다" 로 바뀐 것뿐이고, 그 뒤는 같다: 결제 참조를 붙이고(멱등), pay-service 에
     * COMPLETED 를 보내면 응답이 사가를 깨워 {@code PAID} 로 바꾸고 재고를 차감한다.
     *
     * <p>금액은 받지 않는다. 청구액과 다르게 들어왔으면 확인하지 말고 고객에게 연락한다 —
     * "얼마가 왔다" 를 받기 시작하면 그 값을 믿게 된다.
     */
    @Transactional
    public Order confirmDeposit(String trackingId, String depositReference) {
        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (order.getPaymentProvider() == PaymentProvider.BANK_TRANSFER && order.getProviderPaymentId() != null) {
            log.info("이미 입금이 확인된 주문입니다 — 중복 요청을 무시합니다. orderId={}", order.getOrderId());
            return order;
        }
        if (order.getOrderStatus() != OrderStatus.UNPAID) {
            throw new PaymentVerificationException(
                    "입금 대기 중인 주문이 아닙니다: status=" + order.getOrderStatus()
                            + " — 결제된 주문이거나, 아직 입금 안내를 받지 않았거나, 이미 접힌 주문입니다.");
        }
        // 🔒 가상계좌는 포트원이 입금을 확인한다(웹훅). 사람이 확인하면 PG 의 진실과 갈린다 —
        //    포트원에는 미입금인데 우리만 결제됨이 되고, 나중에 입금되면 두 번 반영된다.
        if (order.hasVirtualAccount()) {
            throw new PaymentVerificationException(
                    "가상계좌 주문의 입금은 포트원 웹훅이 확인합니다 — 여기서 확인할 수 없습니다: orderId=" + order.getOrderId());
        }
        String ref = sanitizeReason(depositReference);
        String paymentRef = "deposit-" + order.getOrderId() + (ref.isBlank() ? "" : "-" + ref.replaceAll("[^A-Za-z0-9._-]", ""));
        order.setPaymentProvider(PaymentProvider.BANK_TRANSFER);
        order.setProviderPaymentId(paymentRef.length() > 255 ? paymentRef.substring(0, 255) : paymentRef);
        order.setPaidCreatedAt(LocalDateTime.now());
        try {
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new PaymentVerificationException("이미 다른 주문에 사용된 입금 참조입니다: " + paymentRef, e);
        }
        log.info("입금 확인: orderId={} ref={}", order.getOrderId(), order.getProviderPaymentId());

        PaymentRequest payload = PaymentRequest.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomerId())
                .trackingId(trackingId)
                .amounts(order.getTotal())
                .paymentProvider(PaymentProvider.BANK_TRANSFER)
                .providerPaymentId(order.getProviderPaymentId())
                .couponName(order.getCouponApplied())
                .orderStatus(order.getOrderStatus())
                .paymentType(PaymentType.CASH)
                .paymentStatus(PaymentStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .build();
        paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
        return order;
    }

    /**
     * 포트원이 발급한 가상계좌를 주문에 붙인다 — {@code NOT_PROCESSED → UNPAID}, 기한은 PG 의
     * 기한. 무통장입금 상태 기계의 "입금 안내" 를 PG 가 만든 경우다(docs/49 §11).
     *
     * <p>발급 웹훅과 브라우저 복귀가 <b>둘 다</b> 부른다(Stripe 의 두 경로와 같다) — 같은
     * 결제 참조면 멱등이고, 다른 참조(손님이 다시 발급받음)면 새 계좌로 바꾼다. 금액·통화는
     * 주문의 청구액과 대조한다: 1원짜리 가상계좌를 붙여 두고 입금하면 결제 확인에서 어차피
     * 거절되지만, 손님에게 틀린 안내를 보여 주지 않기 위해 여기서 먼저 막는다.
     *
     * @param requesterId 브라우저 복귀면 요청자(주문자여야 한다). 웹훅이면 {@code null} —
     *                       그때는 결제 참조가 포트원에서 이 주문을 가리킨다는 것이 근거다
     */
    @Transactional
    public Order registerVirtualAccount(String trackingId, String paymentRef,
            com.project.order_service.payment.portone.VirtualAccount va, Long requesterId, int fallbackDeadlineHours) {
        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (requesterId != null && !requesterId.equals(order.getCustomerId())) {
            log.warn("다른 고객의 주문에 가상계좌를 붙이려는 시도를 차단: orderId={}", order.getOrderId());
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (order.getOrderStatus() == OrderStatus.UNPAID && paymentRef.equals(order.getPendingPaymentRef())) {
            return order;   // 웹훅과 복귀가 겹쳤다
        }
        if (order.getOrderStatus() != OrderStatus.NOT_PROCESSED && order.getOrderStatus() != OrderStatus.UNPAID) {
            throw new PaymentVerificationException(
                    "가상계좌를 붙일 수 있는 주문이 아닙니다: status=" + order.getOrderStatus());
        }
        PaymentCurrencyPolicy.Charge expected = expectedCharge(order);
        if (va.amount() == null || va.amount().compareTo(expected.amount()) != 0) {
            throw new PaymentVerificationException("가상계좌 금액이 청구액과 다릅니다: issued=" + va.amount()
                    + ", expected=" + expected.amount() + " " + expected.currency() + ", orderId=" + order.getOrderId());
        }
        if (va.currency() != null && !va.currency().isBlank() && !expected.currency().equalsIgnoreCase(va.currency())) {
            throw new PaymentVerificationException("가상계좌 통화가 다릅니다: issued=" + va.currency()
                    + ", expected=" + expected.currency());
        }
        statusChanger.change(order, OrderStatus.UNPAID, "virtual-account");
        order.setVbankBank(va.bank());
        order.setVbankAccount(va.accountNumber());
        order.setVbankHolder(va.holder());
        order.setPendingPaymentRef(paymentRef);
        order.setDepositDeadline(va.dueAt() != null ? va.dueAt()
                : LocalDateTime.now().plusHours(Math.max(1, fallbackDeadlineHours)));
        orderRepository.save(order);
        log.info("가상계좌 발급 반영: orderId={} ref={} deadline={}", order.getOrderId(), paymentRef, order.getDepositDeadline());
        return order;
    }

    /**
     * 입금 전에 접는다 — {@code UNPAID → CANCELED}. 돈이 오지 않았으니 환불이 아니라 취소다.
     * 재고는 차감된 적이 없고, 쿠폰은 호출자가 사가로 돌려준다.
     */
    @Transactional
    public Order cancelUnpaid(String trackingId, Long requesterId, boolean admin) {
        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        if (!admin) {
            if (requesterId == null || !requesterId.equals(order.getCustomerId())) {
                log.warn("다른 고객의 주문 취소 시도를 차단: orderId={}", order.getOrderId());
                throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
            }
        }
        if (order.getOrderStatus() == OrderStatus.CANCELED) {
            return order;
        }
        if (order.getOrderStatus() != OrderStatus.UNPAID || order.getProviderPaymentId() != null) {
            throw new PaymentVerificationException(
                    "입금 대기 중인 주문만 취소할 수 있습니다: status=" + order.getOrderStatus());
        }
        statusChanger.change(order, OrderStatus.CANCELED, admin ? "admin" : "customer");
        orderRepository.save(order);
        log.info("입금 전 취소: orderId={} by={}", order.getOrderId(), admin ? "admin" : "customer");
        return order;
    }

    /**
     * 기한이 지난 입금 대기 주문을 접는다. 만료 잡이 부른다. 접힌 주문을 돌려준다 —
     * 호출자가 쿠폰을 돌려준다.
     */
    @Transactional
    public List<Order> expireUnpaid(LocalDateTime now) {
        List<Order> expired = orderRepository.findByOrderStatusAndDepositDeadlineBefore(OrderStatus.UNPAID, now);
        for (Order order : expired) {
            if (order.getProviderPaymentId() != null) {
                continue;   // 입금 확인은 됐는데 사가가 아직 PAID 로 못 바꾼 찰나 — 접지 않는다
            }
            statusChanger.change(order, OrderStatus.CANCELED, "deposit-expired");
            log.info("입금 기한 경과로 취소: orderId={} deadline={}", order.getOrderId(), order.getDepositDeadline());
        }
        List<Order> canceled = expired.stream().filter(o -> o.getOrderStatus() == OrderStatus.CANCELED).toList();
        orderRepository.saveAll(canceled);
        return canceled;
    }

    /** 입금자명 — 한 줄, 50자. 은행 내역과 눈으로 맞추는 값이라 그 이상은 의미가 없다. */
    static String sanitizeDepositor(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replaceAll("[\\r\\n\\t\"]", " ").trim().replaceAll("\\s+", " ");
        return s.length() > 50 ? s.substring(0, 50) : s;
    }

    /** 물건이 창고를 떠난 상태 — 반품 입고를 기록할 수 있는 주문. */
    static final java.util.Set<OrderStatus> SHIPPED_STATUSES =
            java.util.Set.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED, OrderStatus.COMPLETED);

    /**
     * 반품 입고를 기록한다 (관리자) — 전부든 일부든.
     *
     * <p>받는 것은 <b>어느 주문·어느 품목 몇 개</b>와 <b>재고로 되돌릴 것인가</b> 뿐이다.
     * 수량은 품목의 남은 수({@code qty − returnedQty})를 넘지 못한다. 비어 있으면 남은 전부.
     *
     * <ol>
     *   <li>돌아올 것이 없으면(남은 수 0) 멱등하게 끝낸다.</li>
     *   <li>물건이 나간 주문이어야 한다: 발송·배송·확정 상태거나, 발송된 뒤 환불된 주문
     *       ({@code REFUNDED} 인데 아직 안 돌아온 수량이 있다). 발송 전 환불은 환불 때
     *       이미 "돌아온 것" 으로 셌으므로 남은 수가 0 이라 ①에서 끝난다.</li>
     * </ol>
     *
     * <p>재고 복원 이벤트는 여기서 보내지 않는다 — 트랜잭션 밖에서 {@code OrderSaga.restock} 이
     * 보낸다(카프카 발행을 DB 트랜잭션 안에 두지 않는다). {@code restock=false}(파손 등)면
     * 그 수량은 {@code discardedQty} 로 가고 재고에는 가지 않는다.
     *
     * @param items 품목·수량. 비어 있으면 아직 돌아오지 않은 전부
     * @return 입고가 기록된 주문
     */
    @Transactional
    public Order receiveReturn(String trackingId, boolean restock, List<OrderItemQtyRequest> items) {
        Order order = getOrderByTrackingId(trackingId);
        if (order == null) {
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }
        Map<Long, OrderProduct> lines = linesById(order);
        int remainingTotal = order.getOrderedProducts().stream().mapToInt(OrderProduct::remainingToReturn).sum();

        if (remainingTotal == 0 && order.getReturnReceivedAt() != null) {
            // 이미 다 돌아왔다 — 같은 요청이 또 온 것이다.
            log.info("돌아올 수량이 없습니다 — 중복 요청을 무시합니다. orderId={}", order.getOrderId());
            return order;
        }
        boolean shippedOut = remainingTotal > 0
                && (SHIPPED_STATUSES.contains(order.getOrderStatus()) || order.getOrderStatus() == OrderStatus.REFUNDED);
        if (!shippedOut) {
            throw new PaymentVerificationException(
                    "돌아올 물건이 없는 주문입니다: status=" + order.getOrderStatus()
                            + " — 발송 전 환불은 재고가 이미 돌아왔고, 결제 전 주문은 취소의 영역입니다.");
        }

        // 요청을 품목별로 합치고 검증한다. 비어 있으면 남은 전부.
        Map<Long, Integer> wanted = new java.util.LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            for (OrderProduct p : order.getOrderedProducts()) {
                if (p.remainingToReturn() > 0) {
                    wanted.put(p.getOrderProductId(), p.remainingToReturn());
                }
            }
        } else {
            for (OrderItemQtyRequest item : items) {
                if (item == null || item.getOrderProductId() == null) {
                    throw new PaymentVerificationException("입고할 품목이 비어 있습니다.");
                }
                if (item.getQty() < 1) {
                    throw new PaymentVerificationException("입고 수량은 1 이상이어야 합니다: orderProductId=" + item.getOrderProductId());
                }
                OrderProduct line = lines.get(item.getOrderProductId());
                if (line == null) {
                    throw new PaymentVerificationException("이 주문의 품목이 아닙니다: orderProductId=" + item.getOrderProductId());
                }
                int sum = wanted.getOrDefault(item.getOrderProductId(), 0) + item.getQty();
                if (sum > line.remainingToReturn()) {
                    throw new PaymentVerificationException("입고 수량이 남은 수량을 넘습니다: orderProductId="
                            + item.getOrderProductId() + " 남은=" + line.remainingToReturn() + " 요청=" + sum);
                }
                wanted.put(item.getOrderProductId(), sum);
            }
        }

        wanted.forEach((id, q) -> {
            OrderProduct line = lines.get(id);
            line.setReturnedQty(line.getReturnedQty() + q);
            if (!restock) {
                line.setDiscardedQty(line.getDiscardedQty() + q);
            }
        });
        order.setReturnReceivedAt(LocalDateTime.now());
        order.setAwaitingReturn(awaitingReturn(order));
        orderRepository.saveAndFlush(order);
        log.info("반품 입고: orderId={} status={} restock={} items={}", order.getOrderId(), order.getOrderStatus(),
                restock, wanted);
        return order;
    }

    /** 옛 호출 — 남은 전부. */
    @Transactional
    public Order receiveReturn(String trackingId, boolean restock) {
        return receiveReturn(trackingId, restock, null);
    }

    /** 관리자 목록 페이지 크기 상한. 한 화면에 그 이상은 어차피 못 읽는다. */
    private static final int ADMIN_PAGE_MAX = 100;

    /** {@link #listOrdersForAdmin} 의 상태 자리에 오는 가짜 상태 — 반품 대기 목록. */
    public static final String AWAITING_RETURN_FILTER = "AWAITING_RETURN";

    /**
     * 관리자 주문 목록 — 고객과 무관하게, 상태로 거르거나 추적번호 하나를 찾는다.
     *
     * <p>호출자가 관리자임을 <b>이미 확인했어야 한다</b>(컨트롤러의 {@code User-Role}).
     * 이 메서드는 소유권을 보지 않는다 — 그것이 관리자 목록의 정의다.
     *
     * <p>고객 이메일은 한 번에 모아 붙인다. 주문마다 고객을 따로 읽으면 페이지당 N+1 이다.
     *
     * @param status     {@code OrderStatus} 이름. 비어 있으면 전체. 모르는 값이면 예외 —
     *                   조용히 전체를 주면 "필터가 안 먹는다" 가 된다(docs/50 의 교훈)
     * @param trackingId 있으면 그 주문 하나만(상태 필터보다 우선)
     */
    @Transactional(readOnly = true)
    public Page<com.project.common.dto.order.AdminOrderSummaryDto> listOrdersForAdmin(
            String status, String trackingId, int page, int size) {

        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 20 : Math.min(size, ADMIN_PAGE_MAX);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<Order> orders;
        if (trackingId != null && !trackingId.isBlank()) {
            List<Order> one = orderRepository.findByTrackingId(trackingId.trim())
                    .map(List::of).orElse(List.of());
            orders = new org.springframework.data.domain.PageImpl<>(one, pageable, one.size());
        } else if (status == null || status.isBlank()) {
            orders = orderRepository.findAllByOrderByOrderIdDesc(pageable);
        } else if (AWAITING_RETURN_FILTER.equalsIgnoreCase(status.trim())) {
            // OrderStatus 가 아니라 반품 대기 플래그다. 상태 자리에 두는 이유: 화면의 필터
            // 하나로 고르게 하려고 — REFUNDED 중 물건이 아직 안 돌아온 것만.
            orders = orderRepository.findByAwaitingReturnTrueOrderByOrderIdDesc(pageable);
        } else {
            // 모르는 상태는 OrderStatus.getStatus 가 IllegalStateException 을 던진다.
            orders = orderRepository.findByOrderStatusOrderByOrderIdDesc(
                    OrderStatus.getStatus(status.trim().toUpperCase()), pageable);
        }

        // 표시용 이메일은 여기서 채우지 않는다 — 트랜잭션 안에서 user-service 를 부르게 되기 때문이다(docs/37 #5).
        // 트랜잭션 밖의 OrderService.listOrdersForAdmin 이 한 번에 붙인다(docs/48 §6-①).

        return orders.map(o -> com.project.common.dto.order.AdminOrderSummaryDto.builder()
                // id 는 문자열로 내린다 — JSON 숫자로 보내면 브라우저에서 값이 바뀐다(DTO 주석).
                .orderId(String.valueOf(o.getOrderId()))
                .trackingId(o.getTrackingId())
                .customerId(o.getCustomerId() == null ? null : String.valueOf(o.getCustomerId()))
                .total(o.getTotal())
                .paymentMethod(o.getPaymentMethod())
                .paymentProvider(o.getPaymentProvider() == null ? null : o.getPaymentProvider().name())
                .providerPaymentId(o.getProviderPaymentId())
                .orderStatus(o.getOrderStatus() == null ? null : o.getOrderStatus().name())
                .createdAt(o.getCreatedAt())
                .paidCreatedAt(o.getPaidCreatedAt())
                .refundId(o.getRefundId())
                .refundedAt(o.getRefundedAt())
                .currency(o.getCurrency())
                .chargedAmount(o.getChargedAmount())
                .awaitingReturn(o.isAwaitingReturn())
                .returnReceivedAt(o.getReturnReceivedAt())
                .stockRestoredAt(o.getStockRestoredAt())
                .refundedAmount(o.getRefundedAmount())
                .refundedChargedAmount(o.getRefundedChargedAmount())
                .depositDeadline(o.getDepositDeadline())
                .depositorName(o.getDepositorName())
                // 품목은 페이지 크기만큼 지연 로딩된다(행마다 한 번). 관리자 화면 한 페이지에는
                // 충분하고, 부분 환불·반품 패널이 "남은 수량" 을 그리려면 꼭 필요하다.
                .items(o.getOrderedProducts().stream().map(p -> com.project.common.dto.order.AdminOrderItemDto.builder()
                        .orderProductId(String.valueOf(p.getOrderProductId())).name(p.getName()).size(p.getSize())
                        .skuCode(p.getSkuCode())
                        .price(p.getPrice()).qty(p.getQty())
                        .refundedQty(p.getRefundedQty()).returnedQty(p.getReturnedQty())
                        .restockedQty(p.getRestockedQty()).discardedQty(p.getDiscardedQty())
                        .shortQty(p.getShortQty())
                        .build()).toList())
                .build());
    }

    /**
     * 이 주문에 청구된 통화·금액. 컬럼이 생기기 전 주문(currency null)은 "상점 통화로
     * total 만큼" — 그때의 의미 그대로다.
     */
    PaymentCurrencyPolicy.Charge expectedCharge(Order order) {
        if (order.getCurrency() != null && order.getChargedAmount() != null) {
            return new PaymentCurrencyPolicy.Charge(order.getCurrency(), order.getChargedAmount(),
                    order.getFxRate() == null ? BigDecimal.ONE : order.getFxRate());
        }
        return new PaymentCurrencyPolicy.Charge(currencyPolicy.baseCurrency(),
                order.getTotal(), BigDecimal.ONE);
    }

    /** 사용자가 스스로 환불할 수 있는 상태 — 물건이 아직 나가지 않았다. */
    static final java.util.Set<OrderStatus> SELF_SERVICE_REFUNDABLE =
            java.util.Set.of(OrderStatus.PAID, OrderStatus.PROCESSING);

    /** 제공자 영수증에 남을 수 있는 문구다. 길이를 자르고 줄바꿈·따옴표를 뺀다. */
    public static String sanitizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String cleaned = reason.replaceAll("[\\r\\n\"\\\\]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /** 제공자 → 결제 수단. 지금은 1:1 이지만 같은 값이 아니다(PaymentProvider 주석). */
    private static PaymentType paymentTypeOf(PaymentProvider provider) {
        if (provider == PaymentProvider.STRIPE) {
            return PaymentType.STRIPE;
        }
        if (provider == PaymentProvider.PORTONE) {
            return PaymentType.PORTONE;
        }
        if (provider == PaymentProvider.BANK_TRANSFER) {
            return PaymentType.CASH;
        }
        return PaymentType.PAYPAL;
    }
}