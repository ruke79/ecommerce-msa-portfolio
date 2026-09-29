package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redis 캐시가 {@code Page} 를 되읽을 수 있는지 검증한다 (docs/16 B-27).
 *
 * <p>검색 결과 DTO 는 {@code Page<ProductDto>} 를 담고 {@code @Cacheable("search_cache")} 로
 * Redis 에 JSON 저장된다. {@code PageImpl} 에는 기본 생성자도 {@code @JsonCreator} 도 없어
 * <b>역직렬화가 불가능</b>했고, 그 결과 실제 동작은 이랬다:
 *
 * <pre>
 *   1회차 검색 → 캐시 미스 → 정상 응답
 *   2회차 검색 → 캐시 히트 → SerializationException → HTTP 500  (TTL 60분 내내)
 * </pre>
 *
 * <p>즉 <b>같은 검색어는 한 번만 동작</b>했다. 실제 ES 를 붙이고 두 번 호출해야만 드러난다.
 */
class PageJacksonModuleTest {

    /** RedisCacheConfig 의 캐시 직렬화기와 동일하게 구성한 매퍼. */
    private ObjectMapper cacheMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new PageJacksonModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTypingAsProperty(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");
        return mapper;
    }

    @Test
    @DisplayName("PageImpl 을 직렬화한 뒤 다시 읽을 수 있어야 한다")
    void pageSurvivesRoundTrip() throws Exception {
        ObjectMapper mapper = cacheMapper();
        Page<String> original = new PageImpl<>(List.of("a", "b", "c"), PageRequest.of(0, 20), 3);

        String json = mapper.writeValueAsString(original);
        Object restored = mapper.readValue(json, Object.class);

        assertThat(restored).isInstanceOf(Page.class);
        @SuppressWarnings("unchecked")
        Page<String> page = (Page<String>) restored;
        assertThat(page.getContent()).containsExactly("a", "b", "c");
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getNumber()).isZero();
    }

    @Test
    @DisplayName("빈 페이지도 예외 없이 복원되어야 한다")
    void emptyPageSurvivesRoundTrip() throws Exception {
        ObjectMapper mapper = cacheMapper();
        // size 가 0 이면 PageRequest.of 가 IllegalArgumentException 을 던지므로 보정 로직이 필요하다.
        Page<String> original = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        Object restored = mapper.readValue(mapper.writeValueAsString(original), Object.class);

        assertThat(restored).isInstanceOf(Page.class);
        assertThat(((Page<?>) restored).getContent()).isEmpty();
        assertThat(((Page<?>) restored).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("두 번째 페이지의 번호와 총 개수가 보존되어야 한다")
    void pagingMetadataSurvivesRoundTrip() throws Exception {
        ObjectMapper mapper = cacheMapper();
        Page<String> original = new PageImpl<>(List.of("x"), PageRequest.of(2, 10), 25);

        @SuppressWarnings("unchecked")
        Page<String> restored = (Page<String>) mapper.readValue(
                mapper.writeValueAsString(original), Object.class);

        assertThat(restored.getNumber()).isEqualTo(2);
        assertThat(restored.getSize()).isEqualTo(10);
        // PageImpl 은 마지막 페이지에서 total 을 offset+content 크기로 보정한다(여기선 21).
        // 왕복 테스트이므로 입력값이 아니라 **원본이 실제로 보고하는 값**과 비교해야 한다.
        assertThat(restored.getTotalElements()).isEqualTo(original.getTotalElements());
    }
}
