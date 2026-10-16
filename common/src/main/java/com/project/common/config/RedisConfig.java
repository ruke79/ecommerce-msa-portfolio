package com.project.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Configuration
public class RedisConfig {

    /**
     * 서비스의 기본 ObjectMapper — HTTP 응답 · STOMP · 아웃박스 페이로드 · 외부 API 응답 읽기가 쓴다.
     *
     * <p>🐛 예전에는 맨 {@code new ObjectMapper()} 였다. 이 빈이 있으면 Boot 의 자동 설정 매퍼가 만들어지지 않아(같은 타입이 있으면
     * 물러난다) 이 클래스를 스캔하는 cart · order · chat 은 Boot 의 기본값을 하나도 받지 못했다.
     * <ul>
     *   <li>날짜가 배열 · 초 단위 숫자로 나갔다 — {@code LocalDateTime} 은 {@code [2026,9,21,10,0]}, {@code Instant} 는
     *       {@code 1760434200.123}. 화면({@code util/datetime.js} 의 parseServerDate)은 ISO 문자열을 기대하고 숫자는 밀리초로 읽어,
     *       입금 기한이 "2026,9,21,10,0" 으로, 관리자 환불 · 구매 확정 시각이 "-" 로, 재고 예약 안내가 아예 안 보였고, 상담 목록 ·
     *       상담 데스크의 시각은 1970-01-21 로 보였다.</li>
     *   <li>모르는 필드에서 실패했다 — 요청 본문에 필드 하나가 더 오거나 외부 API(PortOne · Stripe · 환율) 응답에 필드가 늘면 400 ·
     *       오류였다.</li>
     *   <li>{@code spring.jackson.*} 설정이 먹지 않았다.</li>
     * </ul>
     * 이제 Boot 의 빌더로 만든다 — 자동 설정과 같은 기본값(ISO 날짜, 모르는 필드 무시, 모듈 자동 등록)과 {@code spring.jackson.*}.
     * 빌더가 없는 문맥(웹이 아닌 단독 사용)에서도 같은 두 기본값을 직접 건다. {@code @Primary} — 캐시 매퍼와 타입이 같아 이름으로만
     * 고르던 주입을 분명히 한다(캐시 매퍼는 {@code @Qualifier("cacheObjectMapper")} 로만 받는다).
     *
     * <p>[JA] サービスの既定の ObjectMapper — HTTP 応答・STOMP・アウトボックスのペイロード・外部 API 応答の読み取りが使う。以前は素の
     * {@code new ObjectMapper()} で、この Bean があると Boot の自動設定のマッパーが作られず(同じ型があれば引く)、cart・order・chat は
     * Boot の既定値を受けていなかった — 日付が配列・秒の数値で出て画面が壊れ(入金期限が "2026,9,21,10,0"、時刻が "-"、相談一覧が
     * 1970-01-21)、未知のフィールドで失敗し、{@code spring.jackson.*} も効かなかった。今は Boot のビルダーで作る。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(ObjectProvider<Jackson2ObjectMapperBuilder> builder) {
        Jackson2ObjectMapperBuilder b = builder.getIfAvailable();
        if (b == null) {
            // Boot 의 자동 설정이 거는 것과 같다(JacksonAutoConfiguration 의 기본 featureDefaults). 빌더의 json() 은 모르는 필드
            // 무시 · java.time 모듈 등록까지만 한다. ／ [JA] Boot の自動設定と同じ既定値を直接掛ける。
            b = Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                            SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        }
        return b.build();
    }

    @Bean
    public ObjectMapper cacheObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.project.")
                .allowIfSubType(ArrayList.class)
                .allowIfSubType(LinkedHashMap.class)
                .allowIfSubType(LinkedHashSet.class)
                .allowIfSubType(HashMap.class)
                .allowIfSubType(HashSet.class)
                .allowIfSubType(LocalDateTime.class)
                .allowIfSubType(BigDecimal.class)
                .allowIfSubType(Instant.class)
                .allowIfSubType(Optional.class)
                .build();
        objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        // 모르는 필드는 무시한다. 캐시는 배포를 넘어 남는다 — DTO(CartDto 등)에 필드를 더하거나 뺀 배포의 롤링 중, 옛 파드가 새 값을
        // (새 파드가 옛 값을) 읽다 UnrecognizedPropertyException 으로 장바구니 조회가 깨진다. 맨 ObjectMapper 는 Boot 의 관대한 기본값을
        // 받지 않아 이것이 켜져 있었다(order-service RedisCacheConfig 는 같은 사고 뒤 껐다).
        // [JA] 未知のフィールドは無視する。キャッシュはデプロイをまたいで残る — DTO にフィールドを足す/消すデプロイのローリング中、
        //      旧 Pod が新しい値を(新 Pod が古い値を)読んで UnrecognizedPropertyException でカート照会が壊れる。
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        return objectMapper;
    }
}