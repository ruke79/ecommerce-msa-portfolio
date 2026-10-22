package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.catalog_service.controller.SearchController;

/**
 * 상대가 사라진 DB 연결 위의 검색 — 소켓 읽기 상한에서 끝나 격벽 자리 · 커넥션이 돌아온다(실제 PostgreSQL · Redis, 사이에 TCP 중계).
 *
 * <p>🐛 2026-10-21 실습 8시간(mixed {@code 030018Z}, docs/83 §2.8): 끝난 뒤 쉬는 동안에도 catalog 파드 셋 모두 격벽 자리 3/3 을 스레드 셋이
 * 쥐고 있었다 — 개수 세기의 PostgreSQL 소켓 읽기에서 몇 시간째. 시간 상한(5초)의 취소는 <b>새 연결</b>로 DB 에 가서 질의를 멈추지만, 그 답이
 * 돌아올 원래 연결이 죽어 있으면(노드 재부팅 — RST 가 오지 않는다) 읽기는 깨어나지 않는다. 그 뒤 DB 를 쓰는 검색은 모두 503 이었다(실패 4.64%).
 *
 * <p>중계는 {@link #blackhole()} 뒤에 <b>그때 열려 있던</b> 연결의 바이트를 양쪽 다 버린다(닫지 않는다) — 새 연결(취소 요청 · 풀이 다시 여는
 * 연결)은 그대로 간다. 이 테스트의 상한: 검색 2초 + 소켓 여유 2초, 자리 1(돌아와야만 다음 검색이 된다) · 풀 2(Flyway 가 둘을 쓴다).
 *
 * <p>[JA] 相手が消えた DB 接続の上の検索 — ソケット読み取りの上限で終わり、隔壁の席・接続が戻る。中継は blackhole() の後、その時に開いていた
 * 接続のバイトを両方向とも捨てる(閉じない)。時間上限の取り消しは別接続で届くが、答えが返る元の接続が死んでいるので読み取りは起きない。
 */
@Tag("containers")
@SpringBootTest(properties = {
        "catalog.search.query-timeout-seconds=2",
        "catalog.search.network-timeout-grace=2s",
        "catalog.search.bulkhead.max-concurrent=1",
        "catalog.search.bulkhead.max-concurrent-filtered=1",
        "spring.datasource-data.hikari.maximum-pool-size=2",
        "spring.datasource-data.hikari.minimum-idle=2" })
@Testcontainers
@DisplayName("죽은 DB 연결 위의 검색 — 소켓 상한에서 끝나 자리가 돌아온다 (실제 PostgreSQL · Redis)")
class SearchDeadConnectionIntegrationTest {

    private static final long CATEGORY = 9500L;

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

    private static BlackholeProxy proxy;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 앱 데이터(검색)만 중계를 지난다 — metadb 는 바로
        registry.add("spring.datasource-data.url", () -> {
            proxy = proxy != null ? proxy : new BlackholeProxy(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
            return "jdbc:postgresql://127.0.0.1:" + proxy.port() + "/productdb";
        });
        registry.add("spring.datasource-data.username", postgres::getUsername);
        registry.add("spring.datasource-data.password", postgres::getPassword);
        registry.add("spring.datasource-meta.url", postgres::getJdbcUrl);
        registry.add("spring.datasource-meta.username", postgres::getUsername);
        registry.add("spring.datasource-meta.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);

        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("spring.elasticsearch.uris", () -> "http://localhost:9200");
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_USERNAME", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_PASSWORD", () -> "");
        registry.add("AMAZON_ACCESS_KEY", () -> "dummy");
        registry.add("AMAZON_SECRET_KEY", () -> "dummy");
        registry.add("AMAZON_BUCKET_NAME", () -> "dummy");
        registry.add("AMAZON_REGION", () -> "ap-northeast-2");
    }

    @AfterAll
    static void stopProxy() {
        if (proxy != null) {
            proxy.close();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("dataDataSource")
    private DataSource dataSource;

    @Autowired
    private SearchController controller;

    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

    private MvcResult search(String lowPrice) throws Exception {
        try (var c = redisConnectionFactory.getConnection()) {
            c.serverCommands().flushAll();   // 캐시가 맞으면 DB 에 가지 않는다
        }
        var request = get("/api/search").param("category", String.valueOf(CATEGORY)).param("page", "1").param("pageSize", "10")
                .param("includeFilters", "false");
        if (lowPrice != null) {
            request.param("lowPrice", lowPrice);   // 검색마다 다른 질의
        }
        return MockMvcBuilders.standaloneSetup(controller).build().perform(request).andReturn();
    }

    @Test
    @DisplayName("🔴 검색 도중 연결의 상대가 사라져도 소켓 상한(2 + 2초)에서 503 — 자리 1 인데 다음 검색이 200(예전: 끝나지 않고 자리를 영영 쥠)")
    void searchOnADeadConnectionGivesItsPermitBack() throws Exception {
        jdbc.update("insert into product.category (category_id, category_name, slug) values (?, 'dead', 'dead') on conflict do nothing", CATEGORY);
        assertThat(search(null).getResponse().getStatus()).as("중계가 살아 있을 때").isEqualTo(200);
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            assertThat(List.of(a.getNetworkTimeout(), b.getNetworkTimeout()))
                    .as("검색이 건 소켓 상한(4초)은 풀로 돌아오며 풀의 값(900초)으로 되돌아간다 — 필터 메뉴 계산 등에 번지지 않는다")
                    .containsOnly(900_000);
        }

        // 검색이 개수 세기를 보낸 뒤 답을 기다리는 동안 상대가 사라지게 — 표를 잠가 질의를 붙잡아 두고, 그 사이 연결을 끊는다
        try (Connection lock = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE product.product IN ACCESS EXCLUSIVE MODE");
            Thread blackholeSoon = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                proxy.blackhole();
            });
            blackholeSoon.start();
            long start = System.nanoTime();
            MvcResult r = assertTimeoutPreemptively(Duration.ofSeconds(25), () -> search("1"),
                    "소켓 상한이 없으면 취소 응답이 돌아오지 않아 읽기가 끝나지 않는다 — 실습에서는 몇 시간");
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(503);
            assertThat(ms).as("질의 상한 2초 + 소켓 여유 2초 + 여유").isBetween(3_000L, 12_000L);
            blackholeSoon.join();
            lock.rollback();
        } finally {
            proxy.dropBlackholed();   // 테스트가 실패해도 매달린 읽기를 풀어 준다
        }
        assertThat(search("2").getResponse().getStatus()).as("자리 1 — 돌아와야 200").isEqualTo(200);
    }

    /**
     * PostgreSQL 앞의 TCP 중계. {@link #blackhole()} 뒤에는 그때 열려 있던 연결의 바이트를 양쪽 다 버린다 — 닫지 않으므로 앱은 끊긴 줄 모른다.
     */
    static final class BlackholeProxy implements AutoCloseable {

        private static final class Pair {
            final Socket client;
            final Socket upstream;
            volatile boolean dropping;

            Pair(Socket client, Socket upstream) {
                this.client = client;
                this.upstream = upstream;
            }

            void close() {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }

        private final ServerSocket server;
        private final List<Pair> pairs = new CopyOnWriteArrayList<>();

        BlackholeProxy(String host, int port) {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        Pair p = new Pair(client, new Socket(host, port));
                        pairs.add(p);
                        pump(p, p.client, p.upstream);
                        pump(p, p.upstream, p.client);
                    } catch (IOException e) {
                        // 닫혔다
                    }
                }
            }, "blackhole-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return server.getLocalPort();
        }

        /** 지금 열린 연결만 — 새 연결은 그대로 간다. */
        void blackhole() {
            pairs.forEach(p -> p.dropping = true);
        }

        /** 버리던 연결을 닫는다(매달린 쪽이 있으면 깨운다). */
        void dropBlackholed() {
            for (Pair p : pairs) {
                if (p.dropping) {
                    p.close();
                    pairs.remove(p);
                }
            }
        }

        private static void pump(Pair p, Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream()) {
                    OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (!p.dropping) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException e) {
                    // 닫혔다
                } finally {
                    if (!p.dropping) {
                        p.close();
                    }
                }
            }, "blackhole-pump");
            t.setDaemon(true);
            t.start();
        }

        private static void closeQuietly(Socket s) {
            try {
                s.close();
            } catch (IOException e) {
                // 무시
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException e) {
                // 무시
            }
            pairs.forEach(Pair::close);
        }
    }
}
