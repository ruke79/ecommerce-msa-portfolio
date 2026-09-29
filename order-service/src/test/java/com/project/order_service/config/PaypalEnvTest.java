package com.project.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PayPal 샌드박스 / 라이브 전환.
 *
 * <h3>🐛 스위치가 없었다</h3>
 *
 * <p>맞춰야 하는 값이 <b>세 군데</b>에 흩어져 있었다 — 서버의 호스트, 서버의
 * client id/secret, 그리고 <b>브라우저에 빌드 시점에 박히는</b>
 * {@code REACT_APP_PAYPAL_CLIENT_ID}(B-51).
 *
 * <p>셋이 어긋나도 <b>기동은 성공한다.</b> 증상은 결제 화면까지 간 뒤에야 나온다:
 * 브라우저는 샌드박스에서 결제를 만들었는데 서버는 라이브에 조회하니 "그런 주문 없음"
 * 이고, 사용자에게는 "결제했는데 반영이 안 된다"로 보인다. <b>돈이 오간 뒤에 드러나는
 * 종류의 설정 실수다.</b>
 */
@DisplayName("PayPal 환경 전환")
class PaypalEnvTest {

    private static PaypalConfig config(String env, String override) throws Exception {
        PaypalConfig config = new PaypalConfig();
        set(config, "env", env);
        set(config, "apiBaseUrlOverride", override);
        return config;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = PaypalConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 🔴 기본값은 <b>sandbox</b> 다. 설정을 깜빡한 결과가 "테스트에 붙는다"여야지
     * "실제 돈이 오간다"여서는 안 된다. mode 의 기본값이 http 인 것과 같은 방향이다 —
     * 실수의 대가가 작은 쪽으로 떨어뜨린다.
     */
    @Test
    @DisplayName("기본은 샌드박스다")
    void defaultsToSandbox() throws Exception {
        assertThat(config("sandbox", "").resolveApiBaseUrl())
                .isEqualTo(PaypalConfig.SANDBOX_URL);
    }

    @Test
    @DisplayName("live 면 라이브 호스트를 쓴다")
    void liveUsesTheLiveHost() throws Exception {
        assertThat(config("live", "").resolveApiBaseUrl())
                .isEqualTo(PaypalConfig.LIVE_URL);
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다")
    void theSwitchIsCaseInsensitive() throws Exception {
        assertThat(config("LIVE", null).resolveApiBaseUrl()).isEqualTo(PaypalConfig.LIVE_URL);
        assertThat(config("Sandbox", null).resolveApiBaseUrl()).isEqualTo(PaypalConfig.SANDBOX_URL);
    }

    /**
     * 🔴 <b>이 파일의 핵심 가드.</b> env 와 호스트가 어긋나면 기동을 막는다.
     *
     * <p>조용히 한쪽을 고르면 브라우저와 서버가 서로 다른 PayPal 을 보게 되고, 그건
     * 결제가 끝난 뒤에야 드러난다. 어느 쪽이 진짜 의도인지 코드는 알 수 없다.
     */
    @Test
    @DisplayName("env 와 호스트가 어긋나면 기동을 막는다")
    void aContradictoryHostFailsFast() throws Exception {
        assertThatThrownBy(() -> config("live", PaypalConfig.SANDBOX_URL).resolveApiBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("반대 환경");

        assertThatThrownBy(() -> config("sandbox", PaypalConfig.LIVE_URL).resolveApiBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("반대 환경");
    }

    /** 사내 프록시처럼 PayPal 호스트가 아닌 주소는 그대로 쓴다 — 막을 근거가 없다. */
    @Test
    @DisplayName("PayPal 호스트가 아닌 override 는 통과시킨다")
    void anUnrelatedHostIsAllowed() throws Exception {
        assertThat(config("sandbox", "https://paypal-proxy.internal").resolveApiBaseUrl())
                .isEqualTo("https://paypal-proxy.internal");
    }

    /** 오타는 기동 실패다. 모르는 값을 sandbox 로 떨어뜨리면 live 오타가 조용히 묻힌다. */
    @Test
    @DisplayName("모르는 env 는 기동을 막는다")
    void anUnknownEnvFailsFast() throws Exception {
        assertThatThrownBy(() -> config("production", "").resolveApiBaseUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox 또는 live");
    }
}
