package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * B-121: 서버 인증서는 서비스마다 — 공유 {@code tls-keystore}(825일 · 모든 서비스 이름의 와일드카드)를 서버로 쓰지 않는다(docs/57 §27).
 *
 * <h3>왜</h3>
 *
 * <p>🐛 예전에는 자바 서비스 열셋이 모두 같은 서버 키(ops/07 의 {@code keystore.p12})로 HTTPS 를 열었다. SAN 에
 * {@code *.ecommerce.svc.cluster.local} 와 모든 서비스 이름이 들어 있어, 파드 하나에서 그 키가 새면 어느 서비스로도 행세할 수
 * 있었고(게이트웨이 → 백엔드 · 서비스 간 호출의 서버 확인이 통과한다), 825일 동안 손으로 바꿔야 했다. 서비스별 신원(docs/57 §16 ·
 * §18 — 90일, cert-manager 가 만료 30일 전 새로 발급, Reloader 가 재시작)에는 이미 serverAuth 와 그 서비스의 이름이 있었다.
 *
 * <h3>지키는 것</h3>
 * <ul>
 *   <li>자바 Deployment 모두: {@code SERVER_SSL_KEY_STORE=/app/identity/keystore.p12}(형식 PKCS12 · 비밀번호는 둘 다
 *       {@code $(LOCAL_KEY_STORE_PASSWORD)} — cert-manager 의 PKCS12 는 키 비밀번호가 스토어와 같다), 그 경로에 자기 신원
 *       {@code tls-id-<이름>} 을 마운트하고, 갱신 때 재시작하는 Reloader 주석이 있다. {@code LOCAL_KEY_STORE_PATH} 는 건드리지 않는다 —
 *       Postgres · Redis 설정이 같은 변수를 읽는다.</li>
 *   <li>k8s/96 의 Certificate {@code id-<이름>}: serverAuth, 서비스 이름 · FQDN · 지표 에이전트가 확인하는 이름
 *       ({@code metrics-agent.yaml} 의 {@code server_name} — 예전에는 공유 인증서의 와일드카드가 덮었다).</li>
 *   <li>손 발급(ops/38)도 같은 서비스 · 같은 이름 — cert-manager(ops/39)와 목록이 같다. 배포(ops/28)는 그 이름이 없는 신원을 막는다.</li>
 * </ul>
 *
 * <p>[JA] B-121: サーバー証明書はサービスごとに — 共有 {@code tls-keystore}(825 日 · 全サービス名のワイルドカード)をサーバーには使わない
 * (docs/57 §27)。以前は Java サービス 13 個がすべて同じサーバー鍵で HTTPS を開いていた。SAN にワイルドカードと全サービス名が入って
 * いたため、Pod 一つでその鍵が漏れればどのサービスにも成りすませ、825 日間手で交換する必要があった。サービスごとの身元(90 日 ·
 * cert-manager が期限 30 日前に再発行 · Reloader が再起動)にはすでに serverAuth とそのサービス名があった。このテストは、全 Java
 * Deployment がサーバー証明書に自分の身元を使い(LOCAL_KEY_STORE_PATH は Postgres · Redis が読むので触らない)、k8s/96 の Certificate
 * に serverAuth · サービス名 · FQDN · メトリクスエージェントの名前があり、手発行(ops/38)と cert-manager(ops/39)の一覧が同じで、
 * デプロイ(ops/28)がその名前のない身元を止めることを守る。
 */
@DisplayName("B-121: 서버 인증서는 서비스마다 — 신원 키스토어 · serverAuth · 지표 이름")
class ServerCertificateGuardTest {

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
    private static <T> List<T> list(Object o) {
        return o == null ? List.of() : (List<T>) o;
    }

    /** k8s/ 의 자바 Deployment(이미지가 ecommerce-msa 의 것 · frontend 밖) — 이름 → 문서. ／ [JA] Java Deployment — 名前 → 文書。 */
    private static Map<String, Map<String, Object>> javaDeployments() throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(repoRoot().resolve("k8s"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                for (Map<String, Object> d : docs(f)) {
                    if (!"Deployment".equals(d.get("kind"))) {
                        continue;
                    }
                    Map<String, Object> c = container(d);
                    String image = String.valueOf(c.get("image"));
                    if (image.contains("/ecommerce-msa/") && !image.contains("/frontend")) {
                        out.put(String.valueOf(map(d, "metadata").get("name")), d);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Object> container(Map<String, Object> deployment) {
        return list(map(deployment, "spec", "template", "spec").get("containers")).stream()
                .map(o -> map(o)).findFirst().orElseThrow();
    }

    private static Map<String, String> env(Map<String, Object> c) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Object o : list(c.get("env"))) {
            Map<String, Object> e = map(o);
            if (e.get("value") != null) {
                out.put(String.valueOf(e.get("name")), String.valueOf(e.get("value")));
            }
        }
        return out;
    }

    /** 지표 에이전트가 모든 파드에서 확인하는 TLS 이름. ／ [JA] メトリクスエージェントが全 Pod で確認する TLS 名。 */
    private static String metricsServerName() throws IOException {
        Matcher m = Pattern.compile("server_name: (\\S+)").matcher(read("k8s-overlays/external/metrics-agent.yaml"));
        assertThat(m.find()).as("metrics-agent 의 tls_config.server_name 을 못 찾았다").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("🔴 자바 Deployment 열셋 모두 — 서버 키스토어는 자기 신원(/app/identity) · 그 Secret 을 마운트 · Reloader 주석")
    void everyJavaServiceServesWithItsOwnIdentity() throws IOException {
        Map<String, Map<String, Object>> deps = javaDeployments();
        assertThat(deps).as("자바 Deployment 를 못 찾았다").hasSizeGreaterThanOrEqualTo(13);
        List<String> bad = new ArrayList<>();
        for (var e : deps.entrySet()) {
            String name = e.getKey();
            Map<String, Object> c = container(e.getValue());
            Map<String, String> env = env(c);
            if (!"/app/identity/keystore.p12".equals(env.get("SERVER_SSL_KEY_STORE"))) {
                bad.add(name + ": SERVER_SSL_KEY_STORE=" + env.get("SERVER_SSL_KEY_STORE") + " — 자기 신원(/app/identity/keystore.p12)이 아니다");
            }
            if (!"PKCS12".equals(env.get("SERVER_SSL_KEY_STORE_TYPE"))) {
                bad.add(name + ": SERVER_SSL_KEY_STORE_TYPE 가 PKCS12 가 아니다");
            }
            for (String k : List.of("SERVER_SSL_KEY_STORE_PASSWORD", "SERVER_SSL_KEY_PASSWORD")) {
                if (!"$(LOCAL_KEY_STORE_PASSWORD)".equals(env.get(k))) {
                    bad.add(name + ": " + k + " 는 $(LOCAL_KEY_STORE_PASSWORD) 여야 한다(cert-manager PKCS12 는 키 비밀번호 = 스토어 비밀번호)");
                }
            }
            if (!"/app/resources/keystore.p12".equals(env.get("LOCAL_KEY_STORE_PATH"))) {
                bad.add(name + ": LOCAL_KEY_STORE_PATH 를 바꿨다 — Postgres · Redis 설정이 같은 변수를 읽는다(서버만 SERVER_SSL_* 로)");
            }
            String mounted = null;
            for (Object o : list(c.get("volumeMounts"))) {
                Map<String, Object> m = map(o);
                if ("/app/identity".equals(m.get("mountPath"))) {
                    mounted = String.valueOf(m.get("name"));
                }
            }
            String secret = null;
            for (Object o : list(map(e.getValue(), "spec", "template", "spec").get("volumes"))) {
                Map<String, Object> v = map(o);
                if (mounted != null && mounted.equals(v.get("name")) && map(v, "secret") != null) {
                    secret = String.valueOf(map(v, "secret").get("secretName"));
                }
            }
            if (!("tls-id-" + name).equals(secret)) {
                bad.add(name + ": /app/identity 에 tls-id-" + name + " 을 마운트하지 않는다(지금 " + secret + ")");
            }
            Map<String, Object> ann = map(e.getValue(), "metadata", "annotations");
            String reload = ann == null ? null : String.valueOf(ann.get("secret.reloader.stakater.com/reload"));
            if (reload == null || !Arrays.asList(reload.split(",")).contains("tls-id-" + name)) {
                bad.add(name + ": Reloader 주석에 tls-id-" + name + " 이 없다 — 갱신된 서버 인증서를 다시 읽지 않는다");
            }
        }
        assertThat(bad).as("서버 인증서가 서비스마다가 아니다").isEmpty();
    }

    @Test
    @DisplayName("🔴 k8s/96 — 서비스마다 Certificate(serverAuth · 이름 · FQDN · 지표 에이전트의 이름)")
    void certificatesCarryServerNames() throws IOException {
        String metrics = metricsServerName();
        Map<String, Map<String, Object>> certs = new LinkedHashMap<>();
        for (Map<String, Object> d : docs(repoRoot().resolve("k8s/96-service-identities.yaml"))) {
            if ("Certificate".equals(d.get("kind"))) {
                certs.put(String.valueOf(map(d, "spec").get("secretName")), d);
            }
        }
        List<String> bad = new ArrayList<>();
        for (String name : javaDeployments().keySet()) {
            Map<String, Object> cert = certs.get("tls-id-" + name);
            if (cert == null) {
                bad.add(name + ": Certificate(secretName tls-id-" + name + ")가 없다");
                continue;
            }
            Map<String, Object> spec = map(cert, "spec");
            List<String> usages = list(spec.get("usages"));
            List<String> dns = list(spec.get("dnsNames"));
            if (!usages.contains("server auth")) {
                bad.add(name + ": usages 에 server auth 가 없다");
            }
            for (String want : List.of(name, name + ".ecommerce.svc.cluster.local", metrics)) {
                if (!dns.contains(want)) {
                    bad.add(name + ": dnsNames 에 " + want + " 이 없다");
                }
            }
            if (dns.stream().anyMatch(n -> n.startsWith("*"))) {
                bad.add(name + ": 와일드카드 이름 — 서비스마다 인증서인 까닭이 사라진다");
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("🔴 손 발급(ops/38) · cert-manager(ops/39) — 같은 서비스 목록, ops/38 의 SAN 에 지표 이름, 배포(ops/28)는 그 이름을 확인")
    void issuanceScriptsAgree() throws IOException {
        String metrics = metricsServerName();
        String o38 = read("ops/38-issue-service-identities.sh");
        String o39 = read("ops/39-cert-manager.sh");
        Set<String> deps = new TreeSet<>(javaDeployments().keySet());
        assertThat(services(o38, "SERVICES=\"\\$\\{SERVICES:-([^}]*)\\}\"")).as("ops/38 의 서비스 = 자바 Deployment").isEqualTo(deps);
        assertThat(services(o39, "SERVICES=\"([^\"]*)\"")).as("ops/39 의 서비스 = 자바 Deployment").isEqualTo(deps);
        assertThat(o38).as("ops/38 SAN 에 지표 이름").contains("DNS:$METRICS_NAME")
                .contains("METRICS_NAME=\"" + metrics.replace(".ecommerce.", ".$NS.") + "\"");
        assertThat(o38).as("이름이 없는 옛 신원은 기한이 남아도 다시 만든다").contains("-ge \"$RENEW_DAYS\" ] && san_ok \"$s\"");
        assertThat(read("ops/28-deploy.sh")).as("배포 전 — 신원에 지표 이름이 없으면 멈춘다")
                .contains("DNS:" + metrics.replace(".ecommerce.", ".$NS."));
    }

    private static Set<String> services(String script, String regex) {
        Matcher m = Pattern.compile(regex).matcher(script);
        assertThat(m.find()).as(regex).isTrue();
        return new TreeSet<>(Arrays.asList(m.group(1).trim().split("\\s+")));
    }
}
