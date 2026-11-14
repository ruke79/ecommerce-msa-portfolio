package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * 경보 규칙을 운영과 같은 Prometheus 판(v2.53.0)의 promtool 로 — 문법 검사 + 단위 시험(observability/tests/slo-rules.test.yml):
 * SLO 소진율 경보가 5% 오류 · 50% 느림에서 울리고, 예산 안 · 요청이 적을 때 · 관리자 경로에서는 울리지 않는다. 컨슈머 지연도.
 *
 * <p>Docker Hub 가 막히면 {@code PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0}(같은 이미지).
 *
 * <p>[JA] 警報ルールを本番と同じ版の promtool で — 文法検査 + 単体テスト。
 */
@Tag("containers")
@DisplayName("SLO · 경보 규칙 — promtool check · test (docs/91)")
class SloRulesPromtoolTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String promtool(String... args) {
        String image = System.getenv().getOrDefault("PROMETHEUS_IMAGE", "prom/prometheus:v2.53.0");
        try (GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("prom/prometheus"))
                .withFileSystemBind(repoRoot().resolve("observability").toString(), "/o", BindMode.READ_ONLY)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("promtool"))
                .withCommand(args)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(5)))) {
            c.start();
            return c.getLogs();
        }
    }

    @Test
    @DisplayName("🔴 promtool check rules — 문법 · 템플릿이 맞다")
    void rulesAreValid() {
        assertThat(promtool("check", "rules", "/o/alert-rules.yml")).contains("SUCCESS");
    }

    @Test
    @DisplayName("🔴 promtool test rules — 소진율 · 컨슈머 지연 경보가 울릴 때만 울린다")
    void burnRateAlertsFireOnlyWhenTheyShould() {
        assertThat(promtool("test", "rules", "/o/tests/slo-rules.test.yml")).contains("SUCCESS").doesNotContain("FAILED");
    }

    @Test
    @DisplayName("🔴 promtool test rules — 14일 예산: 80% 면 절반 경보만 · 120% 면 소진 · 요청이 적으면 조용(10분 간격 15일)")
    void budgetAlertsFireOnlyWhenTheyShould() {
        assertThat(promtool("test", "rules", "/o/tests/slo-budget.test.yml")).contains("SUCCESS").doesNotContain("FAILED");
    }
}
