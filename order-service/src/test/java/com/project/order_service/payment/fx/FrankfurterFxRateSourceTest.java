package com.project.order_service.payment.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Frankfurter 어댑터.
 *
 * <p>실제 API 로는 부르지 않는다 — 여기 고정하는 것은 <b>응답 모양</b>(문서 규격)과
 * <b>실패가 주문 생성을 죽이지 않는다</b>는 계약이다. 실제 응답이 다르면 아래 본문과
 * {@code parse} 를 함께 고친다.
 */
@DisplayName("실시간 환율 — Frankfurter")
class FrankfurterFxRateSourceTest {

    private static final String BODY =
            "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-09-18\","
                    + "\"rates\":{\"JPY\":147.83,\"KRW\":1352.4567891,\"EUR\":0.9}}";

    private MockRestServiceServer server;
    private RestClient client;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-18T10:00:00Z"));
    private Clock clock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.frankfurter.app");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    private FrankfurterFxRateSource source() {
        return new FrankfurterFxRateSource(client, Duration.ofHours(1), clock);
    }

    @Test
    @DisplayName("GET /latest?from=<상점 통화> 의 rates 를 읽는다 — 6자리로 반올림")
    void parsesRates() {
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        FrankfurterFxRateSource s = source();

        assertThat(s.rate("USD", "JPY")).contains(new BigDecimal("147.830000"));
        assertThat(s.rate("usd", "krw")).contains(new BigDecimal("1352.456789"));
        assertThat(s.rate("USD", "XXX")).isEmpty();
        server.verify();
    }

    /** 주문마다 API 를 부르지 않는다. TTL 안에서는 한 번. */
    @Test
    @DisplayName("TTL 안에서는 한 번만 부른다, 지나면 다시")
    void cachesForTtl() {
        server.expect(times(2), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        FrankfurterFxRateSource s = source();
        s.rate("USD", "JPY");
        s.rate("USD", "KRW");
        s.rate("USD", "JPY");
        now.set(now.get().plus(Duration.ofMinutes(61)));
        s.rate("USD", "JPY");

        server.verify();
    }

    /** 🔴 환율 API 장애가 주문 생성 장애가 되어서는 안 된다 — 비어 있음이지 예외가 아니다. */
    @Test
    @DisplayName("🔴 실패하면 비어 있다 — 예외를 던지지 않는다")
    void failureIsEmpty() {
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withServerError());

        Optional<BigDecimal> r = source().rate("USD", "JPY");

        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("실패 뒤 1분은 다시 부르지 않는다 — 장애 중에 주문마다 타임아웃을 기다리지 않는다")
    void doesNotHammerAfterFailure() {
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withServerError());

        FrankfurterFxRateSource s = source();
        s.rate("USD", "JPY");
        s.rate("USD", "JPY");
        s.rate("USD", "KRW");

        server.verify();   // 한 번만
    }

    @Test
    @DisplayName("갱신에 실패하면 옛 표를 계속 쓴다")
    void keepsStaleOnRefreshFailure() {
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withServerError());

        FrankfurterFxRateSource s = source();
        assertThat(s.rate("USD", "JPY")).isPresent();
        now.set(now.get().plus(Duration.ofHours(2)));

        assertThat(s.rate("USD", "JPY")).contains(new BigDecimal("147.830000"));
        server.verify();
    }

    @Test
    @DisplayName("응답의 base 가 요청과 다르면 믿지 않는다")
    void rejectsWrongBase() {
        server.expect(once(), requestTo("https://api.frankfurter.app/latest?from=USD"))
                .andRespond(withSuccess(BODY.replace("\"base\":\"USD\"", "\"base\":\"EUR\""),
                        MediaType.APPLICATION_JSON));

        assertThat(source().rate("USD", "JPY")).isEmpty();
    }

    @Test
    @DisplayName("같은 통화는 1 — API 를 부르지 않는다")
    void sameCurrencyIsOne() {
        assertThat(source().rate("USD", "USD")).contains(BigDecimal.ONE);
        server.verify();
    }
}
