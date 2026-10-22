package com.project.order_service.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 주문 이력 생성기(ops/tools/generate-demo-orders.py, docs/83 §2.4) — Flyway 로 올린 실제 orders 스키마에 생성기가 낸 SQL 을 psql 로 흘려
 * 넣는다(ops/54 가 하는 것과 같다). 제약(상태 CHECK · 배송지 유니크 · 외래 키)이 받아 주는지, 다시 실행해도 같은지, purge 가 데모 행만
 * 지우는지 본다.
 *
 * <p>[JA] 注文履歴生成器 — 本物の orders スキーマに生成器の SQL を流し込み、制約が受け入れるか · 再実行しても同じか · purge がデモの行だけを
 * 消すかを見る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("주문 이력 생성기 — 실제 스키마 · 다시 실행 · 데모만 지우기 (docs/83 §2.4)")
class DemoOrderGeneratorIntegrationTest {

    private static final long ORDER_OFFSET = 8_000_000_000L;
    private static final long USER_OFFSET = 7_000_000_000L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("orderdb").withUsername("product").withPassword("testpw");

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        return Files.exists(p.resolve("ops")) ? p : p.getParent();
    }

    private static boolean hasPython() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** 생성기를 돌려 그 SQL 을 컨테이너의 psql 로 — ops/54 와 같다. */
    private void apply(String name, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", "-I", repoRoot().resolve("ops/tools/generate-demo-orders.py").toString()));
        cmd.addAll(List.of(args));
        Path sql = tmp.resolve(name + ".sql");
        Process p = new ProcessBuilder(cmd).redirectOutput(sql.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0).as("생성기 " + String.join(" ", args)).isTrue();
        postgres.copyFileToContainer(MountableFile.forHostPath(sql), "/tmp/" + name + ".sql");
        ExecResult r = postgres.execInContainer("psql", "-X", "-q", "-U", "product", "-d", "orderdb", "-v", "ON_ERROR_STOP=1",
                "-f", "/tmp/" + name + ".sql");
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
    }

    private static final String[] RANGE = { "--users", "300", "--orders-per-user", "5", "--chunk", "100", "--products", "1000" };

    private static String[] with(String... more) {
        String[] out = new String[RANGE.length + more.length];
        System.arraycopy(RANGE, 0, out, 0, RANGE.length);
        System.arraycopy(more, 0, out, RANGE.length, more.length);
        return out;
    }

    @Test
    @DisplayName("🔴 손님 300 × 5 — 제약을 통과하고, 금액 · 날짜 순서가 맞고, 다시 넣어도 같고, purge 는 범위 안의 진짜 주문을 남긴다")
    void generatesFinishedOrdersAndPurgesOnlyThem() throws Exception {
        assumeTrue(hasPython(), "python3 가 없다");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "product", "testpw").locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), "product", "testpw")) {
            // 범위 안에 이미 있는 진짜 주문 — 생성기는 건너뛰고(ON CONFLICT) purge 는 남겨야 한다(tracking_id 가 demo- 가 아니다)
            long real = ORDER_OFFSET + 3;
            exec(db, "insert into orders.order_address (order_address_id, address1, city, state, zip_code) values (" + real + ", 'a', 'c', 's', 'z')");
            exec(db, "insert into orders.orders (order_id, customer_id, tracking_id, order_status, payment_method, shipping_price, "
                    + "total_before_discount, total, order_address_id) values (" + real + ", 1, 'real-order', 'PAID', 'card', 0, 1, 1, " + real + ")");

            for (int i = 0; i < 3; i++) {
                apply("chunk" + i, with("--chunk-index", String.valueOf(i)));
            }
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%'")).as("1,500 − 진짜 주문 하나").isEqualTo(1499);
            assertThat(scalar(db, "select count(*) from orders.orders where order_id = " + real + " and tracking_id = 'real-order'")).isEqualTo(1);
            assertThat(scalar(db, "select count(*) from orders.order_product where order_id = " + real)).as("진짜 주문에 줄을 붙이지 않는다").isZero();
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%' and order_status in ('NOT_PROCESSED', 'UNPAID')"))
                    .as("입금 대기 · 결제 전은 만들지 않는다(입금 대기 한도 · 재고 예약)").isZero();
            assertThat(scalar(db, "select count(distinct order_status) from orders.orders where tracking_id like 'demo-%'")).isGreaterThanOrEqualTo(5);
            assertThat(scalar(db, "select count(*) from (select o.order_id, count(p.*) n from orders.orders o join orders.order_product p using (order_id) "
                    + "where o.tracking_id like 'demo-%' group by 1) x where n not between 1 and 3")).as("줄 1 ~ 3").isZero();
            assertThat(scalar(db, "select count(*) from orders.orders o where o.tracking_id like 'demo-%' and o.total <> o.shipping_price + "
                    + "(select sum(p.price * p.qty) from orders.order_product p where p.order_id = o.order_id)")).as("합계 = 줄 + 배송비").isZero();
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%' and customer_id not between "
                    + USER_OFFSET + " and " + (USER_OFFSET + 299))).as("손님은 데모 사용자(ops/48)").isZero();
            assertThat(scalar(db, "select count(*) from (select customer_id, order_id, created_at, lag(created_at) over (partition by customer_id order by order_id) prev "
                    + "from orders.orders where tracking_id like 'demo-%') x where prev > created_at")).as("손님마다 주문 id 순 = 시간 순").isZero();
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%' and order_status = 'COMPLETED' "
                    + "and (paid_created_at is null or delivered_created_at is null or completed_at is null)")).isZero();
            long lines = scalar(db, "select count(*) from orders.order_product");

            apply("again", with("--chunk-index", "1"));
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%'")).as("다시 실행해도 같다").isEqualTo(1499);
            assertThat(scalar(db, "select count(*) from orders.order_product")).isEqualTo(lines);

            apply("purge", with("--purge"));
            assertThat(scalar(db, "select count(*) from orders.orders where tracking_id like 'demo-%'")).isZero();
            assertThat(scalar(db, "select count(*) from orders.order_product")).isZero();
            assertThat(scalar(db, "select count(*) from orders.order_address")).as("진짜 주문의 배송지만").isEqualTo(1);
            assertThat(scalar(db, "select count(*) from orders.orders")).as("범위 안의 진짜 주문은 남는다").isEqualTo(1);
        }
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }
}
