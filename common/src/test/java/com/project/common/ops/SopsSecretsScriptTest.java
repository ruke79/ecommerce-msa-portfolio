package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * ops/62 — 운영 Secret 을 SOPS + age 로 암호화해 저장소에(docs/57 §24). 실제 sops · age 로 왕복한다 — 둘이 PATH 에 없으면 건너뛴다
 * (그때도 가드 B-118 이 커밋된 암호문 · 규칙을 본다).
 *
 * <p>[JA] ops/62 — 本番 Secret を SOPS + age で暗号化してリポジトリに。実際の sops · age で往復する(PATH になければスキップ)。
 */
@DisplayName("ops/62 — SOPS 로 Secret 암호화 · 점검 · 복호화 · 적용")
class SopsSecretsScriptTest {

    @TempDir
    Path tmp;

    private Path pub1File;
    private String pub1;
    private String pub2;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean onPath(String tool) {
        for (String dir : System.getenv("PATH").split(":")) {
            if (Files.isExecutable(Path.of(dir, tool))) {
                return true;
            }
        }
        return false;
    }

    private record Run(int code, String out) {}

    private Run run(String keyFile, Map<String, String> extra, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", "ops/62-secrets-sops.sh"));
        cmd.addAll(List.of(args));
        pb.command(cmd).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("SOPS_CONFIG", tmp.resolve(".sops.yaml").toString());
        pb.environment().put("SOPS_PLAIN", tmp.resolve("k8s/20-secret.yaml").toString());
        pb.environment().put("SOPS_ENC", tmp.resolve("k8s/20-secret.enc.yaml").toString());
        pb.environment().put("SOPS_AGE_KEY_FILE", tmp.resolve(keyFile).toString());
        pb.environment().putAll(extra);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private Run run(String... args) throws Exception {
        return run("k1.txt", Map.of(), args);
    }

    private static String lastLine(String s) {
        String[] lines = s.strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(onPath("sops") && onPath("age-keygen") && onPath("python3"), "sops · age · python3 가 PATH 에 없다");
        Files.createDirectories(tmp.resolve("k8s"));
        String plain = Files.readString(repoRoot().resolve("k8s/20-secret.example.yaml"), StandardCharsets.UTF_8)
                .replace("REPLACE_ME", "s3cr3t-value");
        Files.writeString(tmp.resolve("k8s/20-secret.yaml"), plain);
        pub1 = lastLine(run("k1.txt", Map.of(), "keygen").out());
        pub2 = lastLine(run("k2.txt", Map.of(), "keygen").out());
        pub1File = tmp.resolve("k1.txt");
        assertThat(pub1).startsWith("age1");
    }

    private String enc() throws Exception {
        return Files.readString(tmp.resolve("k8s/20-secret.enc.yaml"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 암호화 → 값은 모두 암호문(평문 값 없음) · 키 이름은 보인다 · 점검 통과 · 개인 키는 0600")
    void encryptsValuesOnly() throws Exception {
        assertThat(run("init", pub1, pub2).code()).isZero();
        Run e = run("encrypt");
        assertThat(e.code()).as(e.out()).isZero();

        // 머리 주석(암호화 대상 밖)에는 예시 문장이 남는다 — 값 줄 · 메타데이터에는 평문 값이 없어야 한다
        // [JA] 頭のコメント(暗号化対象外)には例の文が残る — 値の行 · メタデータには平文の値があってはならない
        assertThat(enc().lines().filter(l -> !l.startsWith("#")).filter(l -> l.contains("s3cr3t-value")).toList()).isEmpty();
        assertThat(enc()).contains("ENC[AES256_GCM,", "MAIL_PASSWORD:", "kind: Secret", "sops:");
        assertThat(Files.getPosixFilePermissions(pub1File)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        Run c = run("check");
        assertThat(c.code()).as(c.out()).isZero();
        assertThat(c.out()).contains("값이 모두 암호문", "풀린다", "과 같다");
        // 두 번째 받는 사람도 푼다
        // [JA] 2 人目の受信者も復号する
        assertThat(run("k2.txt", Map.of(), "check").out()).contains("풀린다");
    }

    @Test
    @DisplayName("init — 같은 받는 사람을 두 번 넘겨도 한 번만 쓴다(순서 유지 · 중복 개수를 알린다)")
    void initDropsDuplicateRecipients() throws Exception {
        Run r = run("init", pub1, pub2, pub1);
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("받는 사람 2명", "중복 1개 제거");
        String conf = Files.readString(tmp.resolve(".sops.yaml"), StandardCharsets.UTF_8);
        String age = conf.lines().filter(l -> l.contains("age:")).findFirst().orElseThrow();
        assertThat(age).isEqualTo("    age: " + pub1 + "," + pub2);
        assertThat(age.split(",")).hasSize(2);
    }

    @Test
    @DisplayName("받는 사람이 자리표시(age1REPLACE_ME)이거나 age 키가 아니면 암호화하지 않는다")
    void refusesWithoutRealRecipients() throws Exception {
        Files.writeString(tmp.resolve(".sops.yaml"), "creation_rules:\n  - age: age1REPLACE_ME\n");
        Run r = run("encrypt");
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("자리표시");
        assertThat(tmp.resolve("k8s/20-secret.enc.yaml")).doesNotExist();
        assertThat(run("init", "not-an-age-key").out()).contains("age 공개 키 모양이 아니다");
    }

    @Test
    @DisplayName("🔴 암호문에 평문 값이 섞이면 점검이 그 키 이름을 대며 실패한다(값은 말하지 않는다)")
    void checkCatchesPlaintextInCiphertext() throws Exception {
        run("init", pub1);
        run("encrypt");
        Files.writeString(tmp.resolve("k8s/20-secret.enc.yaml"),
                enc().replaceFirst("(?m)^(\\s+MAIL_PORT:) .*$", "$1 \"587-plain\""));
        Run c = run("check");
        assertThat(c.code()).isNotZero();
        assertThat(c.out()).contains("암호화되지 않은 값: MAIL_PORT").doesNotContain("587-plain");
    }

    @Test
    @DisplayName("🔴 복호화는 다른 평문을 덮어쓰지 않는다 — 다른 키 이름만 대고, FORCE=1 이면 0600 으로")
    void decryptDoesNotClobber() throws Exception {
        run("init", pub1);
        run("encrypt");
        Path plain = tmp.resolve("k8s/20-secret.yaml");
        Files.writeString(plain, Files.readString(plain).replaceFirst("(?m)^(\\s+MAIL_PORT:) .*$", "$1 \"2525\""));

        Run refused = run("decrypt");
        assertThat(refused.code()).isNotZero();
        assertThat(refused.out()).contains("다른 키: MAIL_PORT").doesNotContain("2525");
        assertThat(Files.readString(plain)).contains("\"2525\"");

        Run forced = run("k1.txt", Map.of("FORCE", "1"), "decrypt");
        assertThat(forced.code()).as(forced.out()).isZero();
        assertThat(Files.readString(plain)).doesNotContain("\"2525\"");
        assertThat(Files.getPosixFilePermissions(plain)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("keygen <파일> — 보관용 키를 그 이름으로(0600) · 이 기계의 키는 그대로 · 그 키로 풀린다 · 디렉터리는 거절")
    void keygenWritesNamedFile() throws Exception {
        Path offline = tmp.resolve("vault/offline-backup.txt");
        Run k = run("k1.txt", Map.of(), "keygen", offline.toString());
        assertThat(k.code()).as(k.out()).isZero();
        assertThat(Files.getPosixFilePermissions(offline)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(k.out()).contains("SOPS_AGE_KEY_FILE=" + offline);
        String pubOffline = k.out().lines().filter(l -> l.startsWith("age1")).findFirst().orElseThrow();
        assertThat(pubOffline).isNotEqualTo(pub1);
        // 이 기계의 키(k1)는 바뀌지 않았다 — 같은 공개 키
        // [JA] このマシンの鍵(k1)は変わっていない — 同じ公開鍵
        assertThat(lastLine(run("keygen").out())).isEqualTo(pub1);

        run("init", pub1, pubOffline);
        assertThat(run("encrypt").code()).isZero();
        assertThat(run("vault/offline-backup.txt", Map.of(), "check").out()).contains("풀린다");

        Run dir = run("k1.txt", Map.of(), "keygen", tmp.resolve("vault").toString());
        assertThat(dir.code()).isNotZero();
        assertThat(dir.out()).contains("디렉터리");
    }

    @Test
    @DisplayName("받는 사람에서 빼면(updatekeys) 그 키로는 더 풀리지 않는다")
    void updatekeysRemovesRecipient() throws Exception {
        run("init", pub1, pub2);
        run("encrypt");
        run("init", pub1);
        assertThat(run("updatekeys").code()).isZero();
        assertThat(run("k2.txt", Map.of(), "check").out()).contains("풀리지 않는다");
        assertThat(run("check").out()).contains("풀린다");
    }

    /** get secret 은 $SECRET_JSON(비면 NotFound), apply -f 는 그 파일을 $APPLIED 로 옮기는 가짜 kubectl. */
    // [JA] get secret は $SECRET_JSON(空なら NotFound)、apply -f はそのファイルを $APPLIED へ移す偽 kubectl。
    private Map<String, String> applyKubectl(Path secretJson, Path applied) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin-apply"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, """
                #!/bin/sh
                case "$*" in
                  *"get secret ecommerce-secrets -o json"*)
                    if [ -n "$SECRET_JSON" ]; then cat "$SECRET_JSON"; exit 0; fi
                    echo 'Error from server (NotFound): secrets "ecommerce-secrets" not found' >&2; exit 1 ;;
                  *"apply -f "*) f=$(echo "$*" | sed 's/.*apply -f //'); cp "$f" "$APPLIED"; echo secret/ecommerce-secrets configured ;;
                  *) echo "unexpected: $*" >&2; exit 1 ;;
                esac
                """);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        return Map.of("PATH", bin + ":" + System.getenv("PATH"), "APPLIED", applied.toString(),
                "SECRET_JSON", secretJson == null ? "" : secretJson.toString());
    }

    @Test
    @DisplayName("🔴 apply — 클러스터에 Secret 이 없으면 만든다 · 평문 파일은 남지 않는다")
    void applyCreatesWithoutLeavingPlaintext() throws Exception {
        run("init", pub1);
        run("encrypt");
        Files.delete(tmp.resolve("k8s/20-secret.yaml"));
        Path applied = tmp.resolve("applied.yaml");

        Run r = run("k1.txt", applyKubectl(null, applied), "apply");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("새로 만든다").doesNotContain("s3cr3t-value");
        assertThat(Files.readString(applied)).contains("s3cr3t-value", "name: ecommerce-secrets");
        assertThat(tmp.resolve("k8s/20-secret.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("🔴 apply — 클러스터에만 있는 키가 있으면 거절한다(apply 가 지운다 — 2026-11-20 운영의 Redis 비밀번호 · OAuth 넷)")
    void applyRefusesWhenCiphertextLacksClusterKeys() throws Exception {
        run("init", pub1);
        run("encrypt");
        Path cluster = clusterSecretJson(Map.of());
        String json = Files.readString(cluster);
        Files.writeString(cluster, json.replace("\"data\":{", "\"data\":{\"SPRING_DATA_REDIS_PASSWORD\":\""
                + Base64.getEncoder().encodeToString("only-in-cluster".getBytes(StandardCharsets.UTF_8)) + "\","));
        Path applied = tmp.resolve("applied.yaml");

        Run r = run("k1.txt", applyKubectl(cluster, applied), "apply");

        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("클러스터에만 있는 키: SPRING_DATA_REDIS_PASSWORD", "from-cluster")
                .doesNotContain("only-in-cluster", "s3cr3t-value");
        assertThat(applied).doesNotExist();
    }

    @Test
    @DisplayName("🔴 apply — 바뀌는 키는 EXPECT_CHANGED 로 이름을 대야 한다(낡은 암호문이 운영 값을 덮지 않게) · 같으면 아무것도 안 한다")
    void applyNeedsChangedKeysNamed() throws Exception {
        run("init", pub1);
        run("encrypt");
        String key = firstKey();
        Path stale = clusterSecretJson(Map.of(key, "current-prod-value"));
        Path applied = tmp.resolve("applied.yaml");

        Run refused = run("k1.txt", applyKubectl(stale, applied), "apply");
        assertThat(refused.code()).as(refused.out()).isEqualTo(1);
        assertThat(refused.out()).contains("바뀌거나 새로 생기는 키: " + key, "EXPECT_CHANGED=\"" + key + "\"")
                .doesNotContain("current-prod-value", "s3cr3t-value");
        assertThat(applied).doesNotExist();

        Map<String, String> env = new LinkedHashMap<>(applyKubectl(stale, applied));
        env.put("EXPECT_CHANGED", "OTHER_KEY " + key);
        Run ok = run("k1.txt", env, "apply");
        assertThat(ok.code()).as(ok.out()).isZero();
        assertThat(ok.out()).contains("바뀌는 키: " + key);
        assertThat(applied).exists();

        Files.delete(applied);
        Run same = run("k1.txt", applyKubectl(clusterSecretJson(Map.of()), applied), "apply");
        assertThat(same.code()).as(same.out()).isZero();
        assertThat(same.out()).contains("클러스터와 같다");
        assertThat(applied).doesNotExist();
    }

    @Test
    @DisplayName("🔴 from-cluster → encrypt → drift 가 같다 · 있는 평문은 덮지 않는다 · 값은 출력하지 않는다")
    void fromClusterRebuildsPlaintext() throws Exception {
        run("init", pub1);
        String key = firstKey();
        Path cluster = clusterSecretJson(Map.of(key, "prod \"quoted\" value: 한글"));

        Run refused = run("k1.txt", applyKubectl(cluster, tmp.resolve("x")), "from-cluster");
        assertThat(refused.code()).as(refused.out()).isEqualTo(1);
        assertThat(refused.out()).contains("덮어쓰지 않는다");

        Map<String, String> env = new LinkedHashMap<>(applyKubectl(cluster, tmp.resolve("x")));
        env.put("FORCE", "1");
        Run r = run("k1.txt", env, "from-cluster");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).doesNotContain("s3cr3t-value", "quoted");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp.resolve("k8s/20-secret.yaml")))).isEqualTo("rw-------");

        assertThat(run("encrypt").code()).isZero();
        Run drift = run("k1.txt", fakeKubectl(cluster), "drift");
        assertThat(drift.code()).as(drift.out()).isZero();
        assertThat(drift.out()).contains("✅ 같다");
    }

    @Test
    @DisplayName("🔴 encrypt 가 실패해도 임시 파일(20-secret.enc.yaml.XXXXXX)을 남기지 않는다(2026-11-20 운영 노드)")
    void encryptFailureLeavesNoTempFile() throws Exception {
        run("init", pub2);   // 이 기계의 키(k1)는 받는 사람이 아니다 — 왕복에서 실패 ／ [JA] この機械の鍵(k1)は受取人でない — 往復で失敗
        Run r = run("encrypt");
        assertThat(r.code()).as(r.out()).isNotZero();
        try (var files = Files.list(tmp.resolve("k8s"))) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .noneMatch(n -> n.startsWith("20-secret.enc.yaml."));
        }
    }

    /** 평문의 stringData 를 클러스터 Secret JSON(data = base64)으로 — {@code change} 의 키는 다른 값으로. */
    // [JA] 平文の stringData をクラスターの Secret JSON(data = base64)に — {@code change} のキーは別の値にする。
    @SuppressWarnings("unchecked")
    private Path clusterSecretJson(Map<String, String> change) throws Exception {
        Map<String, Object> doc = new Yaml().load(Files.readString(tmp.resolve("k8s/20-secret.yaml")));
        Map<String, Object> data = new LinkedHashMap<>();
        ((Map<String, Object>) doc.get("stringData")).forEach((k, v) -> data.put(k, Base64.getEncoder().encodeToString(
                change.getOrDefault(k, v == null ? "" : String.valueOf(v)).getBytes(StandardCharsets.UTF_8))));
        StringBuilder json = new StringBuilder("{\"kind\":\"Secret\",\"data\":{");
        data.forEach((k, v) -> json.append('"').append(k).append("\":\"").append(v).append("\","));
        json.setLength(json.length() - 1);
        json.append("}}");
        Path p = tmp.resolve("cluster-" + change.size() + ".json");
        Files.writeString(p, json);
        return p;
    }

    /** get secret 은 $SECRET_JSON 을, 그 밖은 실패하는 가짜 kubectl. */
    // [JA] get secret では $SECRET_JSON を返し、それ以外は失敗する偽の kubectl。
    private Map<String, String> fakeKubectl(Path secretJson) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin-drift"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/bin/sh\ncase \"$*\" in *\"get secret ecommerce-secrets -o json\"*) "
                + "[ -n \"$SECRET_JSON\" ] && cat \"$SECRET_JSON\" && exit 0;; esac\necho 'connection refused' >&2; exit 1\n");
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        return Map.of("PATH", bin + ":" + System.getenv("PATH"), "SECRET_JSON", secretJson == null ? "" : secretJson.toString());
    }

    private String firstKey() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> sd = (Map<String, Object>) ((Map<String, Object>) new Yaml()
                .load(Files.readString(tmp.resolve("k8s/20-secret.yaml")))).get("stringData");
        return sd.keySet().iterator().next();
    }

    @Test
    @DisplayName("🔴 drift — 클러스터 Secret 과 같으면 0, 다르면 그 키 이름만(값은 출력하지 않는다) · 종료 1")
    void driftNamesChangedKeysWithoutValues() throws Exception {
        run("init", pub1);
        assertThat(run("encrypt").code()).isZero();

        Run same = run("k1.txt", fakeKubectl(clusterSecretJson(Map.of())), "drift");
        assertThat(same.code()).as(same.out()).isZero();
        assertThat(same.out()).contains("✅ 같다");

        String key = firstKey();
        Run differ = run("k1.txt", fakeKubectl(clusterSecretJson(Map.of(key, "hand-edited-in-cluster"))), "drift");
        assertThat(differ.code()).as(differ.out()).isEqualTo(1);
        assertThat(differ.out()).contains("값이 다른 키: " + key).doesNotContain("hand-edited-in-cluster", "s3cr3t-value");
    }

    @Test
    @DisplayName("🔴 drill — 보관 키 하나로 풀리면 통과, 받는 사람이 아닌 키는 실패(이 기계의 키는 쓰지 않는다)")
    void drillProvesTheOfflineKeyAlone() throws Exception {
        Path offline = tmp.resolve("vault/offline.txt");
        String pubOffline = run("k1.txt", Map.of(), "keygen", offline.toString()).out().lines()
                .filter(l -> l.startsWith("age1")).findFirst().orElseThrow();
        run("init", pub1, pubOffline);
        assertThat(run("encrypt").code()).isZero();

        Run ok = run("k1.txt", fakeKubectl(clusterSecretJson(Map.of())), "drill", offline.toString());
        assertThat(ok.code()).as(ok.out()).isZero();
        assertThat(ok.out()).contains("받는 사람에 있다", "이 키 하나로 풀린다", "평문과 같다", "클러스터와 같다", "훈련 통과")
                .doesNotContain("s3cr3t-value");

        Run notRecipient = run("k1.txt", fakeKubectl(null), "drill", tmp.resolve("k2.txt").toString());
        assertThat(notRecipient.code()).as(notRecipient.out()).isEqualTo(1);
        assertThat(notRecipient.out()).contains("받는 사람에 없다", "이 키로 풀리지 않는다");
    }

    @Test
    @DisplayName("🔴 rotation — 암호문 줄이 아니라 값의 지문으로 마지막 교체일을 본다(다시 암호화만 한 판은 교체가 아니다)")
    void rotationFollowsValueFingerprints() throws Exception {
        Path repo = tmp.resolve("k8s");
        git(repo, Map.of(), "init", "-q");
        run("init", pub1);
        run("encrypt");
        git(repo, Map.of(), "add", "20-secret.enc.yaml");
        commit(repo, "2026-01-01T00:00:00Z");

        String key = firstKey();
        Path plain = tmp.resolve("k8s/20-secret.yaml");
        String text = Files.readString(plain);
        String line = text.lines().filter(l -> l.trim().startsWith(key + ":")).findFirst().orElseThrow();
        Files.writeString(plain, text.replace(line, "  " + key + ": \"rotated-value\""));
        run("encrypt");
        commit(repo, "2026-11-01T00:00:00Z");
        run("encrypt");   // 값은 그대로 — 암호문 줄은 모두 바뀐다(새 IV) ／ [JA] 値はそのまま — 暗号文の行はすべて変わる(新しい IV)
        commit(repo, "2026-11-15T00:00:00Z");

        Run r = run("k1.txt", Map.of("ROTATION_DAYS", "90"), "rotation");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("판 3개", key + " — 2026-11-01").doesNotContain("rotated-value", "s3cr3t-value");
        String other = r.out().lines().filter(l -> l.contains("—") && !l.contains(key + " —") && l.contains("⏰")).findFirst()
                .orElseThrow(() -> new AssertionError("바꾸지 않은 키가 오래된 것으로 나오지 않았다:\n" + r.out()));
        assertThat(other).contains("2026-01-01");
    }

    private void commit(Path repo, String date) throws Exception {
        git(repo, Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date), "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                "commit", "-q", "-am", "secret " + date);
    }

    private void git(Path repo, Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }
}
