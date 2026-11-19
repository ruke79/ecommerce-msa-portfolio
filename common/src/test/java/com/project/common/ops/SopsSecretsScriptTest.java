package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(enc().lines().filter(l -> !l.startsWith("#")).filter(l -> l.contains("s3cr3t-value")).toList()).isEmpty();
        assertThat(enc()).contains("ENC[AES256_GCM,", "MAIL_PASSWORD:", "kind: Secret", "sops:");
        assertThat(Files.getPosixFilePermissions(pub1File)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        Run c = run("check");
        assertThat(c.code()).as(c.out()).isZero();
        assertThat(c.out()).contains("값이 모두 암호문", "풀린다", "과 같다");
        // 두 번째 받는 사람도 푼다
        assertThat(run("k2.txt", Map.of(), "check").out()).contains("풀린다");
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

    @Test
    @DisplayName("🔴 apply 는 평문 파일 없이 kubectl 로 바로 — 디스크에 평문이 남지 않는다")
    void applyStreamsWithoutPlaintextFile() throws Exception {
        run("init", pub1);
        run("encrypt");
        Files.delete(tmp.resolve("k8s/20-secret.yaml"));
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/bin/sh\ncat > \"$APPLIED\"\necho secret/ecommerce-secrets configured\n");
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        Path applied = tmp.resolve("applied.yaml");

        Run r = run("k1.txt", Map.of("PATH", bin + ":" + System.getenv("PATH"), "APPLIED", applied.toString()), "apply");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(Files.readString(applied)).contains("s3cr3t-value", "name: ecommerce-secrets");
        assertThat(tmp.resolve("k8s/20-secret.yaml")).doesNotExist();
    }
}
