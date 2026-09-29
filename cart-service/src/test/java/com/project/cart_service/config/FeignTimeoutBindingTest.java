package com.project.cart_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.RequestLine;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;

/**
 * Feign 타임아웃이 <b>실제로 걸리는지</b> — 두 단계로.
 *
 * <p>배경(docs/37 §2.1): 모든 yml 이 {@code feign.client.config.default.readTimeout: 5000} 을
 * 갖고 있었고 문서도 "5초로 무한 대기 차단"이라고 적고 있었는데, 그 키는 OpenFeign 4.x 가
 * 읽지 않는 옛 접두사였다. 실제로는 Feign 기본값 연결 10초 / 읽기 60초가 적용되고 있었다.
 * <b>설정을 바꿨다고 믿었는데 안 읽히고 있던 것</b> — 그래서 이번에는 믿지 않고 확인한다.
 *
 * <ol>
 *   <li><b>바인딩</b>: 실제 {@code application-common.yml} 을 읽어 OpenFeign 이 쓰는 바로 그
 *       클래스({@link FeignClientProperties})에 바인딩했을 때 값이 들어오는가. 접두사가
 *       틀리면 여기서 기본값(null)이 나온다 — 정확히 예전 상태다.</li>
 *   <li><b>동작</b>: 그 값으로 만든 {@link Request.Options} 가 8초 걸리는 서버 앞에서
 *       5초에 {@code Read timed out} 을 내는가. 값이 들어와도 Feign 이 그걸 무시하면 소용없다.</li>
 * </ol>
 *
 * <p>2단계는 OpenFeign 의 스프링 연결({@code FeignClientFactoryBean} 이 이 프로퍼티를
 * {@code Request.Options} 로 옮기는 부분)은 통과하지 않는다 — 그건 라이브러리 코드다.
 * 1단계가 "우리 키가 그 클래스에 도달한다"를, 2단계가 "그 값이면 5초에 끊긴다"를 각각 고정한다.
 */
@DisplayName("Feign 타임아웃 바인딩 · 동작")
class FeignTimeoutBindingTest {

    private static HttpServer slowServer;
    private static int port;

    @BeforeAll
    static void startSlowServer() throws IOException {
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext("/slow", exchange -> {
            try {
                // 읽기 타임아웃(5초)보다 확실히 길게 — 8초.
                TimeUnit.SECONDS.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        slowServer.start();
        port = slowServer.getAddress().getPort();
    }

    @AfterAll
    static void stopSlowServer() {
        if (slowServer != null) {
            slowServer.stop(0);
        }
    }

    /** 실제 공유 설정 파일을 OpenFeign 의 프로퍼티 클래스에 바인딩한다. */
    private static FeignClientProperties bindFromRealYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-common", new ClassPathResource("application-common.yml"));
        assertThat(sources).as("common 모듈의 application-common.yml 이 클래스패스에 있어야 한다").isNotEmpty();

        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);

        // ⚠️ 이 접두사 문자열이 핵심이다. OpenFeign 4.x 의 FeignClientProperties 는
        //    @ConfigurationProperties("spring.cloud.openfeign.client") 다. 옛 "feign.client" 로
        //    바인딩하면 아무것도 안 들어온다 — 그게 고치기 전 상태였다.
        return Binder.get(env)
                .bind("spring.cloud.openfeign.client", Bindable.of(FeignClientProperties.class))
                .orElseThrow(() -> new AssertionError(
                        "spring.cloud.openfeign.client.* 가 바인딩되지 않았다 — 접두사가 옛 feign.client.* 로 돌아갔는가?"));
    }

    @Test
    @DisplayName("① application-common.yml 의 값이 OpenFeign 의 FeignClientProperties 에 도달한다")
    void timeoutsBindIntoFeignClientProperties() throws IOException {
        FeignClientProperties props = bindFromRealYml();

        FeignClientProperties.FeignClientConfiguration def = props.getConfig().get("default");
        assertThat(def).as("config.default 항목").isNotNull();
        assertThat(def.getConnectTimeout()).as("connectTimeout").isEqualTo(3000);
        assertThat(def.getReadTimeout()).as("readTimeout").isEqualTo(5000);
    }

    interface SlowApi {
        @RequestLine("GET /slow")
        String slow();
    }

    @Test
    @DisplayName("② 그 값으로 만든 Feign 호출은 8초 걸리는 서버 앞에서 5초에 Read timed out 이 난다")
    void boundReadTimeoutActuallyCutsTheCall() throws IOException {
        FeignClientProperties.FeignClientConfiguration def = bindFromRealYml().getConfig().get("default");

        Request.Options options = new Request.Options(
                def.getConnectTimeout(), TimeUnit.MILLISECONDS,
                def.getReadTimeout(), TimeUnit.MILLISECONDS,
                true);

        SlowApi api = Feign.builder()
                .options(options)
                .retryer(Retryer.NEVER_RETRY)      // 재시도가 끼면 시간 측정이 흐려진다
                .target(SlowApi.class, "http://127.0.0.1:" + port);

        long started = System.nanoTime();
        assertThatThrownBy(api::slow)
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // 5초 근처에서 끊겨야 한다. 60초(기본값)였다면 서버가 8초에 응답해 버려 예외 자체가 안 난다.
        assertThat(elapsedMs)
                .as("readTimeout 5000ms 근처에서 끊겨야 한다 (실측 %dms)", elapsedMs)
                .isBetween(4_500L, 7_500L);
    }
}
