package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * B-119: 관리 포트 분리(docs/57 §25) — actuator 는 앱 포트가 아니라 관리 포트(9180)에, 그 포트는 지표 에이전트만.
 *
 * <h3>왜</h3>
 *
 * <p>🐛 예전에는 actuator(health 상세 · metrics · prometheus)가 앱 포트에 있었고 들어오는 정책(k8s/95)은 같은 네임스페이스에
 * 모든 포트를 열었다. 같은 네임스페이스의 파드 하나만 뚫려도 모든 서비스의 지표 · health 상세를 인증 없이 읽었다(B-112 는 노출
 * 목록만 줄였다). egress 1단계(k8s/97)도 같은 네임스페이스는 연다 — 그 길은 받는 쪽에서 닫아야 한다.
 *
 * <h3>지키는 것</h3>
 * <ul>
 *   <li>자바 Deployment 모두: 환경 변수 {@code MANAGEMENT_SERVER_PORT=9180} · 컨테이너 포트 {@code management}(9180) ·
 *       actuator HTTP 프로브는 그 포트로. Service 는 관리 포트를 내지 않는다(게이트웨이 · Ingress 로 나갈 길이 없다).</li>
 *   <li>지표 에이전트(external)는 포트 이름 {@code management} 만 긁는다 — 앱 포트를 긁으면 404 · up=0.</li>
 *   <li>k8s/95: 같은 네임스페이스 허용은 앱 포트 목록만(매니페스트의 containerPort 와 같다 — 포트를 더하면 여기도),
 *       관리 포트는 {@code app=metrics-agent} 에게만, 다른 허용에는 관리 포트가 없다.</li>
 *   <li>배포 스모크(ops/28)는 게이트웨이 health 를 관리 포트로 본다 — 앱 포트에는 /actuator 가 없어 스모크가 실패 →
 *       자동 복귀했을 것이다.</li>
 * </ul>
 *
 * <p>[JA] 管理ポートの分離 — actuator はアプリのポートではなく管理ポート(9180)に、そのポートは指標エージェントだけ。
 */
@DisplayName("B-119: 관리 포트 — actuator 는 9180, 지표 에이전트만")
class ManagementPortGuardTest {

    static final int MANAGEMENT = 9180;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String read(String rel) throws IOException {
        return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> docs(Path file) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : new Yaml().loadAll(Files.readString(file, StandardCharsets.UTF_8))) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String... path) {
        Object cur = o;
        for (String p : path) {
            cur = cur == null ? null : ((Map<String, Object>) cur).get(p);
        }
        return (Map<String, Object>) cur;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    /** k8s/ 의 자바 Deployment(이미지가 ecommerce-msa 의 것 · frontend 밖) — 파일 이름 → 문서. */
    // [JA] k8s/ の Java Deployment(イメージが ecommerce-msa のもの · frontend 以外)— ファイル名 → ドキュメント。
    private static Map<String, Map<String, Object>> javaDeployments() throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                for (Map<String, Object> d : docs(f)) {
                    if (!"Deployment".equals(d.get("kind"))) {
                        continue;
                    }
                    Map<String, Object> c = list(map(d, "spec", "template", "spec").get("containers")).get(0);
                    String image = String.valueOf(c.get("image"));
                    if (image.contains("/ecommerce-msa/") && !image.contains("/frontend")) {
                        out.put(f.getFileName() + ":" + map(d, "metadata").get("name"), d);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> container(Map<String, Object> deployment) {
        return list(map(deployment, "spec", "template", "spec").get("containers")).get(0);
    }

    @Test
    @DisplayName("🔴 자바 Deployment 열셋 모두 — MANAGEMENT_SERVER_PORT=9180 · 포트 management · actuator 프로브는 그 포트")
    void everyJavaDeploymentUsesTheManagementPort() throws IOException {
        Map<String, Map<String, Object>> deps = javaDeployments();
        assertThat(deps).as("자바 Deployment 를 못 찾았다").hasSizeGreaterThanOrEqualTo(13);
        List<String> bad = new ArrayList<>();
        for (var e : deps.entrySet()) {
            Map<String, Object> c = container(e.getValue());
            String env = list(c.get("env")).stream().filter(v -> "MANAGEMENT_SERVER_PORT".equals(v.get("name")))
                    .map(v -> String.valueOf(v.get("value"))).findFirst().orElse(null);
            if (!String.valueOf(MANAGEMENT).equals(env)) {
                bad.add(e.getKey() + ": MANAGEMENT_SERVER_PORT=" + env + " (9180 이어야 한다)");
            }
            boolean port = list(c.get("ports")).stream().anyMatch(p -> "management".equals(p.get("name"))
                    && Objects.equals(p.get("containerPort"), MANAGEMENT));
            if (!port) {
                bad.add(e.getKey() + ": 컨테이너 포트 management(9180) 없음 — 지표 에이전트가 포트 이름으로 찾는다");
            }
            for (String probe : List.of("startupProbe", "livenessProbe", "readinessProbe")) {
                Map<String, Object> http = map(c, probe, "httpGet");
                if (http != null && String.valueOf(http.get("path")).startsWith("/actuator")) {
                    Object p = http.get("port");
                    if (!"management".equals(p) && !Objects.equals(p, MANAGEMENT)) {
                        bad.add(e.getKey() + ": " + probe + " 가 앱 포트(" + p + ")의 /actuator — 거기에는 없다");
                    }
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("🔴 Service 는 관리 포트를 내지 않는다 — 게이트웨이 · Ingress · 다른 파드가 Service 이름으로 닿지 못하게")
    void servicesDoNotExposeTheManagementPort() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                for (Map<String, Object> d : docs(f)) {
                    if ("Service".equals(d.get("kind"))) {
                        for (Map<String, Object> p : list(map(d, "spec").get("ports"))) {
                            if (Objects.equals(p.get("port"), MANAGEMENT) || Objects.equals(p.get("targetPort"), MANAGEMENT)
                                    || "management".equals(p.get("targetPort"))) {
                                bad.add(f.getFileName() + ": Service " + map(d, "metadata").get("name") + " 가 관리 포트를 낸다");
                            }
                        }
                    }
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("🔴 지표 에이전트(external)는 포트 이름 management 만 긁는다")
    @SuppressWarnings("unchecked")
    void metricsAgentsScrapeOnlyTheManagementPort() throws IOException {
        for (String rel : List.of("k8s-overlays/external/metrics-agent.yaml")) {
            Path f = repoRoot().resolve(rel);
            if (!Files.exists(f)) {
                continue;   // 데모 오버레이가 없는 저장소 ／ [JA] デモオーバーレイがないリポジトリ
            }
            Map<String, Object> cm = docs(f).stream().filter(d -> "ConfigMap".equals(d.get("kind"))).findFirst().orElseThrow();
            Map<String, Object> data = map(cm, "data");
            String tmpl = String.valueOf(data.getOrDefault("prometheus.yml.tmpl", data.get("prometheus.yml")));
            Map<String, Object> prom = new Yaml().load(tmpl);
            Map<String, Object> job = list(prom.get("scrape_configs")).stream()
                    .filter(j -> "spring-actuator".equals(j.get("job_name"))).findFirst().orElseThrow();
            boolean keepsManagement = list(job.get("relabel_configs")).stream()
                    .anyMatch(r -> "keep".equals(r.get("action"))
                            && String.valueOf(r.get("source_labels")).contains("__meta_kubernetes_pod_container_port_name")
                            && "management".equals(r.get("regex")));
            assertThat(keepsManagement).as(rel + ": 포트 이름 management 만 남기는 keep 규칙").isTrue();
        }
    }

    @Test
    @DisplayName("🔴 k8s/95 — 같은 네임스페이스는 앱 포트만(= 매니페스트의 containerPort), 관리 포트는 metrics-agent 만")
    void ingressPolicyOpensTheManagementPortOnlyToTheAgent() throws IOException {
        Set<Integer> appPorts = new TreeSet<>();
        for (Map<String, Object> d : javaDeployments().values()) {
            for (Map<String, Object> p : list(container(d).get("ports"))) {
                appPorts.add((Integer) p.get("containerPort"));
            }
        }
        // frontend(nginx) 도 같은 네임스페이스에서 받는다(cloudflared)
        // [JA] frontend(nginx)も同じネームスペースから受ける(cloudflared)
        for (Map<String, Object> d : docs(repoRoot().resolve("k8s/50-frontend.yaml"))) {
            if ("Deployment".equals(d.get("kind"))) {
                list(container(d).get("ports")).forEach(p -> appPorts.add((Integer) p.get("containerPort")));
            }
        }
        appPorts.remove(MANAGEMENT);

        Map<String, Map<String, Object>> policies = new LinkedHashMap<>();
        for (Map<String, Object> d : docs(repoRoot().resolve("k8s/95-network-policy.yaml"))) {
            if ("ecommerce".equals(map(d, "metadata").get("namespace"))) {
                policies.put(String.valueOf(map(d, "metadata").get("name")), d);
            }
        }
        Map<String, Object> same = policies.get("allow-same-namespace");
        assertThat(same).as("allow-same-namespace").isNotNull();
        Set<Integer> opened = new TreeSet<>();
        for (Map<String, Object> rule : list(map(same, "spec").get("ingress"))) {
            assertThat(rule).as("allow-same-namespace 의 규칙마다 ports — 없으면 관리 포트까지 열린다").containsKey("ports");
            list(rule.get("ports")).forEach(p -> opened.add((Integer) p.get("port")));
        }
        assertThat(opened).as("같은 네임스페이스에 여는 포트 = 매니페스트의 앱 포트(관리 포트 밖)").isEqualTo(appPorts);

        Map<String, Object> mgmt = policies.get("allow-management-from-metrics-agent");
        assertThat(mgmt).as("allow-management-from-metrics-agent").isNotNull();
        List<Map<String, Object>> rules = list(map(mgmt, "spec").get("ingress"));
        assertThat(rules).hasSize(1);
        assertThat(String.valueOf(rules.get(0).get("from")))
                .isEqualTo("[{podSelector={matchExpressions=[{key=app, operator=In, values=[metrics-agent, netpol-probe-metrics]}]}}]");
        assertThat(list(rules.get(0).get("ports"))).extracting(p -> p.get("port")).containsExactly(MANAGEMENT);

        for (var e : policies.entrySet()) {
            if (e.getKey().equals("allow-management-from-metrics-agent")) {
                continue;
            }
            for (Map<String, Object> rule : list(map(e.getValue(), "spec").get("ingress"))) {
                assertThat(rule).as(e.getKey() + " 의 규칙에 ports 가 없다 — 관리 포트까지 연다").containsKey("ports");
                assertThat(list(rule.get("ports"))).extracting(p -> p.get("port")).as(e.getKey()).doesNotContain(MANAGEMENT);
            }
        }
    }

    @Test
    @DisplayName("🔴 배포 스모크는 게이트웨이 health 를 관리 포트로 — 앱 포트에는 /actuator 가 없다")
    void deploySmokeReadsGatewayHealthOnTheManagementPort() throws IOException {
        String deploy = read("ops/28-deploy.sh");
        assertThat(deploy).contains("port-forward deploy/gateway-server 19180:9180", "GATEWAY_HEALTH=\"$SMOKE_GATEWAY_HEALTH\"");
        assertThat(read("ops/06-smoke-test.sh")).contains("\"$GATEWAY_HEALTH\"");
        // 지표 에이전트는 배포가 적용하지 않는다 — 옛 규칙이면 배포가 알린다
        // [JA] メトリクスエージェントはデプロイが適用しない — 古い規則ならデプロイが知らせる
        assertThat(deploy).contains("warn_metrics_agent", "__meta_kubernetes_pod_container_port_name");
    }
}
