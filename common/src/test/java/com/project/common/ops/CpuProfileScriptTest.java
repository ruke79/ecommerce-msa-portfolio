package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 부하 중 CPU 프로파일(ops/58-cpu-profile.sh) — docs/83 §4.
 *
 * <p>파드 안에 실행 파일을 넣고 JVM 에 붙는 도구라 셋을 지킨다. ① 운영 클러스터(APP_PUBLIC_URL 이 운영 주소 · 빈 값)는 아무것도 넣기 전에 거절한다.
 * ② 정한 부하(Prometheus 의 요청/초)에 닿기 전에는 표본을 시작하지 않는다 — 단계 올림의 앞 단계를 뜨면 엉뚱한 곳을 고친다.
 * ③ 파드 안에 결과가 없으면(표본 중 재시작) 옛 파일을 성공으로 보이지 않고 실패로 끝난다. 표의 묶음은 잎에서 가장 가까운 아는 묶음이다.
 * kubectl · curl · async-profiler 는 가짜로 바꿔 돌린다(bash · POSIX 가 없으면 건너뛴다).
 *
 * <p>[JA] 本番クラスタは何も入れる前に拒否 · 決めた負荷に届くまでサンプルを始めない · Pod 内に結果がなければ失敗で終わる。
 */
@DisplayName("ops/58 — 부하 중 CPU 프로파일 (docs/83 §4)")
class CpuProfileScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.isExecutable(Path.of("/bin/bash"));
    }

    private record Run(int code, String out) {
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** async-profiler 모양의 collapsed — 자바 프레임은 슬래시, JVM 은 C++ 프레임. */
    private static final String COLLAPSED = String.join("\n",
            "java/lang/Thread.run;io/netty/channel/nio/NioEventLoop.run;sun/security/ssl/SSLEngineImpl.wrap;com/sun/crypto/provider/GaloisCounterMode.doFinal 50",
            "java/lang/Thread.run;com/project/catalog_service/service/HomeService.home;com/fasterxml/jackson/databind/ObjectMapper.writeValueAsBytes;java/lang/String.charAt 30",
            "java/lang/Thread.run;com/project/catalog_service/MarkupCompiler.render 10",
            "G1ParScanThreadState::trim_queue 7",
            "C2Compiler::compile_method 3",
            "") + "\n";

    /**
     * 가짜 kubectl(부른 것을 calls 에 · cp 는 FAKE_COLLAPSED 를 쓴다 · FAKE_MISSING 의 서비스는 파드 안 결과가 없다) ·
     * 가짜 curl(물을 때마다 FAKE_RPS 의 다음 값을 Prometheus 응답으로) · 가짜 async-profiler 폴더로 돌린다.
     */
    private Run run(Map<String, String> env, String... args) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path ap = Files.createDirectories(tmp.resolve("ap/bin"));
        Files.createDirectories(tmp.resolve("ap/lib"));
        exe(ap.resolve("asprof"), "exit 0\n");
        Files.writeString(tmp.resolve("ap/lib/libasyncProfiler.so"), "x");
        Files.writeString(tmp.resolve("collapsed"), COLLAPSED);
        exe(bin.resolve("kubectl"), """
                echo "kubectl $*" >> "$FAKE/calls"
                case "$*" in
                  *configmap*ecommerce-config*) echo "${FAKE_URL-https://shop.lab}" ;;
                  *"get pods -l app="*) a="$*"; a="${a#*app=}"; echo "${a%% *}-pod-1" ;;
                  *restartCount*) echo 0 ;;
                  *nodeName*) echo agent1 ;;
                  *"-- test -x /tmp/ap/bin/asprof"*) exit 1 ;;
                  *"-- sh -c for p in"*) echo 7 ;;
                  *"-- test -s /tmp/prof-"*)
                     for m in ${FAKE_MISSING:-}; do case "$*" in *"prof-$m.collapsed"*) exit 1 ;; esac; done ;;
                  *" cp "*":/tmp/prof-"*) for last; do :; done; cp "$FAKE/collapsed" "$last" ;;
                esac
                exit 0
                """);
        exe(bin.resolve("curl"), """
                args="$*"; n=$(cat "$FAKE/polls" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$FAKE/polls"
                set -- ${FAKE_RPS:-0}; shift $(( n - 1 < $# ? n - 1 : $# - 1 )); v="$1"
                echo "curl rps=$v $args" >> "$FAKE/calls"
                printf '{"status":"success","data":{"result":[{"metric":{},"value":[0,"%s.7"]}]}}' "$v"
                """);
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", repoRoot().resolve("ops/58-cpu-profile.sh").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("AP", tmp.resolve("ap").toString());
        pb.environment().put("OUT", tmp.resolve("out").toString());
        pb.environment().put("PROM", "http://prom.test:9090");
        pb.environment().put("SECS", "0");
        pb.environment().put("DELAY", "0");
        pb.environment().put("POLL", "0");
        pb.environment().put("WAIT_MAX", "5");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String calls() throws IOException {
        Path c = tmp.resolve("calls");
        return Files.exists(c) ? Files.readString(c) : "";
    }

    @Test
    @DisplayName("운영 클러스터 · 빈 APP_PUBLIC_URL 은 프로파일러를 넣기 전에 거절한다")
    void refusesProductionCluster() throws Exception {
        assumeTrue(posix());
        for (String url : List.of("https://www.ysyproject.com", "")) {
            Files.deleteIfExists(tmp.resolve("calls"));
            Run r = run(Map.of("FAKE_URL", url, "WHEN", "now"));
            assertThat(r.code()).as(r.out()).isNotZero();
            assertThat(r.out()).contains(url.isEmpty() ? "APP_PUBLIC_URL 이 비어 있다" : "운영 클러스터다");
            assertThat(calls()).doesNotContain(" cp ").doesNotContain(" exec ");
        }
    }

    @Test
    @DisplayName("정한 요청/초를 넘기 전에는 표본을 시작하지 않고, 넘으면 서비스마다 표본 → 묶음 · flat · inclusive 표")
    void waitsForLoadThenProfilesEachService() throws Exception {
        assumeTrue(posix());
        Run r = run(Map.of("WHEN", "rps:gateway-server:900", "FAKE_RPS", "300 600 950 960"));
        assertThat(r.code()).as(r.out()).isZero();

        String calls = calls();
        int third = calls.indexOf("curl rps=950");
        int sampleStart = calls.indexOf("nohup /tmp/ap/bin/asprof");
        assertThat(third).as(calls).isPositive();
        assertThat(sampleStart).as("표본은 부하가 900 을 넘은 뒤에야").isGreaterThan(third);
        assertThat(calls.indexOf("curl rps=600")).isLessThan(third);
        // 기본 두 서비스 모두, 넣기 → 붙는지 확인 → 표본
        assertThat(calls).contains("cp " + tmp.resolve("ap") + " catalog-service-pod-1:/tmp/ap")
                .contains("cp " + tmp.resolve("ap") + " gateway-server-pod-1:/tmp/ap")
                .contains("-e itimer -d 3 -o flat=3 7")
                .contains("-f /tmp/prof-catalog-service.collapsed 7")
                .contains("-f /tmp/prof-gateway-server.collapsed 7");
        assertThat(calls).contains("environment=\"demo\",service=\"gateway-server\"");

        String table = Files.readString(tmp.resolve("out/catalog-service.txt"));
        // 묶음: 잎에서 가장 가까운 아는 묶음 — 암호 잎은 TLS, String.charAt 은 부른 Jackson, 자바 클래스 이름의 Markup 은 GC 가 아니다
        assertThat(table).startsWith("표본 100")
                .containsPattern("50 +50\\.00%  TLS · 암호")
                .containsPattern("30 +30\\.00%  JSON")
                .containsPattern("10 +10\\.00%  앱")
                .containsPattern("7 +7\\.00%  GC")
                .containsPattern("3 +3\\.00%  JIT")
                .contains("com.sun.crypto.provider.GaloisCounterMode.doFinal")
                .containsPattern("90 +90\\.00%  java.lang.Thread.run");
        assertThat(Files.readString(tmp.resolve("out/meta.txt")))
                .contains("load_start=gateway-server 950/초")
                .contains("catalog-service pod=catalog-service-pod-1 node=agent1 restarts_before=0 restarts_after=0");
        assertThat(r.out()).contains("✓ catalog-service: 표본 100").contains("✓ gateway-server: 표본 100");
    }

    @Test
    @DisplayName("파드 안에 결과가 없으면(표본 중 재시작) 옛 파일을 남기지 않고 실패로 끝난다")
    void missingResultInPodFails() throws Exception {
        assumeTrue(posix());
        Files.createDirectories(tmp.resolve("out"));
        Files.writeString(tmp.resolve("out/gateway-server.txt"), "옛 결과");
        Run r = run(Map.of("WHEN", "now", "FAKE_MISSING", "gateway-server"));
        assertThat(r.code()).as(r.out()).isNotZero();
        assertThat(r.out()).contains("✗ gateway-server: 파드 안에 결과가 없다").contains("✓ catalog-service");
        assertThat(tmp.resolve("out/gateway-server.txt")).doesNotExist();
    }

    @Test
    @DisplayName("부하가 오지 않으면 WAIT_MAX 뒤 표본 없이 멈춘다 · WHEN=rps 는 PROM 이 필요하다")
    void givesUpWithoutLoadAndNeedsProm() throws Exception {
        assumeTrue(posix());
        Run r = run(Map.of("WHEN", "rps:gateway-server:900", "FAKE_RPS", "100", "POLL", "1", "WAIT_MAX", "2"));
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("조건에 닿지 않았다(gateway-server 마지막 100/초)");
        assertThat(calls()).doesNotContain("nohup");

        Run noProm = run(Map.of("PROM", ""));
        assertThat(noProm.code()).isNotZero();
        assertThat(noProm.out()).contains("PROM(http://<obs-vm>:9090)이 필요하다");
    }
}
