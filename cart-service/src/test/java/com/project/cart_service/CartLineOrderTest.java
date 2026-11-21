package com.project.cart_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartRepository;

/**
 * 장바구니 줄의 순서 — 수량을 바꿔도 <b>담은 순서 그대로</b> 읽힌다(실제 PostgreSQL · 실제 {@link CartRepository#findByUserId}).
 *
 * <p>🐛 2026-11-21 운영에서 발견: 수량을 바꾸면 그 상품이 목록 맨 뒤로 갔다. {@code Cart.cartProducts} 에 순서가 없어 DB 가 돌려주는
 * 순서였는데, PostgreSQL 은 UPDATE 한 행을 새 버전으로 테이블 뒤쪽에 쓴다 — 방금 고친 줄이 뒤로 나온다. 수량 PATCH 의 응답이
 * loadCart 이고 화면은 그 순서를 그대로 쓴다. 정렬은 처음부터 없었고, 2026-09-26 {@code 9c00d7eb}(화면이 서버 응답으로 목록을 바꿔
 * 끼우게 됨)부터 드러났다 — 운영은 2026-10-10 {@code b7c5053a45e8} 부터.
 *
 * <p>[JA] カートの行の順序 — 数量を変えても入れた順のまま読まれる。以前は順序がなく、PostgreSQL は UPDATE した行を新しい版として
 *      テーブルの後ろに書くので、数量を変えた商品が末尾へ動いた(2026-09-26 9c00d7eb から表に出た、本番は 2026-10-10 から)。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("장바구니 줄 순서 — 수량을 바꿔도 담은 순서")
class CartLineOrderTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cartdb").withUsername("product").withPassword("testpw");

    // 앱이 Redis 를 요구한다(Redisson) — 기동 시험과 같은 설정. [JA] アプリが Redis を要求する — 起動試験と同じ設定。
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("outbox.partitions.tables", () -> "cart.outbox_event");
        registry.add("outbox.partitions.initial-delay", () -> "PT1H");
        registry.add("outbox.backlog.initial-delay", () -> "PT1H");
    }

    @Autowired
    CartRepository cartRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("🔴 DB 가 돌려주는 순서가 담은 순서와 달라도(수량을 바꾼 줄이 뒤로 간 상태) findByUserId 는 담은 순서(A B C)")
    void lineOrderIsInsertionOrderNotStorageOrder() {
        long userId = 4242L;
        jdbc.update("INSERT INTO cart.cart (cart_id, user_id, version) VALUES (900, ?, 0)", userId);
        // 운영에서는 A 의 수량을 바꾸면(UPDATE — 새 행 버전이 테이블 뒤쪽에) DB 가 B C A 로 돌려줬다. 그 재현은 HOT 업데이트 · 실행 계획에
        // 따라 흔들리므로, 여기서는 그 결과 상태 — 저장 순서 ≠ 담은 순서 — 를 직접 만든다: 줄을 C(1003) · A(1001) · B(1002) 순으로 넣는다.
        // 줄 id 는 TSID 라 담은 순서는 A B C 다. 같은 키의 인덱스 조회도 행 위치 순이라 순차 · 인덱스 어느 쪽이든 C A B 로 읽힌다.
        // [JA] 本番では A の数量を変えると(UPDATE — 新しい版がテーブルの後ろに)DB が B C A を返した。その再現は HOT 更新 · 実行計画で
        //      揺れるので、ここでは結果の状態 — 格納順 ≠ 入れた順 — を直接作る: C · A · B の順に入れる(入れた順は id の A B C)。
        for (Object[] r : new Object[][] { {1003L, "C"}, {1001L, "A"}, {1002L, "B"} }) {
            jdbc.update("INSERT INTO cart.cart_product (cartproduct_id, cart_id, qty, style, name, version) VALUES (?, 900, 1, 0, ?, 0)",
                    r[0], r[1]);
        }
        assertThat(jdbc.queryForList("SELECT name FROM cart.cart_product WHERE cart_id = 900", String.class))
                .as("전제: 순서 없이 읽으면 저장 순서 — 운영에서 고친 줄이 뒤로 간 것과 같은 상태").containsExactly("C", "A", "B");

        List<String> names = cartRepository.findByUserId(userId).orElseThrow().getCartProducts().stream()
                .map(CartProduct::getName).toList();
        assertThat(names).as("@OrderBy(cartproductId) — 저장 순서와 상관없이 담은 순서").containsExactly("A", "B", "C");
    }
}
