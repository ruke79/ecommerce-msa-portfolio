package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B-86: Eureka 에 등록하는 파드는 <b>자기 신원 인증서</b>로 등록한다 — Eureka 는 등록부를 바꾸는 요청을 그 CN 이 앱 이름과 같을 때만
 * 받는다(eureka-server {@code RegistryWriteGuard}, docs/57 §16 "Eureka 쓰기"). 고리 하나가 빠지면 enforce 에서 그 서비스의
 * 하트비트가 403 → 90초 뒤 등록부에서 빠지고 그 서비스로 가는 {@code lb://} 호출이 전부 503 이다.
 *
 * <p>고리: 공통 설정의 {@code eureka.client.tls}(application-common.yml — JVM 키스토어는 Eureka 클라이언트가 쓰지 않는다)
 * ← 매니페스트의 {@code EUREKA_CLIENT_TLS_ENABLED=true} · {@code /app/identity} 마운트 ← Secret {@code tls-id-<서비스>} 발급
 * (ops/38 · ops/39 · k8s/96) · 받는 쪽 {@code SERVER_SSL_CLIENT_AUTH=want} · ConfigMap {@code EUREKA_WRITE_AUTH_MODE}.
 *
 * <p>[JA] Eureka に登録するポッドは自分の身元証明書で登録する。鎖が一つ抜けると enforce でそのサービスのハートビートが 403 になり、
 * 登録から外れて lb:// 呼び出しが 503 になる。
 */
@DisplayName("B-86: Eureka 클라이언트는 자기 신원 인증서로 등록한다")
class EurekaClientIdentityGuardTest {

    private static Path root() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String read(String file) throws IOException {
        return Files.readString(root().resolve(file));
    }

    /** k8s/3x · 4x 의 Deployment 매니페스트(파일 이름 → 내용). */
    private static List<Path> deployments() throws IOException {
        try (Stream<Path> s = Files.list(root().resolve("k8s"))) {
            return s.filter(p -> p.getFileName().toString().matches("[34]\\d-.*\\.yaml")).sorted().toList();
        }
    }

    private static final Pattern IDENTITY = Pattern.compile("secretName: (tls-id-[a-z-]+)");
    private static final Pattern TLS_ON = Pattern.compile("- name: EUREKA_CLIENT_TLS_ENABLED\\s*\\n\\s*value: \"true\"");

    @Test
    @DisplayName("🔴 공통 설정이 Eureka 클라이언트 TLS 를 신원 keystore 로 낸다 — 켜기는 EUREKA_CLIENT_TLS_ENABLED(기본 꺼짐)")
    void commonConfigPresentsIdentity() throws IOException {
        String yml = read("common/src/main/resources/application-common.yml");
        assertThat(yml).contains(
                "enabled: ${EUREKA_CLIENT_TLS_ENABLED:false}",
                "key-store: ${EUREKA_CLIENT_KEY_STORE:file:/app/identity/keystore.p12}",
                "trust-store: file:${LOCAL_TRUST_STORE_PATH:/app/resources/truststore.p12}");
        // 자리까지 본다 — eureka.instance 아래에 두면 아무도 읽지 않는다(게이트웨이에 실제로 그렇게 있었다).
        Object tls = null;
        for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(yml)) {
            tls = at(doc, "eureka", "client", "tls");
            if (tls != null) {
                break;
            }
        }
        assertThat(tls).as("eureka.client.tls").isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) tls).get("enabled")).isEqualTo("${EUREKA_CLIENT_TLS_ENABLED:false}");
    }

    @Test
    @DisplayName("🔴 신원을 마운트한 Deployment 는 모두 Eureka TLS 를 켠다 — 켠 Deployment 는 모두 신원을 /app/identity 에 마운트한다")
    void identityMountsAndEurekaTlsAgree() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path f : deployments()) {
            String y = Files.readString(f);
            boolean identity = IDENTITY.matcher(y).find();
            boolean tlsOn = TLS_ON.matcher(y).find();
            if (identity || tlsOn) {
                checked++;
            }
            if (identity && !tlsOn) {
                offenders.add(f.getFileName() + ": 신원을 마운트했는데 EUREKA_CLIENT_TLS_ENABLED=\"true\" 가 없다 — Eureka 등록이 인증서 없이 간다");
            }
            if (tlsOn && !(identity && y.contains("mountPath: /app/identity"))) {
                offenders.add(f.getFileName() + ": Eureka TLS 를 켰는데 신원(tls-id-*)을 /app/identity 에 마운트하지 않는다 — 기동 실패");
            }
        }
        assertThat(checked).as("백엔드 10 + 게이트웨이 + config-server").isGreaterThanOrEqualTo(12);
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("🔴 매니페스트가 마운트하는 신원은 모두 발급된다 — ops/38 · ops/39 목록 · k8s/96 Certificate · Reloader 주석")
    void everyMountedIdentityIsIssued() throws IOException {
        Set<String> mounted = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (Path f : deployments()) {
            String y = Files.readString(f);
            Matcher m = IDENTITY.matcher(y);
            while (m.find()) {
                mounted.add(m.group(1).substring("tls-id-".length()));
                if (!y.contains("secret.reloader.stakater.com/reload: \"" + m.group(1) + "\"")) {
                    offenders.add(f.getFileName() + ": Reloader 주석이 없다 — 갱신된 인증서를 읽지 않아 만료일에 그 서비스만 끊긴다");
                }
            }
        }
        assertThat(mounted).contains("config-server", "gateway-server", "user-service");
        Set<String> ops38 = services(read("ops/38-issue-service-identities.sh"), "SERVICES=\"\\$\\{SERVICES:-([^}]+)\\}\"");
        Set<String> ops39 = services(read("ops/39-cert-manager.sh"), "(?m)^SERVICES=\"([^\"]+)\"");
        String certs = read("k8s/96-service-identities.yaml");
        for (String s : mounted) {
            if (!ops38.contains(s)) {
                offenders.add("ops/38 SERVICES 에 " + s + " 가 없다");
            }
            if (!ops39.contains(s)) {
                offenders.add("ops/39 SERVICES 에 " + s + " 가 없다");
            }
            if (!certs.contains("secretName: tls-id-" + s + "\n  commonName: " + s + "\n")) {
                offenders.add("k8s/96 에 tls-id-" + s + " (CN=" + s + ") Certificate 가 없다");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("🔴 받는 쪽 — eureka-server 가 클라이언트 인증서를 요청하고(want), 쓰기 판정 모드가 ConfigMap 에 있다")
    void eurekaServerAsksForCertificates() throws IOException {
        assertThat(read("k8s/30-eureka-server.yaml")).containsPattern("- name: SERVER_SSL_CLIENT_AUTH\\s*\\n\\s*value: \"want\"");
        assertThat(read("k8s/10-configmap.yaml")).containsPattern("(?m)^  EUREKA_WRITE_AUTH_MODE: \"(observe|enforce)\"$");
    }

    private static Object at(Object node, String... keys) {
        for (String k : keys) {
            if (!(node instanceof java.util.Map<?, ?> m)) {
                return null;
            }
            node = m.get(k);
        }
        return node;
    }

    private static Set<String> services(String script, String regex) {
        Matcher m = Pattern.compile(regex).matcher(script);
        assertThat(m.find()).as("SERVICES 목록을 읽었다: " + regex).isTrue();
        return new TreeSet<>(List.of(m.group(1).trim().split("\\s+")));
    }
}
