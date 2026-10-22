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
 * 부하 대기열(ops/57-load-queue.sh) — docs/83.
 *
 * <p>밤새 오래 돌리기 뒤에 사람이 없을 때 다음 측정을 잇는 도구라, 틀리면 아침에야 안다. 그래서 셋을 지킨다.
 * ① 지금 도는 k6 가 끝날 때까지 다음 회차를 시작하지 않는다(두 회차가 겹치면 둘 다 망친다). ② 회차가 임계값을 넘어
 * 99 로 끝나도 다음 회차는 간다 — 그것도 결과다. ③ 끝나면 측정용 설정(유입 제한 · 리뷰 확인)을 되돌린다.
 * ops/53 · pgrep · kubectl 은 가짜로 바꿔 돌린다(bash · POSIX 가 없으면 건너뛴다).
 *
 * <p>[JA] 負荷キュー。いまの k6 が終わるまで次を始めない · 閾値超え(99)でも次へ進む · 最後に測定用の設定を戻す。
 */
@DisplayName("ops/57 — 부하 대기열 (docs/83)")
class LoadQueueScriptTest {

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

    /**
     * 가짜 ops/53(부른 순서 · RATE · STAGES 를 calls 에, order 는 FAKE_ORDER_EXIT 로 끝난다) ·
     * 가짜 pgrep(FAKE_BUSY 번은 'k6 가 돈다', 그 뒤 '없다' — 물을 때마다 calls 에 남긴다) · 가짜 kubectl 로 돌린다.
     */
    private Run run(Map<String, String> env, String... args) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        exe(bin.resolve("load"), """
                echo "load $* RATE=${RATE:-} STAGES=${STAGES:-}" >> "$FAKE/calls"
                [ "${1:-}" = order ] && exit "${FAKE_ORDER_EXIT:-0}"
                exit 0
                """);
        exe(bin.resolve("pgrep"), """
                n=$(cat "$FAKE/busy" 2>/dev/null || echo 0)
                if [ "$n" -lt "${FAKE_BUSY:-0}" ]; then echo $(( n + 1 )) > "$FAKE/busy"; echo "pgrep busy" >> "$FAKE/calls"; exit 0; fi
                echo "pgrep idle" >> "$FAKE/calls"; exit 1
                """);
        exe(bin.resolve("kubectl"), """
                case "$*" in
                  *restartCount*) printf '1\\n2\\n' ;;
                  *payment_outbox_event*) printf 'STARTED=4\\n' ;;
                esac
                """);
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", repoRoot().resolve("ops/57-load-queue.sh").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("LOAD", bin.resolve("load").toString());
        pb.environment().put("PGREP", bin.resolve("pgrep").toString());
        pb.environment().put("LOG_DIR", tmp.resolve("queue").toString());
        pb.environment().put("COOLDOWN", "0");
        pb.environment().put("POLL", "0");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private List<String> calls() throws IOException {
        return Files.readAllLines(tmp.resolve("calls"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 지금 k6 가 끝난 뒤에야 시작 · 순서는 limits load → order → browse(RATE = 가장 높은 단계) → report → 설정 되돌리기")
    void waitsThenRunsStepsInOrderAndCleansUp() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("FAKE_BUSY", "3"), "run");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(calls()).containsExactly(
                "pgrep busy", "pgrep busy", "pgrep busy", "pgrep idle",
                "load limits load RATE= STAGES=",
                "load order RATE=5 STAGES=5m:2,5m:5,55m:5,5m:0",
                "load browse RATE=500 STAGES=5m:100,10m:100,2m:200,10m:200,2m:300,10m:300,2m:400,10m:400,2m:500,10m:500,2m:0",
                "load report RATE= STAGES=",
                "load reviews default RATE= STAGES=",
                "load limits default RATE= STAGES=");
        assertThat(r.out()).contains("끝나기를 기다린다").contains("앞 회차가 끝났다")
                .contains("재시작 합계 3").contains("주문 아웃박스 STARTED=4").contains("대기열 끝");
        assertThat(tmp.resolve("queue/queue.pid")).as("끝나면 pid 파일을 지운다").doesNotExist();
        assertThat(Files.readString(tmp.resolve("queue/state"))).isEqualTo("done\n");
    }

    @Test
    @DisplayName("🔴 회차가 임계값을 넘어(99) 끝나도 다음 회차로 간다 — CLEANUP=0 이면 측정용 설정을 그대로 둔다")
    void thresholdBreachDoesNotStopTheQueue() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("FAKE_ORDER_EXIT", "99", "CLEANUP", "0"), "run");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("종료 코드 99 (임계값 넘음").contains("측정용 설정은 그대로");
        assertThat(calls()).anyMatch(c -> c.startsWith("load browse RATE=500"))
                .contains("load report RATE= STAGES=")
                .noneMatch(c -> c.contains("default"));
    }

    @Test
    @DisplayName("🔴 kubectl 이 없는 기계(loadgen)는 회차만 돌린다 — limits · 되돌리기는 부르지 않고 호스트에서 칠 명령을 남긴다")
    void withoutKubectlRunsStepsOnlyAndLeavesHostCommands() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("KUBECTL", tmp.resolve("no-such-kubectl").toString()), "run");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(calls()).filteredOn(c -> c.startsWith("load ")).containsExactly(
                "load order RATE=5 STAGES=5m:2,5m:5,55m:5,5m:0",
                "load browse RATE=500 STAGES=5m:100,10m:100,2m:200,10m:200,2m:300,10m:300,2m:400,10m:400,2m:500,10m:500,2m:0",
                "load report RATE= STAGES=");
        assertThat(r.out()).contains("kubectl 없음 — 유입 제한은 호스트에서 올린 그대로")
                .contains("호스트에서 되돌린다: ./ops/53-load-k6.sh reviews default && ./ops/53-load-k6.sh limits default")
                .doesNotContain("재시작 합계");
    }

    @Test
    @DisplayName("이미 대기열이 돌고 있으면 두 번째는 시작하지 않는다 · plan 은 회차와 시간 합계만 보이고 아무것도 부르지 않는다")
    void secondQueueRefusedAndPlanIsReadOnly() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Files.createDirectories(tmp.resolve("queue"));
        Files.writeString(tmp.resolve("queue/queue.pid"), ProcessHandle.current().pid() + "\n");
        Run second = run(Map.of(), "run");
        assertThat(second.code()).isNotZero();
        assertThat(second.out()).contains("이미 돌고 있다");
        assertThat(tmp.resolve("calls")).doesNotExist();

        Run plan = run(Map.of("COOLDOWN", "600"), "plan");
        assertThat(plan.code()).as(plan.out()).isZero();
        assertThat(plan.out()).contains("1. order-sustained").contains("2. capacity-steps").contains("합계 약 155분");
        assertThat(tmp.resolve("calls")).doesNotExist();
    }
}
