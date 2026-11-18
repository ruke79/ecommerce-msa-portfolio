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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 평시 GC 기준값(ops/61-gc-baseline.sh) — docs/91 §4-3 · docs/92 F-6.
 *
 * <p>가짜 curl 이 질의마다 정해 둔 Prometheus 응답을 돌려준다. 지키는 것: ① 표의 값이 질의 결과에서 맞게 계산된다(파드당 · 요청당 ·
 * 단위) ② gc 라벨 → Serial · G1 · Parallel, 한 서비스 안에서 다르면 '섞임' ③ 경보 문턱(10%) · GC 뒤 힙 90% · 정지 0.5초를 눈여겨볼 것에
 * ④ 창 · 선택자가 질의에 그대로 들어간다 — 창은 보존(15일) 안, 선택자는 라벨 하나만(질의를 바꿔 끼울 수 없다)
 * ⑤ Prometheus 가 없거나 모든 질의가 실패하면 0 이 아닌 종료.
 *
 * <p>[JA] 偽 curl がクエリごとに決めた Prometheus 応答を返す。表の計算 · GC 種別 · 窓と選択子 · 入力検査 · 失敗時の終了コードを見る。
 */
@DisplayName("ops/61 — 평시 GC 기준값 (docs/91 §4-3 · docs/92 F-6)")
class GcBaselineScriptTest {

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

    /** 서비스 하나의 값 — Prometheus 벡터 결과 한 줄. */
    private static String one(String service, String value) {
        return "{\"metric\":{\"service\":\"" + service + "\"},\"value\":[1,\"" + value + "\"]}";
    }

    private static String vector(String... items) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", items) + "]}}";
    }

    private static String gcKind(String service, String gc) {
        return "{\"metric\":{\"service\":\"" + service + "\",\"gc\":\"" + gc + "\"},\"value\":[1,\"5\"]}";
    }

    /** 질의 열 이름 → 응답. catalog 는 Serial · 두 파드, gateway 는 파드마다 GC 가 다르다(Serial 하나 · G1 하나). */
    private static Map<String, String> sample() {
        Map<String, String> m = new HashMap<>();
        m.put("pods", vector(one("catalog-service", "2"), one("gateway-server", "2")));
        m.put("gctype", vector(gcKind("catalog-service", "Copy"), gcKind("catalog-service", "MarkSweepCompact"),
                gcKind("gateway-server", "Copy"), gcKind("gateway-server", "G1 Young Generation")));
        m.put("heap", vector(one("catalog-service", "536870912"), one("gateway-server", "402653184")));
        m.put("gc_avg", vector(one("catalog-service", "0.004"), one("gateway-server", "0.02")));
        m.put("gc_max", vector(one("catalog-service", "0.12"), one("gateway-server", "0.03")));
        m.put("pause_max", vector(one("catalog-service", "0.25"), one("gateway-server", "0.6")));
        m.put("gc_per_min", vector(one("catalog-service", "3"), one("gateway-server", "8")));
        m.put("alloc", vector(one("catalog-service", "4194304"), one("gateway-server", "20971520")));
        m.put("rps", vector(one("catalog-service", "20"), one("gateway-server", "50")));
        m.put("oldgen", vector(one("catalog-service", "0.93"), one("gateway-server", "0.4")));
        return m;
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * 가짜 curl — 인자를 curl.log 에, /-/ready 는 FAKE_DOWN 이 아니면 성공, 질의는 내용으로 열을 골라 $FAKE/resp/<열>.json 을 낸다
     * (파일이 없으면 실패 — 그 열의 질의가 실패한 것).
     */
    private Run run(Map<String, String> responses, Map<String, String> env) throws Exception {
        assumeTrue(posix(), "bash · POSIX 가 없다");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path resp = Files.createDirectories(tmp.resolve("resp"));
        try (var old = Files.list(resp)) {
            for (Path f : old.toList()) {
                Files.delete(f);
            }
        }
        for (Map.Entry<String, String> e : responses.entrySet()) {
            Files.writeString(resp.resolve(e.getKey() + ".json"), e.getValue(), StandardCharsets.UTF_8);
        }
        exe(bin.resolve("curl"), """
                echo "curl $*" >> "$FAKE/curl.log"
                case "$*" in
                  */-/ready*) [ "${FAKE_DOWN:-0}" = "1" ] && exit 7; exit 0 ;;
                esac
                q=""; for a in "$@"; do case "$a" in query=*) q="${a#query=}" ;; esac; done
                case "$q" in
                  *"up{"*) k=pods ;;
                  *"count by (service, gc)"*) k=gctype ;;
                  *jvm_memory_max_bytes*) k=heap ;;
                  *"avg_over_time(jvm_gc_overhead"*) k=gc_avg ;;
                  *"max_over_time(jvm_gc_overhead"*) k=gc_max ;;
                  *jvm_gc_pause_seconds_max*) k=pause_max ;;
                  *"rate(jvm_gc_pause_seconds_count"*) k=gc_per_min ;;
                  *jvm_gc_memory_allocated*) k=alloc ;;
                  *http_server_requests*) k=rps ;;
                  *jvm_memory_usage_after_gc*) k=oldgen ;;
                  *) exit 22 ;;
                esac
                [ -f "$FAKE/resp/$k.json" ] || exit 22
                cat "$FAKE/resp/$k.json"
                """);
        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/61-gc-baseline.sh").toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("OUT", tmp.resolve("out").toString());
        pb.environment().put("PROM", "http://localhost:9090");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String curlLog() throws IOException {
        return Files.readString(tmp.resolve("curl.log"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 표 — Copy · MarkSweepCompact 는 Serial, 파드당 GC 횟수 · 할당은 파드 수로 나누고 요청당 할당은 요청/초로, 단위(M · % · ms · KB)")
    void tableIsComputedFromQueries() throws Exception {
        Run r = run(sample(), Map.of());
        assertThat(r.code()).as(r.out()).isZero();
        String md = Files.readString(tmp.resolve("out/gc-baseline.md"), StandardCharsets.UTF_8);
        assertThat(md).as("catalog — 512M · 평균 0.40% · 최대 12.0% · 250ms · 3/2 = 1.50 · 4MiB/2 = 2.00 · 4MiB/20/1KiB = 205 · 93%")
                .contains("| catalog-service | 2 | Serial | 512M | 0.40% | 12.0% | 250 | 1.50 | 2.00 | 205 | 93% |")
                .contains("GC 부담 최대 12% — 경보 문턱(10%)", "GC 뒤 힙 93%");
        assertThat(md).as("gateway — 파드마다 GC 가 다르면 섞임 · 정지 600ms")
                .contains("| gateway-server | 2 | 섞임: Serial · G1 | 384M |")
                .contains("파드마다 GC 가 다르다", "정지 최대 600ms");
        assertThat(md).contains("질의 실패: 없음", "# 평시 GC 기준값 — 지난 7d");
    }

    @Test
    @DisplayName("🔴 창 · 선택자가 모든 질의에 그대로 — 기본 지난 7d · job=\"spring-actuator\", WINDOW · MATCH 로 바꾼다")
    void windowAndSelectorReachEveryQuery() throws Exception {
        Run r = run(sample(), Map.of());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(curlLog()).contains("avg_over_time(jvm_gc_overhead{job=\"spring-actuator\"}[7d])",
                "rate(jvm_gc_memory_allocated_bytes_total{job=\"spring-actuator\"}[7d])",
                "jvm_memory_usage_after_gc{job=\"spring-actuator\",area=\"heap\",pool=\"long-lived\"}[7d]");

        Files.delete(tmp.resolve("curl.log"));
        Run demo = run(sample(), Map.of("WINDOW", "24h", "MATCH", "environment=\"demo\""));
        assertThat(demo.code()).as(demo.out()).isZero();
        assertThat(curlLog()).contains("max_over_time(jvm_gc_pause_seconds_max{environment=\"demo\"}[24h])")
                .doesNotContain("spring-actuator");
    }

    @Test
    @DisplayName("🔴 입력 검사 — PROM 없음 · 창 14d 넘음(보존 15일) · 창 모양 · 질의를 바꾸는 선택자는 아무 질의도 하기 전에 거절")
    void rejectsBadInputBeforeQuerying() throws Exception {
        Map<String, String> bad = new HashMap<>();
        bad.put("WINDOW", "15d");
        Run tooLong = run(sample(), bad);
        assertThat(tooLong.code()).isNotZero();
        assertThat(tooLong.out()).contains("WINDOW 는 14d 까지");
        Run shape = run(sample(), Map.of("WINDOW", "7x"));
        assertThat(shape.out()).contains("WINDOW 는 숫자 + h|d");
        Run inject = run(sample(), Map.of("MATCH", "job=\"x\"} or vector(1) #"));
        assertThat(inject.code()).isNotZero();
        assertThat(inject.out()).contains("MATCH 는 라벨=\"값\" 하나");
        Run noProm = run(sample(), Map.of("PROM", ""));
        assertThat(noProm.out()).contains("PROM 이 필요하다");
        assertThat(Files.exists(tmp.resolve("curl.log"))).as("어느 경우도 Prometheus 에 묻지 않았다").isFalse();
    }

    @Test
    @DisplayName("🔴 Prometheus 가 답하지 않으면 멈추고, 모든 질의가 실패하면 0 이 아닌 종료 · 일부만 실패하면 그 열을 적는다")
    void failuresAreVisible() throws Exception {
        Run down = run(sample(), Map.of("FAKE_DOWN", "1"));
        assertThat(down.code()).isNotZero();
        assertThat(down.out()).contains("Prometheus(http://localhost:9090)가 답하지 않는다");

        Run none = run(Map.of(), Map.of());
        assertThat(none.code()).as("모든 질의 실패").isEqualTo(2);
        assertThat(none.out()).as("그래도 결과 위치는 알린다").contains("gc-baseline.md");

        Map<String, String> partial = sample();
        partial.remove("oldgen");
        partial.remove("rps");
        Run some = run(partial, Map.of());
        assertThat(some.code()).as(some.out()).isZero();
        assertThat(Files.readString(tmp.resolve("out/gc-baseline.md"), StandardCharsets.UTF_8))
                .contains("질의 실패: rps, oldgen")
                .as("요청당 할당 · GC 뒤 힙은 '-'").contains("| 2.00 | - | - |");
    }
}
