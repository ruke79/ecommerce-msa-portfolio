package com.project.catalog_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 사가의 재고 차감 구간을 <b>실제 Kafka 브로커</b>로 검증한다.
 *
 * <p>docs/16 §1 에서 손으로 확인했던 것을 빌드가 대신 하게 만든 테스트다. 사가 전체
 * (주문→결제→재고)는 서비스 3개의 컨텍스트가 동시에 필요해 한 테스트로 묶기 어렵지만,
 * <b>돈이 걸린 구간</b>은 여기다 — 재고가 두 번 차감되면 그대로 손실이다.
 *
 * <p>확인하는 것:
 * <ul>
 *   <li>product-update 메시지 하나가 재고를 정확히 주문 수량만큼 줄이는가</li>
 *   <li>같은 eventId 를 재전송해도 <b>더 줄지 않는가</b>(멱등성, docs/12 §2)</li>
 *   <li>주문 항목이 여러 개면 <b>전부</b> 반영되는가(예전에는 첫 항목만 처리하고 return 했다)</li>
 *   <li>망가진 메시지가 컨슈머를 멈추지 않고 DLT 로 격리되는가</li>
 * </ul>
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
// poison message 테스트는 재시도 백오프를 일부러 유발한다. 같은 파티션의 뒤 메시지들이 그동안
// 대기하므로, 그 테스트를 마지막에 돌려 다른 테스트의 대기 시간을 잡아먹지 않게 한다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("사가 재고 차감 · 멱등성 (Kafka Testcontainers)")
class ProductUpdateSagaIntegrationTest {

    private static final String TOPIC = "product-update";
    private static final String TYPE_HEADER = "__TypeId__";
    private static final String REQUEST_TYPE =
            "com.project.common.message.dto.request.ProductUpdateRequest";

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.url", kafka::getBootstrapServers);
        registry.add("kafka.security.protocol", () -> "PLAINTEXT");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource-data.url", postgres::getJdbcUrl);
        registry.add("spring.datasource-data.username", postgres::getUsername);
        registry.add("spring.datasource-data.password", postgres::getPassword);
        registry.add("spring.datasource-meta.url", postgres::getJdbcUrl);
        registry.add("spring.datasource-meta.username", postgres::getUsername);
        registry.add("spring.datasource-meta.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);

        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("AMAZON_ACCESS_KEY", () -> "dummy");
        registry.add("AMAZON_SECRET_KEY", () -> "dummy");
        registry.add("AMAZON_BUCKET_NAME", () -> "dummy");
        registry.add("AMAZON_REGION", () -> "ap-northeast-2");
        registry.add("LOCAL_ELASTICSEARCH_USERNAME", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_PASSWORD", () -> "");
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    // 컨슈머는 비동기다 — 한 테스트가 발행한 메시지가 다음 테스트의 @BeforeEach 이후에 처리될 수
    // 있다. 그래서 테스트마다 재고를 초기화하지 않고, **테스트마다 서로 다른 상품**을 쓴다.
    // 순서나 타이밍에 상관없이 각 테스트는 자기 상품만 검증하므로 서로 간섭하지 않는다.
    private static boolean seeded = false;

    @BeforeEach
    void awaitConsumerAssignment() {
        // 컨슈머가 파티션을 배정받기 전에 발행하면 그 메시지를 영영 못 볼 수 있다
        // (auto.offset.reset 이 latest 인 경우). 배정을 기다린 뒤에 발행한다 —
        // 이걸 빼면 테스트가 간헐적으로 실패한다.
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @BeforeEach
    void seedCatalogOnce() {
        if (seeded) {
            return;
        }
        jdbc.update("INSERT INTO product.category(category_id, category_name, slug, createdat, version)"
                + " VALUES (1, 'fashion', 'fashion', now(), 0) ON CONFLICT DO NOTHING");
        // product_sku 는 color_id 에 UNIQUE 제약이 있어 SKU 마다 색상이 달라야 한다.
        for (long colorId = 10L; colorId <= 17L; colorId++) {
            jdbc.update("INSERT INTO product.product_color(color_id, color, colorimage, createdat, version)"
                    + " VALUES (?, ?, 'c.png', now(), 0) ON CONFLICT DO NOTHING",
                    colorId, "color-" + colorId);
        }

        // productId, skuId, sizeId, 초기 재고 — 테스트별 전용 상품
        insertProduct(100L, 1000L, 5000L, 10L, 50);   // 단건 차감
        insertProduct(101L, 1001L, 5001L, 11L, 80);   // 재전달 멱등성
        insertProduct(102L, 1002L, 5002L, 12L, 50);   // 다중 항목 (1)
        insertProduct(103L, 1003L, 5003L, 13L, 80);   // 다중 항목 (2)
        insertProduct(104L, 1004L, 5004L, 14L, 50);   // poison message 이후 처리
        insertProduct(105L, 1005L, 5005L, 15L, 50);   // 서로 다른 eventId
        insertProduct(106L, 1006L, 5006L, 16L, 50);   // 미존재 상품 주문 (불변이어야 함)
        insertProduct(107L, 1007L, 5007L, 17L, 50);   // 연속 주문 누적
        seeded = true;
    }

    private void insertProduct(long productId, long skuId, long sizeId, long colorId, int quantity) {
        jdbc.update("INSERT INTO product.product(product_id, name, description, brand, slug, rating,"
                + " shipping, refundpolicy, category_id, createdat, version)"
                + " VALUES (?, ?, '설명', '브랜드', ?, 0, 5.00, '30d', 1, now(), 0)"
                + " ON CONFLICT DO NOTHING",
                productId, "상품 " + productId, "slug-" + productId);
        jdbc.update("INSERT INTO product.product_sku(skuproduct_id, sku, discount, sold, product_id,"
                + " color_id, createdat, version) VALUES (?, ?, 0, 0, ?, ?, now(), 0)"
                + " ON CONFLICT DO NOTHING",
                skuId, "SKU-" + productId, productId, colorId);
        jdbc.update("INSERT INTO product.product_size(size_id, size, quantity, price, skuproduct_id,"
                + " createdat, version) VALUES (?, 'M', ?, 29.99, ?, now(), 0)"
                + " ON CONFLICT DO NOTHING",
                sizeId, quantity, skuId);
    }

    private int quantityOf(long productId) {
        return jdbc.queryForObject(
                "SELECT z.quantity FROM product.product_size z"
                        + " JOIN product.product_sku k USING (skuproduct_id) WHERE k.product_id = ?",
                Integer.class, productId);
    }

    private void publish(String payload) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(config)) {
            var record = new ProducerRecord<String, String>(TOPIC, payload);
            // 컨슈머의 JsonDeserializer 는 타입 헤더로 대상 클래스를 정한다.
            record.headers().add(TYPE_HEADER, REQUEST_TYPE.getBytes());
            producer.send(record);
            producer.flush();
        }
    }

    private String orderPayload(String eventId, String items) {
        return "{\"orderProducts\":[" + items + "],\"autorizationToken\":\"test\",\"eventId\":\""
                + eventId + "\"}";
    }

    private static String item(long productId, int qty, String size, long colorId) {
        return "{\"productId\":" + productId + ",\"qty\":" + qty + ",\"size\":\"" + size
                + "\",\"colorId\":" + colorId + "}";
    }

    @Order(1)
    @Test
    @DisplayName("주문 한 건이 재고를 주문 수량만큼 차감한다")
    void stockIsDecrementedOnce() {
        publish(orderPayload("saga-1", item(100L, 3, "M", 10L)));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(100L)).isEqualTo(47));
    }

    @Order(2)
    @Test
    @DisplayName("같은 eventId 를 재전송해도 재고가 더 줄지 않는다 (멱등성)")
    void replayDoesNotDecrementAgain() {
        // Kafka 는 at-least-once 다. 재전달·사가 재실행에서 같은 이벤트가 다시 오는 것은
        // 예외가 아니라 정상이며, 그때 재고가 또 줄면 그대로 손실이 된다.
        publish(orderPayload("saga-2", item(101L, 4, "M", 11L)));
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(101L)).isEqualTo(76));

        for (int i = 0; i < 3; i++) {
            publish(orderPayload("saga-2", item(101L, 4, "M", 11L)));
        }

        // 재전송이 처리될 시간을 준 뒤에도 값이 그대로여야 한다.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(101L))
                        .as("재전달로 재고가 이중 차감되면 안 된다")
                        .isEqualTo(76));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM product.processed_event WHERE event_id = 'saga-2'", Integer.class))
                .as("멱등 원장에 한 번만 기록되어야 한다").isEqualTo(1);
    }

    @Order(3)
    @Test
    @DisplayName("여러 항목 주문은 모든 항목의 재고를 차감한다")
    void everyOrderItemIsApplied() {
        // 예전 코드는 첫 항목을 처리한 뒤 return 해서 나머지가 조용히 누락됐다.
        publish(orderPayload("saga-3", item(102L, 2, "M", 12L) + "," + item(103L, 5, "M", 13L)));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(quantityOf(102L)).isEqualTo(48);
            assertThat(quantityOf(103L)).isEqualTo(75);
        });
    }

    @Order(99)
    @Test
    @DisplayName("망가진 메시지가 컨슈머를 멈추지 않는다 (DLQ 격리)")
    void poisonMessageDoesNotStallTheConsumer() {
        // 타입 헤더 없이 보내면 역직렬화가 실패한다. 그 뒤에 보낸 정상 메시지가 처리되면
        // 컨슈머가 poison message 에 갇히지 않고 DLT 로 넘긴 것이다.
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(config)) {
            producer.send(new ProducerRecord<>(TOPIC, "{\"이건\":\"타입 헤더가 없다\"}"));
            producer.flush();
        }

        publish(orderPayload("saga-4", item(104L, 1, "M", 14L)));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(104L))
                        .as("망가진 메시지 뒤의 정상 메시지가 처리되어야 한다")
                        .isEqualTo(49));
    }

    @Order(4)
    @Test
    @DisplayName("eventId 가 다르면 각각 차감된다 (멱등성이 정상 처리를 막지 않는다)")
    void distinctEventsAreEachApplied() {
        publish(orderPayload("saga-5a", item(105L, 1, "M", 15L)));
        publish(orderPayload("saga-5b", item(105L, 2, "M", 15L)));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(105L)).isEqualTo(47));
    }

    @Order(5)
    @Test
    @DisplayName("존재하지 않는 상품 주문은 다른 재고를 건드리지 않는다")
    void unknownProductDoesNotAffectOtherStock() {
        publish(orderPayload("saga-6", item(999L, 5, "M", 10L)));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(quantityOf(106L)).isEqualTo(50));
    }

    @Order(6)
    @Test
    @DisplayName("연속된 주문이 누적 차감된다")
    void sequentialOrdersAccumulate() {
        List.of("saga-7a", "saga-7b", "saga-7c")
                .forEach(id -> publish(orderPayload(id, item(107L, 5, "M", 17L))));

        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() -> assertThat(quantityOf(107L)).isEqualTo(35));
    }
}
