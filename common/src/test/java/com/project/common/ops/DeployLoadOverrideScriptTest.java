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
 * ops/28 의 측정용 예외 점검(check_load_overrides) — ops/53 이 `kubectl set env` 로 바꾼 값은 apply 가 지우지 않는다.
 *
 * <p>🐛 예전: 유입 제한(버스트 4000)이 남은 채 배포하면 스모크 3번(동시 70발 → 429)이 통과할 수 없어, 멀쩡한 배포가
 * 실패로 판정되고 자동 복귀했다(2026-10-18 데모 a84e4acba5ff). 이제는 적용하기 전에 멈추고 되돌리는 명령을 보인다.
 *
 * <p>[JA] ops/53 の計測用の上書きは apply で消えない。流入制限が残ったままの配備はスモークで失敗し自動復帰していた — 適用前に止める。
 */
@DisplayName("ops/28 — 측정용 예외가 남은 채 배포하지 않는다")
class DeployLoadOverrideScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean bashAvailable() {
        return Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash"));
    }

    /** 가짜 kubectl — 게이트웨이 · catalog 의 지금 env 이름(GATEWAY_ENV · CATALOG_ENV, 줄바꿈 구분)을 돌려준다. */
    private static final String FAKE_KUBECTL = """
            #!/usr/bin/env bash
            a="$*"
            case "$a" in
              *"get deploy gateway-server -o jsonpath"*) printf '%b' "$GATEWAY_ENV" ;;
              *"get deploy catalog-service -o jsonpath"*) printf '%b' "$CATALOG_ENV" ;;
              *) echo "unexpected: $a" >&2; exit 1 ;;
            esac
            """;

    private record Run(int code, String out) {}

    private Run run(String gatewayEnv, String catalogEnv, String rendered, boolean noSmoke) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, FAKE_KUBECTL);
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwx------"));
        // 명령줄 인자는 ASCII 로 — JDK 21 · POSIX 로캘에서는 인자의 한글이 ?? 로 깨진다(jdk21 브랜치에서 실측).
        String body = "set -uo pipefail; NS=ecommerce; MANAGED_DEPLOYS='catalog-service gateway-server'; "
                + "eval \"$(sed -n '/^warn() {/p; /^die()  {/p' ops/28-deploy.sh)\"; "
                + "eval \"$(sed -n '/^check_load_overrides() {/,/^}/p' ops/28-deploy.sh)\"; "
                + "check_load_overrides; echo \"continue rc=$?\"";
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", body).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("GATEWAY_ENV", gatewayEnv);
        pb.environment().put("CATALOG_ENV", catalogEnv);
        pb.environment().put("RENDERED", rendered);
        pb.environment().put("NO_SMOKE", noSmoke ? "1" : "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private static final String RENDERED = """
            kind: Deployment
            metadata:
              name: gateway-server
            spec:
              template:
                spec:
                  containers:
                    - env:
                        - name: SPRING_PROFILES_ACTIVE
                          value: local
            """;

    @Test
    @DisplayName("🔴 ops/53 limits load 의 유입 제한이 남아 있으면 적용 전에 멈추고 limits default 를 안내한다")
    void raisedRateLimitStopsBeforeApply() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run r = run("SPRING_PROFILES_ACTIVE\\nGATEWAY_RATE_LIMIT_REPLENISH\\nGATEWAY_RATE_LIMIT_BURST\\n",
                "SPRING_PROFILES_ACTIVE\\n", RENDERED, false);
        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).as(r.out())
                .contains("gateway-server/GATEWAY_RATE_LIMIT_REPLENISH")
                .contains("gateway-server/GATEWAY_RATE_LIMIT_BURST")
                .contains("./ops/53-load-k6.sh limits default")
                .doesNotContain("continue rc=")
                .doesNotContain("unexpected:");
    }

    @Test
    @DisplayName("DB 풀 · NO_SMOKE · 매니페스트가 선언한 값은 멈추지 않는다 — 풀은 되돌리는 명령만 알린다")
    void poolWarnsAndDeclaredOrNoSmokePasses() throws Exception {
        assumeTrue(bashAvailable(), "bash 가 없다");
        Run pool = run("SPRING_PROFILES_ACTIVE\\n", "DB_POOL_SIZE\\n", RENDERED, false);
        assertThat(pool.out()).as(pool.out())
                .contains("pool default catalog-service")
                .contains("continue rc=0")
                .doesNotContain("unexpected:");

        Run declared = run("GATEWAY_RATE_LIMIT_BURST\\n", "", RENDERED + "            - name: GATEWAY_RATE_LIMIT_BURST\n", false);
        assertThat(declared.out()).as(declared.out()).contains("continue rc=0").doesNotContain("남아 있습니다");

        Run noSmoke = run("GATEWAY_RATE_LIMIT_BURST\\n", "", RENDERED, true);
        assertThat(noSmoke.out()).as(noSmoke.out()).contains("NO_SMOKE=1").contains("continue rc=0");
    }

    @Test
    @DisplayName("배포 본체가 점검을 부른다 — --config-only 가 아닐 때, 적용 전에")
    void deployCallsTheCheckBeforeApplying() throws Exception {
        String s = Files.readString(repoRoot().resolve("ops/28-deploy.sh"));
        int call = s.indexOf("[ \"$CONFIG_ONLY\" = \"1\" ] || check_load_overrides");
        assertThat(call).isPositive();
        assertThat(call).isLessThan(s.indexOf("if deploy_version \"$VERSION\" && run_smoke; then"));
    }
}
