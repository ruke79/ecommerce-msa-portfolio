package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loki 로그 경보 규칙이 <b>운영과 같은 Loki 판(3.4.2)에서 모두 읽히는가</b> — 파일의 그룹 이름 = ruler 가 올린 그룹 이름.
 *
 * <p>🐛 2026-11-21 확인: 규칙 파일에 LogQL 문법 오류가 <b>하나라도</b> 있으면 Loki 는 그 파일을 통째로 버린다 — ruler API 는
 * {@code groups: []} 로 성공을 돌려주고, 로그에 'error parsing … ecommerce-log-alerts.yml' 한 줄만 남는다. 새 경보(ErrorLogSurge)를
 * 더하다 괄호 하나를 틀리면 critical 경보 {@code ManualActionRequired} 까지 조용히 꺼진다. promtool 은 LogQL 을 읽지 못해 이 파일을
 * 시험할 수 없었다.
 *
 * <p>Docker Hub 가 막히면 {@code LOKI_IMAGE=mirror.gcr.io/grafana/loki:3.4.2}(같은 이미지).
 *
 * <p>[JA] Loki のログ警報ルールが本番と同じ版(3.4.2)ですべて読み込まれるか — ファイルのグループ名 = ruler が載せたグループ名。
 *      ルールファイルに LogQL の文法エラーが一つでもあると Loki はそのファイルを丸ごと捨てる(API は groups: [] で成功を返す)。
 *      新しい警報を足して括弧を一つ間違えると critical の ManualActionRequired まで黙って消える。
 */
@Tag("containers")
@DisplayName("Loki 로그 경보 규칙 — 운영과 같은 Loki 에서 모든 그룹이 읽힌다")
class LokiRulesLoadTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    @Test
    @DisplayName("🔴 규칙 파일의 그룹 이름 = Loki ruler 가 올린 그룹 이름(하나라도 틀리면 파일 전체가 빠진다)")
    void allRuleGroupsLoad() throws Exception {
        Path obs = repoRoot().resolve("observability");
        TreeSet<String> expected = new TreeSet<>();
        try (var files = Files.list(obs.resolve("loki-rules"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                Matcher m = Pattern.compile("(?m)^\\s*-\\s*name:\\s*(\\S+)\\s*$").matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    expected.add(m.group(1));
                }
            }
        }
        assertThat(expected).as("규칙 파일에서 그룹을 찾지 못했다 — 파일 모양이 바뀌었으면 이 시험을 고친다")
                .contains("manual-action", "error-rate");

        String image = System.getenv().getOrDefault("LOKI_IMAGE", "grafana/loki:3.4.2");
        try (GenericContainer<?> loki = new GenericContainer<>(DockerImageName.parse(image).asCompatibleSubstituteFor("grafana/loki"))
                .withFileSystemBind(obs.resolve("loki-config.yml").toString(), "/etc/loki/config.yml", BindMode.READ_ONLY)
                .withFileSystemBind(obs.resolve("loki-rules").toString(), "/etc/loki/rules/fake", BindMode.READ_ONLY)
                .withCommand("-config.file=/etc/loki/config.yml")
                .withExposedPorts(3100)
                .waitingFor(Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))) {
            loki.start();
            URI rules = URI.create("http://" + loki.getHost() + ":" + loki.getMappedPort(3100) + "/prometheus/api/v1/rules");
            HttpClient http = HttpClient.newHttpClient();
            TreeSet<String> loaded = new TreeSet<>();
            // ruler 는 기동 직후 한 번 읽는다 — 잠깐 비어 있을 수 있어 몇 번 본다.
            // [JA] ruler は起動直後に一度読む — 少しの間空のことがあるので何度か見る。
            for (int i = 0; i < 30 && !loaded.equals(expected); i++) {
                loaded.clear();
                String body = http.send(HttpRequest.newBuilder(rules).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                JsonNode groups = new ObjectMapper().readTree(body).path("data").path("groups");
                List<String> names = new ArrayList<>();
                groups.forEach(g -> names.add(g.path("name").asText()));
                loaded.addAll(names);
                if (!loaded.equals(expected)) {
                    Thread.sleep(1000);
                }
            }
            assertThat(loaded).as("Loki 가 올리지 못한 그룹이 있다 — 파일 하나의 문법 오류로 그 파일 전체가 빠진다. 로그: "
                    + loki.getLogs().lines().filter(l -> l.contains("error parsing")).findFirst().orElse("(없음)"))
                    .isEqualTo(expected);
        }
    }
}
