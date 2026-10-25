package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpServer;

/**
 * B-102: 꼬리 샘플링의 "오류 추적" · "느린 추적" 은 초당 스팬 상한까지만 남는다 — 운영과 같은 Collector(0.160.0)에서.
 *
 * <p>2026-10-22 실습 용량 측정: 오류가 117만 건 나자 그 추적이 모두 저장돼 Jaeger 볼륨이 12G 로 관측 VM 디스크(19G)를 채웠다 —
 * Jaeger 는 재시작을 되풀이하고 Grafana 는 저장소에 쓰지 못했다. 장애가 가장 클 때 관측이 먼저 멈춘 것이다.
 *
 * <p>저장소의 {@code observability/otel-collector.yml} 에서 {@code tail_sampling} 을 <b>그대로</b> 읽어(결정 대기만 1초로),
 * 받은 추적을 이 JVM 의 작은 HTTP 서버로 내보내는 Collector 를 띄운다. 설정 검사({@code otelcol validate})는 정책의 모르는 키를
 * 그냥 통과시켜서(확인함) 실제로 보내 남는 수를 센다. 상한을 빼면 600 개가 모두 남아 실패한다.
 *
 * <p>[JA] テールサンプリングの「エラー」·「遅い」トレースは毎秒スパン上限までしか残らない — 本番と同じ Collector で。
 *      リポジトリの設定をそのまま読み、実際にトレースを送って残る数を数える(validate は未知のキーを通す)。
 */
@Tag("containers")
@DisplayName("B-102: 오류 · 느린 추적은 초당 상한까지 — 실제 Collector 0.160.0")
class TailSamplingBudgetTest {

    static final DockerImageName IMAGE = DockerImageName.parse("mirror.gcr.io/otel/opentelemetry-collector-contrib:0.160.0");
    static final int CAP = 50;   // 시험용 상한(초당 스팬) — 운영 기본값은 설정의 env 기본값
    static final Pattern TRACE_ID = Pattern.compile("\"traceId\"\\s*:\\s*\"([0-9a-f]{32})\"");

    static HttpServer sink;
    static final Set<String> exported = ConcurrentHashMap.newKeySet();
    static GenericContainer<?> collector;
    static final HttpClient http = HttpClient.newHttpClient();
    static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        return Files.exists(p.resolve("observability")) ? p : p.getParent();
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void start() throws IOException {
        sink = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        sink.createContext("/v1/traces", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = TRACE_ID.matcher(body);
            while (m.find()) {
                exported.add(m.group(1));
            }
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        sink.start();
        int port = sink.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);

        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> repo = yaml.load(Files.readString(repoRoot().resolve("observability/otel-collector.yml")));
        Map<String, Object> tail = (Map<String, Object>) ((Map<String, Object>) repo.get("processors")).get("tail_sampling");
        tail.put("decision_wait", "1s");   // 시험을 빠르게 — 정책은 그대로

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("receivers", Map.of("otlp", Map.of("protocols", Map.of("http", Map.of("endpoint", "0.0.0.0:4318")))));
        cfg.put("processors", Map.of("tail_sampling", tail));
        cfg.put("exporters", Map.of("otlphttp/sink", Map.of(
                "endpoint", "http://host.testcontainers.internal:" + port, "encoding", "json", "compression", "none")));
        cfg.put("service", Map.of("pipelines", Map.of("traces", Map.of(
                "receivers", List.of("otlp"), "processors", List.of("tail_sampling"), "exporters", List.of("otlphttp/sink")))));

        collector = new GenericContainer<>(IMAGE)
                .withCopyToContainer(Transferable.of(yaml.dump(cfg)), "/etc/budget-test.yml")
                .withCommand("--config=/etc/budget-test.yml")
                .withEnv("TAIL_SAMPLING_ERROR_SPANS_PER_SEC", String.valueOf(CAP))
                .withEnv("TAIL_SAMPLING_SLOW_SPANS_PER_SEC", String.valueOf(CAP))
                .withEnv("TAIL_SAMPLING_PERCENT", "0")   // 표본 정책이 섞이지 않게 — 상한만 본다
                .withExposedPorts(4318)
                .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        collector.start();
    }

    @AfterAll
    static void stop() {
        if (collector != null) {
            collector.stop();
        }
        if (sink != null) {
            sink.stop(0);
        }
    }

    /**
     * 추적 n 개(스팬 하나씩)를 한 요청으로 — 모두 같은 순간에 도착한다. prefix 는 추적 id 앞 4자, 나머지는 무작위.
     *
     * <p>🐛 처음에는 나머지를 일련번호로 채웠다 — Collector 의 결정 캐시(decision_cache)는 추적 id 의 <b>뒤 8바이트</b>를 키로
     * 써서, 폭주 때 "버림" 으로 기억된 0 ~ 599 번이 뒤에 보낸 평소 오류 0 ~ 9 번과 겹쳐 함께 버려졌다(상한 아래인데 0 ~ 1 개).
     * 실제 추적 id 는 무작위라 겹치지 않는다 — 시험도 그렇게 만든다.
     * [JA] 追跡 id の残りはランダム — 決定キャッシュは id の下位 8 バイトをキーにするため、連番だと別の追跡と衝突した。
     */
    private static void send(String prefix, int n, String service, boolean error, long durationMs) throws Exception {
        long end = System.currentTimeMillis() * 1_000_000L;
        long start = end - durationMs * 1_000_000L;
        StringBuilder spans = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                spans.append(',');
            }
            spans.append(String.format(
                    "{\"traceId\":\"%s%012x%016x\",\"spanId\":\"%016x\",\"name\":\"GET /api/x\",\"kind\":2,"
                            + "\"startTimeUnixNano\":\"%d\",\"endTimeUnixNano\":\"%d\",\"status\":{\"code\":%d}}",
                    prefix, RANDOM.nextLong() & 0xFFFF_FFFF_FFFFL, RANDOM.nextLong(), i + 1, start, end, error ? 2 : 1));
        }
        String body = "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\""
                + service + "\"}}]},\"scopeSpans\":[{\"spans\":[" + spans + "]}]}]}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create("http://" + collector.getHost() + ":" + collector.getMappedPort(4318) + "/v1/traces"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    /** 결정 대기(1초) · 내보내기가 끝나 그 접두사의 수가 3초 동안 그대로일 때까지. */
    private static long kept(String prefix) throws InterruptedException {
        long last = -1;
        int stable = 0;
        for (int i = 0; i < 40 && stable < 6; i++) {
            Thread.sleep(500);
            long now = exported.stream().filter(t -> t.startsWith(prefix)).count();
            stable = now == last && i >= 6 ? stable + 1 : 0;
            last = now;
        }
        return last;
    }

    @Test
    @DisplayName("🔴 오류 추적 600 개가 한꺼번에 와도 초당 상한만큼만 남는다 — 상한이 없으면 600 개 모두")
    void errorStormIsCapped() throws Exception {
        send("e100", 600, "catalog-service", true, 10);
        long n = kept("e100");
        assertThat(n).as("남은 오류 추적(상한 %d 스팬/초, 스팬 하나짜리 추적)", CAP).isBetween(1L, 3L * CAP);
    }

    @Test
    @DisplayName("🔴 느린 추적 600 개도 상한까지 — 시스템이 무너지면 거의 모든 요청이 느려진다")
    void slowStormIsCapped() throws Exception {
        Thread.sleep(1500);   // 앞 시험과 다른 초에
        send("5100", 600, "catalog-service", false, 2_000);
        long n = kept("5100");
        assertThat(n).as("남은 느린 추적").isBetween(1L, 3L * CAP);
    }

    @Test
    @DisplayName("평소의 오류(상한 아래)는 모두 남고, 돈이 오간 추적(order · pay · delivery)은 상한과 상관없이 모두 남는다")
    void normalErrorsAndMoneyPathAreKept() throws Exception {
        Thread.sleep(1500);
        send("a100", 10, "catalog-service", true, 10);
        assertThat(kept("a100")).as("상한 아래의 오류").isEqualTo(10);
        Thread.sleep(1500);
        send("b100", 200, "order-service", true, 10);
        assertThat(kept("b100")).as("주문 서비스의 오류 — 돈이 오간 추적").isEqualTo(200);
    }
}
