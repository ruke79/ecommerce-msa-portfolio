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

    /** 화면의 경로 — 앞의 / 없이 부르는 곳도 있다(axios 기준 주소에 붙는다 — Review.jsx 의 `user-service/api/review/add/…`). */
    private static final Pattern FRONTEND_PATH = Pattern.compile("[\"'`}]/?((?:[a-z]+-service|saga-projection)/[^\"'`?$]*)");

    private static TreeSet<String> frontendPaths() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(repoRoot().resolve("frontend/src"))) {
            s.filter(p -> p.toString().matches(".*\\.jsx?$") && !p.toString().matches(".*\\.test\\.jsx?$")).forEach(f -> {
                Matcher m = FRONTEND_PATH.matcher(read(f));
                while (m.find()) {
                    out.add("/" + m.group(1));
                }
            });
        }
        return out;
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
    @DisplayName("🔴 order 는 입금 안내가 성공한 주문만 취소한다 — 실패한 주문의 취소는 400 이라 한 번의 실패가 두 번 세어졌다")
    void orderCancelsOnlyAwaitingDeposit() throws IOException {
        String order = read(repoRoot().resolve("ops/k6/order.js"));
        Matcher start = Pattern.compile("const (\\w+) = ok\\(send\\('POST', '/order-service/api/order/payment/bank-transfer/start'")
                .matcher(order);
        assertThat(start.find()).as("입금 안내의 결과를 담는다").isTrue();
        assertThat(order).as("취소는 그 결과가 참일 때만")
                .containsPattern("if \\(" + start.group(1) + "\\) \\{\\s*ok\\(send\\('POST', '/order-service/api/order/payment/bank-transfer/cancel'");
        assertThat(order.split("bank-transfer/cancel", -1)).as("취소를 부르는 곳은 하나").hasSize(2);
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

    /** ops/k6 의 순수 모듈 하나를 node 로 돌린다(ESM — .mjs 로 복사해 불러온다, 이름 s). 출력을 돌려준다. */
    private String node(String body) throws Exception {
        return node("spread", body);
    }

    private String node(String module, String body) throws Exception {
        Path mod = tmp.resolve(module + ".mjs");
        Files.copy(repoRoot().resolve("ops/k6/" + module + ".js"), mod, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path script = tmp.resolve("check.mjs");
        Files.writeString(script, "import * as s from './" + module + ".mjs';\n" + body, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("node", script.toString()).directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0).as(out).isTrue();
        return out;
    }

    private static boolean hasNode() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor(20, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    @DisplayName("🔴 SPREAD — 검색 조합을 넓혀 캐시 밖을 잰다: 이름 검색은 쪽마다 다른 주소 · 필터 검색은 거의 겹치지 않는다 · 끄면 예전 주소 그대로")
    void spreadWidensSearchCombinations() throws Exception {
        // 🐛 2026-10-19 100만 회차(docs/83 §5): 조합이 카테고리 × 50쪽 · 이름 검색어 16개뿐이라 첫 1분 반 뒤로는 search_cache 에서 나왔다
        assumeTrue(hasNode(), "node 가 없다");
        String out = node("""
                const terms = Array.from({ length: 16 }, (_, i) => 't' + i);
                const draw = (n, f) => new Set(Array.from({ length: n }, f)).size;
                const name1 = draw(3000, () => s.nameSearchPath(terms[Math.floor(Math.random() * 16)], 1));
                const name50 = draw(3000, () => s.nameSearchPath(terms[Math.floor(Math.random() * 16)], 50));
                const deep50 = draw(3000, () => s.deepSearchPath('c' + Math.floor(Math.random() * 6), 50));
                const deep1000 = draw(3000, () => s.deepSearchPath('c' + Math.floor(Math.random() * 6), 1000));
                const facets = { colors: ['Black', 'Navy Blue', '', null], sizes: ['M', '', 'XL'] };
                const filters = Array.from({ length: 3000 }, () => s.filterSearchPath('c1', facets));
                const bad = filters.filter((u) => {
                  const q = new URLSearchParams(u.split('?')[1]);
                  const low = Number(q.get('lowPrice')), high = Number(q.get('highPrice'));
                  return !(low >= 0 && low <= 200 && high - low >= 20 && high - low <= 500)
                    || (q.has('color') && !['Black', 'Navy Blue'].includes(q.get('color')))
                    || (q.has('size') && !['M', 'XL'].includes(q.get('size')))
                    || q.get('page') !== '1' || q.get('category') !== 'c1';
                }).length;
                const withColor = filters.filter((u) => u.includes('&color=')).length;
                const pages = Array.from({ length: 3000 }, () => Number(new URLSearchParams(s.nameSearchPath('x', 50).split('?')[1]).get('page')));
                const firstOnlyFilters = Array.from({ length: 500 }, () => s.nameSearchPath('x', 50))
                  .every((u) => u.includes('&page=1&') === u.endsWith('includeFilters=true'));
                console.log(JSON.stringify({ name1, name50, deep50, deep1000, filtersDistinct: new Set(filters).size, bad, withColor,
                  minPage: Math.min(...pages), maxPage: Math.max(...pages), firstOnlyFilters,
                  oldName: s.nameSearchPath('Olive Oil', 1), oldDeep: s.deepSearchPath('42', 2, () => 0.99),
                  noFacets: s.filterSearchPath('c1', null, () => 0) }));
                """);
        assertThat(out)
                .as("끄면(쪽 1) 이름 검색 조합은 검색어 수 그대로 — 예전 주소").contains("\"name1\":16")
                .as("예전 이름 검색 주소").contains("\"oldName\":\"/catalog-service/api/search?search=Olive%20Oil&page=1&pageSize=10&includeFilters=true\"")
                .as("예전 깊은 쪽 주소(includeFilters 없음)").contains("\"oldDeep\":\"/catalog-service/api/search?category=42&page=2&pageSize=10\"")
                .as("가격대 · 색상 · 사이즈는 범위 · 필터 메뉴 안").contains("\"bad\":0")
                .contains("\"minPage\":1,\"maxPage\":50").contains("\"firstOnlyFilters\":true")
                .as("필터 메뉴가 없으면 가격대만").contains("\"noFacets\":\"/catalog-service/api/search?category=c1&lowPrice=0&highPrice=20&page=1&pageSize=10&includeFilters=true\"");
        Matcher n = Pattern.compile("\"name50\":(\\d+),\"deep50\":(\\d+),\"deep1000\":(\\d+),\"filtersDistinct\":(\\d+),\"bad\":0,\"withColor\":(\\d+)").matcher(out);
        assertThat(n.find()).as(out).isTrue();
        assertThat(Integer.parseInt(n.group(1))).as("이름 검색 3,000번 — 16 × 50쪽 = 800 조합을 거의 다 쓴다").isGreaterThan(700);
        assertThat(Integer.parseInt(n.group(2))).as("깊은 쪽 50 — 6 × 49 = 294 가 상한(예전 회차)").isLessThanOrEqualTo(294);
        assertThat(Integer.parseInt(n.group(3))).as("깊은 쪽 1000 — 6 × 999 조합에 3,000번이면 기대 2,358(겹침은 생일 문제만큼)").isGreaterThan(2200);
        assertThat(Integer.parseInt(n.group(4))).as("필터 검색 3,000번이 거의 겹치지 않는다").isGreaterThan(2900);
        assertThat(Integer.parseInt(n.group(5))).as("색상은 절반쯤").isBetween(1200, 1800);

        String browse = read(repoRoot().resolve("ops/k6/browse.js"));
        assertThat(browse).as("SPREAD 의 기본값 — 깊은 쪽 1000 · 이름 검색 50쪽, 필터 검색은 주어야만")
                .contains("__ENV.DEEP_PAGES || (SPREAD ? 1000 : SCALE ? 50 : 2)")
                .contains("__ENV.NAME_PAGES || (SPREAD ? 50 : 1)")
                .contains("__ENV.FILTER_SEARCH || 0")
                .contains("nameSearchPath(pick(NAME_TERMS), NAME_PAGES)").contains("deepSearchPath(category, DEEP_PAGES)")
                .contains("'http_req_duration{name:filter_search}'").contains("'filter_search', 'product'");
        assertThat(read(repoRoot().resolve("ops/k6/spike.js"))).as("spike 도 엔드포인트별 오류 수에").contains("'filter_search', 'product'");
    }

    @Test
    @DisplayName("🔴 mixed — browse 그대로 + 로그인 사용자의 리뷰 쓰기(화면과 같은 multipart) · 받은 상품만 확인이 켜져 있으면 시작 전에 멈춘다")
    void mixedScenarioWritesReviewsWhileBrowsing() throws Exception {
        // 리뷰마다 catalog 의 updateRating 이 불린다(예전에는 search_cache · 필터 메뉴 캐시를 통째로 비웠다) — 읽기만 하는 browse 는 이 경로를 지나지 않는다
        String mixed = read(repoRoot().resolve("ops/k6/mixed.js"));
        assertThat(mixed).as("browse 의 시나리오 · setup 을 그대로")
                .contains("import { browse, setup as browseSetup, options as browseOptions, coldProductId, PRODUCTS } from './browse.js'")
                .contains("browse: Object.assign({}, browseScenario, { exec: 'browse' })")
                .as("리뷰는 일정한 도착률로 browse 가 도는 내내").contains("executor: 'constant-arrival-rate'").contains("rate: REVIEW_RATE")
                .contains("duration: `${totalSeconds(browseScenario.stages)}s`")
                .as("화면(Review.jsx)과 같은 모양 — multipart, 부분 review 는 JSON")
                .contains("`${GATEWAY}/user-service/api/review/add/${encodeURIComponent(productId)}`")
                .contains("{ review: http.file(JSON.stringify(body), 'review.json', 'application/json') }")
                .contains("delete headers['Content-Type']")
                .as("시작 전 확인").contains("probe.status === 403").contains("./ops/53-load-k6.sh reviews open")
                .contains("loginSessions(password, REVIEW_VUS, ID_OFFSET)").contains("not_json: ['count<1']");
        assertThat(read(repoRoot().resolve("ops/k6/lib.js")))
                .as("order 와 같은 로그인").contains("export function loginSessions(password, users, offset)");
        assertThat(read(repoRoot().resolve("ops/k6/order.js"))).contains("loginSessions(password, USERS, ID_OFFSET)");

        // 엔드포인트별 오류 목록 = browse 의 목록 + 리뷰 · 로그인
        Matcher b = Pattern.compile("errorThresholds\\(\\[(.*?)\\]\\)", Pattern.DOTALL).matcher(read(repoRoot().resolve("ops/k6/browse.js")));
        assertThat(b.find()).isTrue();
        Matcher names = Pattern.compile("BROWSE_NAMES = \\[(.*?)\\]", Pattern.DOTALL).matcher(mixed);
        assertThat(names.find()).isTrue();
        assertThat(matches(names.group(1), "'([a-z_]+)'")).as("browse 의 이름이 늘면 mixed 도").isEqualTo(matches(b.group(1), "'([a-z_]+)'"));
        assertThat(mixed).contains("errorThresholds([...BROWSE_NAMES, 'review_add', 'signin', 'token_refresh'])");

        if (hasNode()) {
            assertThat(node("durations", "console.log(s.totalSeconds([{duration:'1m'},{duration:'2m'},{duration:'1m30s'},{duration:'30s'},{duration:'500ms'}]))"))
                    .as("단계 합 — 1m + 2m + 1m30s + 30s + 0.5s").isEqualTo("301");
        }

        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run r = run(Map.of("GATEWAY", "https://shop.lab", "PRODUCTS", "1000000", "REVIEW_RATE", "2"), "mixed");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(read(tmp.resolve("k6.args"))).contains("REVIEW_RATE=2").contains("mixed.js");
        assertThat(read(tmp.resolve("k6.env"))).as("데모 비밀번호는 환경 변수로").contains("DEMO_PASSWORD=S3cret-demo-pw!9");
        assertThat(r.out()).as("길이만").contains("비밀번호 16자").doesNotContain("S3cret");
        Run rep = run(Map.of(), "report");
        assertThat(rep.out()).contains("리뷰 쓰기: 초당 2");
        Run bad = run(Map.of("GATEWAY", "https://shop.lab", "REVIEW_RATE", "1; rm -rf /"), "mixed");
        assertThat(bad.code()).isNotZero();
        assertThat(bad.out()).contains("REVIEW_RATE 는 숫자");
    }

    @Test
    @DisplayName("🔴 reviews open · default — 실습 클러스터에서만 user-service 의 '받은 상품만' 확인을 끄고 되돌린다")
    void reviewsSwitchOnlyOnLabCluster() throws Exception {
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Run prod = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), "reviews", "open");
        assertThat(prod.code()).isNotZero();
        assertThat(prod.out()).contains("운영 클러스터다");
        assertThat(tmp.resolve("kubectl.log")).as("운영에는 바꾸는 명령을 보내지 않는다").satisfies(p ->
                assertThat(Files.exists(p) ? read(p) : "").doesNotContain("set env"));

        Run open = run(Map.of(), "reviews", "open");
        assertThat(open.code()).as(open.out()).isZero();
        assertThat(read(tmp.resolve("kubectl.log"))).contains("set env deploy/user-service REVIEW_REQUIRE_PURCHASE=false")
                .contains("rollout status deploy/user-service");
        Run back = run(Map.of(), "reviews", "default");
        assertThat(back.code()).as(back.out()).isZero();
        assertThat(read(tmp.resolve("kubectl.log"))).contains("set env deploy/user-service REVIEW_REQUIRE_PURCHASE-");
        assertThat(read(repoRoot().resolve("user-service/src/main/java/com/project/userservice/service/ReviewService.java")))
                .as("끄는 이름이 실제 설정과 같다(REVIEW_REQUIRE_PURCHASE → review.require-purchase)").contains("${review.require-purchase:true}");
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
                  *"get pods -l app=gateway-server"*) printf '%s' "${FAKE_GW_PODS-gw-a
                gw-b
                }" ;;
                  *"logs gw-"*"--previous"*) exit 1 ;;
                  *"logs gw-a --tail=-1"*) printf '%s\n' "t1 WARN Fallback triggered. Exception: io.github.resilience4j.bulkhead.BulkheadFullException: Bulkhead 'catalogServiceCircuitBreaker' is full" \
                      "t2 WARN Fallback triggered. Exception: io.github.resilience4j.bulkhead.BulkheadFullException: Bulkhead 'catalogServiceCircuitBreaker' is full" \
                      "t3 INFO ok" "t4 WARN Fallback triggered. Exception: java.util.concurrent.TimeoutException: Did not observe" ;;
                  *"logs gw-b --tail=-1"*) printf '%s\n' "t1 WARN Fallback triggered. Exception: io.github.resilience4j.bulkhead.BulkheadFullException: full" ;;
                  *"get deploy "*"-o jsonpath={.spec.replicas}"*) printf '2' ;;
                  *"requests.cpu}"*) printf '250m' ;;
                  *"requests.memory}"*) printf '512Mi' ;;
                  *"get nodes,pods -A -o json"*) printf '%s' "${FAKE_CLUSTER}" ;;
                  *"get hpa "*"{.spec.minReplicas} {.spec.maxReplicas}"*) [ -n "${FAKE_NO_HPA:-}" ] || printf '2 3' ;;
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
        // 가짜 Prometheus 질의 API — 측정 중 최대(max_over_time)와 지금 값을 따로 돌려준다. FAKE_PROM_FAIL=1 이면 응답 없음
        exe(bin.resolve("curl"), """
                echo "curl $*" >> "$FAKE/curl.log"
                [ "${FAKE_PROM_FAIL:-0}" = "1" ] && exit 7
                case "$*" in
                  *max_over_time*) printf '%s' "${FAKE_PROM_MAX:-}" ;;
                  *) printf '%s' "${FAKE_PROM_UP:-}" ;;
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
        Run r = run(Map.of("GATEWAY", "https://shop.lab", "PRODUCTS", "1000000", "DEEP_PAGES", "200", "NAME_SEARCH", "0.3",
                "SPREAD", "1", "NAME_PAGES", "20", "FILTER_SEARCH", "0.1"), "browse");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(read(tmp.resolve("k6.args"))).contains("PRODUCTS=1000000").contains("DEEP_PAGES=200").contains("NAME_SEARCH=0.3")
                .contains("SPREAD=1").contains("NAME_PAGES=20").contains("FILTER_SEARCH=0.1")
                .doesNotContain("PRODUCT_ID_OFFSET=");
        Path meta;
        try (Stream<Path> s = Files.walk(tmp.resolve("results"))) {
            meta = s.filter(p -> p.getFileName().toString().equals("meta.txt")).findFirst().orElseThrow();
            assertThat(read(meta)).contains("products=1000000").contains("deep_pages=200").contains("name_search=0.3")
                    .contains("spread=1 name_pages=20 filter_search=0.1");
        }
        Run rep = run(Map.of(), "report");
        assertThat(rep.out()).as("넓힌 회차는 캐시를 잰 회차와 견주지 않게 따로 보인다").contains("1000000·넓힘")
                .contains("검색 조합: 넓힘=1 · 깊은 쪽 200 · 이름 검색 쪽 20 · 필터 검색 비율 0.1");
        Run badSpread = run(Map.of("GATEWAY", "https://shop.lab", "PRODUCTS", "1000", "SPREAD", "yes"), "browse");
        assertThat(badSpread.code()).isNotZero();
        assertThat(badSpread.out()).contains("SPREAD 는 숫자");
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

    private static String promVector(String... serviceCount) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < serviceCount.length; i += 2) {
            rows.add("{\"metric\":{\"service\":\"" + serviceCount[i] + "\"},\"value\":[1760000000,\"" + serviceCount[i + 1] + "\"]}");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", rows) + "]}}";
    }

    @Test
    @DisplayName("🔴 레플리카를 Prometheus 에서 — 시작 · 측정 중 최대 · 끝을 meta.txt 에, report 가 보인다(kubectl 없는 loadgen)")
    void replicasFromPrometheusReachMetaAndReport() throws Exception {
        // 🐛 2026-10-18: 0.17% 회차가 레플리카 2 인지 3 인지 meta.txt 로는 알 수 없었다 — HPA(2 ~ 3)가 측정 중에 늘리고, loadgen 에는 kubectl 이 없다
        assumeTrue(posix() && Files.isExecutable(Path.of("/usr/bin/python3")), "bash · python3 가 없다");
        Map<String, String> env = Map.of("GATEWAY", "https://shop.lab", "PROM_RW", "http://obs-vm:9090/api/v1/write",
                "FAKE_PROM_UP", promVector("cart-service", "1", "catalog-service", "2", "gateway-server", "2"),
                "FAKE_PROM_MAX", promVector("cart-service", "1", "catalog-service", "3", "gateway-server", "3"));
        Run r = run(env, "browse");
        assertThat(r.code()).as(r.out()).isZero();
        String curl = read(tmp.resolve("curl.log"));
        assertThat(curl).as("PROM_RW(…/api/v1/write) 에서 질의 주소를 만든다").contains("http://obs-vm:9090/api/v1/query")
                .contains("up{environment=\"demo\"} == 1").contains("max_over_time(").contains("[60s:15s]");
        Path meta;
        try (Stream<Path> s = Files.walk(tmp.resolve("results"))) {
            meta = s.filter(p -> p.getFileName().toString().equals("meta.txt")).findFirst().orElseThrow();
        }
        assertThat(read(meta)).contains("replicas_start=cart-service=1 catalog-service=2 gateway-server=2")
                .contains("replicas_max=cart-service=1 catalog-service=3 gateway-server=3")
                .contains("replicas_end=cart-service=1 catalog-service=2 gateway-server=2")
                .contains("exit=0");

        Run rep = run(Map.of(), "report");
        assertThat(rep.code()).as(rep.out()).isZero();
        assertThat(rep.out()).contains("레플리카(시작 → 측정 중 최대 → 끝): catalog-service 2 → 3 → 2 · gateway-server 2 → 3 → 2 · 나머지 1개는 1")
                .containsPattern("browse-\\S+ .* 3·3  통과");

        Files.delete(tmp.resolve("curl.log"));
        Run down = run(Map.of("GATEWAY", "https://shop.lab", "PROM", "http://obs-vm:9090", "FAKE_PROM_FAIL", "1"), "browse");
        assertThat(down.code()).as("Prometheus 가 없어도 측정은 k6 의 종료 코드로 끝난다").isZero();
        assertThat(down.out()).contains("레플리카를 Prometheus(http://obs-vm:9090)에서 읽지 못했다");
        assertThat(run(Map.of(), "report").out()).contains("레플리카: Prometheus(http://obs-vm:9090)에서 읽지 못했다");

        Run bad = run(Map.of("GATEWAY", "https://shop.lab", "PROM", "http://obs-vm:9090", "PROM_ENV", "demo\"} or vector(9) #"), "browse");
        assertThat(bad.code()).isNotZero();
        assertThat(bad.out()).contains("PROM_ENV 는");
    }

    @Test
    @DisplayName("🔴 게이트웨이 파드를 바꾸기 전에 로그를 남긴다 — limits · replicas(게이트웨이) · logs, 폴백 예외별 수 요약 · 0700 폴더")
    void gatewayLogsAreSavedBeforePodsAreReplaced() throws Exception {
        // 🐛 2026-10-18: limits default 의 롤링이 측정 때의 게이트웨이 파드를 지워, 503 10,281건의 폴백 로그를 셀 수 없었다
        assumeTrue(posix(), "bash · POSIX 권한이 없다");
        Path logs = tmp.resolve("gwlogs");
        Run r = run(Map.of("GATEWAY_LOG_DIR", logs.toString()), "limits", "default");
        assertThat(r.code()).as(r.out()).isZero();
        String calls = read(tmp.resolve("kubectl.log"));
        assertThat(calls.indexOf("logs gw-a --tail=-1")).as("로그가 먼저 — 그다음 롤링").isPositive()
                .isLessThan(calls.indexOf("set env deploy/gateway-server"));
        Path dir;
        try (Stream<Path> st = Files.list(logs)) {
            dir = st.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        assertThat(dir.getFileName().toString()).endsWith("-limits-default");
        assertThat(Files.getPosixFilePermissions(dir)).as("운영자만").isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(dir.resolve("gw-a.log.gz")).exists();
        assertThat(dir.resolve("gw-b.log.gz")).exists();
        assertThat(dir.resolve("gw-a.previous.log.gz")).as("재시작이 없으면 빈 파일을 두지 않는다").doesNotExist();
        String summary = read(dir.resolve("summary.txt"));
        assertThat(summary).contains("reason=limits-default").contains("pods=gw-a gw-b")
                .containsPattern("== gw-a 줄 4 · 폴백 3")
                .containsPattern("(?s)== 모든 파드.*3 io\\.github\\.resilience4j\\.bulkhead\\.BulkheadFullException.*1 java\\.util\\.concurrent\\.TimeoutException");
        assertThat(r.out()).contains("게이트웨이 로그를 남겼다(limits-default, 파드 2개)").contains("BulkheadFullException");

        Run reps = run(Map.of("GATEWAY_LOG_DIR", logs.toString()), "replicas", "default", "gateway-server");
        assertThat(reps.out()).contains("게이트웨이 로그를 남겼다(replicas-default");
        Run catOnly = run(Map.of("GATEWAY_LOG_DIR", logs.toString()), "replicas", "default", "catalog-service");
        assertThat(catOnly.out()).as("게이트웨이를 바꾸지 않으면 남기지 않는다").doesNotContain("게이트웨이 로그를 남겼다");
        Run manual = run(Map.of("GATEWAY_LOG_DIR", logs.toString()), "logs");
        assertThat(manual.code()).as(manual.out()).isZero();
        assertThat(manual.out()).contains("게이트웨이 로그를 남겼다(manual");
        Run none = run(Map.of("GATEWAY_LOG_DIR", logs.toString(), "FAKE_GW_PODS", ""), "limits", "load");
        assertThat(none.code()).as("파드가 없어도 명령은 계속").isZero();
        assertThat(none.out()).contains("게이트웨이 파드가 없다");
        Run prod = run(Map.of("GATEWAY_LOG_DIR", logs.toString(), "FAKE_URL", "https://www.ysyproject.com"), "logs");
        assertThat(prod.code()).isNotZero();
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
    @DisplayName("🔴 replicas max — HPA 최대만 올린다(최소 · 레플리카 그대로) · 최대까지 늘었을 때의 요청 여유를 센다 · HPA 가 없거나 최소보다 작으면 거절")
    void replicasMaxRaisesOnlyHpaMax() throws Exception {
        // 2026-10-19 spike: 게이트웨이가 HPA 최대 3 에서 CPU 100% — 'replicas load' 는 최소도 올려 HPA 가 따라가는 동안을 잴 수 없었다
        assumeTrue(posix() && Files.isExecutable(Path.of("/usr/bin/python3")), "bash · python3 가 없다");
        Path log = tmp.resolve("kubectl.log");
        Run prod = run(Map.of("FAKE_URL", "https://www.ysyproject.com"), "replicas", "max", "6", "gateway-server");
        assertThat(prod.code()).isNotZero();
        assertThat(read(log)).doesNotContain("patch");

        Files.delete(log);
        Run max = run(Map.of(), "replicas", "max", "6", "gateway-server");
        assertThat(max.code()).as(max.out()).isZero();
        assertThat(max.out()).as("2 → 6 까지 늘었을 때 — 파드 4 × 250m")
                .contains("요청 여유 CPU 1000m · 메모리 4096Mi / 더할 요청 CPU 1000m · 메모리 2048Mi");
        assertThat(read(log))
                .contains("patch hpa gateway-server --type merge -p {\"spec\":{\"maxReplicas\":6}}")
                .as("최소 · 레플리카는 HPA 에 맡긴다").doesNotContain("\"minReplicas\"").doesNotContain(" scale ")
                .as("파드를 지우지 않으니 로그를 남기지 않는다").doesNotContain("logs gw-");

        Files.delete(log);
        Run tight = run(Map.of(), "replicas", "max", "7", "gateway-server");
        assertThat(tight.code()).as("파드 5 × 250m = 1250m > 1000m").isNotZero();
        assertThat(tight.out()).contains("요청 여유가 모자라거나");
        assertThat(read(log)).doesNotContain("patch");

        Run noHpa = run(Map.of("FAKE_NO_HPA", "1"), "replicas", "max", "6", "gateway-server");
        assertThat(noHpa.code()).isNotZero();
        assertThat(noHpa.out()).contains("HPA 가 없다");
        Run below = run(Map.of(), "replicas", "max", "1", "gateway-server");
        assertThat(below.code()).isNotZero();
        assertThat(below.out()).contains("HPA 최소 2 보다 작다");
        assertThat(read(log)).doesNotContain("patch");
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
