package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B-117: 나가는 연결(egress) 1단계(k8s/97, docs/57 §23)의 분류가 코드 · 매니페스트와 맞는다.
 *
 * <ul>
 *   <li>ecommerce 의 모든 Deployment 는 두 정책 중 <b>정확히 하나</b>에 — 새 서비스를 빠뜨리면 정책 밖(다 열림)으로 남는다.</li>
 *   <li>"클러스터 안만" 그룹의 코드 · 설정에는 바깥 주소가 없다 — 누가 그 서비스에 결제사 · 외부 API 호출을 더하면 적용 뒤 조용히
 *       끊긴다. 바깥이 필요해지면 그 서비스를 public 그룹으로 옮긴다.</li>
 *   <li>public 그룹은 사설 · 링크 로컬(메타데이터) · CGNAT · 루프백을 뺀다, 클러스터 안만 그룹에는 0.0.0.0/0 이 없다.</li>
 *   <li>노드 수집기(4318)는 ops/35 가 펼치는 자리표시로만 — 손으로 적은 IP 는 노드가 바뀌면 추적을 끊는다.</li>
 *   <li>kustomization 에 넣지 않는다 — ops/35 egress-apply(확인 · 되돌리기)로만.</li>
 * </ul>
 *
 * <p>[JA] egress 第 1 段階の分類がコード · マニフェストと合う。全 Deployment はちょうど一つの方針に、内部のみのグループのコードに
 * 外部アドレスなし、public は私設 · メタデータ · CGNAT を除く、ノード収集器はプレースホルダーで、kustomization に入れない。
 */
@DisplayName("B-117: egress 1단계 — 분류 · 예외 대역 · 적용 경로")
class EgressPolicyGuardTest {

    private static final String INTERNAL = "egress-cluster-only";
    private static final String PUBLIC = "egress-public-no-private";

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String policyDoc(String name) throws IOException {
        String text = Files.readString(repoRoot().resolve("k8s/97-network-policy-egress.yaml"), StandardCharsets.UTF_8);
        for (String doc : text.split("(?m)^---\\s*$")) {
            if (doc.contains("name: " + name + "\n")) {
                return doc;
            }
        }
        throw new AssertionError("k8s/97 에 정책 " + name + " 이 없다");
    }

    /** podSelector 의 app 값 — 두드리기 파드(netpol-probe-*)는 뺀다. */
    // [JA] podSelector の app の値 — 叩き用ポッド(netpol-probe-*)は除く。
    private static Set<String> apps(String doc) {
        Matcher m = Pattern.compile("values:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL).matcher(doc.split("policyTypes")[0]);
        assertThat(m.find()).as("app 목록").isTrue();
        Set<String> out = new TreeSet<>();
        for (String v : m.group(1).split(",")) {
            String s = v.trim();
            if (!s.isEmpty() && !s.startsWith("netpol-probe-")) {
                out.add(s);
            }
        }
        return out;
    }

    private static Set<String> ecommerceDeployments() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("\\d+-.*\\.yaml")).toList()) {
                for (String doc : Files.readString(f, StandardCharsets.UTF_8).split("(?m)^---\\s*$")) {
                    if (doc.matches("(?s).*\\nkind: Deployment\\b.*") && doc.contains("namespace: ecommerce")) {
                        Matcher m = Pattern.compile("(?m)^  name: ([a-z0-9-]+)").matcher(doc);
                        if (m.find()) {
                            out.add(m.group(1));
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("🔴 ecommerce 의 모든 Deployment 는 두 정책 중 정확히 하나에")
    void everyDeploymentIsInExactlyOneGroup() throws IOException {
        Set<String> internal = apps(policyDoc(INTERNAL));
        Set<String> pub = apps(policyDoc(PUBLIC));
        Set<String> both = new TreeSet<>(internal);
        both.retainAll(pub);
        assertThat(both).as("두 정책에 다 들어간 서비스").isEmpty();

        Set<String> deployments = ecommerceDeployments();
        Set<String> missing = new TreeSet<>(deployments);
        missing.removeAll(internal);
        missing.removeAll(pub);
        assertThat(missing).as("정책 밖의 Deployment — k8s/97 의 한 그룹에 넣는다(바깥이 필요하면 public)").isEmpty();
        assertThat(deployments).as("검사한 Deployment").hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    @DisplayName("🔴 '클러스터 안만' 그룹의 코드 · 설정에 바깥 주소가 없다 — 있으면 그 서비스는 public 으로")
    void clusterOnlyServicesHaveNoExternalEndpoints() throws IOException {
        Pattern external = Pattern.compile("https?://[A-Za-z0-9.{}$_-]+|mongodb\\+srv://|amazonaws\\.com|smtp\\.[a-z]");
        Pattern benign = Pattern.compile("localhost|127\\.0\\.0\\.1|svc\\.cluster|example\\.|w3\\.org|apache\\.org|springframework"
                + "|schema|\\$\\{|https?://\\{|eureka-server|config-server|gateway-server|catalog-service|cart-service|user-service"
                + "|order-service|coupon-service|pay-service");
        List<String> offenders = new ArrayList<>();
        for (String app : apps(policyDoc(INTERNAL))) {
            Path main = repoRoot().resolve(app).resolve("src/main");
            if (!Files.isDirectory(main)) {
                continue;   // frontend 는 nginx 설정 — 아래에서 따로 ／ [JA] frontend は nginx 設定 — 下で別に
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path f : files.filter(p -> p.toString().matches(".*\\.(java|ya?ml|properties)$")).toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    Matcher m = external.matcher(text);
                    while (m.find()) {
                        if (!benign.matcher(m.group()).find()) {
                            offenders.add(repoRoot().relativize(f) + "  " + m.group());
                        }
                    }
                }
            }
        }
        // frontend 의 nginx 는 게이트웨이(클러스터 안)로만 넘긴다.
        // [JA] frontend の nginx はゲートウェイ(クラスター内)にだけ渡す。
        String nginx = Files.readString(repoRoot().resolve("frontend/nginx.conf"), StandardCharsets.UTF_8);
        Matcher pass = Pattern.compile("proxy_pass\\s+([^;]+);").matcher(nginx);
        while (pass.find()) {
            if (!pass.group(1).trim().equals("${GATEWAY_URL}")) {
                offenders.add("frontend/nginx.conf  proxy_pass " + pass.group(1));
            }
        }
        assertThat(offenders).as("클러스터 안만 그룹이 바깥을 부른다 — egress 정책이 끊는다, k8s/97 public 그룹으로(B-117)").isEmpty();
    }

    @Test
    @DisplayName("public 은 사설 · 메타데이터 · CGNAT · 루프백을 빼고, 클러스터 안만 그룹에는 0.0.0.0/0 이 없다")
    void cidrRules() throws IOException {
        String pub = policyDoc(PUBLIC);
        Matcher except = Pattern.compile("except:\\s*\\[([^\\]]*)\\]").matcher(pub);
        assertThat(except.find()).isTrue();
        Set<String> excluded = new LinkedHashSet<>(Arrays.stream(except.group(1).split(",")).map(String::trim).toList());
        assertThat(excluded).contains("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
                "127.0.0.0/8");
        assertThat(pub).contains("cidr: 0.0.0.0/0");

        String internal = policyDoc(INTERNAL);
        assertThat(internal).doesNotContain("0.0.0.0/0");
        Matcher cidrs = Pattern.compile("cidr:\\s*([^\\s}]+)").matcher(internal);
        while (cidrs.find()) {
            assertThat(cidrs.group(1)).as("클러스터 안만 그룹의 IP 대역은 노드 자리표시뿐").isEqualTo("__NODE_IP__/32");
        }
    }

    @Test
    @DisplayName("노드 수집기(4318)는 ops/35 가 펼치는 자리표시로 · kustomization 에 넣지 않는다")
    void nodeCollectorAndApplyPath() throws IOException {
        for (String name : List.of(INTERNAL, PUBLIC)) {
            String doc = policyDoc(name);
            assertThat(doc).as(name).contains("cidr: __NODE_IP__/32").contains("port: 4318").contains("k8s-app: kube-dns");
            assertThat(doc).as(name + " — 두드리기 파드가 같은 정책을 받는다").contains("netpol-probe-");
        }
        String kustomization = Files.readString(repoRoot().resolve("k8s/kustomization.yaml"), StandardCharsets.UTF_8);
        assertThat(kustomization).doesNotContain("97-network-policy-egress").doesNotContain("95-network-policy");
        String ops = Files.readString(repoRoot().resolve("ops/35-network-policy.sh"), StandardCharsets.UTF_8);
        assertThat(ops).contains("__NODE_IP__").contains("egress-apply").contains("egress_precheck");
    }

    // ── 2단계(2026-11-20, docs/57 §26) ─────────────────────────────────────────────────────────────────
    // [JA] ── 第 2 段階(2026-11-20, docs/57 §26)
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> policies() throws IOException {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Object o : new org.yaml.snakeyaml.Yaml().loadAll(
                Files.readString(repoRoot().resolve("k8s/97-network-policy-egress.yaml"), StandardCharsets.UTF_8))) {
            if (o instanceof Map<?, ?> m) {
                out.put(String.valueOf(((Map<String, Object>) m.get("metadata")).get("name")), (Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> egressRules(Map<String, Object> policy) {
        return (List<Map<String, Object>>) ((Map<String, Object>) policy.get("spec")).get("egress");
    }

    /** 정책이 고르는 app(두드리기 파드 밖). */
    // [JA] 方針が選ぶ app(叩き用ポッドは除く)。
    @SuppressWarnings("unchecked")
    private static Set<String> selected(Map<String, Object> policy) {
        Map<String, Object> sel = (Map<String, Object>) ((Map<String, Object>) policy.get("spec")).get("podSelector");
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) sel.get("matchExpressions")) {
            for (Object v : (List<Object>) e.get("values")) {
                if (!String.valueOf(v).startsWith("netpol-probe-")) {
                    out.add(String.valueOf(v));
                }
            }
        }
        return out;
    }

    /** 0.0.0.0/0 을 여는 규칙의 포트 "TCP/443" 꼴. */
    // [JA] 0.0.0.0/0 を開く規則のポート("TCP/443" の形)。
    @SuppressWarnings("unchecked")
    private static Set<String> internetPorts(Map<String, Object> policy) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> rule : egressRules(policy)) {
            boolean internet = ((List<Map<String, Object>>) rule.getOrDefault("to", List.of())).stream()
                    .anyMatch(to -> to.get("ipBlock") != null && "0.0.0.0/0".equals(((Map<String, Object>) to.get("ipBlock")).get("cidr")));
            if (internet) {
                for (Map<String, Object> p : (List<Map<String, Object>>) rule.getOrDefault("ports", List.of())) {
                    out.add(p.get("protocol") + "/" + p.get("port"));
                }
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 2단계 — 인터넷(0.0.0.0/0)을 여는 규칙은 모두 포트가 있다 · public 은 443 만, 메일 · Atlas · 터널은 그 파드만")
    void stage2NarrowsInternetPorts() throws IOException {
        Map<String, Map<String, Object>> p = policies();
        for (var e : p.entrySet()) {
            for (Map<String, Object> rule : egressRules(e.getValue())) {
                boolean internet = String.valueOf(rule.get("to")).contains("cidr=0.0.0.0/0");
                if (internet) {
                    assertThat(rule).as(e.getKey() + " — 포트 없는 인터넷 규칙(모든 포트가 열린다)").containsKey("ports");
                }
            }
        }
        assertThat(internetPorts(p.get(PUBLIC))).containsExactly("TCP/443");
        assertThat(internetPorts(p.get("egress-smtp"))).containsExactlyInAnyOrder("TCP/587", "TCP/465");
        assertThat(selected(p.get("egress-smtp"))).containsExactly("notification-service");
        assertThat(internetPorts(p.get("egress-mongo-atlas"))).containsExactly("TCP/27017");
        assertThat(selected(p.get("egress-mongo-atlas"))).containsExactlyInAnyOrder("chat-service", "mongo-backup");
        assertThat(internetPorts(p.get("egress-cloudflared"))).containsExactlyInAnyOrder("TCP/7844", "UDP/7844", "TCP/443");
        assertThat(selected(p.get("egress-cloudflared"))).containsExactly("cloudflared");
        assertThat(internetPorts(p.get("egress-metrics-agent"))).containsExactly("TCP/9090");
        assertThat(selected(p.get("egress-metrics-agent"))).containsExactly("metrics-agent");
        assertThat(selected(p.get("egress-backup-postgres"))).containsExactlyInAnyOrder("postgres-backup", "restore-drill");
        assertThat(String.valueOf(egressRules(p.get("egress-backup-postgres")))).as("백업은 IP 대역을 열지 않는다").doesNotContain("ipBlock");

        // 메일 · Atlas 를 받는 서비스는 public 그룹이기도 하다(DNS · 443 · 클러스터 안은 그쪽에서)
        // [JA] メール · Atlas を受けるサービスは public グループでもある(DNS · 443 · クラスター内はそちらで)
        Set<String> pub = apps(policyDoc(PUBLIC));
        assertThat(pub).contains("notification-service", "chat-service");
        // 지표 에이전트는 같은 네임스페이스에 관리 포트(9180)만 — 앱 포트 · DB 로는 못 간다
        // [JA] メトリクスエージェントは同じネームスペースへは管理ポート(9180)のみ — アプリのポート · DB へは行けない
        String sameNs = egressRules(p.get("egress-metrics-agent")).stream().filter(r -> String.valueOf(r.get("to")).equals("[{podSelector={}}]"))
                .map(r -> String.valueOf(r.get("ports"))).findFirst().orElseThrow();
        assertThat(sameNs).isEqualTo("[{protocol=TCP, port=9180}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 2단계 — 정책이 고르는 이름이 실제 파드 라벨과 같다(백업 CronJob · metrics-agent · cloudflared) · ops/35 가 모든 정책을 지운다")
    void stage2SelectorsMatchRealPods() throws IOException {
        Map<String, Map<String, Object>> p = policies();
        Set<String> cronLabels = new TreeSet<>();
        for (Object o : new org.yaml.snakeyaml.Yaml().loadAll(Files.readString(repoRoot().resolve("k8s/85-backup-cronjobs.yaml")))) {
            if (o instanceof Map<?, ?> m && "CronJob".equals(m.get("kind"))) {
                Map<String, Object> tmpl = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                        m.get("spec")).get("jobTemplate")).get("spec")).get("template");
                Map<String, Object> meta = (Map<String, Object>) tmpl.get("metadata");
                assertThat(meta).as("CronJob 파드 라벨 — 없으면 egress 정책이 고르지 못한다").isNotNull();
                cronLabels.add(String.valueOf(((Map<String, Object>) meta.get("labels")).get("app")));
            }
        }
        Set<String> backupSelected = new TreeSet<>(selected(p.get("egress-backup-postgres")));
        backupSelected.addAll(selected(p.get("egress-mongo-atlas")));
        assertThat(backupSelected).containsAll(cronLabels);
        assertThat(cronLabels).containsExactlyInAnyOrder("postgres-backup", "mongo-backup", "restore-drill");

        assertThat(Files.readString(repoRoot().resolve("k8s-overlays/external/metrics-agent.yaml"))).contains("app: metrics-agent");
        assertThat(Files.readString(repoRoot().resolve("k8s-overlays/prod/cloudflared.yaml"))).contains("app: cloudflared");

        String ops = Files.readString(repoRoot().resolve("ops/35-network-policy.sh"), StandardCharsets.UTF_8);
        Matcher names = Pattern.compile("(?m)^EGRESS_POLICIES=\"([^\"]+)\"").matcher(ops);
        assertThat(names.find()).isTrue();
        assertThat(new TreeSet<>(List.of(names.group(1).split(" ")))).as("ops/35 가 되돌릴 때 지우는 정책 = k8s/97 의 정책")
                .isEqualTo(new TreeSet<>(p.keySet()));
        Matcher groups = Pattern.compile("(?m)^EGRESS_GROUPS=\"([^\"]+)\"").matcher(ops);
        assertThat(groups.find()).isTrue();
        String manifest = Files.readString(repoRoot().resolve("k8s/97-network-policy-egress.yaml"));
        for (String g : groups.group(1).split(" ")) {
            assertThat(manifest).as("두드리기 파드 netpol-probe-" + g + " 가 정책에").contains("netpol-probe-" + g);
        }
    }
}
