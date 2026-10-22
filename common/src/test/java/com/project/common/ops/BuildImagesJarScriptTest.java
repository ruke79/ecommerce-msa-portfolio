package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/19 가 이미지에 넣을 jar 를 고르는 법(pick_jar).
 *
 * <p>예전: {@code ls *.jar | head -1} 을 확인 없이 넣었다 — 잘린 jar 가 들어가도 빌드 · push 는 통과하고, 파드가
 * "Error: Invalid or corrupt jarfile /app/app.jar" 로 1초 만에 죽을 때에야 드러난다. (2026-10-18 데모의 같은 증상은
 * 노드가 풀어 둔 사본의 손상이었다 — 레지스트리 이미지의 jar 해시는 빌드 결과와 같았다. 이 테스트는 빌드 쪽 원인을 막는다.)
 *
 * <p>[JA] ops/19 がイメージに入れる jar の選び方。確認なしだと壊れた jar のイメージが push まで通る。
 */
@DisplayName("ops/19 — 이미지에 넣을 jar 고르기 · 확인")
class BuildImagesJarScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean toolsAvailable() {
        return (Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")))
                && (Files.isExecutable(Path.of("/usr/bin/python3")) || Files.isExecutable(Path.of("/bin/python3")));
    }

    /** bootJar 모양(매니페스트 + 클래스 하나). mainClass 가 null 이면 Main-Class 없는 jar. */
    private Path jar(String name, String mainClass, long epochSecond) throws IOException {
        Path libs = Files.createDirectories(tmp.resolve("saga-projection/build/libs"));
        Path jar = libs.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            String mf = "Manifest-Version: 1.0\r\n" + (mainClass == null ? "" : "Main-Class: " + mainClass + "\r\n");
            zip.write(mf.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/App.class"));
            byte[] body = new byte[64 * 1024];
            Arrays.fill(body, (byte) 7);
            zip.write(body);
            zip.closeEntry();
        }
        Files.setLastModifiedTime(jar, FileTime.from(Instant.ofEpochSecond(epochSecond)));
        return jar;
    }

    private record Run(int code, String out) {}

    /** 스크립트에서 pick_jar 만 꺼내 돌린다 — 빌드(gradle · docker)는 돌지 않는다. */
    private Run pick() throws Exception {
        Path script = repoRoot().resolve("ops/19-build-images.sh");
        String body = "warn() { echo \"WARN: $*\"; }; "
                + "eval \"$(sed -n '/^pick_jar() {/,/^}/p' " + script + ")\"; pick_jar saga-projection";
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(tmp.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out.strip());
    }

    @Test
    @DisplayName("온전한 bootJar — 이름순이 아니라 가장 새 것(남은 옛 판 · plain 은 고르지 않는다)")
    void picksTheNewestRunnableJar() throws Exception {
        assumeTrue(toolsAvailable(), "bash · python3 가 없다");
        jar("saga-projection-0.0.1-SNAPSHOT.jar", "org.springframework.boot.loader.launch.JarLauncher", 1_000_000);
        jar("saga-projection-1.0.0-plain.jar", null, 3_000_000);
        jar("saga-projection-1.0.0.jar", "org.springframework.boot.loader.launch.JarLauncher", 2_000_000);
        Run r = pick();
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).isEqualTo("saga-projection/build/libs/saga-projection-1.0.0.jar");
    }

    @Test
    @DisplayName("🔴 잘린 jar 는 이미지에 넣지 않는다 — 파드가 'Invalid or corrupt jarfile' 로 죽기 전에 빌드에서 멈춘다")
    void refusesATruncatedJar() throws Exception {
        assumeTrue(toolsAvailable(), "bash · python3 가 없다");
        Path jar = jar("saga-projection-1.0.0.jar", "org.springframework.boot.loader.launch.JarLauncher", 2_000_000);
        byte[] whole = Files.readAllBytes(jar);
        Files.write(jar, Arrays.copyOf(whole, whole.length / 2));
        Run r = pick();
        assertThat(r.code()).as(r.out()).isNotZero();
        assertThat(r.out()).contains("zip 으로 읽히지 않는다").contains("실행할 수 없습니다")
                .doesNotContain("saga-projection/build/libs/saga-projection-1.0.0.jar\n");
    }

    @Test
    @DisplayName("🔴 Main-Class 가 없는 jar(실행 jar 가 아님)도 넣지 않는다")
    void refusesAJarWithoutMainClass() throws Exception {
        assumeTrue(toolsAvailable(), "bash · python3 가 없다");
        jar("saga-projection-1.0.0.jar", null, 2_000_000);
        Run r = pick();
        assertThat(r.code()).as(r.out()).isNotZero();
        assertThat(r.out()).contains("Main-Class");
    }
}
