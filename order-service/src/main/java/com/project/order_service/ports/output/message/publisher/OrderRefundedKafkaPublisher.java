package com.project.order_service.ports.output.message.publisher;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderRefund;

import lombok.extern.slf4j.Slf4j;

/**
 * 환불 한 건을 {@code order-refunded} 토픽으로 알린다 (docs/49 §10).
 *
 * <h3>🐛 부분 환불에는 알림이 없었다</h3>
 *
 * <p>"환불 완료" 알림은 pay-service 의 결제 응답(CANCELED)에서 나온다. 그런데 order-service 는 <b>전액이
 * 다 돌아갔을 때만</b> pay-service 에 CANCELED 를 보낸다(부분 환불은 결제가 아직 살아 있다). 그래서 품목
 * 일부를 환불하면 돈은 돌아가는데 앱·메일 어디에도 알림이 없었다.
 *
 * <p>환불 <b>한 건마다</b> 발행한다 — 전액이면 {@code complete=true}. 알림을 만들지는 받는 쪽이 정한다
 * (전액은 결제 응답 쪽이 이미 알리므로 notification-service 는 건너뛴다).
 *
 * <p>DB 트랜잭션 밖, 커밋 뒤에 부른다({@code OrderService.refundOrder}). 발행이 실패해도 환불은 이미
 * 반영됐다 — 로그만 남긴다. {@code eventId} 는 {@code order-refunded:<orderId>:<몇 번째 환불>} 이라 재전달돼도
 * 알림은 하나다.
 *
 * <p>[JA] 返金一件を {@code order-refunded} トピックで知らせる (docs/49 §10)。
 * <b>🐛 部分返金には通知がなかった</b>:
 * 「返金完了」通知は pay-service の決済応答(CANCELED)から出る。ところが order-service は<b>全額が
 * 戻った時だけ</b> pay-service に CANCELED を送る(部分返金では決済がまだ生きている)。そのため品目の
 * 一部を返金するとお金は戻るのに、アプリ・メールのどこにも通知がなかった。
 * 返金<b>一件ごとに</b>発行する — 全額なら {@code complete=true}。通知を作るかは受け取る側が決める
 * (全額は決済応答側が既に知らせるので notification-service はスキップする)。
 * DB トランザクションの外、コミットの後に呼ぶ({@code OrderService.refundOrder})。発行が失敗しても返金は既に
 * 反映されている — ログだけ残す。{@code eventId} は {@code order-refunded:<orderId>:<何回目の返金>} なので再配信されても
 * 通知は一つ。
 */
@Slf4j
@Component
public class OrderRefundedKafkaPublisher {

    public static final String TOPIC = "order-refunded";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderRefundedKafkaPublisher(
            @Qualifier("orderRefundedKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** 이 주문의 <b>가장 최근</b> 환불 한 건을 알린다. 환불 기록이 없으면(이미 다 환불돼 있던 재요청) 보내지 않는다. */
    // [JA] この注文の<b>最新の</b>返金一件を知らせる。返金記録がなければ(既に全額返金済みだった再要求)送らない。
    public void publishLatest(Order order) {
        try {
            Map<String, Object> event = eventFor(order);
            if (event == null) {
                return;
            }
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, String.valueOf(order.getOrderId()), json)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("환불 이벤트 발행: orderId={} complete={}", order.getOrderId(), event.get("complete"));
                        } else {
                            // 알림이 하나 빠질 뿐 환불은 이미 반영됐다. 여기서 던지면 안 된다.
                            // [JA] 通知が一つ抜けるだけで、返金は既に反映されている。ここで投げてはならない。
                            log.error("환불 이벤트 발행 실패: orderId={}", order.getOrderId(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("환불 이벤트를 만들지 못함: orderId={}", order == null ? null : order.getOrderId(), e);
        }
    }

    /** 보낼 내용 — 테스트가 직접 본다. 금액·통화는 <b>청구된</b> 것(손님이 받는 돈)이다. */
    // [JA] 送る内容 — テストが直接見る。金額・通貨は<b>請求された</b>もの(顧客が受け取るお金)である。
    static Map<String, Object> eventFor(Order order) {
        if (order == null || order.getRefunds() == null || order.getRefunds().isEmpty()) {
            return null;
        }
        List<OrderRefund> refunds = order.getRefunds();
        OrderRefund latest = refunds.get(refunds.size() - 1);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("orderId", order.getOrderId());
        event.put("customerId", order.getCustomerId());
        event.put("refundRef", latest.getRefundRef());
        event.put("amount", plain(latest.getChargedAmount()));
        event.put("currency", latest.getCurrency());
        event.put("complete", order.getOrderStatus() == OrderStatus.REFUNDED);
        event.put("refundCount", refunds.size());
        // 재고 부족(품절) 자동 환불이면 표시 — notification 이 "일부 상품 품절 취소" 로 알린다(5단계 15, 대형 EC 의 품절 취소 안내).
        // [JA] 在庫不足(品切れ)の自動返金なら印 — notification が「一部商品の品切れキャンセル」として知らせる。
        if (com.project.order_service.service.OrderStockShortageService.REFUND_REASON.equals(latest.getReason())) {
            event.put("cause", "stock-shortage");
        }
        // 키는 환불 **순번**이다 — 환불 참조는 제공자에 따라 결제 참조로 대신 채워져 여러 번의 부분 환불이
        // 같은 값을 가질 수 있다(그러면 두 번째 알림이 중복으로 걸러진다). n 번째 환불은 하나뿐이다.
        // [JA] キーは返金の**順番**である — 返金参照はプロバイダによって決済参照で代わりに埋められ、複数回の部分返金が
        //      同じ値を持ちうる(そうなると二つ目の通知が重複として除かれる)。n 回目の返金は一つだけである。
        event.put("eventId", TOPIC + ":" + order.getOrderId() + ":" + refunds.size());
        return event;
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
