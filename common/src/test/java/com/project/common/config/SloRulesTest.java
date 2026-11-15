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

    private static final String HTTP_TIMER = "http.server.requests";
    /** Spring Cloud Gateway 의 라우트 타이머(GatewayMetricsFilter — {@code spring.cloud.gateway.server.webflux.metrics.enabled}). */
    private static final String GATEWAY_TIMER = "spring.cloud.gateway.requests";

    /** http.server.requests 타이머 하나를 기록하고 Prometheus 출력의 le 값을 모은다. */
    private static Set<String> exposedBuckets(MetricsProperties props) {
        return exposedBuckets(props, HTTP_TIMER, "method", "GET", "uri", "/api/search", "status", "200");
    }

    /** 그 이름의 타이머 하나를 기록하고 Prometheus 출력의 le 값을 모은다. props 가 null 이면 기본 히스토그램(percentiles-histogram)만. */
    private static Set<String> exposedBuckets(MetricsProperties props, String timer, String... tags) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        if (props != null) {
            registry.config().meterFilter(new PropertiesMeterFilter(props));
        }
        Timer.Builder b = Timer.builder(timer).tags(tags);
        if (props == null) {
            b.publishPercentileHistogram();
        }
        b.register(registry).record(Duration.ofMillis(120));
        Matcher m = Pattern.compile(timer.replace('.', '_') + "_seconds_bucket\\{.*?le=\"([^\"]+)\"").matcher(registry.scrape());
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
        // 게이트웨이 라우트 타이머 — SLO gateway 의 2초 경계. percentiles-histogram 은 켜지 않으므로 slo 경계가 유일한 버킷이다
        Set<String> gateway = exposedBuckets(commonMetricsProperties(), GATEWAY_TIMER, "routeId", "catalog-service", "httpStatusCode", "200");
        assertThat(gateway).as("application-common.yml 의 slo." + GATEWAY_TIMER).contains("2.0");
        assertThat(gateway).as("라우트 타이머에는 경계 버킷만(+Inf 포함) — 백분위 히스토그램을 켜면 라우트 × 상태마다 70여 개").hasSizeLessThanOrEqualTo(4);
    }

    /** 히스토그램 SLO(http.server.requests · gateway 라우트 타이머) — 지연 경계가 버킷(le)이다. site-probe 는 탐침 시간(probe_duration_seconds)이라 따로. */
    private static final Set<String> HTTP_SLOS = Set.of("catalog-browse", "catalog-search", "order-create", "gateway");

    @SuppressWarnings("unchecked")
    private static String label(Map<String, Object> r, String key) {
        return (String) ((Map<String, Object>) r.get("labels")).get(key);
    }

    @Test
    @DisplayName("🔴 slo 그룹이 읽는 경계(le)는 모두 노출되는 버킷이다 · SLO 마다 지연 목표의 threshold_seconds 가 그 SLO 의 지연 SLI 에 있다")
    void ruleBoundariesMatchExposedBuckets() throws IOException {
        List<Map<String, Object>> rules = group("slo");
        Map<String, Set<String>> exposedByTimer = Map.of(
                HTTP_TIMER, exposedBuckets(commonMetricsProperties()),
                GATEWAY_TIMER, exposedBuckets(commonMetricsProperties(), GATEWAY_TIMER, "routeId", "catalog-service", "httpStatusCode", "200"));
        exposedByTimer.forEach((timer, exposed) -> {
            Set<String> used = new TreeSet<>();
            for (Map<String, Object> r : rules) {
                Matcher m = Pattern.compile(Pattern.quote(timer.replace('.', '_')) + "_seconds_bucket\\{[^}]*le=\"([^\"]+)\"").matcher(String.valueOf(r.get("expr")));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
            assertThat(used).as(timer + " 의 경계를 읽는 규칙").isNotEmpty();
            assertThat(exposed).as(timer + " — 규칙의 le 가 실제 버킷에 없으면 지연 SLI 가 빈다").containsAll(used);
        });

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
    @DisplayName("🔴 SLO 다섯(browse · search · order-create · gateway · site-probe)마다 목표 둘 · 창 넷의 SLI · 요청 수 · 예산용 증가분, 소진율 경보 넷은 목표에서 예산을 계산하고 요청이 적으면 울리지 않는다")
    void everySloHasObjectivesSlisAndBurnAlerts() throws IOException {
        List<Map<String, Object>> rules = group("slo");
        Set<String> names = slos(rules, "slo:objective:availability");
        assertThat(names).containsExactlyInAnyOrder("catalog-browse", "catalog-search", "order-create", "gateway", "site-probe");
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
        // API 대상은 앞단 캐시를 지나친다 — catalog 의 EdgeCacheHeaders 가 probe 쿼리에는 no-store(docs/91 §2-9). 빼면 원본이 죽어도
        // 앞단 캐시의 200 으로 탐침이 성공한다
        List<String> apiTargets = statics.stream().flatMap(sc -> ((List<String>) sc.get("targets")).stream())
                .filter(t -> t.contains("/api/")).toList();
        assertThat(apiTargets).as("API 탐침 대상").isNotEmpty().allMatch(t -> t.matches(".*[?&]probe=[^&]+.*"));
        // 규칙은 이 작업 이름으로 탐침을 읽는다
        assertThat(Files.readString(repoRoot().resolve("observability/alert-rules.yml"), StandardCharsets.UTF_8))
                .contains("probe_success{job=\"site-probe\"}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> m, String... path) {
        Map<String, Object> cur = m;
        for (String p : path) {
            cur = cur == null ? null : (Map<String, Object>) cur.get(p);
        }
        return cur;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 SLO gateway — 게이트웨이가 라우트 타이머를 켠다(4.3 기본 꺼짐), 규칙이 빼는 라우트는 게이트웨이에 있는 SockJS · WebSocket 라우트뿐이다")
    void gatewaySliIsWired() throws IOException {
        Map<String, Object> gw = null;
        for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(Files.readString(
                repoRoot().resolve("gateway-server/src/main/resources/application-local.yml"), StandardCharsets.UTF_8))) {
            if (doc instanceof Map<?, ?> m && at((Map<String, Object>) m, "spring", "cloud", "gateway", "server", "webflux") != null) {
                gw = at((Map<String, Object>) m, "spring", "cloud", "gateway", "server", "webflux");
            }
        }
        assertThat(gw).as("게이트웨이 설정 spring.cloud.gateway.server.webflux").isNotNull();
        // 꺼져 있으면 spring_cloud_gateway_requests_seconds 가 없어 SLO gateway 의 SLI 가 조용히 빈다(경보는 요청 하한에 걸려 울리지 않는다)
        assertThat(at(gw, "metrics")).as("spring.cloud.gateway.server.webflux.metrics").isNotNull()
                .containsEntry("enabled", true).doesNotContainKey("path-tags");

        Map<String, String> pathById = new java.util.LinkedHashMap<>();
        for (Map<String, Object> route : (List<Map<String, Object>>) gw.get("routes")) {
            String path = ((List<Object>) route.get("predicates")).stream().map(String::valueOf)
                    .filter(p -> p.startsWith("Path=")).findFirst().orElse("");
            pathById.put((String) route.get("id"), path);
        }
        List<Map<String, Object>> rules = group("slo");
        Set<String> excluded = new TreeSet<>();
        for (Map<String, Object> r : rules) {
            if (!"gateway".equals(label(r, "slo")) || !String.valueOf(r.get("expr")).contains("spring_cloud_gateway_requests_seconds")) {
                continue;
            }
            String expr = String.valueOf(r.get("expr"));
            assertThat(expr).as(r.get("record") + " — 게이트웨이 서비스의 라우트 타이머만").contains("service=\"gateway-server\"");
            Matcher m = Pattern.compile("routeId!=\"([^\"]+)\"").matcher(expr);
            while (m.find()) {
                excluded.add(m.group(1));
            }
        }
        // 라우트 이름을 바꾸면 빼던 라우트가 조용히 SLO 에 들어온다(SockJS 스트리밍은 연결이 끝날 때까지 한 요청 — 지연 예산을 다 쓴다)
        assertThat(excluded).as("규칙이 빼는 라우트").isNotEmpty();
        assertThat(pathById).as("빼는 라우트는 게이트웨이에 있다").containsKeys(excluded.toArray(String[]::new));
        assertThat(excluded).as("빼는 것은 SockJS · WebSocket(/chat-service/chat/**) 라우트만 — 나머지는 모두 SLO 안")
                .allMatch(id -> pathById.get(id).contains("/chat-service/chat/"));
        assertThat(pathById.entrySet().stream().filter(e -> e.getValue().contains("/chat-service/chat/")).map(Map.Entry::getKey))
                .as("SockJS · WebSocket 라우트는 모두 뺀다").allMatch(excluded::contains);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 SLO 대시보드 — 패널이 읽는 slo:* 기록 · 경보 이름은 alert-rules.yml 에 있고, 데이터소스는 프로비저닝된 uid(prometheus)다")
    void dashboardReadsExistingRules() throws IOException {
        String rulesText = Files.readString(repoRoot().resolve("observability/alert-rules.yml"), StandardCharsets.UTF_8);
        Set<String> records = new TreeSet<>();
        Set<String> alertNames = new TreeSet<>();
        Map<String, Object> all = new org.yaml.snakeyaml.Yaml().load(rulesText);
        for (Map<String, Object> g : (List<Map<String, Object>>) all.get("groups")) {
            for (Map<String, Object> r : (List<Map<String, Object>>) g.get("rules")) {
                if (r.containsKey("record")) {
                    records.add((String) r.get("record"));
                } else {
                    alertNames.add((String) r.get("alert"));
                }
            }
        }
        // JSON 은 YAML 의 부분집합 — snakeyaml 로 읽는다
        Map<String, Object> dash = new org.yaml.snakeyaml.Yaml().load(Files.readString(
                repoRoot().resolve("observability/grafana/provisioning/dashboards/slo.json"), StandardCharsets.UTF_8));
        assertThat(dash).containsEntry("uid", "slo");
        List<Map<String, Object>> panels = (List<Map<String, Object>>) dash.get("panels");
        List<String> exprs = new java.util.ArrayList<>();
        for (Map<String, Object> p : panels) {
            if ("row".equals(p.get("type"))) {
                continue;
            }
            assertThat(String.valueOf(p.get("datasource"))).as("패널 " + p.get("title")).contains("uid=prometheus");
            for (Map<String, Object> t : (List<Map<String, Object>>) p.get("targets")) {
                exprs.add((String) t.get("expr"));
            }
        }
        assertThat(exprs).as("대시보드 쿼리").hasSizeGreaterThan(10);
        Set<String> usedRecords = new TreeSet<>();
        Set<String> usedAlerts = new TreeSet<>();
        for (String e : exprs) {
            Matcher m = Pattern.compile("slo:[a-z_0-9]+:[a-z_0-9]+").matcher(e);
            while (m.find()) {
                usedRecords.add(m.group());
            }
            Matcher a = Pattern.compile("alertname=~\"([^\"]+)\"").matcher(e);
            while (a.find()) {
                for (String alt : a.group(1).split("\\|")) {
                    usedAlerts.add(alt);
                }
            }
        }
        assertThat(records).as("대시보드가 읽는 기록 규칙 — 이름을 바꾸면 패널이 빈다").containsAll(usedRecords);
        assertThat(usedRecords).contains("slo:budget_used:ratio14d", "slo:sli_error:ratio_rate1h", "slo:sli_slow:ratio_rate1h", "slo:objective:availability");
        for (String a : usedAlerts) {
            assertThat(alertNames.stream().anyMatch(n -> n.matches(a))).as("대시보드의 경보 이름 " + a).isTrue();
        }
        // 변수 slo 는 목표 기록에서 — SLO 를 더하면 저절로 나온다
        assertThat(String.valueOf(dash.get("templating"))).contains("label_values(slo:objective:availability, slo)");
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
