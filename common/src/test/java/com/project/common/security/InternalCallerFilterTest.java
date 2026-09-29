package com.project.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 🔒 서비스 간 mTLS — "내부 호출" 은 내부 CA 의 클라이언트 인증서로 판정한다(docs/57 §16).
 *
 * <p>인증서는 JDK 의 keytool 로 만든다(외부 라이브러리 없이): 내부 CA, 그 CA 가 서명한 서비스 인증서, 다른 CA 가 서명한 인증서.
 */
@DisplayName("InternalCallerFilter — 서비스 간 mTLS 판정")
class InternalCallerFilterTest {

    @TempDir
    static Path dir;

    static X509Certificate ca;
    static X509Certificate service;      // 내부 CA 가 서명, CN=order-service
    static X509Certificate stranger;     // 다른 CA 가 서명

    @BeforeAll
    static void issue() throws Exception {
        ca = issueCa("ca", "internal-ca");
        service = issueLeaf("ca", "svc", "order-service");
        issueCa("other", "other-ca");
        stranger = issueLeaf("other", "evil", "order-service");
    }

    // ── keytool 로 인증서 만들기 ─────────────────────────────────────────────

    private static void keytool(String... args) throws Exception {
        String bin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> cmd = new java.util.ArrayList<>(List.of(bin));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool 실패: " + String.join(" ", args) + "\n" + out);
        }
    }

    private static X509Certificate read(String file) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(dir.resolve(file))));
    }

    private static X509Certificate issueCa(String alias, String cn) throws Exception {
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-ext", "bc:c", "-validity", "2", "-keystore", alias + ".p12", "-storepass", "changeit", "-storetype", "PKCS12");
        keytool("-exportcert", "-rfc", "-alias", alias, "-file", alias + ".crt", "-keystore", alias + ".p12", "-storepass", "changeit");
        return read(alias + ".crt");
    }

    private static X509Certificate issueLeaf(String caAlias, String alias, String cn) throws Exception {
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-validity", "2", "-keystore", alias + ".p12", "-storepass", "changeit", "-storetype", "PKCS12");
        keytool("-certreq", "-alias", alias, "-file", alias + ".csr", "-keystore", alias + ".p12", "-storepass", "changeit");
        keytool("-gencert", "-alias", caAlias, "-infile", alias + ".csr", "-outfile", alias + ".crt", "-rfc",
                "-ext", "eku=serverAuth,clientAuth", "-validity", "2", "-keystore", caAlias + ".p12", "-storepass", "changeit");
        return read(alias + ".crt");
    }

    // ── 필터를 한 번 통과시킨 요청 ───────────────────────────────────────────

    private static HttpServletRequest pass(InternalCallerFilter filter, MockHttpServletRequest req) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest internalPath(X509Certificate... certs) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/order/internal/purchase-stats");
        if (certs.length > 0) {
            r.setAttribute(InternalCallerFilter.CERT_ATTRIBUTE, certs);
        }
        return r;
    }

    private static boolean guardAllows(HttpServletRequest r) {
        return InternalCallGuard.allowed(r.getHeader("User-Role"), r.getHeader("User-Email"), r.getHeader("User-Id"));
    }

    private static InternalCallerFilter enforce(Set<String> allowed) {
        return new InternalCallerFilter(InternalCallerFilter.Mode.ENFORCE, ca, allowed, List.of("/internal/"));
    }

    @Test
    @DisplayName("🔴 enforce — 인증서 없이 헤더도 없는 요청은 내부 호출이 아니다(게이트웨이 공개 라우트·다른 파드)")
    void noCertIsNotInternal() throws Exception {
        HttpServletRequest seen = pass(enforce(Set.of()), internalPath());
        assertThat(seen.getHeader("User-Role")).isEqualTo(InternalCallerFilter.ANONYMOUS_ROLE);
        assertThat(guardAllows(seen)).isFalse();
    }

    @Test
    @DisplayName("🔴 내부 CA 가 서명한 인증서를 낸 호출은 내부 호출 — 호출한 서비스의 CN 이 남는다")
    void internalCertIsInternal() throws Exception {
        HttpServletRequest seen = pass(enforce(Set.of()), internalPath(service));
        assertThat(seen.getHeader("User-Role")).isNull();
        assertThat(guardAllows(seen)).isTrue();
        assertThat(seen.getAttribute(InternalCallerFilter.CALLER_ATTRIBUTE)).isEqualTo("order-service");
    }

    @Test
    @DisplayName("🔴 다른 CA 가 서명한 인증서는 내부가 아니다 — TLS 가 공개 CA 를 믿어도 여기서 다시 본다")
    void strangerCertIsNotInternal() throws Exception {
        assertThat(guardAllows(pass(enforce(Set.of()), internalPath(stranger)))).isFalse();
    }

    @Test
    @DisplayName("허용 주체 목록이 있으면 그 CN 만")
    void allowedSubjects() throws Exception {
        assertThat(guardAllows(pass(enforce(Set.of("user-service")), internalPath(service)))).isFalse();
        assertThat(guardAllows(pass(enforce(Set.of("order-service")), internalPath(service)))).isTrue();
    }

    @Test
    @DisplayName("게이트웨이가 신원 헤더를 붙인 요청은 건드리지 않는다 — 판정은 그 헤더로")
    void identityHeadersUntouched() throws Exception {
        MockHttpServletRequest r = internalPath();
        r.addHeader("User-Id", "7");
        r.addHeader("User-Role", "ROLE_ADMIN");
        HttpServletRequest seen = pass(enforce(Set.of()), r);
        assertThat(seen.getHeader("User-Role")).isEqualTo("ROLE_ADMIN");
        assertThat(guardAllows(seen)).isTrue();   // 관리자는 외부에서도 된다(기존 규칙)
    }

    @Test
    @DisplayName("off·observe 는 막지 않는다 — observe 는 판정만(켜기 전 확인 단계)")
    void offAndObserveDoNotBlock() throws Exception {
        var off = new InternalCallerFilter(InternalCallerFilter.Mode.OFF, null, Set.of(), List.of());
        var observe = new InternalCallerFilter(InternalCallerFilter.Mode.OBSERVE, ca, Set.of(), List.of("/internal/"));
        assertThat(guardAllows(pass(off, internalPath()))).isTrue();
        assertThat(guardAllows(pass(observe, internalPath()))).isTrue();
    }

    // ── 서비스별 신원 — 허용 주체·경로 규칙 ──────────────────────────────────

    private static InternalCallerFilter withRules(InternalCallerFilter.Mode rulesMode, Set<String> allowed,
            InternalCallerFilter.PathRule... rules) {
        return new InternalCallerFilter(InternalCallerFilter.Mode.ENFORCE, ca, allowed, List.of("/internal/"), List.of(rules), rulesMode);
    }

    private static InternalCallerFilter.PathRule rule(String prefix, String... callers) {
        return new InternalCallerFilter.PathRule(prefix, Set.of(callers));
    }

    @Test
    @DisplayName("🔴 경로 규칙 — 구매 집계는 user-service 만. order-service 인증서로는 내부가 아니다")
    void pathRuleRestrictsCaller() throws Exception {
        var f = withRules(InternalCallerFilter.Mode.ENFORCE, Set.of(),
                rule("/api/order/internal/purchase-stats", "user-service"));
        assertThat(guardAllows(pass(f, internalPath(service)))).isFalse();   // service = CN=order-service

        var ok = withRules(InternalCallerFilter.Mode.ENFORCE, Set.of(),
                rule("/api/order/internal/purchase-stats", "order-service", "user-service"));
        assertThat(guardAllows(pass(ok, internalPath(service)))).isTrue();
    }

    @Test
    @DisplayName("가장 긴 앞부분이 이긴다 — 넓은 규칙보다 구체적인 규칙")
    void longestPrefixWins() throws Exception {
        var f = withRules(InternalCallerFilter.Mode.ENFORCE, Set.of(),
                rule("/api/order/internal", "user-service"),
                rule("/api/order/internal/purchase-stats", "order-service"));
        assertThat(guardAllows(pass(f, internalPath(service)))).isTrue();
    }

    @Test
    @DisplayName("규칙이 없는 경로는 내부 CA 인증서면 된다(허용 주체 목록이 없을 때)")
    void noRuleMeansAnyInternal() throws Exception {
        var f = withRules(InternalCallerFilter.Mode.ENFORCE, Set.of(), rule("/api/coupon/internal", "order-service"));
        assertThat(guardAllows(pass(f, internalPath(service)))).isTrue();
    }

    @Test
    @DisplayName("🔴 rules-mode=observe — 어긋나도 막지 않는다(규칙을 켜기 전 확인). 공유 인증서(localhost) 로 부르는 옛 파드도 통과")
    void rulesObserveDoesNotBlock() throws Exception {
        var f = withRules(InternalCallerFilter.Mode.OBSERVE, Set.of("user-service"),
                rule("/api/order/internal/purchase-stats", "user-service"));
        HttpServletRequest seen = pass(f, internalPath(service));
        assertThat(guardAllows(seen)).isTrue();
        assertThat(seen.getAttribute(InternalCallerFilter.CALLER_ATTRIBUTE)).isEqualTo("order-service");
    }

    @Test
    @DisplayName("규칙 문자열 — 형식이 틀리면 기동을 멈춘다(사람이 적는 값)")
    void parseRules() {
        var rules = InternalCallerAutoConfiguration.parseRules(" /api/order/internal/purchase-stats=user-service ; /api/internal/stock=order-service|cart-service ");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(1).callers()).containsExactlyInAnyOrder("order-service", "cart-service");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> InternalCallerAutoConfiguration.parseRules("api/x=user-service"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> InternalCallerAutoConfiguration.parseRules("/api/x="))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("enforce 인데 CA 가 없으면 기동을 멈춘다 — 조용히 모든 내부 호출이 403 이 되지 않게")
    void enforceWithoutCaFailsFast() {
        var cfg = new InternalCallerAutoConfiguration();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> cfg.build("enforce", dir.resolve("missing.crt").toString(), "", "/internal/", "", "enforce"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("enforce");
    }
}
