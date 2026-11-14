package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.PropertiesMeterFilter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * SLO(docs/91) — 지연 경계 버킷 · 기록 규칙 · 소진율 경보가 서로 맞는다.
 *
 * <p>경보는 {@code http_server_requests_seconds_bucket{le="0.3"}} 같은 <b>정확한 경계</b>를 읽는다. Micrometer 의 기본 히스토그램
 * 버킷에는 0.3 · 1.0 이 없다 — application-common.yml 의 {@code management.metrics.distribution.slo} 가 빠지거나 규칙의 경계가
 * 바뀌면 그 시계열이 없어 지연 SLI 가 조용히 비고 경보는 울릴 수 없다. 실제로 울리는지는 promtool 시험
 * (observability/tests/slo-rules.test.yml, {@code SloRulesPromtoolTest})이 본다.
 *
 * <p>[JA] SLO — 遅延境界のバケット · 記録ルール · バーンレート警報が互いに合う。既定のバケットに 0.3 · 1.0 はない。
 */
@DisplayName("SLO — 지연 경계 버킷 · 기록 규칙 · 소진율 경보 (docs/91)")
class SloRulesTest {

    private static final List<String> WINDOWS = List.of("5m", "30m", "1h", "6h");

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static MetricsProperties commonMetricsProperties() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> docs = new YamlPropertySourceLoader().load("application-common",
                new FileSystemResource(repoRoot().resolve("common/src/main/resources/application-common.yml")));
        // 프로필 문서는 빼고 기본 문서만(그 경계는 모든 프로필에 같아야 한다)
        docs.stream().filter(d -> d.getProperty("spring.config.activate.on-profile") == null)
                .forEach(d -> env.getPropertySources().addLast(d));
        return Binder.get(env).bind("management.metrics", MetricsProperties.class).orElseGet(MetricsProperties::new);
    }

    /** http.server.requests 타이머 하나를 기록하고 Prometheus 출력의 le 값을 모은다. */
    private static Set<String> exposedBuckets(MetricsProperties props) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        if (props != null) {
            registry.config().meterFilter(new PropertiesMeterFilter(props));
        }
        Timer.Builder b = Timer.builder("http.server.requests").tags("method", "GET", "uri", "/api/search", "status", "200");
        if (props == null) {
            b.publishPercentileHistogram();
        }
        b.register(registry).record(Duration.ofMillis(120));
        Matcher m = Pattern.compile("http_server_requests_seconds_bucket\\{.*?le=\"([^\"]+)\"").matcher(registry.scrape());
        Set<String> out = new TreeSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> group(String name) throws IOException {
        Map<String, Object> rules = new org.yaml.snakeyaml.Yaml().load(
                Files.readString(repoRoot().resolve("observability/alert-rules.yml"), StandardCharsets.UTF_8));
        return ((List<Map<String, Object>>) rules.get("groups")).stream()
                .filter(g -> name.equals(g.get("name"))).findFirst()
                .map(g -> (List<Map<String, Object>>) g.get("rules")).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> slos(List<Map<String, Object>> rules, String record) {
        return rules.stream().filter(r -> record.equals(r.get("record")))
                .map(r -> (String) ((Map<String, Object>) r.get("labels")).get("slo")).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("🔴 application-common.yml 의 slo 경계가 Prometheus 출력에 le 로 있다 — 기본 히스토그램만으로는 없다")
    void sloBoundariesAreExposedAsBuckets() throws IOException {
        Set<String> withSlo = exposedBuckets(commonMetricsProperties());
        assertThat(withSlo).as("application-common.yml 의 management.metrics.distribution.slo").contains("0.3", "1.0");
        assertThat(exposedBuckets(null)).as("기본 히스토그램 버킷만 — 그래서 slo 설정이 필요하다").doesNotContain("0.3", "1.0");
    }

    /** 서비스 쪽 SLO(http.server.requests) — 지연 경계가 버킷(le)이다. site-probe 는 탐침 시간(probe_duration_seconds)이라 따로. */
    private static final Set<String> HTTP_SLOS = Set.of("catalog-browse", "catalog-search", "order-create");

    @SuppressWarnings("unchecked")
    private static String label(Map<String, Object> r, String key) {
        return (String) ((Map<String, Object>) r.get("labels")).get(key);
    }

    @Test
    @DisplayName("🔴 slo 그룹이 읽는 경계(le)는 모두 노출되는 버킷이다 · SLO 마다 지연 목표의 threshold_seconds 가 그 SLO 의 지연 SLI 에 있다")
    void ruleBoundariesMatchExposedBuckets() throws IOException {
        Set<String> exposed = exposedBuckets(commonMetricsProperties());
        List<Map<String, Object>> rules = group("slo");
        Set<String> used = new TreeSet<>();
        for (Map<String, Object> r : rules) {
            Matcher m = Pattern.compile("le=\"([^\"]+)\"").matcher(String.valueOf(r.get("expr")));
            while (m.find()) {
                used.add(m.group(1));
            }
        }
        assertThat(used).isNotEmpty();
        assertThat(exposed).as("규칙의 le 가 실제 버킷에 없으면 지연 SLI 가 빈다").containsAll(used);

        for (Map<String, Object> objective : rules.stream().filter(r -> "slo:objective:latency".equals(r.get("record"))).toList()) {
            String slo = label(objective, "slo");
            String threshold = label(objective, "threshold_seconds");
            List<String> slow = rules.stream().filter(r -> String.valueOf(r.get("record")).startsWith("slo:sli_slow:") && slo.equals(label(r, "slo")))
                    .map(r -> String.valueOf(r.get("expr"))).toList();
            assertThat(slow).as(slo + " 의 지연 SLI 넷").hasSize(WINDOWS.size());
            String expected = HTTP_SLOS.contains(slo) ? "le=\"" + threshold + "\"" : "> bool " + threshold;
            assertThat(slow).as(slo + " — 목표의 경계 " + threshold + " 를 SLI 가 읽는다").allMatch(e -> e.contains(expected));
        }
    }

    @Test
    @DisplayName("🔴 SLO 넷(browse · search · order-create · site-probe)마다 목표 둘 · 창 넷의 SLI · 요청 수 · 예산용 증가분, 소진율 경보 넷은 목표에서 예산을 계산하고 요청이 적으면 울리지 않는다")
    void everySloHasObjectivesSlisAndBurnAlerts() throws IOException {
        List<Map<String, Object>> rules = group("slo");
        Set<String> names = slos(rules, "slo:objective:availability");
        assertThat(names).containsExactlyInAnyOrder("catalog-browse", "catalog-search", "order-create", "site-probe");
        for (String r : List.of("slo:objective:latency", "slo:requests:rate1h", "slo:requests:increase1h", "slo:errors:increase1h", "slo:slow:increase1h")) {
            assertThat(slos(rules, r)).as(r).isEqualTo(names);
        }
        for (String w : WINDOWS) {
            assertThat(slos(rules, "slo:sli_error:ratio_rate" + w)).as("오류 SLI " + w).isEqualTo(names);
            assertThat(slos(rules, "slo:sli_slow:ratio_rate" + w)).as("지연 SLI " + w).isEqualTo(names);
        }
        Map<String, String> alerts = rules.stream().filter(r -> r.containsKey("alert"))
                .collect(Collectors.toMap(r -> (String) r.get("alert"), r -> String.valueOf(r.get("expr"))));
        assertThat(alerts).containsOnlyKeys("SloErrorBudgetBurnFast", "SloErrorBudgetBurnSlow",
                "SloLatencyBudgetBurnFast", "SloLatencyBudgetBurnSlow", "SiteProbeDown");
        alerts.forEach((name, expr) -> {
            if (name.equals("SiteProbeDown")) {
                return;
            }
            assertThat(expr).as(name).contains(name.contains("Error") ? "slo:objective:availability" : "slo:objective:latency")
                    .contains("slo:requests:rate1h > 0.03");
            assertThat(expr).as(name + " — 긴 창과 짧은 창 둘 다")
                    .contains(name.endsWith("Fast") ? "ratio_rate1h" : "ratio_rate6h")
                    .contains(name.endsWith("Fast") ? "ratio_rate5m" : "ratio_rate30m");
        });
        // 검색은 catalog-browse 에서 빠지고 catalog-search 에만 — 드문 필터 조합의 503 이 상품 · 홈의 예산을 쓰지 않게
        String browse = rules.stream().filter(r -> "slo:requests:rate1h".equals(r.get("record")) && "catalog-browse".equals(label(r, "slo")))
                .map(r -> String.valueOf(r.get("expr"))).findFirst().orElseThrow();
        assertThat(browse).contains("uri!~\".*(actuator|admin|internal|search).*\"");
    }

    @Test
    @DisplayName("🔴 14일 예산 — 오류 · 지연 예산 사용률 · 절반(경고) · 소진(심각) 경보, 요청이 적으면 울리지 않는다(보존 15일 안)")
    void budgetGroupFitsRetention() throws IOException {
        List<Map<String, Object>> rules = group("slo-budget");
        Set<String> budgets = rules.stream().filter(r -> "slo:budget_used:ratio14d".equals(r.get("record")))
                .map(r -> label(r, "budget")).collect(Collectors.toCollection(TreeSet::new));
        assertThat(budgets).containsExactly("error", "latency");
        Map<String, String> alerts = rules.stream().filter(r -> r.containsKey("alert"))
                .collect(Collectors.toMap(r -> (String) r.get("alert"), r -> String.valueOf(r.get("expr"))));
        assertThat(alerts).containsOnlyKeys("SloErrorBudgetHalfSpent", "SloErrorBudgetExhausted");
        assertThat(alerts.get("SloErrorBudgetHalfSpent")).contains(">= 0.5", "slo:requests:avg_rate14d > 0.03");
        assertThat(alerts.get("SloErrorBudgetExhausted")).contains(">= 1 ", "slo:requests:avg_rate14d > 0.03");
        // 창(14d) < 운영 Prometheus 보존 — 넘으면 앞부분이 없는 채로 셈이 틀린다
        String compose = Files.readString(repoRoot().resolve("docker-compose-observability.oci.yml"), StandardCharsets.UTF_8);
        Matcher retention = Pattern.compile("retention\\.time=(\\d+)d").matcher(compose);
        assertThat(retention.find()).isTrue();
        assertThat(Integer.parseInt(retention.group(1))).as("보존(일) > 예산 창 14일").isGreaterThan(14);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 바깥 탐침 — 운영 덧씌우기의 blackbox-exporter(정확한 판 · 포트 없음 · 설정 마운트), TLS 검증, Prometheus 작업이 운영 주소를 30초마다 그 모듈로")
    void siteProbeIsWiredOnProductionNode() throws IOException {
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> services = (Map<String, Object>) yaml.<Map<String, Object>>load(Files.readString(
                repoRoot().resolve("docker-compose-observability.oci.yml"), StandardCharsets.UTF_8)).get("services");
        Map<String, Object> exporter = (Map<String, Object>) services.get("blackbox-exporter");
        assertThat(exporter).as("운영 덧씌우기의 blackbox-exporter").isNotNull();
        assertThat((String) exporter.get("image")).matches("prom/blackbox-exporter:v\\d+\\.\\d+\\.\\d+");
        assertThat(exporter).as("관측 포트는 공개하지 않는다").doesNotContainKeys("ports", "network_mode");
        assertThat((List<String>) exporter.get("volumes")).contains("./observability/blackbox.yml:/etc/blackbox/blackbox.yml:ro");

        String bbText = Files.readString(repoRoot().resolve("observability/blackbox.yml"), StandardCharsets.UTF_8);
        assertThat(bbText.replaceAll("(?m)^\\s*#.*$", "")).as("TLS 검증을 끄지 않는다").doesNotContain("insecure_skip_verify");
        Map<String, Object> modules = (Map<String, Object>) yaml.<Map<String, Object>>load(bbText).get("modules");

        Map<String, Object> prom = yaml.load(Files.readString(repoRoot().resolve("observability/prometheus.oci.yml"), StandardCharsets.UTF_8));
        Map<String, Object> job = ((List<Map<String, Object>>) prom.get("scrape_configs")).stream()
                .filter(j -> "site-probe".equals(j.get("job_name"))).findFirst().orElseThrow();
        assertThat(job).containsEntry("metrics_path", "/probe").containsEntry("scrape_interval", "30s");
        List<Map<String, Object>> statics = (List<Map<String, Object>>) job.get("static_configs");
        assertThat(statics).hasSizeGreaterThanOrEqualTo(2).allSatisfy(sc -> {
            assertThat((List<String>) sc.get("targets")).allMatch(t -> t.startsWith("https://www.ysyproject.com/"));
            assertThat(modules).as("작업이 부르는 모듈이 blackbox.yml 에 있다").containsKey((String) ((Map<String, Object>) sc.get("labels")).get("module"));
        });
        assertThat(String.valueOf(job.get("relabel_configs"))).contains("__param_target", "__param_module", "blackbox-exporter:9115");
        // 규칙은 이 작업 이름으로 탐침을 읽는다
        assertThat(Files.readString(repoRoot().resolve("observability/alert-rules.yml"), StandardCharsets.UTF_8))
                .contains("probe_success{job=\"site-probe\"}");
    }

    @Test
    @DisplayName("🔴 promtool 시험이 slo · slo-budget · kafka-consumer 그룹의 모든 경보를 다룬다")
    void promtoolTestCoversEveryAlert() throws IOException {
        String test = Files.readString(repoRoot().resolve("observability/tests/slo-rules.test.yml"), StandardCharsets.UTF_8)
                + Files.readString(repoRoot().resolve("observability/tests/slo-budget.test.yml"), StandardCharsets.UTF_8);
        assertThat(test).contains("../alert-rules.yml");
        for (String g : List.of("slo", "slo-budget", "kafka-consumer")) {
            group(g).stream().filter(r -> r.containsKey("alert")).map(r -> (String) r.get("alert"))
                    .forEach(a -> assertThat(test).as("promtool 시험의 " + a).contains("alertname: " + a));
        }
    }
}
