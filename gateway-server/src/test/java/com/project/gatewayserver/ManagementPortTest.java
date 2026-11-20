package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.client.HttpClient;

/**
 * B-119: 관리 포트 분리(docs/57 §25) — k8s 의 {@code MANAGEMENT_SERVER_PORT=9180} 과 같은 설정으로 게이트웨이(WebFlux)를 띄운다.
 *
 * <ul>
 *   <li>앱 포트에는 /actuator 가 없다(404) — Ingress · 터널이 닿는 포트에서 지표가 빠진다.</li>
 *   <li>관리 포트는 <b>앱 포트의 TLS 를 물려받는다</b> — k8s 프로브는 HTTPS 다. 물려받지 않으면 프로브가 모두 실패한다.</li>
 *   <li>관리 포트에 지표(prometheus) · health 가 있다 — 지표 에이전트 · 프로브.</li>
 * </ul>
 *
 * <p>시험용 인증서는 keytool 로 그 자리에서 만들고, 클라이언트는 그 인증서만 믿는다(검증을 끄지 않는다).
 *
 * <p>[JA] 管理ポートの分離 — アプリのポートに /actuator はなく、管理ポートはアプリの TLS を引き継ぐ。
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@DisplayName("B-119: 게이트웨이 관리 포트 — 앱 포트에 /actuator 없음 · 관리 포트는 HTTPS")
class ManagementPortTest {

    static final Path DIR;
    static final String PASSWORD = "changeit-test";

    static {
        try {
            DIR = Files.createTempDirectory("mgmt-tls");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            run(keytool, "-genkeypair", "-alias", "test", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", DIR.resolve("ks.p12").toString(), "-storepass", PASSWORD, "-keypass", PASSWORD);
            run(keytool, "-exportcert", "-rfc", "-alias", "test", "-keystore", DIR.resolve("ks.p12").toString(),
                    "-storepass", PASSWORD, "-file", DIR.resolve("ca.pem").toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(List.of(cmd)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(out);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("server.ssl.enabled", () -> true);
        registry.add("LOCAL_KEY_STORE_PATH", () -> "file:" + DIR.resolve("ks.p12"));
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> PASSWORD);
        registry.add("LOCAL_KEY_PASSWORD", () -> PASSWORD);
    }

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    private int status(int port, String path) throws Exception {
        var ssl = SslContextBuilder.forClient().trustManager(DIR.resolve("ca.pem").toFile()).build();
        HttpClient http = HttpClient.create().secure(s -> s.sslContext(ssl));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).baseUrl("https://localhost:" + port)
                .build().get().uri(path).exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                .block(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("🔴 앱 포트에는 /actuator 가 없다 — health · prometheus 모두 404")
    void appPortHasNoActuator() throws Exception {
        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(status(appPort, "/actuator/health")).as("앱 포트 health").isEqualTo(404);
        assertThat(status(appPort, "/actuator/prometheus")).as("앱 포트 prometheus").isEqualTo(404);
    }

    @Test
    @DisplayName("🔴 관리 포트는 앱 포트의 TLS 로 health · prometheus 를 낸다(프로브 · 지표 에이전트는 HTTPS)")
    void managementPortServesActuatorOverTls() throws Exception {
        // 시험에는 Redis 가 없어(포트 1) health 는 DOWN = 503 — '있다'(404 가 아니다)만 본다
        // [JA] テストには Redis がない(ポート 1)ので health は DOWN = 503 — 「ある」(404 ではない)ことだけを見る
        assertThat(status(managementPort, "/actuator/health")).as("관리 포트 health").isIn(200, 503);
        assertThat(status(managementPort, "/actuator/prometheus")).as("관리 포트 prometheus").isEqualTo(200);
    }
}
