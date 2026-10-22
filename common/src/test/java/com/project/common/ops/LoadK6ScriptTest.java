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
                  *"get deploy gateway-server"*) printf '%s' "${FAKE_ENV:-}" ;;
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
