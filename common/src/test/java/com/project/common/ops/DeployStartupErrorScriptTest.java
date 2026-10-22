package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/28 의 롤아웃 실패 진단(show_startup_error) — 자동 복귀 전에 실패한 파드의 증거를 남긴다.
 *
 * <p>🐛 예전: 가장 새 파드를 골라, 그것이 이미지를 받는 중인 Pending 파드면 로그가 없어 아무것도 찍히지 않았다.
 * 자동 복귀가 CrashLoop 파드를 지워 손으로도 볼 수 없었고(2026-10-17 데모 saga-projection — 재시작 6번 · Pending 3개),
 * OOMKilled 는 로그에 원인이 없어 보이지 않았다.
 *
 * <p>[JA] ops/28 のロールアウト失敗の診断。以前は最新の Pod(Pending ならログなし)を選び、自動復帰で証拠が消えた。
 */
@DisplayName("ops/28 — 롤아웃 실패 파드의 증거")
class DeployStartupErrorScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bashAvailable() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    /**
     * 가짜 kubectl — 오래된 정상 파드 · CrashLoop 파드(재시작 6, OOMKilled) · 가장 새 Pending 파드(컨테이너 없음).
     * 생성 순서로 정렬된 목록에서 마지막이 Pending 이다.
     */
    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            case "$a" in
              *"get pods -l app=saga-projection"*)
                printf '0 saga-old\\n6 saga-crash\\n saga-pending\\n' ;;
              *"get pod saga-crash -o jsonpath"*)
                printf 'worker-node-2|OOMKilled|137' ;;
              *"get pod "*"-o jsonpath"*)
                printf '||' ;;
              *"describe pod saga-crash"*)
                echo "Name: saga-crash" ;;
              *"logs saga-crash --tail="*"--previous"*)
                printf 'INFO starting\\nERROR boom in streams\\nCaused by: java.lang.IllegalStateException: x\\n' ;;
              *"logs saga-crash --tail="*)
                echo "INFO current attempt" ;;
              *"get events --field-selector involvedObject.name=saga-crash"*)
                echo "Warning BackOff saga-crash" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    /**
     * Spring 전에 JVM 이 죽은 파드 — 로그는 "Error: Invalid or corrupt jarfile" 한 줄(2026-10-18 데모: 레지스트리 이미지는
     * 멀쩡하고 agent2 가 풀어 둔 사본만 깨져 있었다).
     */
    private static final String FAKE_KUBECTL_BAD_JAR = """
            #!/usr/bin/env bash
            a="$*"
            case "$a" in
              *"get pods -l app=saga-projection"*)
                printf '0 saga-old\\n7 saga-bad\\n' ;;
              *"get pod saga-bad -o jsonpath"*)
                printf 'agent2|Error|1|registry.local:5000/ecommerce-msa/saga-projection:2ec90f02a254' ;;
              *"describe pod saga-bad"*)
                echo "Name: saga-bad" ;;
              *"logs saga-bad --tail="*"--previous"*)
                echo "Error: Invalid or corrupt jarfile /app/app.jar" ;;
              *"logs saga-bad --tail="*)
                echo "Error: Invalid or corrupt jarfile /app/app.jar" ;;
              *"get events"*)
                echo "Warning BackOff saga-bad" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    private record Run(int code, String out) {}

    private Run run(Path failedDir) throws Exception {
        return run(failedDir, FAKE_KUBECTL);
    }

    private Run run(Path failedDir, String fake) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, fake);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        String body = "set -uo pipefail; NS=ecommerce; "
                + "eval \"$(sed -n '/^show_startup_error() {/,/^}/p' ops/28-deploy.sh)\"; "
                + "show_startup_error saga-projection";
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAILED_POD_DIR", failedDir.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    @Test
    @DisplayName("🔴 가장 새 Pending 파드가 아니라 재시작이 가장 많은 파드 — 노드 · 직전 종료 이유(OOMKilled) · 기동 오류 줄")
    void picksTheCrashingPodAndShowsWhyItDied() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(tmp.resolve("failed"));
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).as(r.out())
                .contains("파드 saga-crash")
                .doesNotContain("saga-pending")
                .contains("노드 worker-node-2")
                .contains("OOMKilled (exit 137)")
                .contains("메모리 한도 초과")
                .contains("ERROR boom in streams")
                .contains("Caused by: java.lang.IllegalStateException: x")
                .doesNotContain("jar 를 열지 못했습니다")
                .doesNotContain("unexpected:");
    }

    @Test
    @DisplayName("🔴 'Invalid or corrupt jarfile' — 그 줄을 보이고, 그 노드의 이미지 사본을 지우는 길을 안내한다")
    void corruptJarPointsAtTheNodeCopy() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(tmp.resolve("failed"), FAKE_KUBECTL_BAD_JAR);
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).as(r.out())
                .contains("Error: Invalid or corrupt jarfile /app/app.jar")
                .contains("노드 agent2 의 사본")
                .contains("sudo k3s crictl rmi registry.local:5000/ecommerce-msa/saga-projection:2ec90f02a254")
                .contains("--name saga-projection")
                .contains("ecommerce-msa/saga-projection:2ec90f02a254 /app/app.jar")
                .contains("kubectl -n ecommerce delete pod saga-bad")
                .doesNotContain("unexpected:");
    }

    @Test
    @DisplayName("🔴 describe · 로그(직전 시도) · 이벤트를 파일로 남긴다 — 디렉터리는 본인만(700)")
    void savesTheEvidenceBeforeRollbackDeletesThePod() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Path failed = tmp.resolve("failed");
        Run r = run(failed);
        assertThat(r.code()).as(r.out()).isZero();

        List<Path> dirs;
        try (Stream<Path> s = Files.list(failed)) {
            dirs = s.toList();
        }
        assertThat(dirs).as(r.out()).hasSize(1);
        Path dir = dirs.get(0);
        assertThat(dir.getFileName().toString()).endsWith("-saga-crash");
        assertThat(Files.getPosixFilePermissions(dir))
                .as("로그에는 사용자 id · 내부 주소가 있다 — 남이 읽지 못하게")
                .doesNotContain(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        assertThat(Files.readString(dir.resolve("previous.log"))).contains("ERROR boom in streams");
        assertThat(Files.readString(dir.resolve("current.log"))).contains("INFO current attempt");
        assertThat(Files.readString(dir.resolve("describe.txt"))).contains("Name: saga-crash");
        assertThat(Files.readString(dir.resolve("events.txt"))).contains("BackOff");
        assertThat(r.out()).contains("남김:  " + dir);
    }
}
