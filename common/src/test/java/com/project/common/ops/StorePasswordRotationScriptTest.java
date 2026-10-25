package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ops/59 · 스토어 비밀번호 교체 — 가짜 kubectl(Secret 을 JSON 파일로 둔다) 위에서 <b>실제</b> PKCS12 · JKS 스토어로 돈다.
 *
 * <p>확인하는 것: 키 · 인증서는 그대로 두고 스토어마다 새 비밀번호로만 열린다(개인 키도 새 비밀번호로 꺼내진다) ·
 * ecommerce-secrets 는 옛 값과 같은 키만 바뀐다 · 그 값을 읽는 Deployment 만 재시작 · 이 기계의 certs/ · certs.env ·
 * 20-secret.yaml 도 바뀐다 · 되돌리기와 정리 · 열리지 않는 스토어가 하나라도 있거나 지난 사본이 남아 있으면 아무것도 바꾸지
 * 않는다 · 🔒 옛 값도 새 값도 출력에 나오지 않는다.
 *
 * <p>[JA] ops/59 · ストアのパスワード交換 — 偽の kubectl の上で本物の PKCS12・JKS で動かす。鍵・証明書はそのままで新しい
 * パスワードでだけ開く、同じ値のキーだけ替える、読む Deployment だけ再起動、戻し・後始末、開かないストアがあれば何も変えない、
 * パスワードを出力しない。
 */
@DisplayName("ops/59 · 스토어 비밀번호 교체")
class StorePasswordRotationScriptTest {

    static final String OLD = "OldStorePassword123";
    static final String KAFKA_OTHER = "KafkaSeparateValue9";

    @TempDir
    Path tmp;

    private Path repo;
    private Path state;
    private Path certs;
    private Path secretFile;
    private final ObjectMapper json = new ObjectMapper();

    private record Run(int code, String out) {}

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean toolsAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "command -v python3 && command -v keytool && command -v openssl").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                    && FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** 가짜 kubectl — 이 스크립트가 쓰는 명령만. Secret 은 state/secrets/<이름>.json. */
    private static final String FAKE_KUBECTL = String.join("\n",
            "#!/usr/bin/env python3",
            "import json, os, sys",
            "state = os.environ['FAKE_STATE']",
            "sd = os.path.join(state, 'secrets')",
            "a = sys.argv[1:]",
            "if a[:1] == ['-n']: a = a[2:]",
            "open(os.path.join(state, 'calls.log'), 'a').write(' '.join(a) + '\\n')",
            "def path(n): return os.path.join(sd, n + '.json')",
            "def items(): return [json.load(open(os.path.join(sd, f))) for f in sorted(os.listdir(sd))]",
            "if a[:2] == ['get', 'secret']:",
            "    p = path(a[2])",
            "    if not os.path.exists(p): sys.exit(1)",
            "    print(open(p).read())",
            "elif a[:2] == ['get', 'secrets']:",
            "    print(json.dumps({'items': items()}))",
            "elif a[:2] == ['get', 'deploy']:",
            "    print(open(os.path.join(state, 'deploys.json')).read())",
            "elif a[:2] == ['get', 'certificates']:",
            "    pass",
            "elif a[0] in ('create', 'replace'):",
            "    obj = json.load(open(a[2]))",
            "    p = path(obj['metadata']['name'])",
            "    if (a[0] == 'create') == os.path.exists(p): sys.exit(1)",
            "    if a[0] == 'replace' and os.environ.get('FAKE_REPLACE_FAIL') == obj['metadata']['name']: sys.exit(1)",
            "    json.dump(obj, open(p, 'w'))",
            "    open(os.path.join(state, 'writes.log'), 'a').write(a[0] + ' ' + obj['metadata']['name'] + '\\n')",
            "elif a[:2] == ['delete', 'secret']:",
            "    os.remove(path(a[2]))",
            "elif a[:2] == ['rollout', 'restart']:",
            "    open(os.path.join(state, 'restarted.log'), 'a').write(a[2] + '\\n')",
            "elif a[:2] == ['rollout', 'status']:",
            "    pass",
            "else:",
            "    sys.exit('fake kubectl: ' + ' '.join(a))",
            "");

    @BeforeEach
    void cluster() throws Exception {
        assumeTrue(toolsAvailable(), "python3 · keytool · openssl · POSIX 가 없다");
        repo = Files.createDirectories(tmp.resolve("repo/ops"));
        Files.copy(repoRoot().resolve("ops/59-rotate-store-password.sh"), repo.resolve("59-rotate-store-password.sh"));
        Files.copy(repoRoot().resolve("ops/Dockerfile.runtime"), repo.resolve("Dockerfile.runtime"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("kubectl"), FAKE_KUBECTL);
        bin.resolve("kubectl").toFile().setExecutable(true);
        state = Files.createDirectories(tmp.resolve("state/secrets")).getParent();
        certs = Files.createDirectories(tmp.resolve("certs"));
        secretFile = tmp.resolve("20-secret.yaml");

        // 실제 스토어 — ops/07 · ops/38 과 같은 도구(openssl pkcs12 -export · keytool -importcert)
        Path w = Files.createDirectories(tmp.resolve("make"));
        Files.writeString(w.resolve("pw"), OLD);
        Files.writeString(w.resolve("other"), "SomethingElse12345");
        sh(w, String.join("\n",
                "set -e",
                "openssl req -x509 -newkey rsa:2048 -nodes -keyout tls.key -out tls.crt -days 2 -subj /CN=localhost 2>/dev/null",
                "openssl req -x509 -newkey rsa:2048 -nodes -keyout id.key -out id.crt -days 2 -subj /CN=order-service 2>/dev/null",
                "openssl pkcs12 -export -in tls.crt -inkey tls.key -name ecommerce -out keystore.p12 -passout file:pw",
                "openssl pkcs12 -export -in id.crt -inkey id.key -name order-service -out id.p12 -passout file:pw",
                "openssl pkcs12 -export -in id.crt -inkey id.key -name cart-service -out other.p12 -passout file:other",
                "keytool -importcert -noprompt -alias ca -file tls.crt -keystore truststore.p12 -storetype PKCS12 -storepass:file pw >/dev/null 2>&1",
                "keytool -importcert -noprompt -alias ca -file tls.crt -keystore truststore.jks -storetype JKS -storepass:file pw >/dev/null 2>&1"));

        secret("tls-keystore", Map.of(
                "keystore.p12", w.resolve("keystore.p12"), "truststore.p12", w.resolve("truststore.p12"),
                "truststore.jks", w.resolve("truststore.jks"), "tls.crt", w.resolve("tls.crt"), "tls.key", w.resolve("tls.key")));
        secret("tls-id-order-service", Map.of("keystore.p12", w.resolve("id.p12"), "tls.crt", w.resolve("id.crt")));
        // cert-manager(ops/39)가 만든 Secret 의 모양 — 라벨 · 주석 · 소유자로 자기 것을 알아본다
        Path idJson = state.resolve("secrets/tls-id-order-service.json");
        Files.writeString(idJson, Files.readString(idJson).replace("\"resourceVersion\":\"7\"",
                "\"resourceVersion\":\"7\",\"labels\":{\"controller.cert-manager.io/fao\":\"true\"},"
                        + "\"annotations\":{\"cert-manager.io/certificate-name\":\"id-order-service\"},"
                        + "\"ownerReferences\":[{\"apiVersion\":\"cert-manager.io/v1\",\"kind\":\"Certificate\",\"name\":\"id-order-service\",\"uid\":\"u-1\"}]"));
        secretValues("ecommerce-secrets", Map.of(
                "LOCAL_KEY_STORE_PASSWORD", OLD, "LOCAL_KEY_PASSWORD", OLD,
                "KAFKA_SSL_KEY_STORE_PASSWORD", KAFKA_OTHER, "LOCAL_DB_PASSWORD", "DbPassword555"));
        secretValues("redis-auth", Map.of("REDIS_PASSWORD", "RedisPassword777"));
        Files.writeString(state.resolve("deploys.json"), """
                {"items":[
                 {"metadata":{"name":"order-service"},"spec":{"template":{"spec":{"containers":[{"name":"app","env":[
                   {"name":"LOCAL_KEY_STORE_PASSWORD","valueFrom":{"secretKeyRef":{"name":"ecommerce-secrets","key":"LOCAL_KEY_STORE_PASSWORD"}}}]}]}}}},
                 {"metadata":{"name":"gateway-server"},"spec":{"template":{"spec":{"containers":[{"name":"app","env":[
                   {"name":"LOCAL_KEY_STORE_PASSWORD","valueFrom":{"secretKeyRef":{"name":"ecommerce-secrets","key":"LOCAL_KEY_STORE_PASSWORD"}}}]}]}}}},
                 {"metadata":{"name":"eureka-server"},"spec":{"template":{"spec":{"containers":[{"name":"app","envFrom":[
                   {"secretRef":{"name":"ecommerce-secrets"}}]}]}}}},
                 {"metadata":{"name":"redis"},"spec":{"template":{"spec":{"containers":[{"name":"redis","env":[
                   {"name":"REDIS_PASSWORD","valueFrom":{"secretKeyRef":{"name":"redis-auth","key":"REDIS_PASSWORD"}}}]}]}}}}
                ]}""");

        Files.copy(w.resolve("keystore.p12"), certs.resolve("keystore.p12"));
        Files.writeString(certs.resolve("certs.env"),
                "export LOCAL_KEY_STORE_PASSWORD=\"" + OLD + "\"\nexport LOCAL_DB_PASSWORD=DbPassword555\n");
        Files.writeString(secretFile, "stringData:\n  LOCAL_KEY_STORE_PASSWORD: \"" + OLD + "\"\n"
                + "  KAFKA_SSL_KEY_STORE_PASSWORD: \"" + KAFKA_OTHER + "\"\n");
    }

    private void secret(String name, Map<String, Path> files) throws IOException {
        StringBuilder data = new StringBuilder();
        for (var e : files.entrySet()) {
            if (data.length() > 0) data.append(',');
            data.append('"').append(e.getKey()).append("\":\"")
                    .append(Base64.getEncoder().encodeToString(Files.readAllBytes(e.getValue()))).append('"');
        }
        Files.writeString(state.resolve("secrets/" + name + ".json"),
                "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"type\":\"Opaque\",\"metadata\":{\"name\":\"" + name
                        + "\",\"namespace\":\"ecommerce\",\"resourceVersion\":\"7\"},\"data\":{" + data + "}}");
    }

    private void secretValues(String name, Map<String, String> values) throws IOException {
        Path d = Files.createDirectories(tmp.resolve("values/" + name));
        Map<String, Path> files = new java.util.HashMap<>();
        for (var e : values.entrySet()) {
            files.put(e.getKey(), Files.writeString(d.resolve(e.getKey()), e.getValue()));
        }
        secret(name, files);
    }

    private void sh(Path dir, String script) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", script).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0).as(out).isTrue();
    }

    private Run run(String mode, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", repo.resolve("59-rotate-store-password.sh").toString(), mode)
                .redirectErrorStream(true);
        pb.environment().put("PATH", tmp.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE_STATE", state.toString());
        pb.environment().put("CERTS_DIR", certs.toString());
        pb.environment().put("SECRET_FILE", secretFile.toString());
        pb.environment().put("YES", "1");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(300, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private Run run(String mode) throws Exception {
        return run(mode, Map.of());
    }

    private JsonNode secretJson(String name) throws IOException {
        return json.readTree(state.resolve("secrets/" + name + ".json").toFile());
    }

    private byte[] data(String secret, String key) throws IOException {
        return Base64.getDecoder().decode(secretJson(secret).path("data").path(key).asText());
    }

    private String value(String secret, String key) throws IOException {
        return new String(data(secret, key), StandardCharsets.UTF_8);
    }

    private static boolean opens(byte[] store, String type, String password) {
        try {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(new ByteArrayInputStream(store), password.toCharArray());
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias) && ks.getKey(alias, password.toCharArray()) == null) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> secretNames() throws IOException {
        try (var s = Files.list(state.resolve("secrets"))) {
            return s.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
        }
    }

    @Test
    @DisplayName("check — 스토어마다 지금 비밀번호로 열리는지 · 같은 값의 키 · 재시작 대상을 보이고 아무것도 바꾸지 않는다")
    void checkShowsPlan() throws Exception {
        byte[] before = data("tls-keystore", "keystore.p12");

        Run r = run("check");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("tls-keystore/keystore.p12 (PKCS12) — 지금 비밀번호로 열린다",
                "tls-keystore/truststore.jks (JKS)", "tls-id-order-service/keystore.p12",
                "LOCAL_KEY_PASSWORD — 같은 값(바꾼다)", "KAFKA_SSL_KEY_STORE_PASSWORD — 다른 값(그대로 둔다)", "order-service");
        assertThat(r.out()).doesNotContain("redis-auth/", OLD);
        assertThat(data("tls-keystore", "keystore.p12")).isEqualTo(before);
    }

    @Test
    @DisplayName("🔴 apply — 모든 스토어가 새 비밀번호로만 열리고(키 · 인증서 그대로) 같은 값의 키 · 이 기계의 파일까지, 값은 출력하지 않는다")
    void applyRotatesEverything() throws Exception {
        byte[] crt = data("tls-keystore", "tls.crt");
        byte[] key = data("tls-keystore", "tls.key");

        Run r = run("apply");

        assertThat(r.code()).as(r.out()).isZero();
        String fresh = value("ecommerce-secrets", "LOCAL_KEY_STORE_PASSWORD");
        assertThat(fresh).hasSize(32).matches("[A-Za-z0-9]+").isNotEqualTo(OLD);
        for (String[] s : new String[][] {{"tls-keystore", "keystore.p12", "PKCS12"}, {"tls-keystore", "truststore.p12", "PKCS12"},
                {"tls-keystore", "truststore.jks", "JKS"}, {"tls-id-order-service", "keystore.p12", "PKCS12"}}) {
            byte[] store = data(s[0], s[1]);
            assertThat(opens(store, s[2], fresh)).as(s[0] + "/" + s[1] + " 새 값").isTrue();
            assertThat(opens(store, s[2], OLD)).as(s[0] + "/" + s[1] + " 옛 값").isFalse();
        }
        assertThat(data("tls-keystore", "tls.crt")).isEqualTo(crt);
        assertThat(data("tls-keystore", "tls.key")).isEqualTo(key);

        // ecommerce-secrets — 옛 값과 같은 키만
        assertThat(value("ecommerce-secrets", "LOCAL_KEY_PASSWORD")).isEqualTo(fresh);
        assertThat(value("ecommerce-secrets", "KAFKA_SSL_KEY_STORE_PASSWORD")).isEqualTo(KAFKA_OTHER);
        assertThat(value("ecommerce-secrets", "LOCAL_DB_PASSWORD")).isEqualTo("DbPassword555");

        // 🔴 cert-manager 의 표지는 그대로(빠지면 인증서를 새로 발급할 수 있다) · 사본에는 없다(사본을 자기 것으로 보지 않게)
        JsonNode id = secretJson("tls-id-order-service").path("metadata");
        assertThat(id.path("annotations").path("cert-manager.io/certificate-name").asText()).isEqualTo("id-order-service");
        assertThat(id.path("labels").path("controller.cert-manager.io/fao").asText()).isEqualTo("true");
        assertThat(id.path("ownerReferences").path(0).path("name").asText()).isEqualTo("id-order-service");
        JsonNode copy = secretJson("tls-id-order-service-prerotate").path("metadata");
        assertThat(copy.path("ownerReferences").isMissingNode()).isTrue();
        assertThat(copy.path("annotations").path("cert-manager.io/certificate-name").isMissingNode()).isTrue();

        // 🔴 ecommerce-secrets 를 스토어보다 먼저 — cert-manager 가 그 사이 다시 만들어도 새 값이게
        List<String> writes = Files.readAllLines(state.resolve("writes.log")).stream().filter(l -> l.startsWith("replace")).toList();
        assertThat(writes.get(0)).isEqualTo("replace ecommerce-secrets");
        assertThat(writes).contains("replace tls-keystore", "replace tls-id-order-service");

        // 되돌리기용 사본 · 재시작 대상
        assertThat(secretNames()).contains("tls-keystore-prerotate", "tls-id-order-service-prerotate", "ecommerce-secrets-prerotate")
                .doesNotContain("redis-auth-prerotate");
        // 하나씩 — 등록부 먼저, 바깥 문은 마지막. Secret 을 읽지 않는 redis 는 건드리지 않는다
        assertThat(Files.readString(state.resolve("restarted.log")))
                .isEqualTo("deploy/eureka-server\ndeploy/order-service\ndeploy/gateway-server\n");

        // 이 기계의 파일
        assertThat(opens(Files.readAllBytes(certs.resolve("keystore.p12")), "PKCS12", fresh)).isTrue();
        assertThat(Files.readString(certs.resolve("certs.env")))
                .contains("export LOCAL_KEY_STORE_PASSWORD=\"" + fresh + "\"", "export LOCAL_DB_PASSWORD=DbPassword555");
        assertThat(Files.readString(secretFile))
                .contains("LOCAL_KEY_STORE_PASSWORD: \"" + fresh + "\"", "KAFKA_SSL_KEY_STORE_PASSWORD: \"" + KAFKA_OTHER + "\"");
        assertThat(certs.resolve("certs.env-prerotate")).exists();
        assertThat(certs.resolve("keystore.p12-prerotate")).exists();
        assertThat(tmp.resolve("20-secret.yaml-prerotate")).exists();

        // 🔒 출력에 비밀번호가 없다
        assertThat(r.out()).doesNotContain(OLD, fresh).contains("32자");
    }

    @Test
    @DisplayName("rollback — 교체 전 Secret · 파일로, cleanup — 사본을 지운다")
    void rollbackThenCleanup() throws Exception {
        assertThat(run("apply").code()).isZero();

        Run back = run("rollback");

        assertThat(back.code()).as(back.out()).isZero();
        assertThat(value("ecommerce-secrets", "LOCAL_KEY_STORE_PASSWORD")).isEqualTo(OLD);
        assertThat(opens(data("tls-keystore", "keystore.p12"), "PKCS12", OLD)).isTrue();
        assertThat(opens(data("tls-id-order-service", "keystore.p12"), "PKCS12", OLD)).isTrue();
        assertThat(secretJson("tls-keystore").path("metadata").path("labels").isMissingNode()).isTrue();
        JsonNode id = secretJson("tls-id-order-service").path("metadata");
        assertThat(id.path("annotations").path("cert-manager.io/certificate-name").asText()).isEqualTo("id-order-service");
        assertThat(id.path("annotations").has("ecommerce.io/store-password-rotation-of")).isFalse();
        assertThat(id.path("labels").path("controller.cert-manager.io/fao").asText()).isEqualTo("true");
        assertThat(id.path("labels").has("ecommerce.io/store-password-rotation")).isFalse();
        assertThat(id.path("ownerReferences").path(0).path("kind").asText()).isEqualTo("Certificate");
        assertThat(Files.readString(certs.resolve("certs.env"))).contains(OLD);
        assertThat(Files.readString(secretFile)).contains(OLD);
        assertThat(opens(Files.readAllBytes(certs.resolve("keystore.p12")), "PKCS12", OLD)).isTrue();

        Run clean = run("cleanup");

        assertThat(clean.code()).as(clean.out()).isZero();
        assertThat(secretNames()).noneMatch(n -> n.endsWith("-prerotate"));
        try (var s = Files.list(certs)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith("-prerotate"));
        }
        assertThat(tmp.resolve("20-secret.yaml-prerotate")).doesNotExist();
    }

    @Test
    @DisplayName("🔴 지금 비밀번호로 열리지 않는 스토어가 하나라도 있으면 아무것도 바꾸지 않는다")
    void refusesWhenAStoreDoesNotOpen() throws Exception {
        secret("tls-id-cart-service", Map.of("keystore.p12", tmp.resolve("make/other.p12")));

        Run r = run("apply");

        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("tls-id-cart-service/keystore.p12 가 지금 비밀번호로 열리지 않습니다", "아무것도 바꾸지 않았습니다");
        assertThat(value("ecommerce-secrets", "LOCAL_KEY_STORE_PASSWORD")).isEqualTo(OLD);
        assertThat(opens(data("tls-keystore", "keystore.p12"), "PKCS12", OLD)).isTrue();
        assertThat(secretNames()).noneMatch(n -> n.endsWith("-prerotate"));
        assertThat(state.resolve("restarted.log")).doesNotExist();
    }

    @Test
    @DisplayName("지난 교체의 사본이 남아 있으면 apply 를 멈춘다 — 되돌릴 기준이 둘이 되지 않게")
    void refusesWithLeftoverBackups() throws Exception {
        assertThat(run("apply").code()).isZero();
        String afterFirst = value("ecommerce-secrets", "LOCAL_KEY_STORE_PASSWORD");

        Run again = run("apply");

        assertThat(again.code()).isNotZero();
        assertThat(again.out()).contains("지난 교체의 사본");
        assertThat(value("ecommerce-secrets", "LOCAL_KEY_STORE_PASSWORD")).isEqualTo(afterFirst);
    }
}
