package com.project.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 사가 <b>전 구간</b>을 실제 서비스 컨테이너로 검증한다 — 주문 → 결제 → 재고.
 *
 * <p>docs/16 §1 에서 손으로 확인하고 `ops/06-smoke-test.sh` 가 "수동 확인 항목"으로 남겨 두었던
 * 흐름이다. catalog 단독 테스트(`ProductUpdateSagaIntegrationTest`)는 재고 차감 구간만 덮으므로,
 * <b>서비스 경계를 넘는 부분</b>(pay 가 아웃박스를 쓰고, 스케줄러가 발행하고, order 가 소비해
 * 상태를 바꾸고, 다시 catalog 가 재고를 줄이는 사슬)은 검증되지 않은 채 남아 있었다.
 *
 * <p>서비스 3개를 한 JVM 에 올릴 수는 없다 — 각 모듈의 {@code application-local.yml} 이 클래스패스에서
 * 충돌한다. 그래서 <b>미리 빌드한 jar 를 컨테이너로 띄운다.</b> 배포되는 산출물 그대로를 검증한다는
 * 부수 효과도 있다.
 *
 * <pre>
 *   order 토픽 ─▶ pay-service ─▶ pay.payment + 아웃박스 ─(스케줄러)─▶ payment 토픽
 *                                                                      │
 *                    ┌─────────────────────────────────────────────────┘
 *                    ▼
 *              order-service ─▶ orders.order_status: NOT_PROCESSED → PAID
 *                    └─▶ product-update 토픽 ─▶ catalog-service ─▶ 재고 차감
 * </pre>
 */
@Tag("containers")
@Testcontainers
// 두 테스트는 같은 주문을 공유한다 — 전파 테스트가 먼저 끝나야 재전송 테스트의 전제가 성립한다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("사가 전 구간 (주문→결제→재고, 서비스 컨테이너)")
class SagaEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SagaEndToEndTest.class);

    private static final Network NETWORK = Network.newNetwork();
    private static final String DB_PASSWORD = "testpw";

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka")
                    // 기본 부트스트랩 주소는 **호스트에서 접근하는 용도**라 같은 네트워크의 컨테이너는
                    // 쓸 수 없다. 서비스 컨테이너용 내부 리스너를 따로 연다.
                    .withListener("kafka:19092");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withDatabaseName("postgres")
                    .withUsername("product")
                    .withPassword(DB_PASSWORD);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", DB_PASSWORD);

    private static GenericContainer<?> payService;
    private static GenericContainer<?> orderService;
    private static GenericContainer<?> catalogService;

    /**
     * 베이스 JRE 이미지. Docker Hub 가 막힌 환경에서는 미러를 지정할 수 있게 해 둔다
     * (예: {@code E2E_JRE_IMAGE=mirror.gcr.io/library/eclipse-temurin:<같은 판>}). 기본값은 ops/Dockerfile.runtime 의 고정값과 같다(B-98).
     * Dockerfile 안의 FROM 은 데몬이 직접 받아오므로 Testcontainers 의 이미지 치환이 적용되지 않는다.
     */
    private static final String BASE_IMAGE = System.getenv().getOrDefault(
            "E2E_JRE_IMAGE", "eclipse-temurin:17.0.20.1_1-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1");

    /**
     * 파이프라인이 이미 만들어 레지스트리에 올린 이미지를 쓸 때의 접두사.
     * 예: {@code E2E_IMAGE_PREFIX=ghcr.io/ruke79/ecommerce-msa/}, {@code E2E_IMAGE_TAG=<커밋 SHA>}.
     */
    private static final String IMAGE_PREFIX = System.getenv().getOrDefault("E2E_IMAGE_PREFIX", "");
    private static final String IMAGE_TAG = System.getenv().getOrDefault("E2E_IMAGE_TAG", "latest");

    /**
     * 테스트할 컨테이너를 만든다.
     *
     * <p>⭐ 두 경로가 있는데, 둘의 차이가 이 테스트의 의미를 바꾼다.
     *
     * <p><b>기본(로컬)</b>: 방금 빌드한 jar 를 최소 이미지로 감싼다. 개발자가 손 안에 있는
     * 코드로 사가를 확인하는 용도다.
     *
     * <p><b>E2E_IMAGE_PREFIX 지정(파이프라인)</b>: 레지스트리에 **이미 올라간 그 이미지**를
     * 그대로 받아서 띄운다. 여기서 통과한 것과 운영에 배포되는 것이 비트 단위로 같은
     * 산출물이 된다. 다시 빌드하면 그 보장이 사라진다 — 같은 소스라도 베이스 이미지
     * 갱신, 의존성 해석 시점, 빌드 환경이 달라 다른 바이트가 나올 수 있고, 그러면
     * "테스트한 것"과 "배포한 것"이 다른 물건이 된다.
     */
    private static GenericContainer<?> newContainer(String module) {
        if (!IMAGE_PREFIX.isBlank()) {
            // 레지스트리 이미지는 Testcontainers 의 hub 접두사 치환 대상이 아니다
            // (이미 완전한 주소이므로). asCompatibleSubstituteFor 도 필요 없다.
            return new GenericContainer<>(
                    DockerImageName.parse(IMAGE_PREFIX + module + ":" + IMAGE_TAG));
        }
        return new GenericContainer<>(serviceImage(module));
    }

    /** 미리 빌드된 부트 jar 를 그대로 실행하는 최소 이미지. */
    private static ImageFromDockerfile serviceImage(String module) {
        Path jar = Paths.get("..", module, "build", "libs", module + "-0.0.1-SNAPSHOT.jar")
                .toAbsolutePath().normalize();
        if (!Files.exists(jar)) {
            throw new IllegalStateException(
                    "부트 jar 가 없다: " + jar + " — ./gradlew :" + module + ":bootJar 가 선행돼야 한다");
        }
        // 이름에 레지스트리 성분(점 포함)을 넣어 둔다. 그래야 hub.image.name.prefix 치환이
        // **로컬 빌드 이미지에는 적용되지 않는다**(적용되면 미러에서 찾다가 404 가 난다).
        return new ImageFromDockerfile("e2e.local/" + module + ":test", false)
                .withDockerfileFromBuilder(builder -> builder
                        .from(BASE_IMAGE)
                        .copy("app.jar", "/app.jar")
                        .entryPoint("java", "-jar", "/app.jar", "--spring.profiles.active=local")
                        .build())
                .withFileFromPath("app.jar", jar);
    }

    private static GenericContainer<?> service(String module, String alias, int port, String db,
            Map<String, String> extra) {
        GenericContainer<?> container = newContainer(module)
                .withNetwork(NETWORK)
                .withNetworkAliases(alias)
                .withExposedPorts(port)
                .withEnv("SPRING_APPLICATION_JSON", applicationJson(db, port))
                .withEnv("LOCAL_DB_PASSWORD", DB_PASSWORD)
                .withEnv("JWT_SECRET_KEY",
                        "0123456789012345678901234567890123456789012345678901234567890123")
                .withEnv("AMAZON_ACCESS_KEY", "dummy")
                .withEnv("AMAZON_SECRET_KEY", "dummy")
                .withEnv("AMAZON_BUCKET_NAME", "dummy")
                .withEnv("AMAZON_REGION", "ap-northeast-2")
                .withEnv("MAIL_HOST", "localhost")
                .withEnv("MAIL_PORT", "25")
                .withEnv("MAIL_USERNAME", "dummy")
                .withEnv("MAIL_PASSWORD", "dummy")
                .withEnv("LOCAL_CACERT_PATH", "")
                .withEnv("LOCAL_CRT_PATH", "")
                .withEnv("LOCAL_KEY_PATH", "")
                .withEnv("LOCAL_KEY_STORE_PATH", "")
                .withEnv("LOCAL_TRUST_STORE_PATH", "")
                .withEnv("LOCAL_KEY_STORE_PASSWORD", "")
                .withEnv("LOCAL_KEY_PASSWORD", "")
                .withEnv("LOCAL_ELASTICSEARCH_USERNAME", "")
                .withEnv("LOCAL_ELASTICSEARCH_PASSWORD", "")
                // PayPal 검증은 자격증명이 없으면 **기동을 거부한다**(order-service PaypalConfig —
                // 의도된 fail closed). 이 테스트는 결제 검증이 아니라 사가 전파를 보므로 끈다.
                // 이 줄이 필요하다는 사실 자체가 기본값이 안전한 쪽이라는 증거다(docs/35).
                .withEnv("PAYPAL_VERIFICATION_MODE", "disabled")
                .waitingFor(Wait.forLogMessage(".*Started \\w+Application.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(4)))
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix(alias));
        extra.forEach(container::withEnv);
        return container;
    }

    /**
     * 컨테이너 내부에서 보는 인프라 주소로 덮어쓴다. TLS·Eureka 는 이 테스트의 관심사가 아니라 끈다
     * (사가가 도는지를 보는 것이지 전송 보안을 보는 것이 아니다).
     */
    private static String applicationJson(String db, int port) {
        String jdbc = "jdbc:postgresql://postgres:5432/" + db;
        return "{"
                + "\"server\":{\"ssl\":{\"enabled\":false},\"port\":" + port + "},"
                + "\"kafka\":{\"url\":\"kafka:19092\",\"security\":{\"protocol\":\"PLAINTEXT\"}},"
                + "\"spring\":{"
                + "  \"datasource\":{\"url\":\"" + jdbc + "\",\"username\":\"product\",\"password\":\""
                + DB_PASSWORD + "\"},"
                + "  \"datasource-data\":{\"url\":\"" + jdbc + "\",\"username\":\"product\",\"password\":\""
                + DB_PASSWORD + "\"},"
                + "  \"datasource-meta\":{\"url\":\"" + jdbc + "\",\"username\":\"product\",\"password\":\""
                + DB_PASSWORD + "\"},"
                + "  \"data\":{\"redis\":{\"host\":\"redis\",\"port\":6379,\"password\":\"" + DB_PASSWORD
                + "\",\"ssl\":{\"enabled\":false}}}"
                + "},"
                + "\"eureka\":{\"client\":{\"enabled\":false,\"register-with-eureka\":false,"
                + "\"fetch-registry\":false}},"
                + "\"management\":{\"tracing\":{\"enabled\":false}}"
                + "}";
    }

    @BeforeAll
    static void startServices() throws Exception {
        createDatabases();

        // pay → order → catalog 순으로 띄운다. 순서 자체는 중요하지 않지만(모두 Kafka 로 느슨하게
        // 연결돼 있다) 로그를 읽기 쉽다.
        payService = service("pay-service", "pay", 8087, "paydb", Map.of());
        orderService = service("order-service", "order", 8084, "orderdb", Map.of(
                "FEIGN_CART_URL", "http://cart:8082",
                "FEIGN_CATALOG_URL", "http://catalog:8081",
                "FEIGN_USER_URL", "http://user:8080"));
        catalogService = service("catalog-service", "catalog", 8081, "productdb", Map.of());

        payService.start();
        orderService.start();
        catalogService.start();

        seedCatalog();
        seedOrder();
    }

    private static void createDatabases() throws Exception {
        try (Connection conn = adminConnection("postgres"); Statement st = conn.createStatement()) {
            for (String db : new String[] {"paydb", "orderdb", "productdb"}) {
                st.execute("CREATE DATABASE " + db + " OWNER product");
            }
        }
    }

    private static Connection adminConnection(String db) throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + db;
        return DriverManager.getConnection(url, "product", DB_PASSWORD);
    }

    private static void seedCatalog() throws Exception {
        try (Connection conn = adminConnection("productdb"); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO product.category(category_id, category_name, slug, createdat, version)"
                    + " VALUES (1, 'fashion', 'fashion', now(), 0)");
            st.execute("INSERT INTO product.product_color(color_id, color, colorimage, createdat, version)"
                    + " VALUES (10, 'red', 'r.png', now(), 0)");
            st.execute("INSERT INTO product.product(product_id, name, description, brand, slug, rating,"
                    + " shipping, refundpolicy, category_id, createdat, version)"
                    + " VALUES (500, '사가 상품', '설명', '브랜드', 'saga-product', 0, 5.00, '30d', 1, now(), 0)");
            st.execute("INSERT INTO product.product_sku(skuproduct_id, sku, discount, sold, product_id,"
                    + " color_id, createdat, version) VALUES (5500, 'SKU-SAGA', 0, 0, 500, 10, now(), 0)");
            st.execute("INSERT INTO product.product_size(size_id, size, quantity, price, skuproduct_id,"
                    + " createdat, version) VALUES (55000, 'M', 100, 29.99, 5500, now(), 0)");
        }
    }

    private static void seedOrder() throws Exception {
        try (Connection conn = adminConnection("orderdb"); Statement st = conn.createStatement()) {
            // 고객 사본(orders.customer)은 더 쓰지 않는다 — 주문의 customer_id 가 곧 토큰의 uid 다(docs/48 §6-①).
            st.execute("INSERT INTO orders.orders(order_id, customer_id, order_status, payment_method,"
                    + " total, total_before_discount, shipping_price, tax_price, tracking_id,"
                    + " created_at, version)"
                    + " VALUES (9100, 7, 'NOT_PROCESSED', 'PAYPAL', 59.98, 59.98, 5.00, 0.50,"
                    + " 'trk-9100', now(), 0)");
            st.execute("INSERT INTO orders.order_product(order_product_id, order_id, product_id, qty,"
                    + " size, color_id, name, price, image, created_at, version)"
                    + " VALUES (95000, 9100, 500, 2, 'M', 10, '사가 상품', 29.99, 'i.png', now(), 0)");
        }
    }

    private static int queryInt(String db, String sql) {
        try (Connection conn = adminConnection(db);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryText(String db, String sql) {
        try (Connection conn = adminConnection(db);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void publishPaymentRequest(long orderId) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        String payload = "{\"orderId\":" + orderId + ",\"customerId\":7,\"trackingId\":\"trk-" + orderId
                + "\",\"paypalOrderId\":\"PP-" + orderId + "\",\"amounts\":59.98,"
                + "\"orderStatus\":\"NOT_PROCESSED\",\"paymentType\":\"PAYPAL\","
                + "\"paymentStatus\":\"COMPLETED\",\"createdAt\":\"2026-07-28T00:00:00\","
                + "\"authorizationToken\":\"testtoken\"}";
        try (var producer = new KafkaProducer<String, String>(config)) {
            var record = new ProducerRecord<String, String>("order", payload);
            record.headers().add("__TypeId__",
                    "com.project.common.message.dto.request.PaymentRequest".getBytes());
            // pay-service 리스너는 Authorization 헤더가 없으면 배치를 통째로 건너뛴다.
            record.headers().add("Authorization", "Bearer testtoken".getBytes());
            producer.send(record);
            producer.flush();
        }
    }

    @Test
    @Order(1)
    @DisplayName("결제 완료가 주문 상태와 재고까지 전파된다")
    void paymentPropagatesToOrderStatusAndStock() {
        assertThat(queryText("orderdb", "SELECT order_status FROM orders.orders WHERE order_id = 9100"))
                .isEqualTo("NOT_PROCESSED");
        assertThat(queryInt("productdb", "SELECT quantity FROM product.product_size WHERE size_id = 55000"))
                .isEqualTo(100);

        publishPaymentRequest(9100L);

        // 1) pay-service 가 결제를 만들고 아웃박스에 기록한다.
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(queryInt("paydb", "SELECT count(*) FROM pay.payment WHERE order_id = 9100"))
                        .as("pay-service 가 결제를 생성해야 한다")
                        .isEqualTo(1));

        // 2) 아웃박스 스케줄러가 payment 토픽으로 발행한다(상태가 COMPLETED 로 바뀐다).
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(queryInt("paydb",
                        "SELECT count(*) FROM pay.order_outbox_event WHERE outbox_status = 'COMPLETED'"))
                        .as("아웃박스가 발행 완료로 바뀌어야 한다")
                        .isPositive());

        // 3) order-service 가 소비해 주문 상태를 PAID 로 바꾼다.
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(queryText("orderdb",
                        "SELECT order_status FROM orders.orders WHERE order_id = 9100"))
                        .as("주문이 PAID 로 전이돼야 한다")
                        .isEqualTo("PAID"));

        // 4) 사가가 product-update 를 발행하고 catalog 가 재고를 줄인다(주문 수량 2).
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(queryInt("productdb",
                        "SELECT quantity FROM product.product_size WHERE size_id = 55000"))
                        .as("재고가 주문 수량만큼 줄어야 한다")
                        .isEqualTo(98));

        // 5) 멱등 원장에 사가가 만든 키가 남는다 — 재전달이 와도 재고가 또 줄지 않는다는 근거.
        assertThat(queryInt("productdb",
                "SELECT count(*) FROM product.processed_event WHERE event_id = 'order-9100'"))
                .as("사가가 멱등 키를 남겨야 한다")
                .isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("같은 결제를 재전송해도 재고가 또 줄지 않는다")
    void replayingPaymentDoesNotDoubleDecrement() {
        // 첫 테스트가 주문 9100 을 이미 PAID 로 만들었다. 같은 결제 요청을 다시 보낸다 —
        // 실제 운영에서 Kafka 재전달로 흔히 일어나는 상황이다.
        int before = queryInt("productdb",
                "SELECT quantity FROM product.product_size WHERE size_id = 55000");

        publishPaymentRequest(9100L);

        // 잠시 기다린 뒤에도 값이 그대로여야 한다. 사가의 상태 검사와 catalog 의 멱등 원장이
        // 각각 방어선 역할을 한다.
        await().during(Duration.ofSeconds(20)).atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(queryInt("productdb",
                        "SELECT quantity FROM product.product_size WHERE size_id = 55000"))
                        .as("재전달로 재고가 이중 차감되면 안 된다")
                        .isEqualTo(before));
    }
}
