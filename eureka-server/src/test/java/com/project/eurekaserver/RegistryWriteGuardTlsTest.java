package com.project.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.netflix.eureka.registry.PeerAwareInstanceRegistry;

/**
 * 실제 TLS 로 띄운 Eureka(client-auth=want, 쓰기 판정 enforce)에 <b>실제 Eureka 클라이언트</b>가 등록해 본다 — 클라이언트 설정은 서비스가
 * 쓰는 {@code application-common.yml} 그대로다(docs/57 §16 "Eureka 쓰기").
 *
 * <p>확인하는 것:
 * <ul>
 *   <li>신원 인증서(CN=user-service)를 낸 user-service 는 등록된다</li>
 *   <li>🐛 JVM 키스토어만 있고 {@code eureka.client.tls} 가 꺼진 클라이언트는 인증서를 <b>내지 않는다</b> — 같은 JVM 키스토어로 JDK
 *       기본 연결(Feign 과 같은 길)은 인증서를 낸다. 그래서 공통 설정에 클라이언트 TLS 를 따로 둔다</li>
 *   <li>다른 서비스의 인증서(CN=cart-service)로 user-service 를 등록하지 못한다</li>
 *   <li>인증서 없이 OUT_OF_SERVICE 를 지정하지 못한다 · 읽기는 그대로 된다</li>
 * </ul>
 *
 * <p>[JA] 実際の TLS で起動した Eureka に実際の Eureka クライアントが登録してみる。クライアント設定はサービスが使う
 * application-common.yml そのまま。JVM キーストアだけでは Eureka クライアントは証明書を出さないことも確かめる。
 */
@DisplayName("Eureka 쓰기 판정 — 실제 TLS · 실제 Eureka 클라이언트")
class RegistryWriteGuardTlsTest {

    private static final String PW = "changeit";

    @TempDir
    static Path dir;

    private static ConfigurableApplicationContext server;
    private static int port;
    private static final List<String> savedProps = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=internal-ca",
                "-ext", "bc:c", "-validity", "2", "-keystore", "ca.p12", "-storepass", PW, "-storetype", "PKCS12");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-file", "ca.crt", "-keystore", "ca.p12", "-storepass", PW);
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.crt", "-keystore", "truststore.p12",
                "-storepass", PW, "-storetype", "PKCS12");
        leaf("server", "localhost");
        leaf("user-service", "user-service");
        leaf("cart-service", "cart-service");

        // JDK 기본 SSLContext(= Feign)가 읽는 JVM 키스토어 — 운영 매니페스트의 -Djavax.net.ssl.keyStore 와 같은 자리.
        setProp("javax.net.ssl.keyStore", dir.resolve("user-service.p12").toString());
        setProp("javax.net.ssl.keyStorePassword", PW);
        setProp("javax.net.ssl.keyStoreType", "PKCS12");
        setProp("javax.net.ssl.trustStore", dir.resolve("truststore.p12").toString());
        setProp("javax.net.ssl.trustStorePassword", PW);
        setProp("javax.net.ssl.trustStoreType", "PKCS12");

        server = new SpringApplicationBuilder(EurekaServerApplication.class).run(
                "--server.port=0",
                "--server.ssl.key-store=file:" + dir.resolve("server.p12"),
                "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-store-password=" + PW,
                "--server.ssl.key-password=" + PW,
                "--server.ssl.client-auth=want",
                "--eureka.client.register-with-eureka=false",
                "--eureka.client.fetch-registry=false",
                "--eureka.client.tls.enabled=false",
                "--eureka.server.response-cache-update-interval-ms=100",
                "--eureka.server.use-read-only-response-cache=false",
                "--LOCAL_KEY_STORE_PATH=" + dir.resolve("server.p12"),
                "--LOCAL_TRUST_STORE_PATH=" + dir.resolve("truststore.p12"),
                "--LOCAL_KEY_STORE_PASSWORD=" + PW,
                "--LOCAL_KEY_PASSWORD=" + PW,
                "--LOCAL_CACERT_PATH=" + dir.resolve("ca.crt"),
                "--eureka-guard.write-mode=enforce",
                // 운영 ConfigMap 그대로 — 서비스 간 내부 API 판정은 enforce 다(Eureka 에서는 꺼져야 한다).
                "--internal-auth.mode=enforce",
                "--internal-auth.allowed-subjects=user-service");
        port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
        for (String k : savedProps) {
            System.clearProperty(k);
        }
    }

    // ── 도우미 ───────────────────────────────────────────────────────────

    private static void setProp(String k, String v) {
        if (System.getProperty(k) == null) {
            savedProps.add(k);
        }
        System.setProperty(k, v);
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool 실패: " + String.join(" ", args) + "\n" + out);
        }
    }

    /** 내부 CA 가 서명한 키스토어(이름.p12, 별칭 이름) — 사슬에 CA 를 함께 넣는다(cert-manager 의 keystore.p12 와 같은 모양). */
    private static void leaf(String name, String cn) throws Exception {
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-validity", "2", "-keystore", name + ".p12", "-storepass", PW, "-storetype", "PKCS12");
        keytool("-certreq", "-alias", name, "-file", name + ".csr", "-keystore", name + ".p12", "-storepass", PW);
        keytool("-gencert", "-alias", "ca", "-infile", name + ".csr", "-outfile", name + ".crt", "-rfc",
                "-ext", "eku=serverAuth,clientAuth", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-keystore", "ca.p12", "-storepass", PW);
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.crt", "-keystore", name + ".p12", "-storepass", PW);
        keytool("-importcert", "-alias", name, "-file", name + ".crt", "-keystore", name + ".p12", "-storepass", PW);
    }

    /** 클라이언트 쪽 애플리케이션 — @Configuration 을 붙이지 않는다(서버 · 다른 테스트의 컴포넌트 스캔에 걸리지 않게). */
    @EnableAutoConfiguration
    static class Client {
    }

    /** 서비스와 같은 공통 설정(application-common.yml)으로 Eureka 클라이언트를 띄워 기동 중에 등록시킨다. 등록이 거절되면 기동이 실패한다. */
    private static ConfigurableApplicationContext client(String app, boolean tls, String keyStore) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.import=classpath:application-common.yml",
                "--spring.application.name=" + app,
                "--eureka.client.service-url.defaultZone=https://localhost:" + port + "/eureka/",
                "--eureka.client.fetch-registry=false",
                // 서비스의 클래스패스에는 Jersey 가 없어 RestTemplate(HttpClient 5) 전송을 쓴다. 이 모듈에는 서버용 Jersey 가 있으므로
                // 서비스와 같은 전송이 되게 끈다.
                "--eureka.client.jersey.enabled=false",
                "--eureka.client.should-enforce-registration-at-init=true",
                "--eureka.instance.instance-id=" + app + ":test",
                "--EUREKA_CLIENT_TLS_ENABLED=" + tls,
                "--LOCAL_KEY_STORE_PASSWORD=" + PW,
                "--LOCAL_TRUST_STORE_PATH=" + dir.resolve("truststore.p12")));
        if (keyStore != null) {
            args.add("--EUREKA_CLIENT_KEY_STORE=file:" + dir.resolve(keyStore));
        }
        return new SpringApplicationBuilder(Client.class).web(WebApplicationType.NONE).run(args.toArray(String[]::new));
    }

    @SuppressWarnings("unchecked")
    private static long rejected() {
        return ((org.springframework.boot.web.servlet.FilterRegistrationBean<RegistryWriteGuard>) server
                .getBean("registryWriteGuard")).getFilter().rejected();
    }

    private static boolean registered(String app) {
        var a = server.getBean(PeerAwareInstanceRegistry.class).getApplication(app.toUpperCase());
        return a != null && !a.getInstances().isEmpty();
    }

    /** CA 만 믿고 클라이언트 인증서는 내지 않는 HTTP 클라이언트. */
    private static HttpClient noCertClient() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(dir.resolve("truststore.p12").toFile())) {
            trust.load(in, PW.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    // ── 확인 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 신원 인증서(CN=user-service)를 내는 공통 설정의 클라이언트는 등록된다")
    void identityClientRegisters() {
        long before = rejected();
        try (var c = client("user-service", true, "user-service.p12")) {
            assertThat(registered("user-service")).isTrue();
        }
        assertThat(rejected()).as("등록 · 상태 갱신 · 취소 모두 통과했다").isEqualTo(before);
    }

    @Test
    @DisplayName("🐛 eureka.client.tls 가 꺼진 클라이언트는 JVM 키스토어가 있어도 인증서를 내지 않아 거절된다 — JDK 기본 연결은 낸다")
    void jvmKeystoreIsNotUsedByEurekaClient() throws Exception {
        // 같은 JVM 키스토어로 JDK 기본 SSLContext(Feign 의 길)는 클라이언트 인증서를 낸다 — 쓰기가 통과해 404(없는 인스턴스)까지 간다.
        var conn = (HttpsURLConnection) URI.create("https://localhost:" + port
                + "/eureka/apps/USER-SERVICE/nobody/status?value=UP").toURL().openConnection();
        conn.setRequestMethod("PUT");
        assertThat(conn.getResponseCode()).as("JDK 기본 연결은 user-service 인증서를 낸다").isNotEqualTo(403);
        conn.disconnect();

        // Eureka 클라이언트(HttpClient 5)는 같은 JVM 에서 인증서를 내지 않는다 → 판정이 거절 → 등록 실패로 기동 실패.
        long before = rejected();
        assertThatThrownBy(() -> client("coupon-service", false, null).close())
                .isInstanceOf(RuntimeException.class);
        assertThat(rejected()).as("판정이 거절했다(다른 이유의 실패가 아니다)").isGreaterThan(before);
        assertThat(registered("coupon-service")).isFalse();
    }

    @Test
    @DisplayName("🔴 다른 서비스의 인증서(CN=cart-service)로 user-service 를 등록하지 못한다")
    void otherServiceCannotRegisterAsUserService() {
        long before = rejected();
        assertThatThrownBy(() -> client("chat-service", true, "cart-service.p12").close())
                .isInstanceOf(RuntimeException.class);
        assertThat(rejected()).as("판정이 거절했다").isGreaterThan(before);
        assertThat(registered("chat-service")).isFalse();
    }

    @Test
    @DisplayName("🔴 인증서 없이 OUT_OF_SERVICE 를 지정하지 못한다(403) · 등록부 읽기는 된다(200)")
    void anonymousWritesAreRejectedReadsAreNot() throws Exception {
        HttpClient http = noCertClient();
        var put = http.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port
                + "/eureka/apps/ORDER-SERVICE/x/status?value=OUT_OF_SERVICE")).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(403);
        var get = http.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/eureka/apps"))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(Files.exists(dir.resolve("ca.crt"))).isTrue();
    }

    @Test
    @DisplayName("🔒 판정은 Jersey 가 보는 앱 이름(디코딩 · ;… 제거 뒤)으로 한다 — 인코딩해도 자기 앱은 자기 앱, 남의 앱은 남의 앱")
    void encodedPathIsJudgedAsJerseySeesIt() throws Exception {
        // JDK 기본 연결은 JVM 키스토어(CN=user-service)를 낸다.
        assertThat(put("/eureka/apps/USER%2DSERVICE;v=1/nobody/status?value=UP"))
                .as("자기 앱을 인코딩해 적어도 자기 앱 — 판정을 지나 Eureka 가 없는 인스턴스라 답한다").isNotEqualTo(403);
        assertThat(put("/eureka/apps/ORDER%2DSERVICE/nobody/status?value=OUT_OF_SERVICE"))
                .as("남의 앱을 인코딩해도 남의 앱").isEqualTo(403);
    }

    private static int put(String pathAndQuery) throws Exception {
        var conn = (HttpsURLConnection) URI.create("https://localhost:" + port + pathAndQuery).toURL().openConnection();
        conn.setRequestMethod("PUT");
        try {
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    @Test
    @DisplayName("🔴 common 의 내부 API 판정(InternalCallerFilter)은 Eureka 에서 꺼져 있다 — 운영처럼 enforce 를 줘도")
    void internalCallerFilterIsOff() {
        var bean = (org.springframework.boot.web.servlet.FilterRegistrationBean<?>) server.getBean("internalCallerFilter");
        assertThat(bean.isEnabled()).as("허용 주체 밖(config-server)의 하트비트마다 [mTLS rule] 경고가 남지 않게").isFalse();
    }

    /** KeyManagerFactory 가 키스토어를 읽는지 — 테스트 자료가 틀려서 통과하는 일이 없게. */
    @Test
    @DisplayName("테스트 자료: 신원 키스토어에 키가 있다")
    void fixtureHasKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(dir.resolve("user-service.p12").toFile())) {
            ks.load(in, PW.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW.toCharArray());
        assertThat(ks.isKeyEntry("user-service")).isTrue();
    }
}
