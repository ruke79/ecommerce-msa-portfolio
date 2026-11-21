package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/28 의 지표 에이전트 점검(warn_metrics_agent) — <b>옛 설정을 쥔 에이전트 파드를 배포 뒤에 다시 띄우는가.</b>
 *
 * <p>🐛 2026-11-20 운영(d8922fc53e0c): ConfigMap 은 이미 새 규칙(관리 포트 9180 만 긁기, B-119)이라 아무 경고도 없었는데 서비스 지표가
 * 21분 끊겼다. 에이전트는 기동할 때 initContainer 가 ConfigMap 을 설정 파일로 만들고 그 뒤로는 다시 읽지 않는다 — 파드는 옛 규칙
 * (앱 포트를 긁어 404)을 쥐고 있었다. 이제 파드가 뜬 시각이 ConfigMap 의 마지막 변경보다 이르면 재시작한다.
 *
 * <p>[JA] ops/28 の指標エージェント点検 — 古い設定を握ったエージェント Pod をデプロイ後に起動し直すか。2026-11-20 本番では ConfigMap は
 * すでに新しい規則で警告が出なかったのに、サービス指標が 21 分途切れた。エージェントは起動時にしか ConfigMap を読まない。今は Pod の
 * 起動時刻が ConfigMap の最終更新より早ければ再起動する。
 */
@DisplayName("ops/28 — 옛 설정을 쥔 지표 에이전트를 배포 뒤 재시작한다")
class MetricsAgentStaleScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bashAvailable() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    /** 가짜 kubectl — ConfigMap 유무 · 규칙 · 변경 시각, 에이전트 파드의 뜬 시각, 재시작 호출을 기록한다. */
    // [JA] 偽 kubectl — ConfigMap の有無 · ルール · 変更時刻、エージェント Pod の起動時刻、再起動の呼び出しを記録する。
    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            echo "$a" >> "$CALLS"
            case "$a" in
              *"get configmap metrics-agent-config -o yaml"*) printf '%s\\n' "$CM_YAML" ;;
              *"get configmap metrics-agent-config -o jsonpath"*) printf '%b' "$CM_TIMES" ;;
              *"get configmap metrics-agent-config"*) [ "$CM_EXISTS" = 1 ] ;;
              *"get pods -l app=metrics-agent"*) printf '%b' "$PODS" ;;
              *"rollout restart deploy/metrics-agent"*) exit 0 ;;
              *"rollout status deploy/metrics-agent"*) exit 0 ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    private static final String NEW_RULE = "  prometheus.yml.tmpl: |\n    - source_labels: [__meta_kubernetes_pod_container_port_name]";
    private static final String OLD_RULE = "  prometheus.yml.tmpl: |\n    - source_labels: [__meta_kubernetes_pod_container_port_number]";

    private record Run(String out, String calls) {}

    private Run run(boolean cmExists, String yaml, String cmTimes, String pods, String restartFlag) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, FAKE_KUBECTL);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        Path calls = tmp.resolve("calls-" + System.nanoTime());
        Files.writeString(calls, "");
        String body = "set -uo pipefail; NS=ecommerce; "
                + "eval \"$(sed -n '/^info() {/p; /^warn() {/p' ops/28-deploy.sh)\"; "
                + "eval \"$(sed -n '/^warn_metrics_agent() {/,/^}/p' ops/28-deploy.sh)\"; "
                + "warn_metrics_agent; echo \"done rc=$?\"";
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("CALLS", calls.toString());
        pb.environment().put("CM_EXISTS", cmExists ? "1" : "0");
        pb.environment().put("CM_YAML", yaml);
        pb.environment().put("CM_TIMES", cmTimes);
        pb.environment().put("PODS", pods);
        if (restartFlag != null) {
            pb.environment().put("METRICS_AGENT_RESTART", restartFlag);
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(out, Files.readString(calls));
    }

    /** ConfigMap 이 두 번 바뀌었다 — 마지막은 16:10. ／ [JA] ConfigMap は二回変わった — 最後は 16:10。 */
    private static final String CM_TIMES = "2026-11-20T15:00:00Z\\n2026-11-20T16:10:00Z\\n";

    @Test
    @DisplayName("🔴 파드가 ConfigMap 의 마지막 변경보다 먼저 떴으면 재시작하고 확인할 지표를 알린다(2026-11-20 운영의 21분)")
    void staleAgentIsRestarted() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(true, NEW_RULE, CM_TIMES, "metrics-agent-7d9 2026-11-20T15:30:00Z\\n", null);
        assertThat(r.out()).as(r.out()).contains("metrics-agent-7d9").contains("done rc=0").doesNotContain("unexpected:");
        assertThat(r.calls()).as(r.calls())
                .contains("rollout restart deploy/metrics-agent")
                .contains("rollout status deploy/metrics-agent");
    }

    @Test
    @DisplayName("ConfigMap 이 바뀐 뒤에 뜬 파드는 그대로 둔다")
    void freshAgentIsLeftAlone() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(true, NEW_RULE, CM_TIMES, "metrics-agent-7d9 2026-11-20T16:10:05Z\\n", null);
        assertThat(r.out()).as(r.out()).contains("done rc=0").doesNotContain("metrics-agent-7d9").doesNotContain("unexpected:");
        assertThat(r.calls()).doesNotContain("rollout restart");
    }

    @Test
    @DisplayName("METRICS_AGENT_RESTART=0 이면 재시작하지 않고 명령만 알린다")
    void optOutOnlyWarns() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(true, NEW_RULE, CM_TIMES, "metrics-agent-7d9 2026-11-20T15:30:00Z\\n", "0");
        assertThat(r.out()).as(r.out()).contains("METRICS_AGENT_RESTART=0").contains("rollout restart deploy/metrics-agent");
        assertThat(r.calls()).doesNotContain("rollout restart");
    }

    @Test
    @DisplayName("ConfigMap 이 옛 규칙이면 다시 적용하라고 알린다 — 재시작만으로는 옛 규칙 그대로라 재시작하지 않는다")
    void oldRuleAsksToReapply() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(true, OLD_RULE, CM_TIMES, "metrics-agent-7d9 2026-11-20T15:30:00Z\\n", null);
        assertThat(r.out()).as(r.out()).contains("kubectl apply -f k8s-overlays/external/metrics-agent.yaml");
        assertThat(r.calls()).doesNotContain("rollout restart");
    }

    @Test
    @DisplayName("에이전트가 없는 클러스터(개발 · 데모 밖)는 아무것도 하지 않는다")
    void noAgentNoop() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run(false, "", "", "", null);
        assertThat(r.out()).as(r.out()).contains("done rc=0").doesNotContain("!");
        assertThat(r.calls()).doesNotContain("rollout");
    }
}
