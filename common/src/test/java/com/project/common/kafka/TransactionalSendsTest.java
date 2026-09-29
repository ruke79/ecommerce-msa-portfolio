package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

/**
 * 🐛 환불하면 재고가 돌아가지 않았다 — "재고 복원 발행 실패 … No transaction is in process".
 *
 * <p>트랜잭션 프로듀서를 HTTP 요청(트랜잭션 없음)에서 부르면 보낼 수 없다. {@link TransactionalSends}
 * 는 그때 이 한 번의 발송만 감싼 트랜잭션으로 보낸다.
 */
@DisplayName("TransactionalSends — 트랜잭션 프로듀서를 어디서든")
class TransactionalSendsTest {

    private final MockProducer<String, String>[] last = newArray();

    @SuppressWarnings("unchecked")
    private static MockProducer<String, String>[] newArray() {
        return new MockProducer[1];
    }

    private KafkaTemplate<String, String> transactionalTemplate() {
        MockProducerFactory<String, String> factory = new MockProducerFactory<>((tx, id) -> {
            last[0] = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
            last[0].initTransactions();   // 실제 팩토리가 하는 일
            return last[0];
        }, "tx-");
        return new KafkaTemplate<>(factory);
    }

    @Test
    @DisplayName("대조: 트랜잭션 없이 그냥 보내면 예전처럼 실패한다")
    void plainSendOutsideTransactionFails() {
        KafkaTemplate<String, String> template = transactionalTemplate();
        assertThat(template.isTransactional()).isTrue();

        assertThatThrownBy(() -> template.send("product-update", "k", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No transaction is in process");
    }

    @Test
    @DisplayName("🔴 트랜잭션이 없으면 한 번짜리 트랜잭션으로 보내고 커밋한다")
    void runWrapsInItsOwnTransaction() throws Exception {
        KafkaTemplate<String, String> template = transactionalTemplate();

        TransactionalSends.run(template, ops -> ops.send("product-update", "k", "v")).get(5, TimeUnit.SECONDS);

        assertThat(last[0].history()).hasSize(1);
        assertThat(last[0].transactionCommitted()).isTrue();
    }

    @Test
    @DisplayName("이미 트랜잭션 안이면 그 트랜잭션에 붙는다 — 따로 커밋하지 않는다")
    void joinsAnOngoingTransaction() {
        KafkaTemplate<String, String> template = transactionalTemplate();

        template.executeInTransaction(outer -> {
            TransactionalSends.run(template, ops -> ops.send("product-update", "k", "v"));
            assertThat(last[0].transactionCommitted()).as("바깥 트랜잭션이 끝나기 전").isFalse();
            return null;
        });

        assertThat(last[0].history()).hasSize(1);
        assertThat(last[0].transactionCommitted()).isTrue();
    }

    @Test
    @DisplayName("트랜잭션 프로듀서가 아니면 그냥 보낸다")
    void nonTransactionalTemplateSendsDirectly() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));

        TransactionalSends.run(template, ops -> ops.send("cart-empty", "v"));

        assertThat(producer.history()).hasSize(1);
    }
}
