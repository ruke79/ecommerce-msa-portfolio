package com.project.order_service.outbox;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Transactional
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxHelper {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    // 커밋되면 폴링을 기다리지 않고 바로 릴레이한다 — 결제 사가의 첫 번째 대기(최대 10초)를 없앤다(docs/56).
    // [JA] コミットされたらポーリングを待たずにすぐリレーする — 決済 Saga の一つ目の待ち(最大 10 秒)をなくす(docs/56)。
    private final com.project.common.outbox.OutboxRelayTrigger relayTrigger;

    
    public void save(PaymentOutboxEvent outboxEvent) {
       // 새 행일 때만 추적 문맥을 남긴다. 같은 save() 가 릴레이의 상태 갱신에도 쓰이는데, 그때 남기면
       // 스케줄러의 문맥으로 덮어쓴다(docs/51 §4e).
       // [JA] 新しい行のときだけトレースのコンテキストを残す。同じ save() がリレーの状態更新にも使われるため、
       //      そのときに残すとスケジューラのコンテキストで上書きしてしまう(docs/51 §4e)。
       if (outboxEvent.isNew() && outboxEvent.getTraceContext() == null) {
           outboxEvent.setTraceContext(outboxTraceContext.capture());
       }
       PaymentOutboxEvent response = paymentOutboxRepository.save(outboxEvent);
       if (response == null) {
           log.error("Could not save OutboxEvent with outbox id: {}", outboxEvent.getId());
           throw new OrderDomainException("Could not save OutboxEvent with outbox id: " +
           outboxEvent.getId());
       }
       log.info("OutboxEvent saved with outbox id: {}", outboxEvent.getId());
    }

    @Transactional(readOnly = true)
    public Optional<List<PaymentOutboxEvent>> getPaymentOutboxEventByOutboxStatus(OutboxStatus status) {

        return paymentOutboxRepository.findByOutboxStatus(status);        
    }

    /**
     * 발행 대기 이벤트를 <b>선점</b>해 온다 ({@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>읽기 전용이 아니다 — 행을 잠그고, 호출한 트랜잭션이 끝날 때까지 잡고 있어야 한다.
     * 그래야 다른 레플리카가 같은 행을 집어 중복 발행하는 일이 없다.
     *
     * <p>[JA] 発行待ちイベントを<b>先取り</b>して取ってくる({@code FOR UPDATE SKIP LOCKED})。
     *
     * <p>[JA] 読み取り専用ではない — 行をロックし、呼び出したトランザクションが終わるまで握っておく必要がある。
     * そうすることで、別のレプリカが同じ行を拾って二重に発行することがなくなる。
     */
    public List<PaymentOutboxEvent> claimPaymentOutboxEvents(OutboxStatus status, Pageable pageable) {

        return paymentOutboxRepository.claimByOutboxStatus(status, pageable);
    }



    
    public void savePaymentOutbox(PaymentRequest request, OutboxStatus status)  {

        String payload = this.createPayload(request);

        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(Long.toString(request.getOrderId()), "Order", "payment-request", payload, status);

        this.save(outboxEvent);
        relayOnCommit(status);
    }

    /**
     * 발행 대기 행을 썼으면 커밋 뒤 릴레이를 예약한다. 상태 갱신용 {@link #save} 에서 부르지 않는 것은
     * 의도다 — 릴레이 자신이 거기서 다음 릴레이를 부르게 된다.
     *
     * <p>[JA] 発行待ちの行を書いたら、コミット後のリレーを予約する。状態更新用の {@link #save} から呼ばないのは
     * 意図的である — そうするとリレー自身がそこで次のリレーを呼ぶことになる。
     */
    private void relayOnCommit(OutboxStatus status) {
        if (status == OutboxStatus.STARTED) {
            relayTrigger.afterCommit();
        }
    }


      private String createPayload(PaymentRequest payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Could not create PayRequest object for order id: {}",
            payload.getOrderId(), e);
            throw new OrderDomainException("Could not create PayRequest object for order id: " +
            payload.getOrderId(), e);
        }
    }



    /** 결제 확정 차감 · 반품 입고 복원(catalog) — 토픽 product-update. */
    // [JA] 決済確定時の在庫減算 · 返品入庫時の復元(catalog) — トピック product-update。
    public static final String PRODUCT_UPDATE_EVENT = "product-update";

    /** 결제 뒤 장바구니에서 주문한 줄 빼기(cart) — 토픽 cart-empty. */
    // [JA] 決済後、買い物かごから注文した明細行を外す(cart) — トピック cart-empty。
    public static final String CART_EMPTY_EVENT = "cart-empty";

    /** 배송 수량 다시 맞추기(delivery) — 토픽 delivery-resync, 키 주문 id(5단계 15 — 재고 부족이 배송 생성보다 늦게 적혔을 때).
     *  [JA] 配送数量の合わせ直し(delivery) — トピック delivery-resync、キーは注文 id。 */
    public static final String DELIVERY_RESYNC_EVENT = "delivery-resync";

    /**
     * 사가의 후속 메시지(재고 차감 · 복원 · 장바구니 비우기)를 <b>상태 변경과 같은 트랜잭션</b>의 아웃박스 행으로 쓴다.
     *
     * <p>🐛 예전에는 Kafka 로 바로 보냈다(fire-and-forget — 결과는 로그뿐). 주문이 PAID 로 커밋된 뒤 브로커가 흔들려 발행이 빠지면
     * 재고가 차감되지 않은 채 남았고(다시 팔린다), 반품 입고는 "보냈다" 고 표시한 뒤 실제로는 안 갔을 수 있었다. 이제 행이 커밋돼야
     * 릴레이가 보내고, 보낼 때까지 다시 시도한다(at-least-once — 받는 쪽은 eventId 로 거른다).
     *
     * <p>[JA] Saga の後続メッセージ(在庫の減算 · 復元 · 買い物かごの整理)を<b>状態変更と同じトランザクション</b>の
     * アウトボックス行として書く。
     *
     * <p>[JA] 🐛 以前は Kafka へ直接送っていた(fire-and-forget — 結果はログだけ)。注文が PAID でコミットされた後に
     * ブローカーが不安定になり発行が抜けると、在庫が減らないまま残り(もう一度売れてしまう)、返品入庫は「送った」と
     * 表示した後に実際には届いていないことがあり得た。今は行がコミットされて初めてリレーが送り、送れるまで再試行する
     * (at-least-once — 受け取る側は eventId で重複を除く)。
     */
    /**
     * @param messageKey Kafka 메시지 키 — 행의 aggregate_id 에 둔다. 폴링 릴레이는 발행기가 같은 키를 다시 만들고, CDC 릴레이
     *                   (Debezium EventRouter)는 aggregate_id 를 키로 쓴다 — 둘 다 같은 파티션으로 간다(product-update 는 eventId,
     *                   cart-empty 는 사용자 id — docs/38 §1.15).
     *                   [JA] Kafka のメッセージキー — 行の aggregate_id に置く。ポーリングのリレーは発行側が同じキーを作り直し、
     *                   CDC のリレー(Debezium EventRouter)は aggregate_id をキーに使う — どちらも同じパーティションに行く
     *                   (product-update は eventId、cart-empty はユーザー id — docs/38 §1.15)。
     */
    public void saveEventOutbox(String eventType, String messageKey, Object payload) {
        String aggregateId = messageKey;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException(eventType + " 아웃박스 페이로드를 직렬화하지 못했다: " + aggregateId, e);
        }
        this.save(new PaymentOutboxEvent(aggregateId, "Order", eventType, json, OutboxStatus.STARTED));
        relayOnCommit(OutboxStatus.STARTED);
    }

    /** 아웃박스 이벤트 타입 — 릴레이가 이 이름으로 분기하고, Debezium EventRouter 는 이 이름을 토픽으로 쓴다(docs/76). */
    // [JA] アウトボックスのイベント種別 — リレーはこの名前で分岐し、Debezium EventRouter はこの名前をトピックに使う(docs/76)。
    public static final String ORDER_STATUS_EVENT = "order-status";

    /**
     * 주문 상태 변경(docs/76) — {@link com.project.order_service.service.OrderStatusChanger} 만 부른다. 행 id 가 곧
     * 이벤트 id 다(릴레이가 Avro 의 eventId 에 넣는다 → 읽는 쪽이 재발행을 거른다).
     * [JA] 注文状態変更(docs/76) — {@link com.project.order_service.service.OrderStatusChanger} だけが呼ぶ。行 id が
     * そのままイベント id になる(リレーが Avro の eventId に入れる → 読む側が再発行を除外する)。
     */
    public void saveOrderStatusOutbox(com.project.order_service.service.OrderStatusChanger.Payload payload, OutboxStatus status) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("주문 상태 이벤트를 직렬화하지 못했다: orderId=" + payload.orderId(), e);
        }
        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(Long.toString(payload.orderId()), "Order",
                ORDER_STATUS_EVENT, json, status);
        this.save(outboxEvent);
        relayOnCommit(status);
    }

    
    

}
