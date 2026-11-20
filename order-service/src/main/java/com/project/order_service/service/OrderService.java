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
    // [JA] 管理者一覧の表示用メールのみ — 本人判定には使わない(それは User-Id ヘッダーである)。
    private final UserServiceClient userServiceClient;
    private final OrderPricing orderPricing;
    /** 결제 전 재고 예약(docs/59). */
    // [JA] 決済前の在庫予約(docs/59)。
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
     *
     * <p>[JA] 注文を作る。
     *
     * <h3>🔴 リモート呼び出しはトランザクションの<b>外</b>で(docs/37 #5)</h3>
     *
     * <p>以前は {@code OrderDataService.createOrder} がトランザクションを開いた直後に
     * cart-service を呼んでいた。DB コネクションを握ったまま他サービスを待つ形のため、<b>注文作成の遅延が
     * cart-service の遅延に正比例</b>し、cart-service が止まると order-service の DB
     * プールが先に枯渇した。
     *
     * <p>docs/40 #10(金額はサーバーが決める)を直す際にクーポン見積もり呼び出しが一つ増え、<b>その窓が倍になった。</b>
     * Feign の既定は接続 3 秒・読み取り 5 秒なので、最悪コネクション一つを 16 秒まで握り得た。
     *
     * <p>そこで二つの呼び出しをここに上げた。このメソッドは {@code NOT_SUPPORTED} なのでトランザクションは開かない —
     * クラスに付いた {@code @Transactional} をこのメソッドだけで切っている。DB 処理は {@code
     * cahcedOrderService.createOrder} が<b>短く</b>開く。
     *
     * <p>⚠️ ここに DB 処理を足すとトランザクションなしで動く。保存は下の段階に置くこと。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Order createOrder(OrderRequest request, Long customerId) {
        return createOrder(request, customerId, null);
    }

    /** 멱등 키로 쓸 수 있는 모양 — 화면은 UUID 를 보낸다. 그 밖은 키가 없는 것으로 본다(오래된 화면 · 지어낸 값). */
    // [JA] 冪等キーとして使える形 — 画面は UUID を送る。それ以外はキーなしとみなす(古い画面・でっち上げの値)。
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
        // [JA] ① カート — 何を注文したかの根拠である。本文の products は読まない。
        CartDto latestCart = objectMapper.convertValue(
                cartServiceClient.getCart(customerId).getBody(), CartDto.class);

        // 🐛 예전에는 여기서 null 을 돌려줬다. 컨트롤러는 그 null 에 대고
        //    newOrder.getOrderId() 를 불렀으므로 NPE 가 나고, 그것이
        //    catch (RuntimeException) 에 걸려 **본문이 JVM 의 NPE 문구인 400** 이 됐다
        //    ("Cannot invoke ... because \"newOrder\" is null"). 사용자는 원인을 알 수
        //    없고 로그에도 아무것도 남지 않았다. 실패는 이유를 달고 던진다.
        // [JA] 🐛 以前はここで null を返していた。コントローラーはその null に対して newOrder.getOrderId() を呼んだため
        //      NPE になり、それが catch (RuntimeException) に捕まって**本文が JVM の NPE 文言の 400**
        //      になった("Cannot invoke ... because \"newOrder\" is null")。ユーザーは原因が分からず、
        //      ログにも何も残らなかった。失敗は理由を付けて投げる。
        if (latestCart == null || latestCart.getProducts() == null || latestCart.getProducts().isEmpty()) {
            log.warn("주문 생성 실패: 장바구니가 비어 있다");
            throw new OrderDomainException("장바구니가 비어 있습니다. 상품을 담은 뒤 다시 시도해 주세요.");
        }
        fillMissingProductIds(latestCart);
        requireOrderableQuantities(latestCart);

        // ② 금액 — 서버가 정한다(docs/40 #10). 쿠폰 견적 Feign 호출이 여기 들어 있다.
        // [JA] ② 金額 — サーバーが決める(docs/40 #10)。クーポン見積もりの Feign 呼び出しがここに入っている。
        OrderPricing.Amounts amounts =
                orderPricing.priceOrder(latestCart, request.getCouponApplied(), customerId);

        // ③ 주문자는 게이트웨이가 검증한 토큰의 uid(User-Id 헤더)다. 예전에는 이메일로 고객 사본을 찾고, 없으면
        //    user-service 에 물어 사본을 메꿨다(백필) — 이미 아는 답을 얻으려고 복제·이벤트·동기 호출을 두고 있었다.
        //    docs/48 §6-①. 이메일은 장바구니·쿠폰 서비스가 그것을 키로 받으므로 위 두 호출에만 쓴다.
        // [JA] ③ 注文者はゲートウェイが検証したトークンの uid(User-Id ヘッダー)である。以前はメールで顧客コピーを探し、なければ
        //      user-service に問い合わせてコピーを埋めていた(バックフィル)—
        //      既に分かっている答えを得るために複製・イベント・同期呼び出しを抱えていた。docs/48 §6-①。
        //      メールはカート・クーポンサービスがそれをキーに受け取るので、上の二つの呼び出しにだけ使う。

        // ④ 여기서부터 DB. 원격 호출이 끝난 뒤라 트랜잭션이 짧다.
        // [JA] ④ ここから DB。リモート呼び出しが終わった後なのでトランザクションが短い。
        Order created;
        try {
            created = key == null ? cahcedOrderService.createOrder(request, latestCart, amounts, customerId)
                    : cahcedOrderService.createOrder(request, latestCart, amounts, customerId, key);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 같은 키의 요청이 동시에 왔다 — 앞선 쪽이 만들었다(부분 유니크, V26). 그 주문을 돌려준다.
            // [JA] 同じキーのリクエストが同時に来た — 先行した側が作った(部分ユニーク、V26)。その注文を返す。
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
        // [JA] ⑤ 在庫を確保する(docs/59)— 注文 id が予約のキーなので保存後である。
        //      足りなければ今作った注文を捨てて理由を返す(決済前なので捨てられる)。catalog に届かなければ予約なしで進む —
        //      決済後の不足分返金が受け止める。カートは決済後にようやく空になる — 注文して戻り再度注文すると、前の注文の予約がこの注文を塞ぐ。
        //      同じ顧客の先行する決済前注文の予約を先に解く(入金待ちはそのまま)。
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
            // [JA] リクエストが不正 — 在庫チェックを飛ばして決済に送らない(以前は「届かない」と同様に予約なしで進めていた)。
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
    // [JA] 1 行の数量上限 — カート(CartService.MAX_LINE_QTY)と同じ。
    static final int MAX_LINE_QTY = 1000;

    static final String UNORDERABLE_MESSAGE = "주문할 수 없는 수량이 있습니다. 장바구니에서 수량(1 ~ 1000)을 조정해 주세요.";

    /**
     * 장바구니의 줄 수량은 1 ~ {@link #MAX_LINE_QTY} — 금액을 정하기 전에 본다.
     *
     * <p>🐛 예전에는 보지 않았다 — 장바구니의 {@code qty=-5} 줄이 그대로 주문 줄이 되어 주문 금액이 다른 줄의 값을 깎았고(가격 ×
     * 음수), 재고 예약의 거절도 "닿지 못함" 으로 읽혀 그대로 결제까지 갔다. 장바구니도 이제 막지만(checkedQty) 옛 장바구니 ·
     * 캐시 사본이 남아 있다.
     *
     * <p>[JA] カートの行数量は 1 〜 {@link #MAX_LINE_QTY} — 金額を決める前に見る。
     *
     * <p>[JA] 以前は見ていなかった — カートの {@code qty=-5} の行がそのまま注文行になり、注文金額が他の行の値を削り(価格 ×
     * 負数)、在庫予約の拒否も「届かない」と読まれてそのまま決済まで進んだ。カートも今は防ぐが(checkedQty)古いカート ·
     * キャッシュのコピーが残っている。
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
     *
     * <p>[JA] 商品 id が空のカート行を埋める。cart-service は {@code productId} を載せるが、
     * そのフィールドができる前に Redis に入ったカートのコピーにはない。空なら行 id で cart に問い合わせる(トランザクション外 —
     * このメソッドの呼び出し側がそうである)。
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
     * <p>[JA] 決済画面を開く<b>直前に</b>在庫予約を取り直す(docs/59 §8)。
     *
     * <p>注文作成時に 30 分確保する。その後決済画面に長く留まると予約が解け、その間に他人が買うと決済後の不足分返金に落ちる —
     * カードなら自動返金だが、<b>PortOne の仮想口座は口座が PG の決済画面内で発行され</b>、
     * こちらが止める隙がなかった(入金されると「手動返金が必要」)。そこで決済画面を開く瞬間に取り直す: 足りなければ決済画面を開かず理由を伝える。
     *
     * <p>決済前(NOT_PROCESSED)の注文のみ。それ以外の状態・予約機能がない・catalog
     * に届かない場合は何も止めない(fail-open、注文作成と同じ)。🔒 注文者のみ — でなければ null(404、
     * 他人の注文かどうかを知らせない)。
     *
     * <p>クーポンがあればこの注文に確保する({@link OrderCouponClaim})—
     * 同じクーポンで別の注文が決済中なら投げる(fail-closed)。
     *
     * @return {@code stockHoldUntil}(잡았으면) 을 담은 맵. 주문이 없거나 남의 것이면 null
     *        [JA] {@code stockHoldUntil}(確保した場合)を入れたマップ。注文がない・他人のものなら null
     * @throws OrderDomainException 모자라다 — 문구는 주문 생성 때와 같은 모양("결제를 시작하지 못했습니다")
     *        [JA] 足りない — 文言は注文作成時と同じ形(「決済を開始できませんでした」)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public java.util.Map<String, Object> renewCheckoutHold(String trackingId, Long requesterId) {
        return renewCheckoutHold(trackingId, requesterId, null);
    }

    /**
     * @param attempt 화면이 창마다 만든 결제 시도 키(Payment-Attempt 헤더, V27) — 다른 창의 시도가 살아 있으면 던진다. 없으면(옛 화면)
     *                시도는 적지 않는다. 이미 결제가 붙은 주문은 키와 상관없이 던진다.
     *        [JA] 画面がウィンドウごとに作った決済試行キー(Payment-Attempt ヘッダー、V27)— 別ウィンドウの試行が生きていれば投げる。なければ(古い画面)試行は記録しない。既に決済が付いた注文はキーに関係なく投げる。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public java.util.Map<String, Object> renewCheckoutHold(String trackingId, Long requesterId, String attempt) {
        Order order = cahcedOrderService.getOrderByTrackingId(trackingId);
        if (order == null || requesterId == null || !requesterId.equals(order.getCustomerId())) {
            return null;
        }
        // 주문 하나에 결제창 하나(V27) — 이미 결제됐거나 다른 창이 결제 중이면 여기서 멈춘다(재고 · 쿠폰을 건드리기 전에).
        // [JA] 注文一つに決済画面一つ(V27)— 既に決済済み、または別ウィンドウが決済中ならここで止まる(在庫・クーポンに触れる前に)。
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
        // [JA] クーポンをこの注文に確保する(coupon V7)— 同じクーポンで別の注文が決済中なら決済画面を開かない。在庫と違い fail-closed。
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
    // [JA] 決済画面一つの寿命(分、V27)— この間、他のウィンドウは決済を開始できない。決済画面を閉じて新しいタブで開き直すとこの分だけ待つ。
    @org.springframework.beans.factory.annotation.Value("${order.payment-attempt.window-minutes:10}")
    private long paymentAttemptWindowMinutes = 10;

    /** 결제 시작 때 쿠폰 잡기(coupon V7). 세터로 받는다 — 생성자를 쓰는 곳을 늘리지 않으려고. 없으면(테스트) 건드리지 않는다. */
    // [JA] 決済開始時のクーポン確保(coupon V7)。セッターで受け取る — コンストラクタを使う箇所を増やさないため。なければ(テスト)触らない。
    private OrderCouponClaim couponClaim;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCouponClaim(OrderCouponClaim couponClaim) {
        this.couponClaim = couponClaim;
    }

    /**
     * 모자란 줄을 사람이 읽을 문장으로 — "티셔츠(M) 남은 수량 1개" 처럼. 상품 이름은 주문에 적힌 것(장바구니에서 온 것)을
     * 쓴다 — catalog 는 id 만 돌려준다.
     *
     * <p>[JA] 足りない行を人が読める文に — 「Tシャツ(M) 残り 1 個」のように。商品名は注文に記録されたもの(カート由来)を使う —
     * catalog は id しか返さない。
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
    // [JA] @Cacheable: orderId と email をキーに注文詳細をキャッシュする。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만 ／ [JA] 色(catalog)を呼ぶ — トランザクションは OrderDataService.load* の中だけ
    public OrderDto getOrder(Long orderId, Long requesterId, String filter) {
        log.debug("Fetching order details for order ID: {}, filter: {}", orderId, filter);
        return cahcedOrderService.getOrder(orderId, requesterId, filter);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만 ／ [JA] 色(catalog)を呼ぶ — トランザクションは OrderDataService.load* の中だけ
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
     *
     * <p>[JA] 注文一覧 — 状態で絞ったカーソルページング(段階的ロード)。
     *
     * <p>{@link #getOrders} の三つの問題(無制限・N+1・絞った結果に null が混ざる)をなくした経路である。詳しい説明は
     * {@code OrderDataService.getOrdersPage} のコメントにある。
     *
     * <p><b>キャッシュしない。</b> カーソルとフィルターごとにキーが分かれてヒット率が低く、注文一覧はたった今の注文が見えるべき画面である。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만 ／ [JA] 色(catalog)を呼ぶ — トランザクションは OrderDataService.load* の中だけ
    public Page<OrderDto> getOrdersPage(Long customerId, String filter, Long cursor, int size) {
        return cahcedOrderService.getOrdersPage(customerId, filter, cursor, size);
    }

     // 페이지별로 주문 목록을 조회하는 새로운 메서드    
     // [JA] ページごとに注文一覧を取得する新しいメソッド
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만 ／ [JA] 色(catalog)を呼ぶ — トランザクションは OrderDataService.load* の中だけ
    public Page<OrderDto> getOrdersByCustomerIdWithPagination(Long customerId, int page, int size) {
        
        return cahcedOrderService.getOrdersByCustomerIdWithPagination(customerId, page, size);
    }

    // --- 점진적 로딩(커서 기반 페이징) 메서드 추가 ---
    // [JA] --- 段階的ロード(カーソルベースのページング)メソッド追加 ---
    /**
     * 고객 ID로 주문 목록을 커서 기반으로 조회합니다 (점진적 로딩).
     *
     * <p>[JA] 顧客 ID で注文一覧をカーソルベースで取得する(段階的ロード)。
     *
     * @param customerId 조회할 고객의 ID
     *        [JA] 取得する顧客の ID
     * @param cursorId 마지막으로 조회한 주문의 ID (첫 페이지 조회 시 null)
     *        [JA] 最後に取得した注文の ID(最初のページ取得時は null)
     * @param size 페이지당 주문 개수
     *        [JA] 1 ページあたりの注文数
     * @return OrderDto 목록과 다음 커서 ID
     *        [JA] OrderDto の一覧と次のカーソル ID
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)   // 색상(catalog)을 부른다 — 트랜잭션은 OrderDataService.load* 안에서만 ／ [JA] 色(catalog)を呼ぶ — トランザクションは OrderDataService.load* の中だけ
    public Page<OrderDto> getOrdersByCustomerIdWithCursor(Long customerId, Long cursorId, int size) {
        
        return cahcedOrderService.getOrdersByCustomerIdWithCursor(customerId, cursorId, size);
    }


   
    // 결제 완료 처리 메서드    
    // [JA] 決済完了処理メソッド
    /**
     * 확인된 결제를 주문에 반영한다 — PayPal · Stripe 공통.
     *
     * <p>제공자별 조회는 {@code PaymentGateway} 뒤에 있고, 여기 오는 것은 "어느 제공자의
     * 어떤 참조" 두 값뿐이다.
     *
     * <p>[JA] 確認済みの決済を注文に反映する — PayPal・Stripe 共通。
     *
     * <p>プロバイダーごとの照会は {@code PaymentGateway} の裏にあり、
     * ここに来るのは「どのプロバイダーのどの参照」の二つの値だけである。
     */
    public void recordPayment(String trackingId, PaymentProvider provider, String providerPaymentId,
            Long requesterId) {
        cahcedOrderService.recordPayment(trackingId, provider, providerPaymentId, requesterId);
    }

    /**
     * 결제 취소. 결제와 <b>다른 연산</b>이므로 다른 메서드다 — 왜 그런지는
     * {@code OrderDataService.cancelPaypalPayment} 주석에 있다.
     *
     * <p>[JA] 決済キャンセル。決済とは<b>別の演算</b>なので別メソッドである — 理由は {@code
     * OrderDataService.cancelPaypalPayment} のコメントにある。
     */
    public void cancelPaypalPayment(PaypalPaymentRequest request, Long requesterId) {
        cahcedOrderService.cancelPaypalPayment(request, requesterId);
        // 결제를 접었다 — 잡아 둔 재고를 푼다(docs/59). 취소가 거절됐으면(결제된 주문 등) 상태가 CANCELED 가 아니라 풀지 않는다.
        // [JA] 決済を取りやめた — 確保していた在庫を解く(docs/59)。キャンセルが拒否された場合(決済済み注文など)は状態が CANCELED
        //      ではないので解かない。
        Order after = cahcedOrderService.getOrderByTrackingId(request.getTrackingId());
        if (after != null && after.getOrderStatus() == com.project.common.constants.OrderStatus.CANCELED) {
            stockHold.releaseAfterCommit(after.getOrderId());
            // 결제 시작 때 이 주문에 잡은 쿠폰을 푼다(coupon V7 — coupon-rollback 이 이 주문의 표시만 지운다). 예전에는 보상이 없어도
            // 됐다(쿠폰은 결제 확정 때만 쓰였다). 이제는 없으면 PayPal 창을 닫고 다시 주문할 때 30분 동안 같은 쿠폰이 막힌다.
            // [JA] 決済開始時にこの注文に確保したクーポンを解く(coupon V7 — coupon-rollback がこの注文の印だけを消す)。
            //      以前は補償がなくてもよかった(クーポンは決済確定時にしか使われなかった)。今はないと、PayPal ウィンドウを閉じて再注文するとき 30
            //      分間同じクーポンが塞がる。
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
     *
     * <p>[JA] 返金。
     *
     * <p>ルールとプロバイダー呼び出しは {@code OrderDataService.refundOrder}(自前のトランザクション)が行う。
     * ここではその後に<b>補償</b>を発行する — クーポン復元。トランザクション外で行う理由は createOrder
     * と同じ(NOT_SUPPORTED のコメント): Kafka 発行を DB トランザクション内に置くと、
     * コミットが失敗してもメッセージは既に出ている。返金自体はアウトボックスで出るので安全で、クーポン復元は {@code OrderSaga}
     * の他の補償と同じ等級(ベストエフォート)である。
     */
    /**
     * 관리자 주문 목록. 권한은 컨트롤러가 이미 확인했다.
     *
     * <p>목록은 트랜잭션 안에서 만들고({@code OrderDataService}), 표시용 고객 이메일은 <b>그 밖에서</b> user-service 에
     * 한 번 물어 붙인다 — 예전에는 고객 사본(orders.customer)에서 읽었다(docs/48 §6-①). 트랜잭션 밖이어야 하는 이유는
     * createOrder 와 같다(원격 호출 동안 DB 커넥션을 쥐지 않는다, docs/37 #5).
     *
     * <p>[JA] 管理者の注文一覧。権限はコントローラーが既に確認した。
     *
     * <p>一覧はトランザクション内で作り({@code OrderDataService})、表示用の顧客メールは<b>その外で</b>
     * user-service に一度問い合わせて付ける — 以前は顧客コピー(orders.customer)から読んでいた(docs/48
     * §6-①)。トランザクション外である理由は createOrder と同じ(リモート呼び出しの間 DB コネクションを握らない、docs/37
     * #5)。
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
    // [JA] 顧客 id でメールを一括で受け取って付ける。失敗したらメールなしにする — 管理者一覧が user-service
    //      のせいで止まってはならない。
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
        // [JA] 客に「返金された」を知らせる — 部分返金はこれしかない(OrderRefundedKafkaPublisher のコメント)。
        orderRefundedPublisher.publishLatest(refunded);
        return refunded;
    }

    /**
     * 반품 입고 (관리자). 기록은 {@code OrderDataService.receiveReturn}(자기 트랜잭션), 재고
     * 복원 이벤트는 그 뒤 트랜잭션 밖에서 — 환불과 같은 모양이다.
     *
     * <p>[JA] 返品入庫(管理者)。記録は {@code OrderDataService.receiveReturn}(自前のトランザクション
     * )、在庫復元イベントはその後トランザクション外で — 返金と同じ形である。
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
     *
     * <p>[JA] 注文の束に出てくる色を catalog から<b>一度に</b>取得する。
     *
     * <p>以前は {@link #convertToOrderDto} が注文商品ごとに {@code getColorInfo} を呼んでいた —
     * 注文一覧 1 ページ(注文 10 件 × 商品 3 個)で Feign 往復 30 回。ページ単位で色 id を集めて一度に受け取り、
     * 変換はそのマップを受ける(docs/38 §1.13)。見つからない色はマップになく、その商品の color は以前どおり null である。
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
        // [JA] 色は装飾である — catalog が揺らいだら(再デプロイ・一時障害)色なしで注文を見せる。以前はこの例外が注文照会を丸ごと 400 にし、
        //      コントローラーがログなしで握りつぶして痕跡もなかった。
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
    // [JA] 注文一つ — 色のバッチをこの注文一つについて受け取る。一覧はページ単位で {@link #fetchColors} を先に呼ぶこと。
    static public OrderDto convertToOrderDto(Order data, ObjectMapper objectMapper, CatalogServiceClient catalogServiceClient, OrderAddressRepository orderAddressRepository ) {
        return convertToOrderDto(data, fetchColors(List.of(data), catalogServiceClient));
    }

       // 도메인 엔티티를 DTO로 변환하는 공통 메서드
       // [JA] ドメインエンティティを DTO に変換する共通メソッド
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
                            // [JA] 🐛 size は DTO にあったが埋めたことがない — 注文履歴でサイズが空に見えていた。
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
                // [JA] 🐛 shippingPrice は DTO にフィールドがあるのに**移す行がなかった。** 画面は常に 0 を受け取っていた —
                //      エンティティ側も埋められたことがなく(常に 0)目立たなかった。二つの穴が互いを隠していた。
                .shippingPrice(data.getShippingPrice())
                .total(data.getTotal())
                // 청구 통화·금액 — 결제 화면이 제공자에게 넘길 값이다(total 은 상점 통화).
                // [JA] 請求通貨・金額 — 決済画面がプロバイダーに渡す値である(total はストア通貨)。
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