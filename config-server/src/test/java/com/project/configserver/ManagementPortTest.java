package com.project.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * B-119: 관리 포트 분리(docs/57 §25) — 서블릿 + Spring Security(config-server)에서 k8s 와 같은 설정.
 *
 * <ul>
 *   <li>관리 포트는 앱 포트의 TLS 를 물려받는다(프로브는 HTTPS).</li>
 *   <li>관리 포트에도 <b>같은 보안 규칙</b>이 걸린다 — 프로브는 비밀번호 없이 열리고, 노출 목록 밖은 그대로 닫힌다.
 *       (관리 컨텍스트가 부모의 보안 필터를 물려받지 않으면 프로브 · 지표 밖도 모두 열렸을 것이다.)</li>
 *   <li>앱 포트에는 /actuator 가 없다 — 설정 · 복호화 API 는 그대로 비밀번호로만.</li>
 * </ul>
 *
 * <p>[JA] 管理ポートの分離 — サーブレット + Spring Security。管理ポートはアプリの TLS と同じセキュリティ規則を引き継ぐ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "management.endpoint.health.probes.enabled=true", "config-server.password=s3cret-for-test"})
@DisplayName("B-119: config-server 관리 포트 — 같은 TLS · 같은 보안 규칙, 앱 포트에 /actuator 없음")
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
        registry.add("server.ssl.enabled", () -> true);
        registry.add("server.ssl.key-store", () -> "file:" + DIR.resolve("ks.p12"));
        registry.add("server.ssl.key-store-type", () -> "PKCS12");
        registry.add("server.ssl.key-store-password", () -> PASSWORD);
        registry.add("server.ssl.key-password", () -> PASSWORD);
    }

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    /** 시험 인증서만 믿는 클라이언트(검증을 끄지 않는다). */
    // [JA] テスト証明書だけを信頼するクライアント(検証は切らない)。
    private int status(int port, String path) throws Exception {
        return status(port, path, null);
    }

    private int status(int port, String path, String basicPassword) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(DIR.resolve("ks.p12"))) {
            ks.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        HttpClient client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).GET();
        if (basicPassword != null) {
            req.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (ConfigServerSecurity.USER + ":" + basicPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("🔴 관리 포트 — HTTPS 로 프로브는 비밀번호 없이 200, 열린 목록 밖(metrics)은 비밀번호 없이 401")
    void managementPortKeepsTlsAndTheSecurityRules() throws Exception {
        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(status(managementPort, "/actuator/health/liveness")).as("liveness").isEqualTo(200);
        assertThat(status(managementPort, "/actuator/health/readiness")).as("readiness").isEqualTo(200);
        // ConfigServerSecurity 의 열린 목록 밖 — 보안 규칙이 관리 포트에도 걸리면 비밀번호 없이 401, 맞는 비밀번호면 인증을 지나
        // 노출 목록 밖(config-server 는 health · info · prometheus 만)이라 404. 보안이 걸리지 않았다면 둘 다 404 였다.
        // [JA] ConfigServerSecurity の開放リスト外 — セキュリティ規則が管理ポートにも掛かればパスワードなしで 401、正しいパスワードなら認証を通過し
        //      公開リスト外(config-server は health · info · prometheus のみ)なので 404。セキュリティが掛かっていなければ両方 404 だった。
        assertThat(status(managementPort, "/actuator/metrics")).as("metrics — 비밀번호 없이").isEqualTo(401);
        assertThat(status(managementPort, "/actuator/metrics", "s3cret-for-test")).as("metrics — 비밀번호로").isEqualTo(404);
    }

    @Test
    @DisplayName("🔴 앱 포트에는 /actuator 가 없다 — 설정 API 는 그대로 비밀번호로만")
    void appPortHasNoActuator() throws Exception {
        assertThat(status(appPort, "/actuator/health/liveness")).as("앱 포트 liveness").isNotEqualTo(200);
        assertThat(status(appPort, "/actuator/prometheus")).as("앱 포트 prometheus").isNotEqualTo(200);
        assertThat(status(appPort, "/order-service/default")).as("설정 API").isEqualTo(401);
    }
}
