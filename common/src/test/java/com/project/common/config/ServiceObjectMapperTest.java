package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.order.OrderDto;
import com.project.common.dto.order.request.OrderRequest;

/**
 * cart · order · chat 의 기본 ObjectMapper({@link RedisConfig#objectMapper}) — Boot 의 기본값으로 HTTP 응답을 쓰는가.
 *
 * <p>예전 맨 {@code new ObjectMapper()} 는 Boot 의 자동 설정 매퍼를 밀어내고 HTTP 변환기에 들어가, 날짜를 배열 · 초 단위 숫자로
 * 냈다. 화면(frontend/src/util/datetime.js 의 parseServerDate)은 ISO 문자열을 기대해 입금 기한 · 환불 · 구매 확정 · 재고 예약 ·
 * 상담 목록의 시각이 깨졌다. 여기서는 Boot 의 실제 자동 설정과 함께 띄워 HTTP 변환기가 받는 매퍼를 본다.
 *
 * <p>[JA] cart・order・chat の既定の ObjectMapper — Boot の既定値で HTTP 応答を書くか。以前の素の {@code new ObjectMapper()} は
 * 日付を配列・秒の数値で出し、画面の時刻が壊れていた。Boot の実際の自動設定と一緒に起動し、HTTP 変換器が受けるマッパーを見る。
 */
@DisplayName("서비스 기본 ObjectMapper — HTTP 응답이 ISO 날짜 · 모르는 필드 무시")
class ServiceObjectMapperTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 9, 21, 10, 0);
    private static final Instant AT = Instant.parse("2026-10-14T09:30:00.123Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
            .withUserConfiguration(RedisConfig.class);

    /** 입금 기한(LocalDateTime) · 시각(Instant)이 ISO 문자열이고, 모르는 필드가 있는 요청 본문을 읽는다. */
    private static void assertServiceDefaults(ObjectMapper m) throws Exception {
        JsonNode order = m.valueToTree(OrderDto.builder().depositDeadline(DEADLINE).stockHoldUntil(DEADLINE).build());
        assertThat(order.get("depositDeadline").isTextual()).as("배열이면 화면이 \"2026,9,21,10,0\" 을 보인다: %s",
                order.get("depositDeadline")).isTrue();
        assertThat(order.get("depositDeadline").asText()).isEqualTo("2026-09-21T10:00:00");
        assertThat(order.get("stockHoldUntil").asText()).isEqualTo("2026-09-21T10:00:00");

        JsonNode at = m.valueToTree(Map.of("lastMessageAt", AT));
        assertThat(at.get("lastMessageAt").asText()).as("초 단위 숫자면 화면이 1970-01-21 을 보인다")
                .isEqualTo("2026-10-14T09:30:00.123Z");

        OrderRequest read = m.readValue("{\"paymentMethod\":\"paypal\",\"addedByNewerFrontend\":1}", OrderRequest.class);
        assertThat(read.getPaymentMethod()).isEqualTo("paypal");
    }

    @Test
    @DisplayName("🔴 HTTP 변환기가 받는 매퍼(= 주입되는 ObjectMapper)가 ISO 날짜를 쓰고 모르는 필드를 무시한다")
    void httpConverterUsesServiceDefaults() {
        runner.run(ctx -> {
            ObjectMapper injected = ctx.getBean(ObjectMapper.class);   // 둘(objectMapper · cacheObjectMapper) 중 모호하지 않다
            assertThat(injected).isSameAs(ctx.getBean("objectMapper"));
            assertThat(injected).isNotSameAs(ctx.getBean("cacheObjectMapper"));

            ObjectMapper http = ctx.getBean(MappingJackson2HttpMessageConverter.class).getObjectMapper();
            assertThat(http).isSameAs(injected);
            assertServiceDefaults(http);
        });
    }

    @Test
    @DisplayName("spring.jackson.* 설정이 먹는다 — Boot 의 빌더로 만든다")
    void springJacksonPropertiesApply() {
        runner.withPropertyValues("spring.jackson.serialization.indent-output=true")
                .run(ctx -> assertThat(ctx.getBean(ObjectMapper.class).writeValueAsString(Map.of("a", 1))).contains("\n"));
    }

    @Test
    @DisplayName("빌더가 없는 문맥에서도 같은 기본값")
    void sameDefaultsWithoutBootBuilder() throws Exception {
        ObjectMapper m = new RedisConfig().objectMapper(
                new StaticListableBeanFactory().getBeanProvider(Jackson2ObjectMapperBuilder.class));
        assertServiceDefaults(m);
    }
}
