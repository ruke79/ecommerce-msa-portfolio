package com.project.common.feign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * 서비스 간 Feign 응답을 끝까지 읽는다 — chunked JSON 이어도 연결을 다시 쓴다(docs/83 §5).
 *
 * <p>🐛 2026-10-18 order 부하 측정: order 의 Feign 시간의 2/3 가 cart · catalog 로 가는 새 TLS 핸드셰이크였다. Spring MVC 의 JSON 은
 * chunked 인데 Jackson 이 마지막 닫는 중괄호에서 멈추고 닫아, JDK 가 끝 청크를 못 본 연결을 버렸다 — 호출마다 새 연결.
 *
 * <p>실제 Spring Cloud OpenFeign 배선(자동 설정 · {@code @EnableFeignClients} · Spring 디코더)으로, Spring MVC 처럼 chunked 로 답하는
 * <b>HTTPS</b> 서버에 같은 클라이언트로 여러 번 부르고 서버가 받은 연결 수를 센다. 평문 HTTP 에서는 JDK 가 닫을 때 남은 끝 청크를
 * 읽어 내 연결을 살리므로(그래서 이 결함이 잘 안 보였다) 서비스 간과 같은 TLS 로 잰다.
 *
 * <p>[JA] サービス間の Feign 応答を最後まで読む — chunked JSON でも接続を再利用する。本物の OpenFeign の配線で数える。
 */
@DisplayName("Feign 응답을 끝까지 읽기 — chunked JSON 의 연결 재사용")
class FeignResponseBodyTest {

    private static final int CALLS = 50;

    @TempDir
    Path tmp;

    private HttpsServer server;
    private SSLSocketFactory previousDefault;
    private final Set<Integer> connections = ConcurrentHashMap.newKeySet();

    @FeignClient(name = "chunked", url = "${test.chunked-url}")
    interface ChunkedClient {
        @GetMapping("/items")
        Map<String, Object> items();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = ChunkedClient.class)
    static class Clients {
    }

    /** localhost 자체 서명 키 — 서버 인증서이자 클라이언트가 믿는 것(서비스 간 mTLS 의 자리). */
    private SSLContext tls() throws Exception {
        Path ks = tmp.resolve("ks.p12");
        Process k = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        k.getInputStream().readAllBytes();
        assertThat(k.waitFor(60, TimeUnit.SECONDS) && k.exitValue() == 0 && Files.exists(ks)).as("keytool").isTrue();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks.toFile())) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(store, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(store);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    @BeforeEach
    void startServer() throws Exception {
        SSLContext ctx = tls();
        // Feign 기본 클라이언트는 JDK 기본 소켓 팩토리를 쓴다 — 서비스에서는 JVM 의 트러스트 스토어(javax.net.ssl.*)가 그 자리다
        previousDefault = HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/items", ex -> {
            connections.add(ex.getRemoteAddress().getPort());   // 클라이언트 포트 = 연결 하나
            byte[] b = "{\"ok\":true,\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);                      // 0 = chunked — Spring MVC 의 JSON 응답과 같다
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        HttpsURLConnection.setDefaultSSLSocketFactory(previousDefault);
    }

    private ApplicationContextRunner runner(boolean withCapability) {
        AutoConfigurations auto = withCapability
                ? AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class, FeignResponseBodyAutoConfiguration.class)
                : AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class);
        return new ApplicationContextRunner()
                .withConfiguration(auto)
                .withUserConfiguration(Clients.class)
                .withPropertyValues("test.chunked-url=https://localhost:" + server.getAddress().getPort(),
                        "spring.cloud.loadbalancer.enabled=false");
    }

    private int connectionsFor(boolean withCapability) {
        connections.clear();
        runner(withCapability).run(ctx -> {
            ChunkedClient client = ctx.getBean(ChunkedClient.class);
            for (int i = 0; i < CALLS; i++) {
                assertThat(client.items()).containsEntry("ok", true);   // 응답은 그대로 해석된다
            }
        });
        return connections.size();
    }

    @Test
    @DisplayName("🔴 자동 설정이 있으면 chunked JSON 을 50번 불러도 연결 하나 — 없으면(예전) 호출마다 새 연결")
    void reusesConnectionForChunkedJson() {
        int without = connectionsFor(false);
        int with = connectionsFor(true);
        assertThat(with).as("끝까지 읽으면 JDK 가 연결을 다시 쓴다").isLessThanOrEqualTo(2);
        assertThat(without).as("예전: Jackson 이 끝 청크 전에 닫아 연결을 버린다").isGreaterThan(CALLS / 2);
    }

    @Test
    @DisplayName("Feign 이 있는 서비스에는 Capability 빈이 생긴다 — 이미 감싼 디코더는 다시 감싸지 않는다")
    void capabilityBeanAndIdempotentWrap() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeignResponseBodyAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(FeignResponseBody.class));
        FeignResponseBody cap = new FeignResponseBody();
        feign.codec.Decoder once = cap.enrich(new feign.codec.StringDecoder());
        assertThat(cap.enrich(once)).isSameAs(once);
    }
}
