package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.core.io.support.SpringFactoriesLoader;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * 서비스 간 호출의 쉬는 연결 수 — {@code http.maxConnections} 5 → 64 (docs/83 §5).
 *
 * <p>🐛 2026-10-18 order 부하 측정: cart CPU 의 23% 가 catalog 로 가는 Feign 호출의 새 TLS 핸드셰이크였다. JDK keep-alive 캐시가
 * 대상마다 쉬는 연결을 5개만 남겨, 동시 호출이 오르내릴 때마다 넘친 연결을 닫고 다시 열었다.
 *
 * <p>[JA] サービス間呼び出しの待機中の接続数 — http.maxConnections 5 → 64。cart の CPU の 23% が新しい TLS ハンドシェイクだった。
 */
@DisplayName("서비스 간 호출의 쉬는 연결 수(http.maxConnections)")
class HttpKeepAliveTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("기본 64 · 환경 변수(양의 정수만) · 이미 정해진 속성은 덮지 않는다")
    void appliesDefaultEnvAndKeepsExplicit() {
        Properties p = new Properties();
        HttpKeepAlive.apply(Map.of(), p);
        assertThat(p.getProperty("http.maxConnections")).isEqualTo("64");

        p = new Properties();
        HttpKeepAlive.apply(Map.of("HTTP_KEEPALIVE_MAX_CONNECTIONS", " 128 "), p);
        assertThat(p.getProperty("http.maxConnections")).isEqualTo("128");

        for (String bad : List.of("0", "-3", "many")) {
            p = new Properties();
            HttpKeepAlive.apply(Map.of("HTTP_KEEPALIVE_MAX_CONNECTIONS", bad), p);
            assertThat(p.getProperty("http.maxConnections")).as(bad).isEqualTo("64");
        }

        p = new Properties();
        p.setProperty("http.maxConnections", "7");   // 명령행 -D
        HttpKeepAlive.apply(Map.of("HTTP_KEEPALIVE_MAX_CONNECTIONS", "128"), p);
        assertThat(p.getProperty("http.maxConnections")).isEqualTo("7");
    }

    @Test
    @DisplayName("🔴 SpringApplication 의 실행 리스너로 등록돼 있다 — keep-alive 캐시를 처음 쓰기(설정 서버 · Eureka) 전에 돈다")
    void registeredAsRunListener() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(SpringApplicationRunListener.class, getClass().getClassLoader()))
                .contains(HttpKeepAlive.RunListener.class.getName());
    }

    /**
     * 실제 HTTPS 로 — 동시 호출이 오르내리는 모양(32 스레드, 호출 사이 평균 20ms 의 지수 분포 대기, 1,600 호출)에서 서버가 받은 연결 수.
     * JDK 는 이 속성을 한 번만 읽으므로 JVM 을 따로 띄운다.
     */
    @Test
    @DisplayName("🔴 동시 호출이 오르내려도 연결을 다시 쓴다 — 적용하면 연결 ≈ 스레드 수, 안 하면(JDK 기본 5) 그 몇 배의 새 TLS 연결")
    void reusesConnectionsUnderFluctuatingConcurrency() throws Exception {
        Path keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "keytool 이 없다");
        Path ks = tmp.resolve("ks.p12");
        Process k = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit").redirectErrorStream(true).start();
        k.getInputStream().readAllBytes();
        assertThat(k.waitFor(60, TimeUnit.SECONDS) && k.exitValue() == 0).as("keytool").isTrue();

        int with = connections(ks, "apply");
        int without = connections(ks, "jdk-default");
        assertThat(with).as("적용: 연결 ≈ 스레드 32").isLessThanOrEqualTo(40);
        assertThat(without).as("JDK 기본 5: 넘친 연결을 닫고 다시 연다").isGreaterThan(2 * with);
    }

    private static int connections(Path ks, String mode) throws Exception {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        Process p = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), Probe.class.getName(), ks.toString(), mode)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as(out).isTrue();
        String last = out.lines().filter(l -> l.startsWith("connections=")).findFirst().orElseThrow(() -> new AssertionError(out));
        return Integer.parseInt(last.substring("connections=".length()));
    }

    /** 따로 띄우는 JVM — HTTPS 서버와 Feign 기본 클라이언트와 같은 HttpURLConnection 호출. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            if (args[1].equals("apply")) {
                HttpKeepAlive.apply(Map.of(), System.getProperties());
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(args[0])) {
                ks.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, "changeit".toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(ctx));
            Set<Integer> conns = ConcurrentHashMap.newKeySet();
            server.createContext("/", ex -> {
                conns.add(ex.getRemoteAddress().getPort());   // 클라이언트 포트 = 연결 하나
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.setExecutor(Executors.newFixedThreadPool(64));
            server.start();
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            URL url = new URL("https://localhost:" + server.getAddress().getPort() + "/x");

            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                long seed = t;
                pool.submit(() -> {
                    Random r = new Random(seed);
                    try {
                        for (int i = 0; i < 50; i++) {
                            Thread.sleep((long) (-Math.log(1 - r.nextDouble()) * 20));
                            HttpURLConnection c = (HttpURLConnection) url.openConnection();
                            c.setRequestProperty("Connection", "keep-alive");
                            try (InputStream in = c.getInputStream()) {
                                in.readAllBytes();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            System.out.println("connections=" + conns.size());
            System.exit(0);
        }
    }
}
