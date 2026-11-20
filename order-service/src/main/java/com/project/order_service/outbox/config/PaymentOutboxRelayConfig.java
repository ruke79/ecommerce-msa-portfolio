package com.project.order_service.outbox.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.common.outbox.OutboxRelayTrigger;
import com.project.order_service.outbox.scheduler.PaymentOutboxScheduler;

/**
 * 결제 요청 아웃박스를 커밋 직후 릴레이한다 (docs/56).
 *
 * <p>스케줄러를 {@link ObjectProvider} 로 받는 이유: 스케줄러 → 헬퍼 → 이 트리거 → 스케줄러로
 * 순환한다. 쓰는 순간에 꺼내면 순환이 없고, 꺼낸 것은 {@code @Transactional} 프록시다 — 선점
 * 잠금이 발행이 끝날 때까지 유지된다.
 *
 * <p>[JA] 決済リクエストのアウトボックスをコミット直後にリレーする(docs/56)。
 * スケジューラーを {@link ObjectProvider} で受ける理由: スケジューラー → ヘルパー → このトリガー → スケジューラーと循環する。
 * 使う瞬間に取り出せば循環はなく、取り出したものは {@code @Transactional} プロキシだ — 先取りのロックが発行が終わるまで保たれる。
 */
@Configuration
public class PaymentOutboxRelayConfig {

    @Bean
    OutboxRelayTrigger paymentOutboxRelayTrigger(
            ObjectProvider<PaymentOutboxScheduler> scheduler,
            @Value("${order-service.outbox-relay-on-commit:true}") boolean enabled) {
        return new OutboxRelayTrigger("payment-outbox",
                () -> scheduler.getObject().processOutboxMessage(), enabled);
    }
}
