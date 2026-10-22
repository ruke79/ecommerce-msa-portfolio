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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * k6 부하 시나리오(ops/k6) · 실행기(ops/53-load-k6.sh) — docs/83.
 *
 * <p>둘을 지킨다. ① 시나리오가 부르는 주소는 <b>화면이 부르는 주소</b>다(frontend/src 에 같은 경로가 있고, 첫 조각은 게이트웨이 라우트) —
 * 화면이 주소를 바꿨는데 시나리오만 옛 주소를 치면 404 · 400 을 재게 된다. ② 실행기는 운영에 걸지 않고, 비밀번호를 명령줄에 올리지 않고,
 * 유입 제한을 운영 클러스터에서 바꾸지 않는다. k6 · kubectl 은 가짜 명령으로 바꿔 돌린다(bash · POSIX 가 없으면 건너뛴다).
 *
 * <p>[JA] k6 シナリオのアドレスは画面のアドレスと同じ。実行器は本番に掛けず、パスワードをコマンドラインに載せず、本番の流入制限を変えない。
 */
@DisplayName("ops/53 · ops/k6 — 부하 시나리오와 실행기 (docs/83)")
class LoadK6ScriptTest {

    @TempDir
    Path tmp;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── ① 시나리오의 주소 = 화면의 주소 ───────────────────────────────────

    /** 문자열 · 템플릿 리터럴 안의 API 경로(`${GATEWAY}/…` 포함) — 첫 `${` · `?` 앞까지. */
    private static final Pattern API_PATH = Pattern.compile("[\"'`}](/(?:[a-z]+-service|saga-projection)/[^\"'`?$]*)");

    private static TreeSet<String> paths(Stream<Path> files) {
        TreeSet<String> out = new TreeSet<>();
        files.forEach(f -> {
            Matcher m = API_PATH.matcher(read(f));
            while (m.find()) {
                out.add(m.group(1));
            }
        });
        return out;
    }

    private static TreeSet<String> k6Paths() throws IOException {
        try (Stream<Path> s = Files.list(repoRoot().resolve("ops/k6"))) {
            return paths(s.filter(p -> p.toString().endsWith(".js")).sorted());
        }
    }

    private static TreeSet<String> frontendPaths() throws IOException {
        try (Stream<Path> s = Files.walk(repoRoot().resolve("frontend/src"))) {
            return paths(s.filter(p -> p.toString().matches(".*\\.jsx?$") && !p.toString().matches(".*\\.test\\.jsx?$")));
        }
    }

    @Test
    @DisplayName("🔴 시나리오의 모든 API 경로를 화면도 부른다(frontend/src) — 첫 조각은 게이트웨이 라우트")
    void everyK6PathIsOneTheBrowserCalls() throws IOException {
        TreeSet<String> k6 = k6Paths();
        assertThat(k6).as("시나리오의 경로를 읽었다")
                .contains("/order-service/api/order/create", "/cart-service/api/cart/items", "/user-service/api/auth/public/signin");
        TreeSet<String> browser = frontendPaths();
        List<String> unknown = k6.stream().filter(p -> browser.stream().noneMatch(b -> b.equals(p) || b.startsWith(p) && p.endsWith("/")))
                .toList();
        assertThat(unknown).as("화면이 부르지 않는 경로 — 화면의 주소(util/api.js 등)로 맞춘다").isEmpty();

        TreeSet<String> prefixes = com.project.common.config.EdgeRoutingGuardTest.gatewayPrefixes();
        assertThat(k6).allSatisfy(p -> assertThat(prefixes).as(p).contains(p.replaceFirst("^(/[^/]+).*$", "$1")));
    }

    @Test
    @DisplayName("🔴 응답이 JSON 인지 늘 본다 · 시작 전에 HTML(프론트로 간 API)이면 멈춘다 — 상태 코드만 보면 index.html 200 이 성공이다")
    void scenariosRequireJson() throws IOException {
        String lib = read(repoRoot().resolve("ops/k6/lib.js"));
        assertThat(lib).contains("not_json").contains("application/json").contains("export function homeOrFail");
        for (String s : List.of("browse.js", "order.js", "spike.js")) {
            assertThat(read(repoRoot().resolve("ops/k6/" + s))).as(s).contains("not_json: ['count<1']");
        }
        assertThat(read(repoRoot().resolve("ops/k6/browse.js"))).contains("homeOrFail(");
        assertThat(read(repoRoot().resolve("ops/k6/order.js"))).contains("homeOrFail(")
                // 계정 하나는 VU 하나 — 리프레시 토큰이 회전한다
                .contains("maxVUs: USERS").contains("'Idempotency-Key': idempotencyKey()").contains("paymentMethod: 'cash'");
    }

    @Test
    @DisplayName("🔴 데이터 규모 모드의 id 범위 기본값 = ops/44 의 ID_OFFSET 기본값 · 이름 검색어는 생성기 상품 이름에 있다")
    void scaleModeMatchesTheCatalogGenerator() throws IOException {
        Matcher ops44 = Pattern.compile("ID_OFFSET=\"\\$\\{ID_OFFSET:-(\\d+)}\"").matcher(read(repoRoot().resolve("ops/44-bulk-catalog.sh")));
        assertThat(ops44.find()).as("ops/44 의 ID_OFFSET 기본값").isTrue();
        String browse = read(repoRoot().resolve("ops/k6/browse.js"));
        Matcher k6 = Pattern.compile("__ENV\\.PRODUCT_ID_OFFSET \\|\\| (\\d+)").matcher(browse);
        assertThat(k6.find()).as("browse.js 의 PRODUCT_ID_OFFSET 기본값").isTrue();
        assertThat(k6.group(1)).as("어긋나면 상세가 전부 없는 상품(400)").isEqualTo(ops44.group(1));

        Matcher terms = Pattern.compile("NAME_TERMS = \\[([^\\]]+)]", Pattern.DOTALL).matcher(browse);
        assertThat(terms.find()).isTrue();
        String generator = read(repoRoot().resolve("ops/tools/generate-catalog-data.py"));
        Matcher term = Pattern.compile("'([^']+)'").matcher(terms.group(1));
        int n = 0;
        while (term.find()) {
            n++;
            assertThat(generator).as("이름 검색어 %s — 생성기 TAXONOMY 의 명사에 없으면 결과 0건을 잰다", term.group(1))
                    .contains(term.group(1));
        }
        assertThat(n).isGreaterThan(5);
    }

    // ── ② 실행기 ──────────────────────────────────────────────────────────

    private record Run(int code, String out) {}

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && Files.isExecutable(Path.of("/bin/bash"));
    }

    private void exe(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 가짜 k6(인자 · 환경을 남기고 K6_EXIT 로 끝난다) · 가짜 kubectl(APP_PUBLIC_URL · 호출을 남긴다)로 ops/53 을 돌린다. */
    private Run run(Map<String, String> env, String... args) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        exe(bin.resolve("k6"), """
                printf '%s\\n' "$@" > "$FAKE/k6.args"
                { echo "DEMO_PASSWORD=${DEMO_PASSWORD:-}"; echo "SSL_CERT_FILE=${SSL_CERT_FILE:-}"; } > "$FAKE/k6.env"
                for a in "$@"; do case "$a" in */summary.json) : ;; esac; done
                echo "k6 fake run"
                exit "${K6_EXIT:-0}"
                """);
        exe(bin.resolve("kubectl"), """
                echo "kubectl $*" >> "$FAKE/kubectl.log"
                case "$*" in
                  *"get configmap ecommerce-config"*) printf '%s' "${FAKE_URL-https://shop.lab}" ;;
                  *"get deploy "*"-o jsonpath={.spec.replicas}"*) printf '2' ;;
                  *"requests.cpu}"*) printf '250m' ;;
                  *"requests.memory}"*) printf '512Mi' ;;
                  *"get nodes,pods -A -o json"*) printf '%s' "${FAKE_CLUSTER}" ;;
                  *"get hpa "*"{.spec.minReplicas} {.spec.maxReplicas}"*) printf '2 3' ;;
                  *"get deploy gateway-server"*) printf '%s' "${FAKE_ENV:-}" ;;
                  *"get hpa catalog-service"*) printf '3' ;;
                  *"get hpa gateway-server"*) echo ok ;;
                  *"show max_connections"*) printf '%s\n' "${FAKE_MAX-300}" ;;
                  *"pg_stat_activity"*) printf '%s\n' "${FAKE_USED:-120}" ;;
                  *"get deploy catalog-service -o jsonpath"*) printf '%s' "${FAKE_POOL:-}" ;;
                  *"get deploy catalog-service"*) echo ok ;;
                  *"get deploy"*) echo "" ;;
                  *"rollout status"*) echo "rolled out" ;;
                  *) : ;;
                esac
                """);
        Path pw = tmp.resolve("pw");
        Files.writeString(pw, "S3cret-demo-pw!9");
        Path ca = tmp.resolve("ca.crt");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n");
        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/53-load-k6.sh").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE", tmp.toString());
        pb.environment().put("PASSWORD_FILE", pw.toString());
        pb.environment().put("CA_FILE", ca.toString());
        pb.environment().put("OUT", tmp.resolve("results").toString());
        pb.environment().put("FAKE_CLUSTER", cluster(3000));
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://www.ysyproject.com", "https://ysyproject.com/", "HTTPS://WWW.YSYPROJECT.COM:443/x" })
    @DisplayName("🔴 운영 주소에는 걸지 않는다 — k6 를 부르기 전에 멈춘다")
    void refusesProduction(String gateway) throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("GATEWAY", gateway), "browse");
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("운영 주소");
        assertThat(tmp.resolve("k6.args")).doesNotExist();
    }

    @Test
    @DisplayName("🔴 비밀번호는 k6 의 환경 변수로만 — 명령줄 · 결과 파일에 없다. 데모 CA 를 믿게 한다(검증을 끄지 않는다)")
    void passwordOnlyInEnvAndCaTrusted() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("GATEWAY", "https://shop.lab", "USERS", "3"), "order");
        assertThat(r.code()).as(r.out()).isZero();
        String argv = read(tmp.resolve("k6.args"));
        assertThat(argv).doesNotContain("S3cret-demo-pw!9").contains("USERS=3").contains("GATEWAY=https://shop.lab")
                .doesNotContain("insecure");
        assertThat(read(tmp.resolve("k6.env"))).contains("DEMO_PASSWORD=S3cret-demo-pw!9")
                .contains("SSL_CERT_FILE=" + tmp.resolve("ca.crt"));
        assertThat(r.out()).doesNotContain("S3cret-demo-pw!9").contains("비밀번호 16자");
        try (Stream<Path> s = Files.walk(tmp.resolve("results"))) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                assertThat(read(f)).as(f.toString()).doesNotContain("S3cret-demo-pw!9");
            }
        }
    }

    @Test
    @DisplayName("🔴 데이터 규모 변수(PRODUCTS · PRODUCT_ID_OFFSET · DEEP_PAGES · NAME_SEARCH)를 k6 에 넘기고 meta.txt 에 남긴다 · 숫자만")
    void scaleVariablesReachK6AndMeta() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("GATEWAY", "https://shop.lab", "PRODUCTS", "1000000", "DEEP_PAGES", "200", "NAME_SEARCH", "0.3"), "browse");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(read(tmp.resolve("k6.args"))).contains("PRODUCTS=1000000").contains("DEEP_PAGES=200").contains("NAME_SEARCH=0.3")
                .doesNotContain("PRODUCT_ID_OFFSET=");
        try (Stream<Path> s = Files.walk(tmp.resolve("results"))) {
            Path meta = s.filter(p -> p.getFileName().toString().equals("meta.txt")).findFirst().orElseThrow();
            assertThat(read(meta)).contains("products=1000000").contains("deep_pages=200").contains("name_search=0.3");
        }
        Run bad = run(Map.of("GATEWAY", "https://shop.lab", "PRODUCTS", "1e6; rm -rf /"), "browse");
        assertThat(bad.code()).isNotZero();
        assertThat(bad.out()).contains("PRODUCTS 는 숫자");
    }

    @Test
    @DisplayName("🔴 종료 코드는 tee 가 아니라 k6 의 것 — 임계값을 넘으면(99) 실패로 끝나고 meta.txt 에 남는다")
    void k6ExitCodeIsKept() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("GATEWAY", "https://shop.lab", "K6_EXIT", "99"), "browse");
        assertThat(r.code()).isEqualTo(99);
        assertThat(r.out()).contains("임계값을 넘었다");
        try (Stream<Path> s = Files.walk(tmp.resolve("results"))) {
            Path meta = s.filter(p -> p.getFileName().toString().equals("meta.txt")).findFirst().orElseThrow();
            assertThat(read(meta)).contains("scenario=browse").contains("exit=99");
        }
    }

    @Test
    @DisplayName("🔴 측정 시나리오(browse · spike · order)는 시작 전에 유입 제한을 올렸는지 본다 — smoke 는 건너뛰고, ALLOW_RATE_LIMIT=1 은 ops/53 이 넘긴다")
    void scenariosRefuseToMeasureTheDefaultRateLimit() throws Exception {
        // 🐛 2026-10-18 첫 측정: 제한이 기본값(초당 20)인 채 돌아 browse 실패의 99.95% 가 429 — 서비스가 아니라 제한값을 쟀다
        String lib = read(repoRoot().resolve("ops/k6/lib.js"));
        Matcher fn = Pattern.compile("export function limitsRaisedOrFail\\(\\) \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(lib);
        assertThat(fn.find()).as("lib.js 에 limitsRaisedOrFail").isTrue();
        assertThat(fn.group(1)).contains("__ENV.SCENARIO === 'smoke'").contains("__ENV.ALLOW_RATE_LIMIT === '1'")
                .contains("http.batch").contains("429").contains("fail(");
        for (String scenario : List.of("browse", "order")) {
            String js = read(repoRoot().resolve("ops/k6/" + scenario + ".js"));
            Matcher setup = Pattern.compile("export function setup\\(\\) \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(js);
            assertThat(setup.find()).as(scenario + " setup").isTrue();
            assertThat(setup.group(1)).as(scenario + " 의 setup 이 확인을 부른다").contains("limitsRaisedOrFail();");
        }
        assertThat(read(repoRoot().resolve("ops/k6/spike.js"))).as("spike 는 browse 의 setup").contains("setup as browseSetup");
        assertThat(read(repoRoot().resolve("ops/k6/smoke.js"))).as("smoke 는 order 의 setup — lib 이 smoke 를 건너뛴다").contains("setup = orderSetup");

        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run allowed = run(Map.of("GATEWAY", "https://shop.lab", "ALLOW_RATE_LIMIT", "1"), "browse");
        assertThat(allowed.code()).as(allowed.out()).isZero();
        assertThat(read(tmp.resolve("k6.args"))).contains("ALLOW_RATE_LIMIT=1");
        Run plain = run(Map.of("GATEWAY", "https://shop.lab"), "browse");
        assertThat(plain.code()).isZero();
        assertThat(read(tmp.resolve("k6.args"))).doesNotContain("ALLOW_RATE_LIMIT");
    }

    private static TreeSet<String> matches(String text, String regex) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("🔴 429 가 아닌 실패를 5xx · 4xx · 응답 없음으로 센다 — 시나리오가 부르는 모든 이름이 엔드포인트별 오류 목록에 있다")
    void nonRateLimitFailuresAreCountedPerEndpoint() throws Exception {
        // 🐛 2026-10-18: 요약에 실패율만 남아 cart_add 의 실패 464건 · browse 의 1.06% 가 무엇인지 알 수 없었다
        String lib = read(repoRoot().resolve("ops/k6/lib.js"));
        Matcher ok = Pattern.compile("export function ok\\(res, name, expected = 200\\) \\{(.*?)\\n\\}", Pattern.DOTALL).matcher(lib);
        assertThat(ok.find()).isTrue();
        assertThat(ok.group(1)).contains("errors5xx.add(1, { name })").contains("errors4xx.add(1, { name })")
                .contains("noResponse.add(1, { name })").contains("res.status === 0").contains("res.status >= 500");
        TreeSet<String> libNames = matches(lib, "ok\\(res, '([a-z_]+)'\\)");   // signin · token_refresh — order 의 setup 이 부른다
        for (String scenario : List.of("browse", "order")) {
            String js = read(repoRoot().resolve("ops/k6/" + scenario + ".js"));
            TreeSet<String> used = matches(js, "(?:\\)|ok\\(\\w+),\\s*'([a-z_]+)'\\)");
            if (scenario.equals("order")) {
                used.addAll(libNames);
            }
            Matcher list = Pattern.compile("errorThresholds\\(\\[(.*?)\\]\\)", Pattern.DOTALL).matcher(js);
            assertThat(list.find()).as(scenario + " 의 thresholds 에 errorThresholds").isTrue();
            TreeSet<String> listed = matches(list.group(1), "'([a-z_]+)'");
            assertThat(used).as(scenario + " 가 부르는 이름").hasSizeGreaterThanOrEqualTo(7);
            assertThat(listed).as(scenario + " 의 엔드포인트별 오류 목록 = 부르는 이름").isEqualTo(used);
        }
        assertThat(read(repoRoot().resolve("ops/k6/spike.js"))).contains("...errorThresholds(");
    }

    @Test
    @DisplayName("🔴 report — 회차를 시각 순으로 한 줄씩 · 가장 최근 회차의 엔드포인트별 응답 시간 · 조건(k6 를 부르지 않는다)")
    void reportSummarizesRuns() throws Exception {
        assumeTrue(posix() && Files.isExecutable(Path.of("/usr/bin/python3")), "bash · python3 가 없다");
        Path results = tmp.resolve("results");
        // 이름순(browse < order)과 시각순이 반대 — 가장 최근은 browse 다
        Path order = Files.createDirectories(results.resolve("order-20261018T110000Z"));
        Files.writeString(order.resolve("meta.txt"), "scenario=order\ngit=aaaa1111\nusers=200 id_offset=0 rate=20 stages=default duration=default\nexit=99\n");
        Path browse = Files.createDirectories(results.resolve("browse-20261018T120000Z"));
        Files.writeString(browse.resolve("meta.txt"), """
                scenario=browse
                git=bbbb2222
                users=100 id_offset=0 rate=100 stages=default duration=default
                products=홈 상품만 product_id_offset=1000000 deep_pages=default name_search=default
                gateway_limits=GATEWAY_RATE_LIMIT_REPLENISH=2000 GATEWAY_RATE_LIMIT_BURST=4000\s
                db_pool=catalog-service=10\s
                hpa catalog-service cpu:40%/70% 2 3 2
                exit=0
                """);
        Files.writeString(browse.resolve("summary.json"), """
                {"metrics":{"http_reqs":{"count":36000,"rate":60.1},"http_req_failed":{"value":0.0000833},
                "checks":{"value":0.9997},"rate_limited":{"count":7,"rate":0.01},
                "errors_5xx":{"count":12},"errors_4xx":{"count":3},"errors_no_response":{"count":1},
                "errors_5xx{name:search}":{"count":12},"errors_4xx{name:product}":{"count":3},"errors_4xx{name:home}":{"count":0},
                "http_req_duration{name:search_deep}":{"avg":0,"p(50)":0,"p(95)":0,"p(99)":0,"max":0},
                "http_req_duration":{"avg":85.2,"p(50)":60,"p(95)":240.5,"p(99)":610,"max":1800},
                "http_req_duration{name:search}":{"avg":150,"p(50)":120,"p(95)":420,"p(99)":800,"max":1800}}}
                """);

        Run r = run(Map.of(), "report");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out().indexOf("order-20261018T110000Z")).as("시각 순").isLessThan(r.out().indexOf("browse-20261018T120000Z"));
        assertThat(r.out()).contains("── browse-20261018T120000Z")
                .doesNotContain("── order-20261018T110000Z")
                .contains("GATEWAY_RATE_LIMIT_REPLENISH=2000 GATEWAY_RATE_LIMIT_BURST=4000")
                .contains("DB 커넥션 풀: catalog-service=10")
                .contains("요청 36,000 (초당 60.1)").contains("429 7")
                .containsPattern("search\\s+150\\s+120\\s+420\\s+800\\s+1,800")
                .contains("hpa catalog-service")
                .contains("오류(429 제외): 5xx 12 · 4xx 3 · 응답 없음 1")
                .contains("엔드포인트별: product 4xx 3 · search 5xx 12").doesNotContain("home 4xx")
                .as("표본 없는 이름(전부 0)은 줄을 숨긴다").doesNotContain("search_deep");
        assertThat(tmp.resolve("k6.args")).as("k6 를 부르지 않는다").doesNotExist();

        Run one = run(Map.of(), "report", "order-20261018T110000Z");
        assertThat(one.out()).contains("── order-20261018T110000Z").contains("임계값 넘음").contains("summary.json 이 없다")
                .as("kubectl 없는 기계(loadgen)의 회차 — 기록이 없는 것을 기본값으로 읽지 않는다")
                .contains("유입 제한: 기록 없음").doesNotContain("기본값(20 · 40)");
        Run none = run(Map.of(), "report", "nope");
        assertThat(none.code()).isNotZero();
        assertThat(none.out()).contains("그런 회차가 없다");
    }

    @Test
    @DisplayName("🔴 DB 커넥션 풀 — 운영 클러스터는 거절 · max_connections 안에 드는지 먼저 센다(풀 수 × 늘린 만큼 × 최대 레플리카) · 되돌린다")
    void poolOnlyOnLabClusterAndWithinConnectionBudget() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Path log = tmp.resolve("kubectl.log");
        Run prod = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), "pool", "load", "10");
        assertThat(prod.code()).isNotZero();
        assertThat(prod.out()).contains("운영 클러스터").contains("DB 커넥션 풀");
        assertThat(read(log)).doesNotContain("set env");

        Files.delete(log);
        Run load = run(Map.of(), "pool", "load", "10");
        assertThat(load.code()).as(load.out()).isZero();
        assertThat(load.out()).as("catalog 는 데이터소스 2개 · HPA 최대 3").contains("2 풀 × (10 − 5) × 최대 레플리카 3 = 30");
        assertThat(read(log)).contains("set env deploy/catalog-service DB_POOL_SIZE=10");

        Files.delete(log);
        Run tight = run(Map.of("FAKE_USED", "270"), "pool", "load", "10");
        assertThat(tight.code()).isNotZero();
        assertThat(tight.out()).contains("모자란다");
        assertThat(read(log)).doesNotContain("set env");

        Files.delete(log);
        Run blind = run(Map.of("FAKE_MAX", ""), "pool", "load", "10");
        assertThat(blind.code()).isNotZero();
        assertThat(blind.out()).contains("예산을 모르면 바꾸지 않는다");
        assertThat(read(log)).doesNotContain("set env");

        Run zero = run(Map.of(), "pool", "load", "0");
        assertThat(zero.code()).isNotZero();
        assertThat(zero.out()).contains("크기는 1 ~ 99");

        Files.delete(log);
        Run back = run(Map.of(), "pool", "default");
        assertThat(back.code()).as(back.out()).isZero();
        assertThat(read(log)).contains("set env deploy/catalog-service DB_POOL_SIZE-");
    }

    /** 노드 둘 — agent(4 코어 · 8Gi, 파드 요청 usedCpuMillis · 4Gi)와 데이터 노드(NoSchedule 테인트, 비어 있음 — 세면 안 된다). */
    private static String cluster(int usedCpuMillis) {
        return """
                {"items":[
                 {"kind":"Node","metadata":{"name":"agent1"},"spec":{},"status":{"allocatable":{"cpu":"4","memory":"8Gi"}}},
                 {"kind":"Node","metadata":{"name":"data-node"},"spec":{"taints":[{"key":"ecommerce.io/data","effect":"NoSchedule"}]},
                  "status":{"allocatable":{"cpu":"16","memory":"32Gi"}}},
                 {"kind":"Pod","spec":{"nodeName":"agent1","containers":[{"resources":{"requests":{"cpu":"%dm","memory":"4096Mi"}}}]},
                  "status":{"phase":"Running"}},
                 {"kind":"Pod","spec":{"nodeName":"agent1","containers":[{"resources":{"requests":{"cpu":"2","memory":"1Gi"}}}]},
                  "status":{"phase":"Succeeded"}}
                ]}""".formatted(usedCpuMillis);
    }

    @Test
    @DisplayName("🔴 레플리카 — 운영 클러스터는 거절 · 스케줄 가능한 노드의 요청 여유를 먼저 센다(테인트 노드 · 끝난 파드 제외) · HPA 최소를 함께 올리고 되돌린다")
    void replicasOnlyOnLabClusterAndWithinNodeBudget() throws Exception {
        assumeTrue(posix() && Files.isExecutable(Path.of("/usr/bin/python3")), "bash · python3 가 없다");
        Path log = tmp.resolve("kubectl.log");
        Run prod = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), "replicas", "load");
        assertThat(prod.code()).isNotZero();
        assertThat(prod.out()).contains("운영 클러스터").contains("레플리카");
        assertThat(read(log)).doesNotContain("patch").doesNotContain(" scale ");

        Files.delete(log);
        Run load = run(Map.of(), "replicas", "load");
        assertThat(load.code()).as(load.out()).isZero();
        assertThat(load.out()).as("catalog · 게이트웨이를 2 → 3, 요청 250m · 512Mi 씩")
                .contains("스케줄 가능한 노드 1대의 요청 여유 CPU 1000m · 메모리 4096Mi / 더할 요청 CPU 500m · 메모리 1024Mi");
        assertThat(read(log))
                .contains("patch hpa catalog-service --type merge -p {\"spec\":{\"minReplicas\":3,\"maxReplicas\":3}}")
                .contains("scale deploy/catalog-service --replicas=3")
                .contains("patch hpa gateway-server --type merge -p {\"spec\":{\"minReplicas\":3,\"maxReplicas\":3}}")
                .contains("scale deploy/gateway-server --replicas=3");

        Files.delete(log);
        Run four = run(Map.of(), "replicas", "load", "4", "catalog-service");
        assertThat(four.code()).as(four.out()).isZero();
        assertThat(read(log)).as("HPA 최대보다 크면 최대도 올린다 · 이름을 준 서비스만")
                .contains("{\"spec\":{\"minReplicas\":4,\"maxReplicas\":4}}").doesNotContain("gateway-server --replicas");

        Files.delete(log);
        Run tight = run(Map.of("FAKE_CLUSTER", cluster(3700)), "replicas", "load");
        assertThat(tight.code()).as("여유 300m < 500m — 데이터 노드의 16 코어는 세지 않는다").isNotZero();
        assertThat(tight.out()).contains("요청 여유가 모자라거나");
        assertThat(read(log)).doesNotContain("patch").doesNotContain(" scale ");

        Run zero = run(Map.of(), "replicas", "load", "0");
        assertThat(zero.code()).isNotZero();
        assertThat(zero.out()).contains("레플리카 수는 1 ~ 9");

        Files.delete(log);
        Run back = run(Map.of(), "replicas", "default");
        assertThat(back.code()).as(back.out()).isZero();
        assertThat(read(log)).as("k8s/70 의 최소 2 · 최대 3 으로")
                .contains("patch hpa catalog-service --type merge -p {\"spec\":{\"minReplicas\":2,\"maxReplicas\":3}}")
                .contains("patch hpa gateway-server --type merge -p {\"spec\":{\"minReplicas\":2,\"maxReplicas\":3}}");
    }

    @Test
    @DisplayName("🔴 유입 제한은 운영 클러스터(APP_PUBLIC_URL 이 운영 주소)에서 바꾸지 않는다 · 실습 클러스터에서는 올리고 되돌린다")
    void limitsOnlyOnLabCluster() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run prod = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), "limits", "load");
        assertThat(prod.code()).isNotZero();
        assertThat(prod.out()).contains("운영 클러스터");
        assertThat(read(tmp.resolve("kubectl.log"))).doesNotContain("set env");

        Files.delete(tmp.resolve("kubectl.log"));
        Run load = run(Map.of(), "limits", "load", "1500");
        assertThat(load.code()).as(load.out()).isZero();
        assertThat(read(tmp.resolve("kubectl.log")))
                .contains("set env deploy/gateway-server GATEWAY_RATE_LIMIT_REPLENISH=1500 GATEWAY_RATE_LIMIT_BURST=3000");

        Files.delete(tmp.resolve("kubectl.log"));
        Run back = run(Map.of(), "limits", "default");
        assertThat(back.code()).isZero();
        assertThat(read(tmp.resolve("kubectl.log")))
                .contains("set env deploy/gateway-server GATEWAY_RATE_LIMIT_REPLENISH- GATEWAY_RATE_LIMIT_BURST-");

        Run empty = run(Map.of("FAKE_URL", ""), "limits", "load");
        assertThat(empty.code()).as("어느 클러스터인지 모르면 바꾸지 않는다").isNotZero();
    }
}
