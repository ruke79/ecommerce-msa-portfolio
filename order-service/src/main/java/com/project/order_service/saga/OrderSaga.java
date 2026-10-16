package com.project.order_service.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bouncycastle.internal.asn1.misc.CAST5CBCParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.request.CartRollbackRequest;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.OrderProductRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j // Lombok을 사용하여 로그 객체 자동 주입 ／ [JA] Lombok でロガーを自動生成する
@Component
@RequiredArgsConstructor
public class OrderSaga {

    /** 결제 응답으로 PAID 가 될 수 있는 상태 — 카드는 NOT_PROCESSED, 무통장입금은 UNPAID. */
    // [JA] 決済応答で PAID になり得る状態 — カードは NOT_PROCESSED、銀行振込は UNPAID。
    static final List<OrderStatus> AWAITING_PAYMENT = List.of(OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID);

    private final OrderRepository orderRepository;
    private final OrderProductRepository orderProductRepository;
    /**
     * 결제 확정 차감 · 반품 복원 · 장바구니 비우기는 상태 변경과 같은 트랜잭션의 아웃박스 행으로 쓴다 — 릴레이가 보낸다.
     * 🐛 예전에는 Kafka 로 바로 보냈다(결과는 로그뿐) — PAID 커밋 뒤 발행이 빠지면 재고가 차감되지 않았다.
     * [JA] 決済確定の減算 · 返品の復元 · カートを空にするは状態変更と同じトランザクションのアウトボックス行として書く。
     */
    private final com.project.order_service.outbox.PaymentOutboxHelper outbox;
    private final CouponRollbackKafkaPublisher couponRollbackKafkaPublisher;
    private final CartRollbackKafkaPublisher cartRollbackKafkaPublisher;
    /** 상태를 바꾸면 캐시를 비운다 — 안 비우면 상세가 한 시간 동안 옛 상태다(OrderCacheEvictor 주석). */
    // [JA] 状態を変えたらキャッシュを消す — 消さないと詳細画面が 1 時間古い状態のままになる(OrderCacheEvictor のコメント参照)。
    private final com.project.order_service.service.OrderCacheEvictor cacheEvictor;

    /** 결제 전 재고 예약(docs/59). 세터로 받는다 — 생성자를 쓰는 테스트를 늘리지 않으려고. 없으면 건드리지 않는다. */
    // [JA] 決済前の在庫の仮押さえ(docs/59)。セッターで受け取る — コンストラクタを使うテストを増やさないため。
    //      無ければ何もしない。
    private com.project.order_service.stock.OrderStockHold stockHold;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStockHold(com.project.order_service.stock.OrderStockHold stockHold) {
        this.stockHold = stockHold;
    }

    /** 상태 변경 + 이벤트(docs/76). 세터 — 생성자를 쓰는 테스트가 많다. 기본값은 이벤트 없이 상태만 바꾼다(단위 테스트). */
    // [JA] 状態変更 + イベント(docs/76)。セッター — コンストラクタを使うテストが多い。デフォルトはイベントなしで状態だけ変える。
    private com.project.order_service.service.OrderStatusChanger statusChanger = com.project.order_service.service.OrderStatusChanger.withoutEvents();

    /** 재고 복원의 트랜잭션. 세터 — 생성자를 쓰는 테스트가 많다. 없으면(단위 테스트) 트랜잭션 없이 돈다. */
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setStatusChanger(com.project.order_service.service.OrderStatusChanger statusChanger) {
        this.statusChanger = statusChanger;
    }

    @Transactional(readOnly = true)
    private Order getOrderByOrderStatus(Long orderId, OrderStatus orderStatus) {
        log.debug("OrderSaga: Attempting to get order with ID {} and status {}", orderId, orderStatus);
        Optional<Order> order = orderRepository.findByOrderIdAndOrderStatus(orderId, orderStatus);

        if (order.isPresent()) {
            log.debug("OrderSaga: Found order with ID {} and status {}. Order details: {}", orderId, orderStatus, order.get());
            return order.get();
        }
        log.warn("OrderSaga: Order with ID {} and status {} not found.", orderId, orderStatus);
        return null;
    }

    @Transactional(readOnly = true)
    private List<OrderProduct> getOrderProductsByOrder(Long orderId) {
        log.debug("OrderSaga: Attempting to get order products for order ID {}", orderId);
        Optional<List<OrderProduct>> orderProductsOptional = orderProductRepository.findByOrder_OrderId(orderId); // 변수명 충돌 방지
        // [JA] 変数名の衝突を避ける

        if (orderProductsOptional.isPresent()) {
            log.debug("OrderSaga: Found {} order products for order ID {}. Details: {}", orderProductsOptional.get().size(), orderId, orderProductsOptional.get());
            return orderProductsOptional.get();
        }
        log.warn("OrderSaga: No order products found for order ID {}", orderId);
        return new ArrayList<>(); // null 대신 빈 리스트 반환하여 NullPointerException 방지 ／ [JA] null ではなく空リストを返して NullPointerException を防ぐ
    }

    @Transactional
    public void process(PaymentResponse paymentResponse) {
        log.info("OrderSaga: Starting order processing for PaymentResponse: {}", paymentResponse);

        // 카드·간편결제는 NOT_PROCESSED 에서, 무통장입금은 UNPAID(입금 대기)에서 결제 응답을 받는다.
        // [JA] カード・簡易決済は NOT_PROCESSED で、銀行振込は UNPAID(入金待ち)で決済応答を受け取る。
        Order order = orderRepository.findByOrderIdAndOrderStatusIn(paymentResponse.getOrderId(),
                AWAITING_PAYMENT).orElse(null);

        if (null != order) {
            log.info("OrderSaga: Order {} found with status {}. Proceeding with payment processing.", order.getOrderId(), order.getOrderStatus());
            
            // 1. 주문 상태 업데이트 (NOT_PROCESSED -> PAID)
            // [JA] 1. 注文ステータスを更新 (NOT_PROCESSED -> PAID)
            updateOrderStatus(order, OrderStatus.PAID);
            log.info("OrderSaga: Order {} status updated to PAID.", order.getOrderId());

            // 2. 상품 상태 업데이트 메시지 발행 (재고 감소, 판매량 증가 등)
            // [JA] 2. 商品状態の更新メッセージを発行 (在庫の減算、販売数の加算など)
            publishProductStateUpdate(order.getOrderId());
            log.info("OrderSaga: Published product state update for order {}.", order.getOrderId());

            // 3. 장바구니 비우기 메시지 발행 — 주문한 품목만(OrderCacheEvictor 와 같은 커밋의 cart-service 주석)
            // [JA] 3. カートを空にするメッセージを発行 — 注文した品目のみ
            //      (OrderCacheEvictor と同じコミットの cart-service のコメント参照)
            publishCartEmptyState(order);
            log.info("OrderSaga: Published cart empty state for customer {}.", order.getCustomerId());

        } else {
            log.warn("OrderSaga: Order for payment ID {} (Order ID: {}) not found or not in NOT_PROCESSED status. Skipping processing.", paymentResponse.getPaymentId(), paymentResponse.getOrderId());
        }
    }

    @Transactional
    private void publishProductStateUpdate(Long orderId) {
        log.debug("OrderSaga: Preparing to publish product state update for order ID {}.", orderId);
        List<OrderProduct> orderProducts = getOrderProductsByOrder(orderId);

        if (orderProducts.isEmpty()) {
            log.warn("OrderSaga: No order products to update for order ID {}. Skipping product state update.", orderId);
            return;
        }

        ProductUpdateRequest request = new ProductUpdateRequest();
                
        // ProductUpdateRequest의 orderProducts 리스트가 null일 수 있으므로 초기화
        // [JA] ProductUpdateRequest の orderProducts リストは null の可能性があるので初期化する
        if (request.getOrderProducts() == null) {
            request.setOrderProducts(new ArrayList<>());
        }

        for (OrderProduct orderProduct : orderProducts) {
            OrderProductRequest item = mappingOrderProductToOrderProductRequest(orderProduct);
            request.getOrderProducts().add(item);
            log.debug("OrderSaga: Added order product {} (Qty: {}) to ProductUpdateRequest.", orderProduct.getProductId(), orderProduct.getQty());
        }

        // 멱등성 키: 재고 차감은 주문당 1회 — Kafka 재전달/사가 재실행 시 catalog 가 이 키로 중복 스킵 (docs/11 §4)
        // [JA] 冪等キー: 在庫の減算は注文ごとに 1 回 — Kafka の再配信や Saga の再実行時には
        //      catalog がこのキーで重複をスキップする (docs/11 §4)
        request.setEventId("order-" + orderId);
        outbox.saveEventOutbox(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT, request.getEventId(), request);
        log.info("OrderSaga: ProductUpdateRequest queued (outbox) for order ID {} with {} items.", orderId, request.getOrderProducts().size());
    }

    /**
     * 장바구니에서 <b>이 주문의 품목만</b> 뺀다.
     *
     * <p>🐛 예전에는 "이 사용자의 장바구니를 비워라" 였다. 이 이벤트는 결제 응답 뒤에 비동기로
     * 도착하므로, 그 사이 손님이 새로 담은 물건까지 함께 사라졌다 — 확정이 늦어질수록 더
     * 자주. 주문에 든 줄(상품·사이즈·색상·수량)을 실어 보내고 cart-service 가 그것만 뺀다.
     * 품목이 비어 오면(옛 발행자) cart-service 는 예전처럼 전부 비운다.
     *
     * <p>[JA] カートから<b>この注文の品目だけ</b>を取り除く。
     *
     * <p>🐛 以前は「このユーザーのカートを空にせよ」だった。このイベントは決済応答の後に非同期で
     * 届くため、その間に客が新たに入れた商品まで一緒に消えていた — 確定が遅れるほど頻繁に。
     * 注文に含まれる行(商品・サイズ・色・数量)を載せて送り、cart-service はそれだけを取り除く。
     * 品目が空で届いた場合(旧い発行元)、cart-service は従来どおり全部空にする。
     */
    @Transactional
    private void publishCartEmptyState(Order order) {
        Long customerId = order.getCustomerId();
        log.debug("OrderSaga: Preparing to publish cart empty state for customer ID {}.", customerId);
        List<com.project.common.message.dto.request.CartEmptyItem> items = new ArrayList<>();
        for (OrderProduct p : getOrderProductsByOrder(order.getOrderId())) {
            items.add(new com.project.common.message.dto.request.CartEmptyItem(
                    p.getProductId(), p.getSize(), p.getColorId(), p.getQty()));
        }
        // 사용자 토큰은 싣지 않는다 — cart 는 캐시 키가 사용자 id 라 토큰이 필요 없다(docs/54 §10.1).
        // [JA] ユーザートークンは載せない — cart はキャッシュキーがユーザー id なのでトークンが不要である(docs/54 §10.1)。
        CartEmptyRequest request = new CartEmptyRequest(customerId, true, items, order.getOrderId());
        outbox.saveEventOutbox(com.project.order_service.outbox.PaymentOutboxHelper.CART_EMPTY_EVENT, String.valueOf(customerId), request);
        log.info("OrderSaga: CartEmptyRequest queued (outbox) for customer ID {} with {} lines.", customerId, items.size());
    }

    private OrderProductRequest mappingOrderProductToOrderProductRequest(OrderProduct orderProduct) {
        log.debug("OrderSaga: Mapping OrderProduct (ID: {}, Qty: {}, Size: {}, ColorID: {}) to OrderProductRequest.",
                orderProduct.getProductId(), orderProduct.getQty(), orderProduct.getSize(), orderProduct.getColorId());
        OrderProductRequest request = new OrderProductRequest(
                orderProduct.getProductId(), orderProduct.getQty(),
                orderProduct.getSize(), orderProduct.getColorId()                
        );
        return request;
    }

    @Transactional
    private Order updateOrderStatus(Order order, OrderStatus orderStatus) {
        log.debug("OrderSaga: Updating order {} status from {} to {}.", order.getOrderId(), order.getOrderStatus(), orderStatus);
        if (orderStatus == OrderStatus.PAID) {
            // 결제 시각도 함께 — 카드 · 간편결제 경로는 여기서만 PAID 가 된다
            statusChanger.markPaid(order, java.time.LocalDateTime.now(), "payment-saga");
        } else {
            statusChanger.change(order, orderStatus, "payment-saga");
        }
        order = orderRepository.save(order);
        cacheEvictor.evictOrderCaches(order.getOrderId(), "status→" + orderStatus);
        log.info("OrderSaga: Order {} status successfully updated to {}.", order.getOrderId(), orderStatus);
        return order;
    }

    /** 쿠폰 보상 — 주문 id 를 싣는다: coupon 은 이 주문이 쓴 쿠폰일 때만 되돌린다(예전에는 아무 주문이 쓴 쿠폰이나 되돌렸다). */
    private void publishCouponStateRollback(Order order) {
        Long customerId = order.getCustomerId();
        String couponName = order.getCouponApplied();
        log.debug("OrderSaga: Preparing to publish coupon rollback for customer ID {} and coupon {}.", customerId, couponName);
        if (couponName == null || couponName.trim().isEmpty()) {
            log.info("OrderSaga: No coupon applied for customer ID {}. Skipping coupon rollback.", customerId);
            return;
        }
        CouponRollbackRequest request = new CouponRollbackRequest(customerId, couponName, order.getOrderId());
        couponRollbackKafkaPublisher.publish(request);
        log.info("OrderSaga: CouponRollbackRequest published for customer ID {} with coupon {}.", customerId, couponName);
    }

    private void publicCartStateRollback(Long customerId) {
        log.debug("OrderSaga: Preparing to publish cart rollback for customer ID {}.", customerId);
        CartRollbackRequest request = new CartRollbackRequest(customerId, 1); // '1'은 롤백할 카트 아이템 수량인가? 확인 필요.
        // [JA] '1' はロールバックするカート品目の数量か? 要確認。
        cartRollbackKafkaPublisher.publish(request);
        log.info("OrderSaga: CartRollbackRequest published for customer ID {}.", customerId);
    }

    /**
     * 환불 뒤 보상 — 쿠폰을 돌려주고, 물건이 안 나간 주문이면 재고도 돌려준다.
     *
     * <p>결제 때 {@link #process} 가 한 것의 역이다. 재고는 <b>물건이 어디 있느냐</b>가 정한다:
     * 발송 전 환불({@code awaitingReturn=false})은 물건이 창고에 그대로라 지금 돌려주고,
     * 발송된 뒤의 환불은 반품이 <b>입고된 때</b>({@link #restock}) 돌려준다. 환불이 곧
     * 반품은 아니다.
     *
     * <p>멱등하다 — 이미 환불돼 있던 주문에 다시 와도 쿠폰 복원은 coupon-service 가
     * "USED 인 것만 NOT_USED 로" 라 두 번 받아도 결과가 같고, 재고 복원은
     * {@code stockRestoredAt} 과 catalog 의 {@code eventId} 가 각각 한 번으로 막는다.
     *
     * <p>[JA] 返金後の補償 — クーポンを戻し、商品が出荷されていない注文なら在庫も戻す。
     *
     * <p>決済時に {@link #process} が行ったことの逆である。在庫は<b>商品がどこにあるか</b>で決まる:
     * 発送前の返金({@code awaitingReturn=false})は商品が倉庫にそのまま残っているので今戻し、
     * 発送後の返金は返品が<b>入庫された時</b>({@link #restock})に戻す。返金がすなわち
     * 返品というわけではない。
     *
     * <p>冪等である — すでに返金済みの注文に再び来ても、クーポン復元は coupon-service が
     * 「USED のものだけ NOT_USED に」戻すので二度受けても結果は同じであり、在庫復元は
     * {@code stockRestoredAt} と catalog の {@code eventId} がそれぞれ一回に抑える。
     */
    public void compensateRefund(Order order) {
        if (order == null) {
            return;
        }
        // 쿠폰은 **전액이 다 돌아갔을 때만** 돌려준다. 일부만 환불된 주문은 여전히 그 쿠폰으로
        // 산 주문이다 — 돌려주면 같은 쿠폰을 두 번 쓰게 된다.
        // [JA] クーポンは**全額が返金された時だけ**戻す。一部だけ返金された注文は依然としてそのクーポンで
        //      買った注文である — 戻すと同じクーポンを二度使えてしまう。
        if (order.getOrderStatus() == OrderStatus.REFUNDED) {
            try {
                publishCouponStateRollback(order);
            } catch (RuntimeException e) {
                // 환불은 이미 반영됐다. 쿠폰 복원이 실패했다고 환불을 되돌릴 수는 없다 —
                // 로그로 남기고 운영자가 맞춘다.
                // [JA] 返金はすでに反映済みである。クーポン復元が失敗したからといって返金を取り消すことはできない —
                //      ログに残して運用者が整合させる。
                log.error("환불 보상(쿠폰 복원) 발행 실패 — 수동 확인 필요. orderId={} coupon={}",
                        order.getOrderId(), order.getCouponApplied(), e);
            }
        }
        // 재고는 품목의 수량 카운터가 정한다 — 발송 전에 환불된 수량은 "돌아온 것" 으로
        // 세어져 있고, 발송된 뒤의 수량은 입고될 때까지 카운터가 0 이라 보낼 것이 없다.
        // [JA] 在庫は品目の数量カウンタが決める — 発送前に返金された数量は「戻ってきたもの」として
        //      数えられており、発送後の数量は入庫されるまでカウンタが 0 なので送るものがない。
        restock(order);
    }

    /**
     * 입금 전 취소의 보상 — 쿠폰만 돌려준다. 재고는 차감된 적이 없고(결제 뒤에 차감한다),
     * 장바구니도 비워진 적이 없다. 무통장입금 취소·기한 만료가 부른다(docs/49 §11).
     *
     * <p>[JA] 入金前キャンセルの補償 — クーポンだけを戻す。在庫は減算されたことがなく(決済後に減算する)、
     * カートも空にされたことがない。銀行振込のキャンセル・期限切れから呼ばれる(docs/49 §11)。
     */
    public void compensateCancel(Order order) {
        if (order == null) {
            return;
        }
        try {
            publishCouponStateRollback(order);
        } catch (RuntimeException e) {
            log.error("취소 보상(쿠폰 복원) 발행 실패 — 수동 확인 필요. orderId={} coupon={}",
                    order.getOrderId(), order.getCouponApplied(), e);
        }
    }

    /**
     * 재고 복원 — {@link #publishProductStateUpdate} 의 역, 품목 수량 단위(V9).
     *
     * <p>품목마다 <b>돌아왔지만 아직 재고로도 폐기로도 처리되지 않은 수</b>
     * ({@code returned − restocked − discarded})를 모아 같은 {@code ProductUpdateRequest} 에
     * {@code restock=true} 로 보낸다. catalog 는 같은 항목을 부호만 바꿔 적용한다(사이즈
     * 수량 +, 판매량 −). 보낼 것이 없으면 아무것도 하지 않는다 — 발송된 뒤 환불된 주문은
     * 입고되기 전까지 정확히 이 상태다.
     *
     * <p>멱등 키는 {@code restock-<orderId>-u<누적 복원 수>} — 같은 상태에서 다시 불리면
     * 같은 키(재시도가 접힌다), 다음 입고 뒤에는 다른 키. 차감({@code order-<orderId>})과도
     * 다르다.
     *
     * <p><b>아웃박스 행과 표시가 한 트랜잭션이다.</b> 둘 다 남거나 둘 다 없다 — 릴레이가 행을 보낼 때까지 다시 시도하고, catalog 가
     * eventId 로 재전달을 버린다. 🐛 예전에는 Kafka 로 보내고(결과를 기다리지 않고) 표시해, 발행이 빠져도 "돌려놨다" 로 남았다.
     *
     * <p>실패는 로그로 끝난다 — 환불·입고는 이미 반영됐고, 재고 복원이 실패했다고
     * 그것을 되돌릴 수는 없다. 다른 보상과 같은 등급(최선 노력)이다.
     *
     * <p>[JA] 在庫復元 — {@link #publishProductStateUpdate} の逆で、品目の数量単位で行う(V9)。
     *
     * <p>品目ごとに<b>戻ってきたが、まだ在庫にも廃棄にも処理されていない数</b>
     * ({@code returned − restocked − discarded})を集め、同じ {@code ProductUpdateRequest} に
     * {@code restock=true} で送る。catalog は同じ項目を符号だけ反転して適用する(サイズ
     * 数量 +、販売数 −)。送るものがなければ何もしない — 発送後に返金された注文は
     * 入庫されるまでまさにこの状態である。
     *
     * <p>冪等キーは {@code restock-<orderId>-u<累積復元数>} — 同じ状態で再度呼ばれれば
     * 同じキー(リトライは一つに畳まれる)、次の入庫後は別のキーになる。減算({@code order-<orderId>})とも
     * 異なる。
     *
     * <p><b>アウトボックス行と記録が一つのトランザクションである。</b>両方残るか両方ない — リレーが送れるまで再試行し、catalog が
     * eventId で再配信を捨てる。以前は送って(結果を待たず)記録し、発行が抜けても「戻した」と残った。
     *
     * <p>失敗はログで終わる — 返金・入庫はすでに反映されており、在庫復元が失敗したからといって
     * それを取り消すことはできない。他の補償と同じ等級(ベストエフォート)である。
     */
    public void restock(Order order) {
        if (order == null) {
            return;
        }
        try {
            // 행과 표시를 한 트랜잭션으로 — 실패하면 둘 다 없고(롤백), 예외는 여기서 끝난다(환불 · 입고는 이미 반영됐다).
            // 프록시의 @Transactional 이 아니라 템플릿인 것은 compensateRefund 안에서도 불리기 때문이다(자기 호출은 프록시를 지나지 않는다).
            // [JA] 行と記録を一つのトランザクションで — 失敗すれば両方ない。テンプレートなのは compensateRefund 内の自己呼び出しのため。
            if (transactions == null) {
                restockInTransaction(order);
            } else {
                transactions.executeWithoutResult(status -> restockInTransaction(order));
            }
        } catch (RuntimeException e) {
            log.error("재고 복원 발행 실패 — 수동 확인 필요. orderId={}", order.getOrderId(), e);
        }
    }

    private void restockInTransaction(Order order) {
        List<OrderProduct> orderProducts = getOrderProductsByOrder(order.getOrderId());
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setOrderProducts(new ArrayList<>());
        List<OrderProduct> pendingLines = new ArrayList<>();
        int restockedSoFar = 0;
        int pendingUnits = 0;
        for (OrderProduct p : orderProducts) {
            restockedSoFar += p.getRestockedQty();
            int pending = p.pendingRestock();
            if (pending <= 0) {
                continue;
            }
            request.getOrderProducts().add(new OrderProductRequest(
                    p.getProductId(), pending, p.getSize(), p.getColorId()));
            pendingLines.add(p);
            pendingUnits += pending;
        }
        if (pendingUnits == 0) {
            log.debug("재고 복원: 보낼 수량이 없다. orderId={}", order.getOrderId());
            return;
        }
        request.setRestock(true);
        request.setEventId("restock-" + order.getOrderId() + "-u" + (restockedSoFar + pendingUnits));
        // 아웃박스 행과 복원 표시가 한 트랜잭션이다 — 예전에는 보내고(결과를 기다리지 않고) 표시해, 발행이 빠져도 "돌려놨다" 로 남았다.
        // [JA] アウトボックス行と復元の記録が一つのトランザクション — 以前は送って(結果を待たず)記録し、発行が抜けても「戻した」と残った。
        outbox.saveEventOutbox(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT,
                request.getEventId(), request);

        int markedLines = 0;
        for (OrderProduct p : pendingLines) {
            markedLines += orderProductRepository.addRestocked(p.getOrderProductId(), p.pendingRestock());
        }
        if (markedLines != pendingLines.size()) {
            // 품목 카운터의 조건부 UPDATE 가 일부 실패했다(동시에 다른 입고 · 복원) — 보내면 실제보다 많이 돌려준다. 행째 되돌린다.
            // [JA] 品目カウンタの条件付き UPDATE が一部失敗した — 送ると実際より多く戻す。行ごと取り消す。
            throw new IllegalStateException("재고 복원 표시가 일부 실패했다 — 아웃박스 행째 되돌린다. orderId="
                    + order.getOrderId() + " lines=" + pendingLines.size() + " marked=" + markedLines);
        }
        orderRepository.markStockRestored(order.getOrderId(), java.time.LocalDateTime.now());
        cacheEvictor.evictOrderCaches(order.getOrderId(), "stockRestored");
        log.info("재고 복원 발행: orderId={} eventId={} units={} lines={}",
                order.getOrderId(), request.getEventId(), pendingUnits, pendingLines.size());
    }

    // 결제 취소 -> 주문상태
    // [JA] 決済キャンセル -> 注文ステータス
    @Transactional
    public void rollback(PaymentResponse paymentResponse) {
        log.info("OrderSaga: Starting rollback for PaymentResponse: {}", paymentResponse);

        Order order = getOrderByOrderStatus(paymentResponse.getOrderId(), OrderStatus.NOT_PROCESSED); // 여기도 orderStatus 확인 필요. 이미 PAID 상태일 수도 있음.
        // [JA] ここも orderStatus の確認が必要。すでに PAID の可能性がある。

        if (null != order) {
            log.info("OrderSaga: Order {} found for rollback with status NOT_PROCESSED. Proceeding.", order.getOrderId());
            
            // 1. 주문 상태 업데이트 (NOT_PROCESSED -> CANCELED)
            // [JA] 1. 注文ステータスを更新 (NOT_PROCESSED -> CANCELED)
            updateOrderStatus(order, OrderStatus.CANCELED);
            log.info("OrderSaga: Order {} status updated to CANCELED.", order.getOrderId());

            // 2. 장바구니 롤백 메시지 발행
            // [JA] 2. カートのロールバックメッセージを発行
            publicCartStateRollback(order.getCustomerId());
            log.info("OrderSaga: Published cart rollback state for customer {}.", order.getCustomerId());

            // 3. 쿠폰 롤백 메시지 발행
            // [JA] 3. クーポンのロールバックメッセージを発行
            publishCouponStateRollback(order);
            log.info("OrderSaga: Published coupon rollback state for customer {}.", order.getCustomerId());

            // 4. 결제가 실패했다 — 잡아 둔 재고를 푼다(docs/59). 커밋된 뒤에 — 롤백된 취소로 풀면 안 된다.
            // [JA] 4. 決済が失敗した — 仮押さえした在庫を解放する(docs/59)。コミット後に行う —
            //      ロールバックされたキャンセルで解放してはならない。
            if (stockHold != null) {
                stockHold.releaseAfterCommit(order.getOrderId());
            }

        } else {
            log.warn("OrderSaga: Order for payment ID {} (Order ID: {}) not found or not in NOT_PROCESSED status for rollback. Skipping rollback.", paymentResponse.getPaymentId(), paymentResponse.getOrderId());
            // 만약 이미 PAID 상태인데 롤백 요청이 들어온 경우 처리 로직 추가 필요
            // [JA] すでに PAID 状態なのにロールバック要求が来た場合の処理ロジックを追加する必要がある
            Order paidOrder = getOrderByOrderStatus(paymentResponse.getOrderId(), OrderStatus.PAID);
            if (paidOrder != null) {
                log.warn("OrderSaga: Order {} is already PAID. Consider implementing specific refund/cancellation logic.", paidOrder.getOrderId());
                // 여기에 환불/취소 처리를 위한 추가 로직 또는 예외 처리를 할 수 있습니다.
                // [JA] ここに返金/キャンセル処理のための追加ロジックや例外処理を入れることができる。
            }
        }
    }
}