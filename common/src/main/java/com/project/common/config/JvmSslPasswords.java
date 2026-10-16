package com.project.common.config;

import java.util.Map;
import java.util.Properties;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.core.Ordered;

/**
 * JVM 기본 트러스트 · 키 스토어의 비밀번호를 <b>환경 변수에서</b> 시스템 속성으로 옮긴다.
 *
 * <p>🐛 예전에는 매니페스트가 {@code JAVA_TOOL_OPTIONS} 에 {@code -Djavax.net.ssl.trustStorePassword=$(LOCAL_KEY_STORE_PASSWORD)} 를
 * 넣었다. JVM 은 기동할 때마다 그 변수를 <b>통째로</b> {@code Picked up JAVA_TOOL_OPTIONS: …} 로 stderr 에 찍는다 — logback 을 거치지
 * 않아 마스킹도 없이 파드 로그와 Loki 에 평문으로 남았다. 같은 값이 와일드카드 개인 키 · 서비스 신원 키스토어의 비밀번호다.
 *
 * <p>이제 {@code JAVA_TOOL_OPTIONS} 에는 경로 · 형식만 두고, 비밀번호는 {@code JAVAX_NET_SSL_TRUST_STORE_PASSWORD} ·
 * {@code JAVAX_NET_SSL_KEY_STORE_PASSWORD} 로 받는다. JDK 는 기본 SSLContext 를 <b>처음 쓸 때</b> 이 속성을 읽으므로, 그보다 먼저
 * — {@link SpringApplication#run} 의 맨 처음(설정 서버 · Eureka 보다 앞) — 옮기면 된다. 명령행 등으로 이미 정해진 속성은 덮지 않는다.
 *
 * <p>[JA] JVM 既定のトラスト · キーストアのパスワードを環境変数からシステムプロパティへ移す。以前は JAVA_TOOL_OPTIONS に入れて
 * おり、JVM が起動のたびに "Picked up JAVA_TOOL_OPTIONS: …" として平文で stderr に出し、Pod ログ · Loki に残っていた。
 * 今は JAVA_TOOL_OPTIONS にはパスと形式だけを置き、SpringApplication.run の最初(設定サーバー · Eureka より前)に移す。
 */
public final class JvmSslPasswords {

    /** 환경 변수 → 시스템 속성. */
    public static final Map<String, String> ENV_TO_PROPERTY = Map.of(
            "JAVAX_NET_SSL_TRUST_STORE_PASSWORD", "javax.net.ssl.trustStorePassword",
            "JAVAX_NET_SSL_KEY_STORE_PASSWORD", "javax.net.ssl.keyStorePassword");

    private JvmSslPasswords() {
    }

    /** 비어 있지 않은 값만, 아직 정해지지 않은 속성에만 옮긴다. 값은 어디에도 남기지 않는다. */
    public static void apply(Map<String, String> env, Properties properties) {
        ENV_TO_PROPERTY.forEach((variable, property) -> {
            String value = env.get(variable);
            if (value != null && !value.isBlank() && properties.getProperty(property) == null) {
                properties.setProperty(property, value);
            }
        });
    }

    public static void apply() {
        apply(System.getenv(), System.getProperties());
    }

    /**
     * {@code SpringApplication.run} 의 첫 단계 — 다른 리스너(로깅 · 설정 데이터)보다 먼저 만들어진다(spring.factories).
     */
    // [JA] SpringApplication.run の最初の段階 — 他のリスナー(ログ · 設定データ)より先に作られる。
    public static final class RunListener implements SpringApplicationRunListener, Ordered {

        public RunListener(SpringApplication application, String[] args) {
            apply();
        }

        @Override
        public void starting(ConfigurableBootstrapContext bootstrapContext) {
            apply();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
