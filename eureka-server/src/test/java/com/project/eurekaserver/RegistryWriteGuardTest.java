package com.project.eurekaserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.UrlPathHelper;

/**
 * {@link RegistryWriteGuard} 의 판정 — 모드 · 메서드 · 경로(인증서가 걸린 실제 TLS 는 {@link RegistryWriteGuardTlsTest}).
 *
 * <p>[JA] RegistryWriteGuard の判定 — モード・メソッド・パス。
 */
@DisplayName("Eureka 쓰기 판정 — 모드 · 메서드 · 경로")
class RegistryWriteGuardTest {

    /** 인증서 없는 요청을 통과시켜 본다 — 다음 필터로 넘어갔으면 true. */
    private static boolean passes(RegistryWriteGuard.Mode mode, String method, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new RegistryWriteGuard(mode, null).doFilter(req, res, chain);
        return chain.getRequest() != null && res.getStatus() != 403;
    }

    @Test
    @DisplayName("🔴 enforce — 인증서 없는 등록 · 상태 변경 · 취소는 403")
    void enforceRejectsAnonymousWrites() throws Exception {
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "POST", "/eureka/apps/ORDER-SERVICE")).isFalse();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "PUT", "/eureka/apps/ORDER-SERVICE/i1/status")).isFalse();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "DELETE", "/eureka/apps/ORDER-SERVICE/i1")).isFalse();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "post", "/eureka/peerreplication/batch")).isFalse();
    }

    @Test
    @DisplayName("enforce 라도 읽기 · Eureka 밖 경로는 그대로 — 대시보드 · 등록부 조회 · 프로브")
    void readsAndOtherPathsPass() throws Exception {
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "GET", "/eureka/apps")).isTrue();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "GET", "/eureka/apps/delta")).isTrue();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "HEAD", "/eureka/apps/ORDER-SERVICE")).isTrue();
        assertThat(passes(RegistryWriteGuard.Mode.ENFORCE, "POST", "/actuator/health")).isTrue();
    }

    @Test
    @DisplayName("observe 는 남기고 통과 · off 는 보지 않는다 — 켜기 전 단계")
    void observeAndOffPass() throws Exception {
        assertThat(passes(RegistryWriteGuard.Mode.OBSERVE, "PUT", "/eureka/apps/ORDER-SERVICE/i1/status")).isTrue();
        assertThat(passes(RegistryWriteGuard.Mode.OFF, "PUT", "/eureka/apps/ORDER-SERVICE/i1/status")).isTrue();
    }

    @Test
    @DisplayName("🔒 앱 이름은 디코딩 · ;… 제거 뒤에 본다 — 인코딩으로 CN 비교를 비껴가지 못한다")
    void targetAppIsDecoded() {
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/eureka/apps/ORDER%2DSERVICE;x=1/i1/status");
        String path = new UrlPathHelper().getPathWithinApplication(req);
        assertThat(RegistryWriteGuard.targetApp(path)).isEqualTo("ORDER-SERVICE");
        assertThat(RegistryWriteGuard.targetApp("/eureka/apps/USER-SERVICE")).isEqualTo("USER-SERVICE");
        assertThat(RegistryWriteGuard.targetApp("/eureka/peerreplication/batch")).isNull();
    }

    @Test
    @DisplayName("🔴 CN 과 앱 이름이 같아야 한다(대소문자 무시) — 다른 서비스 · 인증서 없음 · 앱 밖 쓰기는 거절 이유가 있다")
    void violation() {
        assertThat(RegistryWriteGuard.violation("order-service", "ORDER-SERVICE")).isNull();
        assertThat(RegistryWriteGuard.violation("cart-service", "ORDER-SERVICE")).isNotNull();
        assertThat(RegistryWriteGuard.violation(null, "ORDER-SERVICE")).isNotNull();
        assertThat(RegistryWriteGuard.violation("order-service", null)).isNotNull();
    }

    @Test
    @DisplayName("enforce 인데 내부 CA 를 읽지 못하면 기동을 멈춘다 · off 는 CA 가 없어도 뜬다")
    void enforceNeedsCa() {
        assertThatThrownBy(() -> RegistryWriteGuardConfig.build("enforce", "/nonexistent/ca.crt"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RegistryWriteGuardConfig.build("off", "").getFilter().mode()).isEqualTo(RegistryWriteGuard.Mode.OFF);
        assertThat(RegistryWriteGuardConfig.build(" Observe ", "").getFilter().mode()).isEqualTo(RegistryWriteGuard.Mode.OBSERVE);
    }
}
