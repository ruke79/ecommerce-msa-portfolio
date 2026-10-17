package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ops/30(관리자 계정) · ops/48(데모 사용자)이 BCrypt 해시에 쓰는 spring-security-crypto jar 를 고르는 법.
 *
 * <p>🐛 예전: {@code find … -name 'spring-security-crypto-*.jar' | head -1} 이 IDE 가 받아 둔 {@code -sources.jar} 를 집어
 * "package org.springframework.security.crypto.bcrypt does not exist" 로 해시를 만들지 못했다(2026-10-17, IDE 가 있는 기계).
 * 이제 -sources · -javadoc · -tests 를 빼고, BCrypt.class 가 든 것 중 가장 새 판을 쓴다.
 *
 * <p>[JA] ops/30 · ops/48 が BCrypt に使う jar の選び方。以前は head -1 が -sources.jar を拾って失敗した。
 */
@DisplayName("ops/30 · ops/48 — BCrypt 용 jar 고르기")
class CryptoJarScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bashAvailable() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    /** Gradle 캐시 모양으로 zip 하나 — 안에는 주어진 항목만. */
    private Path jar(Path home, String version, String fileName, String entry, byte[] body) throws IOException {
        Path dir = Files.createDirectories(home.resolve("caches/modules-2/files-2.1/org.springframework.security/"
                + "spring-security-crypto/" + version + "/" + Integer.toHexString(fileName.hashCode())));
        Path jar = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(body);
            zip.closeEntry();
        }
        return jar;
    }

    private record Run(int code, String out) {}

    /** 스크립트에서 함수만 꺼내(stub 과 함께) 돌린다 — 스크립트 본체(인자 처리 · DB 연결)는 돌지 않는다. */
    private Run fn(String script, Path gradleHome, String call, String stdin) throws Exception {
        String body = "die() { echo \"DIE: $1\" >&2; exit 1; }; need() { command -v \"$1\" >/dev/null || die \"$1\"; }; "
                // 함수 하나를 꺼낸다 — bcrypt_hash 안의 Java here-doc 에도 첫 칸의 } 가 있어 sed 범위로는 중간에서 끊긴다
                + "fn_src() { awk -v n=\"$1\" '$0 ~ \"^\"n\"\\\\(\\\\) \\\\{\" {p=1} p {print} p && /<<.JAVA.$/ {h=1; next} "
                + "h && /^JAVA$/ {h=0; next} p && !h && /^}$/ {exit}' \"$2\"; }; "
                + "eval \"$(fn_src crypto_jar " + script + ")\"; eval \"$(fn_src bcrypt_hash " + script + ")\"; " + call;
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("GRADLE_USER_HOME", gradleHome.toString());
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out.strip());
    }

    private static final String BCRYPT_CLASS = "org/springframework/security/crypto/bcrypt/BCrypt.class";
    private static final String BCRYPT_SOURCE = "org/springframework/security/crypto/bcrypt/BCrypt.java";

    @ParameterizedTest
    @ValueSource(strings = { "ops/30-admin-account.sh", "ops/48-bulk-users.sh" })
    @DisplayName("🔴 -sources · -javadoc jar 만 있으면 고르지 않는다 — 클래스 jar 가 있으면 그것(더 새 판의 sources 보다)")
    void picksTheClassJarOnly(String script) throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Path home = tmp.resolve("gradle");
        jar(home, "6.9.0", "spring-security-crypto-6.9.0-sources.jar", BCRYPT_SOURCE, "class BCrypt {}".getBytes());
        jar(home, "6.9.0", "spring-security-crypto-6.9.0-javadoc.jar", "index.html", "<html/>".getBytes());
        Run none = fn(script, home, "crypto_jar", "");
        assertThat(none.out()).as("sources · javadoc 만 있으면 비어야 한다(그래야 분명한 오류로 멈춘다)").isEmpty();

        Path classes = jar(home, "6.5.11", "spring-security-crypto-6.5.11.jar", BCRYPT_CLASS, new byte[] { (byte) 0xCA, (byte) 0xFE });
        Run picked = fn(script, home, "crypto_jar", "");
        assertThat(picked.out()).isEqualTo(classes.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "ops/30-admin-account.sh", "ops/48-bulk-users.sh" })
    @DisplayName("🔴 실제 jar 옆에 sources jar 가 있어도 BCrypt 해시($2a$10$)를 만든다")
    void hashesWithTheRealJarNextToSources(String script) throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Path cache = Path.of(Optional.ofNullable(System.getenv("GRADLE_USER_HOME")).orElse(System.getProperty("user.home") + "/.gradle"));
        Optional<Path> real;
        try (Stream<Path> s = Files.exists(cache.resolve("caches")) ? Files.walk(cache.resolve("caches")) : Stream.empty()) {
            real = s.filter(p -> p.getFileName().toString().matches("spring-security-crypto-[0-9.]+\\.jar")).findFirst();
        }
        assumeTrue(real.isPresent(), "Gradle 캐시에 spring-security-crypto 가 없다");
        Path home = tmp.resolve("gradle-real");
        jar(home, "9.9.9", "spring-security-crypto-9.9.9-sources.jar", BCRYPT_SOURCE, "class BCrypt {}".getBytes());
        Path dir = Files.createDirectories(home.resolve("caches/modules-2/files-2.1/org.springframework.security/spring-security-crypto/real/x"));
        Files.copy(real.get(), dir.resolve(real.get().getFileName()));

        Run r = fn(script, home, "bcrypt_hash", "demo-password\n");
        assertThat(r.code()).as(r.out()).isZero();
        List<String> lines = r.out().lines().toList();
        assertThat(lines.get(lines.size() - 1)).matches("\\$2a\\$10\\$.{53}");
    }
}
