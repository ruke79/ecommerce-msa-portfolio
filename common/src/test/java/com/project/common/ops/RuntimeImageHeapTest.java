package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 백엔드 런타임 이미지(ops/Dockerfile.runtime)는 최대 힙을 컨테이너 한도의 50% 로 연다 — 실제 {@code sh -c} 로 펼쳐 본다.
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 힙 설정이 없어 JVM 기본 25% — catalog(1Gi)는 256MB 로
 * {@code OutOfMemoryError: Java heap space} 를 내며 재시작을 되풀이했다(측정 실패 99%).
 *
 * <p>[JA] バックエンドのランタイムイメージは最大ヒープをコンテナ上限の 50% で起動する — 実際に sh -c で展開して確かめる。
 */
@DisplayName("런타임 이미지 — 최대 힙은 한도의 50% (JVM 기본 25% 아님)")
class RuntimeImageHeapTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String dockerfile() throws IOException {
        return Files.readString(repoRoot().resolve("ops/Dockerfile.runtime"), StandardCharsets.UTF_8);
    }

    /** ENTRYPOINT ["sh","-c","…"] 의 셸 명령. */
    private static String entrypointCommand(String dockerfile) {
        Matcher m = Pattern.compile("(?m)^ENTRYPOINT \\[\"sh\",\"-c\",\"(.*)\"\\]\\s*$").matcher(dockerfile);
        assertThat(m.find()).as("ENTRYPOINT [\"sh\",\"-c\",…]").isTrue();
        return m.group(1);
    }

    /** 가짜 java(인자를 한 줄에 하나씩 남긴다)로 ENTRYPOINT 를 이미지의 ENV 와 함께 돌린다. */
    private List<String> javaArgs(Map<String, String> env) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "sh 가 없다");
        String df = dockerfile();
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\nfor a in \"$@\"; do echo \"$a\"; done > \"$FAKE_OUT\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", entrypointCommand(df)).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.environment().put("FAKE_OUT", tmp.resolve("args").toString());
        Matcher e = Pattern.compile("(?m)^ENV (\\w+)=(\\S+)\\s*$").matcher(df);
        while (e.find()) {
            pb.environment().put(e.group(1), e.group(2));   // 이미지의 기본값
        }
        pb.environment().putAll(env);                         // 매니페스트가 주는 값
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return Files.readAllLines(tmp.resolve("args"));
    }

    @Test
    @DisplayName("🔴 기본은 -XX:MaxRAMPercentage=50 이 java 에 넘어간다 · JAVA_OPTS 는 그 뒤(이긴다) · 서비스마다 JVM_MAX_RAM_PERCENTAGE")
    void entrypointPassesHeapPercentage() throws Exception {
        List<String> def = javaArgs(Map.of());
        assertThat(def).as("이미지 기본값").startsWith("-XX:MaxRAMPercentage=50",
                "-XX:ErrorFile=/var/log/jvm/hs_err_pid%p.log", "-XX:-CreateCoredumpOnCrash", "-jar", "/app/app.jar");

        List<String> withOpts = javaArgs(Map.of("JAVA_OPTS", "-Xmx700m -Dfoo=bar"));
        assertThat(withOpts.indexOf("-Xmx700m")).as("JAVA_OPTS 가 뒤 — 같은 옵션이면 뒤가 이긴다")
                .isGreaterThan(withOpts.indexOf("-XX:MaxRAMPercentage=50"));

        assertThat(javaArgs(Map.of("JVM_MAX_RAM_PERCENTAGE", "60"))).contains("-XX:MaxRAMPercentage=60");
    }

    @Test
    @DisplayName("🔴 JVM 이 죽으면 충돌 기록은 /var/log/jvm(매니페스트의 emptyDir)에 · 코어 덤프는 없다 — 실제 JVM 을 SIGSEGV 로 죽여 본다(B-97)")
    void crashRecordGoesToTheMountedDirectoryWithoutCoreDump() throws Exception {
        // 🐛 2026-10-21 실습 8시간(docs/83 §2.8): catalog 파드가 SIGSEGV(LinkResolver::runtime_resolve_virtual_method)로 죽었는데
        //    기록 /app/hs_err_pid7.log 가 컨테이너와 함께 사라졌다 — 어디서 죽었는지 볼 수 없었다
        List<String> args = javaArgs(Map.of());
        String errorFile = args.stream().filter(a -> a.startsWith("-XX:ErrorFile=")).findFirst().orElseThrow();
        assertThat(errorFile).isEqualTo("-XX:ErrorFile=/var/log/jvm/hs_err_pid%p.log");
        assertThat(args).contains("-XX:-CreateCoredumpOnCrash");
        assertThat(dockerfile()).as("이미지에 그 디렉터리가 있고 앱 사용자(1000)가 쓸 수 있다 — emptyDir 이 없는 실행(compose)에서도")
                .contains("mkdir -p /app/logs /var/log/jvm && chown -R 1000:1000 /app /var/log/jvm");

        // 같은 두 옵션(경로만 임시 디렉터리)으로 실제 JVM 을 죽인다 — Unsafe 로 주소 0 에 쓴다
        Path crashDir = Files.createDirectories(tmp.resolve("jvm"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path src = work.resolve("Crash.java");
        Files.writeString(src, """
                public class Crash {
                    public static void main(String[] a) throws Exception {
                        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                        f.setAccessible(true);
                        ((sun.misc.Unsafe) f.get(null)).putAddress(0L, 42L);
                    }
                }
                """);
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        Process p = new ProcessBuilder(java.toString(), errorFile.replace("/var/log/jvm", crashDir.toString()), "-XX:-CreateCoredumpOnCrash",
                src.toString()).directory(work.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isNotZero();
        try (var files = Files.list(crashDir)) {
            List<Path> records = files.toList();
            assertThat(records).as(out).singleElement().satisfies(r -> {
                assertThat(r.getFileName().toString()).matches("hs_err_pid\\d+\\.log");
                assertThat(Files.readString(r)).contains("A fatal error has been detected by the Java Runtime Environment");
            });
        }
        try (var files = Files.list(work)) {
            assertThat(files.map(f -> f.getFileName().toString())).as("작업 디렉터리에는 기록도 코어도 없다")
                    .noneMatch(n -> n.startsWith("hs_err") || n.startsWith("core"));
        }
        assertThat(out).as("코어 덤프를 만들지 않는다고 알린다").contains("CreateCoredumpOnCrash turned off");
    }

    @Test
    @DisplayName("그 옵션을 실제 JVM 이 받아들인다 — MaxRAMPercentage = 50")
    void realJvmAcceptsTheOption() throws Exception {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
        List<String> cmd = new ArrayList<>(List.of(java.toString(), javaArgs(Map.of()).get(0), "-XX:+PrintFlagsFinal", "-version"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0).as(out).isTrue();
        assertThat(out).containsPattern("MaxRAMPercentage\\s+=\\s+50\\.0+");
    }
}
