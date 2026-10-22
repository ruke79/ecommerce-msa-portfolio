package com.project.common.config;

import java.util.Map;
import java.util.Properties;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.core.Ordered;

/**
 * 서비스 간 호출(Feign 기본 클라이언트 = JDK {@code HttpURLConnection})이 쉬는 연결을 대상마다 더 많이 남기게 한다
 * — {@code http.maxConnections} 의 기본 5 → {@value #DEFAULT}.
 *
 * <h3>왜</h3>
 *
 * <p>2026-10-18 order 부하 측정(docs/83 §5)에서 cart CPU 의 <b>23%</b> 가 catalog 로 가는 Feign 호출의 <b>새 TLS 핸드셰이크</b>였다
 * (async-profiler — {@code HttpsClient.afterConnect}, 진입점은 모두 {@code CartService} 의 catalog 조회). JDK 의 keep-alive 캐시는
 * 대상(호스트 · 포트)마다 쉬는 연결을 {@code http.maxConnections} 개(기본 5)까지만 남긴다. 동시 호출이 5 를 넘었다 줄었다 하면
 * 넘친 연결은 쓰자마자 닫히고, 다음에 동시 호출이 늘 때 새로 열린다 — 서비스 간은 mTLS 라 그때마다 ECDHE · 인증서 서명 검증이다.
 * 이 저장소에서 같은 모양으로 재 보니(HTTPS 서버 · 32 스레드 · 호출 사이 지수 분포 대기 · 1,600 호출) 기본 5 는 연결 234개,
 * 64 는 32개(스레드 수)였다 — 데모 Traefik 의 쉬는 연결 2개(docs/83 §5, maxIdleConnsPerHost)와 같은 결함이다.
 *
 * <p>⚠️ 이것만으로는 모자랐다 — 배포 뒤에도 order 는 호출마다 새 연결이었다. Spring MVC 의 JSON 은 chunked 이고, TLS 위에서는
 * Jackson 이 끝 청크 전에 닫으면 JDK 가 연결을 캐시에 넣지 않고 버린다. 그 쪽은 {@code FeignResponseBody}(응답을 끝까지 읽기)가
 * 고친다. 이 값은 그 뒤에 동시 호출이 오르내릴 때 의미가 있다.
 *
 * <h3>어떻게</h3>
 *
 * <p>JDK 는 이 속성을 keep-alive 캐시를 <b>처음 쓸 때</b> 한 번 읽는다 — {@link SpringApplication#run} 의 맨 처음(설정 서버 ·
 * Eureka 의 첫 호출보다 앞)에 정한다. 이미 정해진 값(명령행 {@code -D})은 덮지 않는다. {@code HTTP_KEEPALIVE_MAX_CONNECTIONS}
 * 로 바꿀 수 있다(양의 정수만 — 아니면 기본값). 쉬는 연결은 JDK 가 5초 뒤 닫으므로 대상에 오래 남지 않는다.
 *
 * <p>[JA] サービス間呼び出し(Feign 既定のクライアント = JDK HttpURLConnection)が宛先ごとに残す待機中の接続を 5 → 64 に。
 * 負荷計測で cart の CPU の 23% が catalog への新しい TLS ハンドシェイクだった — 同時呼び出しが 5 を超えると、溢れた接続は
 * 使ってすぐ閉じられ、次に増えたとき作り直される。JDK は初回使用時に一度だけ読むので SpringApplication.run の最初に設定する。
 */
public final class HttpKeepAlive {

    /** JDK 의 속성 — 대상마다 남기는 쉬는 연결 수. */
    public static final String PROPERTY = "http.maxConnections";
    /** 바꾸는 환경 변수. */
    public static final String ENV = "HTTP_KEEPALIVE_MAX_CONNECTIONS";
    /** 파드 하나에서 한 대상으로의 동시 호출 상한보다 넉넉히(Tomcat 스레드가 같은 대상을 부르는 수). */
    public static final int DEFAULT = 64;

    private HttpKeepAlive() {
    }

    /** 이미 정해진 속성은 그대로 · 환경 변수가 양의 정수면 그 값 · 아니면 {@value #DEFAULT}. */
    public static void apply(Map<String, String> env, Properties properties) {
        if (properties.getProperty(PROPERTY) != null) {
            return;
        }
        int n = DEFAULT;
        String raw = env.get(ENV);
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) {
                    n = v;
                }
            } catch (NumberFormatException ignored) {
                // 기본값 — 잘못된 값으로 기동을 막지 않는다
            }
        }
        properties.setProperty(PROPERTY, Integer.toString(n));
    }

    public static void apply() {
        apply(System.getenv(), System.getProperties());
    }

    /** {@code SpringApplication.run} 의 첫 단계 — 다른 리스너보다 먼저 만들어진다(spring.factories). */
    // [JA] SpringApplication.run の最初の段階 — 他のリスナーより先に作られる。
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
