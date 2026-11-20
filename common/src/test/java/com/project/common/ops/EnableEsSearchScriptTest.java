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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 검색 ES 전환(ops/03-enable-es-search.sh) — 재색인을 **게이트웨이 경유 · 관리자 토큰**으로 부르고, ConfigMap 을
 * patch 하지 않는다.
 *
 * <p>🐛 2026-11-20: 이 스크립트는 catalog 에 직접 `POST /api/products/sync` 했다. catalog 의
 * `AdminWriteInterceptor` 는 모든 쓰기에 `User-Role: ROLE_ADMIN` 을 요구하고, 그 헤더는
 * `internal-auth.identity-mode=enforce`(2026-10-26 부터 기본) 아래서 게이트웨이가 mTLS 로 보낸 것만 신뢰된다 —
 * 직접 호출은 401 이고, 스크립트는 그 뒤 alias 를 5분 기다리다 실패했다. 또 마지막 단계가
 * `kubectl patch configmap` 을 돌렸다 — 이 저장소의 규칙이 금지하는 것(ConfigMap 의 원본은 저장소,
 * `ops/28 --config-only` 로만 올린다)이고, 다음 배포가 조용히 되돌린다.
 *
 * <p>지키는 것: ① 주소 · 토큰이 없으면 **아무 요청도 하기 전에** 멈춘다 ② 재색인은 게이트웨이 경로로 · 토큰은
 * Authorization 헤더로(URL 에 싣지 않는다) ③ 어디서도 kubectl 을 부르지 않는다 ④ alias · 개수 · 애널라이저를 ES 에 묻는다.
 *
 * <p>[JA] 再索引はゲートウェイ経由で管理者トークンで呼び、ConfigMap を patch しない。
 */
@DisplayName("ops/03 — 검색 ES 전환(게이트웨이 경유 · ConfigMap patch 금지)")
class EnableEsSearchScriptTest {

    @TempDir
    Path tmp;

    private record Run(int code, String out) {
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.isExecutable(Path.of("/bin/bash"));
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 가짜 curl(요청을 적고 정해진 답을 낸다) · 가짜 kubectl(불리면 그 사실을 적는다 — 불려선 안 된다). */
    // [JA] 偽の curl(リクエストを記録し決まった答えを返す)· 偽の kubectl(呼ばれたらその事実を記録する — 呼ばれてはならない)。
    private Run run(Map<String, String> env) throws Exception {
        assumeTrue(posix(), "bash · POSIX 가 없다");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        // MODE: ready(기본) = alias 가 바로 있다 · slow = alias 는 없고 문서 수가 는다 · dead = 둘 다 없다
        // [JA] MODE: ready(既定)= alias がすぐある · slow = alias はなく文書数が増える · dead = どちらもない
        exe(bin.resolve("curl"), """
                echo "curl $*" >> "$FAKE/curl.log"
                case "$*" in
                  *_cat/indices*)
                      case "${MODE:-ready}" in
                        slow) n=$(cat "$FAKE/n" 2>/dev/null || echo 1000); echo $((n + 500)) > "$FAKE/n"; echo "$((n + 500))" ;;
                        dead) echo "" ;;
                        *)    echo "1234" ;;
                      esac ;;
                  *_alias/product-alias*)
                      case "${MODE:-ready}" in
                        slow|dead) exit 22 ;;
                        *) echo '{"products-1":{"aliases":{"product-alias":{}}}}' ;;
                      esac ;;
                  *_count*)   echo '{"count":1234}' ;;
                  *_analyze*) echo '{"tokens":[{"token":"운동"},{"token":"화"}]}' ;;
                  *products/sync*) echo 'Batch synchronization job started: products-1' ;;
                  *) echo '{}' ;;
                esac
                """);
        exe(bin.resolve("kubectl"), """
                echo "kubectl $*" >> "$FAKE/kubectl.log"
                exit 0
                """);

        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/03-enable-es-search.sh").toString())
                .directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private String curlLog() throws IOException {
        Path f = tmp.resolve("curl.log");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    private static Map<String, String> ok() {
        Map<String, String> e = new HashMap<>();
        e.put("GATEWAY", "https://shop.lab");
        e.put("ADMIN_TOKEN", "tok-abc123");
        e.put("ES_URL", "http://es.example:9200");
        e.put("INSECURE", "1");
        return e;
    }

    @Test
    @DisplayName("🔴 주소 · 관리자 토큰이 없으면 아무 요청도 하기 전에 멈춘다 — 없이 부르면 401 이고 alias 를 5분 기다린다")
    void refusesWithoutGatewayOrToken() throws Exception {
        Run noGateway = run(Map.of("ADMIN_TOKEN", "tok"));
        assertThat(noGateway.code()).as(noGateway.out()).isEqualTo(2);
        assertThat(noGateway.out()).contains("GATEWAY");
        assertThat(curlLog()).as("멈추기 전에 요청하지 않는다").isEmpty();

        Files.deleteIfExists(tmp.resolve("curl.log"));
        Run noToken = run(Map.of("GATEWAY", "https://shop.lab"));
        assertThat(noToken.code()).as(noToken.out()).isEqualTo(2);
        assertThat(noToken.out()).contains("ADMIN_TOKEN", "관리자");
        assertThat(curlLog()).isEmpty();
    }

    @Test
    @DisplayName("🔴 재색인은 게이트웨이 경로로 · 토큰은 Authorization 헤더로(URL 에 싣지 않는다)")
    void reindexGoesThroughTheGatewayWithABearerToken() throws Exception {
        Run r = run(ok());
        assertThat(r.code()).as(r.out()).isZero();

        String log = curlLog();
        assertThat(log).as("catalog 직접 호출은 enforce 아래서 401 이다")
                .contains("-X POST")
                .contains("Authorization: Bearer tok-abc123")
                .contains("https://shop.lab/catalog-service/api/products/sync");
        List<String> sync = log.lines().filter(l -> l.contains("products/sync")).toList();
        assertThat(sync).hasSize(1);
        assertThat(sync.get(0)).as("토큰을 URL 의 쿼리에 싣지 않는다 — 접근 로그 · 중간 프록시에 남는다")
                .doesNotContain("token=", "?access", "sync?");
    }

    @Test
    @DisplayName("🔴 SYNC_URL 로 주소를 직접 줄 수 있다 — 그래도 토큰은 필요하다")
    void syncUrlCanBeGivenDirectly() throws Exception {
        Map<String, String> e = new HashMap<>();
        e.put("SYNC_URL", "https://edge.example/catalog-service/api/products/sync");
        e.put("ADMIN_TOKEN", "tok-xyz");
        e.put("ES_URL", "http://es.example:9200");
        Run r = run(e);
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(curlLog()).contains("https://edge.example/catalog-service/api/products/sync",
                "Authorization: Bearer tok-xyz");
    }

    @Test
    @DisplayName("🔴 어디서도 kubectl 을 부르지 않는다 — ConfigMap 의 원본은 저장소(ops/28 --config-only), patch 금지")
    void neverPatchesTheConfigMap() throws Exception {
        Run r = run(ok());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(Files.exists(tmp.resolve("kubectl.log")))
                .as("kubectl 이 불렸다 (ConfigMap 은 ops/28 --config-only 로만)")
                .isFalse();
        assertThat(r.out()).as("대신 두 단계를 안내한다")
                .contains("ops/28-deploy.sh", "--config-only", "SEARCH_ENGINE", "catalog.search-engine");
        // 소스에도 patch 명령이 남아 있지 않다(주석 · 안내 문구는 제외).
        // [JA] ソースにも patch コマンドが残っていない(コメント · 案内文言は除く)。
        String script = Files.readString(repoRoot().resolve("ops/03-enable-es-search.sh"), StandardCharsets.UTF_8);
        List<String> offenders = script.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .filter(l -> l.matches(".*\\brun\\s+kubectl\\b.*") || l.matches("^\\s*kubectl\\s+.*patch.*"))
                .toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("🔴 시간이 다 됐을 때 — 문서 수가 늘고 있었으면 '느린 것', 늘지 않았으면 '죽은 것' 으로 갈라 말한다")
    void distinguishesSlowFromDeadOnTimeout() throws Exception {
        // 🐛 예전에는 둘 다 "alias 미생성 — 배치 잡 로그를 확인하세요" 였다. 실습 1,000만 상품에서 색인은
        //    초당 5.7만 건으로 멀쩡히 돌고 있었는데 그 메시지로는 알 수 없었다.
        // [JA] 🐛 以前はどちらも "alias 미생성 — 배치 잡 로그를 확인하세요"(alias 未作成 — バッチジョブのログを確認せよ)だった。実習の 1,000 万商品で
        //      インデックス作成は毎秒 5.7 万件で正常に動いていたのに、そのメッセージでは分からなかった。
        Map<String, String> slow = new HashMap<>(ok());
        slow.put("MODE", "slow");
        slow.put("TIMEOUT", "12");
        Run r = run(slow);
        assertThat(r.code()).as(r.out()).isEqualTo(1);
        assertThat(r.out()).contains("색인은 **돌고 있다**", "TIMEOUT", "409")
                .doesNotContain("배치 잡이 죽었습니다");
        assertThat(curlLog()).as("기다리는 동안 진행(문서 수)을 묻는다").contains("_cat/indices/products-*-products");

        Files.deleteIfExists(tmp.resolve("curl.log"));
        Files.deleteIfExists(tmp.resolve("n"));
        Map<String, String> dead = new HashMap<>(ok());
        dead.put("MODE", "dead");
        dead.put("TIMEOUT", "12");
        Run d = run(dead);
        assertThat(d.code()).as(d.out()).isEqualTo(1);
        assertThat(d.out()).contains("배치 잡이 죽었습니다", "logs deploy/catalog-service")
                .doesNotContain("돌고 있다");
    }

    @Test
    @DisplayName("🔴 기본 대기는 1,000만 상품이 끝날 만큼 — 300초로는 실습에서 끝나지 않았다")
    void defaultTimeoutFitsAFullReindex() throws Exception {
        String script = Files.readString(repoRoot().resolve("ops/03-enable-es-search.sh"), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("TIMEOUT=\"?\\$\\{TIMEOUT:-(\\d+)\\}").matcher(script);
        assertThat(m.find()).as("TIMEOUT 기본값을 찾지 못했다").isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("1,000만 상품이 약 3분 — 병합 · 느린 디스크를 보면 300초는 모자란다")
                .isGreaterThanOrEqualTo(900);
    }

    @Test
    @DisplayName("🔴 ES 에 alias · 문서 수 · 애널라이저를 묻는다 — 색인이 비면 사람이 바로 알아야 한다")
    void checksAliasCountAndAnalyzer() throws Exception {
        Run r = run(ok());
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(curlLog()).contains("http://es.example:9200/_alias/product-alias",
                "http://es.example:9200/product-alias/_count",
                "http://es.example:9200/product-alias/_analyze");
        assertThat(r.out()).contains("1234");
    }
}
