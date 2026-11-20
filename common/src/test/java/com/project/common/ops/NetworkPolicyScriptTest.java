package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/35 의 들어오는 정책(k8s/95) 적용 — 관리 포트(9180, B-119)까지 두드리고, 지표 에이전트가 막히면 되돌린다. 가짜 kubectl 로.
 *
 * <p>가짜 kubectl: 두드리기 파드(`run &lt;라벨&gt;-N … --labels=app=&lt;라벨&gt; … sh -c "&lt;스크립트&gt;"`)는 대상마다 OK 를 내되,
 * 적용 뒤면 FAIL_AFTER 의 "네임스페이스/라벨:대상" 은 FAIL. apply 는 표시를 남기고 delete 는 기록한다.
 *
 * <p>[JA] ops/35 の入ってくるポリシーの適用 — 管理ポートまで確認し、指標エージェントが塞がれたら戻す。偽の kubectl で。
 */
@DisplayName("ops/35 apply — 관리 포트(9180)는 지표 에이전트만, 막히면 되돌린다")
class NetworkPolicyScriptTest {

    @TempDir
    Path tmp;

    private Path state;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            case "$a" in
              *" run "*|run\\ *)
                ns=$(sed -E 's/.*-n ([a-z]+) run.*/\\1/' <<<"$a")
                label=$(sed -E 's/.*--labels=app=([a-z-]+).*/\\1/' <<<"$a")
                if [ -f "$STATE/applied" ]; then fails="$FAIL_AFTER"; else fails="$FAIL_BEFORE"; fi
                grep -oE "echo '[^ ']+ OK'" <<<"$a" | sed -E "s/echo '([^ ]+) OK'/\\1/" | while read -r t; do
                  if [[ " $fails " == *" $ns/$label:$t "* ]]; then echo "$t FAIL"; else echo "$t OK"; fi
                done ;;
              *"apply -f k8s/95"*) touch "$STATE/applied"; echo applied ;;
              *"delete -f k8s/95"*) echo "$a" >> "$STATE/deleted"; rm -f "$STATE/applied" ;;
              *"get pods -l app=user-service"*) echo "10.42.0.9 http management" ;;
              *"get pods --no-headers"*) echo "user-service-1 1/1 Running 0 1m" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    /** 정책이 집행될 때 막히는 것 — 다른 네임스페이스에서 앱 · DB, 같은 네임스페이스의 보통 파드에서 관리 포트. */
    // [JA] 方針が施行されたときに遮断されるもの — 別のネームスペースからアプリ · DB、同じネームスペースの普通のポッドから管理ポート。
    private static final String ENFORCED = "default/netpol-probe:user-service.ecommerce.svc.cluster.local:8080 "
            + "default/netpol-probe:order-service.ecommerce.svc.cluster.local:8084 "
            + "default/netpol-probe:postgres.postgres.svc.cluster.local:5432 "
            + "default/netpol-probe:redis.redis.svc.cluster.local:6379 "
            + "ecommerce/netpol-probe:10.42.0.9:9180";

    private record Run(int code, String out) {}

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")));
        state = Files.createDirectories(tmp.resolve("state"));
    }

    private Run run(String failAfter) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, FAKE_KUBECTL);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder pb = new ProcessBuilder("bash", "ops/35-network-policy.sh", "apply")
                .directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("STATE", state.toString());
        pb.environment().put("FAIL_BEFORE", "");
        pb.environment().put("FAIL_AFTER", failAfter);
        pb.environment().put("NETPOL_SETTLE", "0");
        pb.environment().put("PROBE_WARMUP", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    @Test
    @DisplayName("🔴 집행되면 남긴다 — 지표 에이전트는 관리 포트에 닿고, 같은 네임스페이스의 다른 파드는 막힌다")
    void keepsPolicyWhenOnlyTheAgentReachesTheManagementPort() throws Exception {
        Run r = run(ENFORCED);

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("✅ metrics-agent → 관리 포트", "✅ 다른 파드(ecommerce) → 관리 포트 — 막혔다", "✅ 적용 완료");
        assertThat(state.resolve("deleted")).doesNotExist();
    }

    @Test
    @DisplayName("🔴 지표 에이전트가 관리 포트에서 막히면 되돌린다 — 지표가 끊긴다")
    void rollsBackWhenTheAgentIsBlocked() throws Exception {
        Run r = run(ENFORCED + " ecommerce/netpol-probe-metrics:10.42.0.9:9180");

        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("🔴 metrics-agent → 관리 포트 — 막혔다", "되돌린다");
        assertThat(Files.readString(state.resolve("deleted"))).contains("delete -f k8s/95");
    }

    @Test
    @DisplayName("관리 포트가 다른 파드에 열려 있으면 집행되지 않은 것으로 알린다(종료 2 — 정책은 남긴다)")
    void warnsWhenTheManagementPortStaysOpen() throws Exception {
        Run r = run(ENFORCED.replace("ecommerce/netpol-probe:10.42.0.9:9180", ""));

        assertThat(r.code()).as(r.out()).isEqualTo(2);
        assertThat(r.out()).contains("⚠️  다른 파드(ecommerce) → 관리 포트 — 열려 있다");
        assertThat(state.resolve("deleted")).doesNotExist();
    }
}
