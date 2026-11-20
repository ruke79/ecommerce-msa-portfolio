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
}
