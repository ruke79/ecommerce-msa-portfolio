package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

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

    /** 트리 노드를 만들 때마다 센다 — 예전 역직렬화기는 페이지 전체를 readTree 로 트리로 만들었다. */
    static final class CountingNodeFactory extends JsonNodeFactory {
        final AtomicInteger made = new AtomicInteger();

        @Override
        public ObjectNode objectNode() {
            made.incrementAndGet();
            return super.objectNode();
        }

        @Override
        public ArrayNode arrayNode() {
            made.incrementAndGet();
            return super.arrayNode();
        }
    }

    /** 예전 구현 그대로(트리로 읽은 뒤 원소마다 다시 읽기) — 새 구현이 같은 값을 읽는지 비교하는 기준. */
    static final class LegacyTreePageModule extends SimpleModule {
        LegacyTreePageModule() {
            addDeserializer(PageImpl.class, new JsonDeserializer<PageImpl<?>>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public PageImpl<?> deserialize(JsonParser p, DeserializationContext ctxt) throws java.io.IOException {
                    ObjectMapper mapper = (ObjectMapper) p.getCodec();
                    JsonNode node = mapper.readTree(p);
                    List<Object> content = new ArrayList<>();
                    JsonNode contentNode = node.get("content");
                    if (contentNode != null && contentNode.isArray() && contentNode.size() == 2
                            && contentNode.get(0).isTextual() && contentNode.get(1).isArray()) {
                        contentNode = contentNode.get(1);
                    }
                    if (contentNode != null && contentNode.isArray()) {
                        for (JsonNode element : contentNode) {
                            content.add(mapper.treeToValue(element, Object.class));
                        }
                    }
                    int number = node.hasNonNull("number") ? node.get("number").asInt() : 0;
                    int size = node.hasNonNull("size") ? node.get("size").asInt() : 0;
                    long total = node.hasNonNull("totalElements") ? node.get("totalElements").asLong() : content.size();
                    return new PageImpl(content, PageRequest.of(number, size > 0 ? size : Math.max(content.size(), 1)), total);
                }
            });
        }
    }

    private static SearchResultDto searchResult(int products) {
        List<ProductDto> list = new ArrayList<>();
        for (int i = 0; i < products; i++) {
            ProductSizeDto size = ProductSizeDto.builder().id("z" + i).size("M").skuCode("FAS-" + i + "-M").quantity(3).price(new BigDecimal("9.90")).build();
            ProductSkuDto sku = ProductSkuDto.builder().id("k" + i).sku("FAS-" + i).discount(5).images(new ArrayList<>(List.of("a.jpg", "b.jpg")))
                    .color(ProductColorDto.builder().id("1").color("#fff").build()).sizes(new ArrayList<>(List.of(size))).build();
            list.add(ProductDto.builder().id("" + i).name("셔츠 " + i).slug("shirt-" + i).skus(new ArrayList<>(List.of(sku))).build());
        }
        return SearchResultDto.builder().product(new PageImpl<>(list, PageRequest.of(1, 20), 45))
                .colors(new ArrayList<>(List.of("red"))).totalProducts(45).build();
    }

    @Test
    @DisplayName("🔴 Page 를 트리 없이 한 번에 읽는다 — 예전에는 상품 목록 전체를 트리로 만든 뒤 다시 읽었다(docs/83 §5)")
    void readsPageWithoutBuildingATree() throws Exception {
        ObjectMapper mapper = cacheMapper();
        CountingNodeFactory nodes = new CountingNodeFactory();
        mapper.setNodeFactory(nodes);
        byte[] stored = mapper.writeValueAsBytes(searchResult(20));

        Object restored = mapper.readValue(stored, Object.class);

        assertThat(((SearchResultDto) restored).getProduct().getContent()).hasSize(20);
        assertThat(nodes.made).as("트리 노드를 만들지 않는다").hasValue(0);
    }

    @Test
    @DisplayName("읽은 값이 예전 구현과 같다 — 상품 · 쪽 번호 · 크기 · 총 개수(옛 파드가 쓴 캐시를 그대로 읽는다)")
    void sameValuesAsLegacyTreeReader() throws Exception {
        ObjectMapper now = cacheMapper();
        ObjectMapper legacy = new ObjectMapper();
        legacy.registerModule(new JavaTimeModule());
        legacy.registerModule(new LegacyTreePageModule());
        legacy.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        legacy.activateDefaultTypingAsProperty(legacy.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL, "@class");
        ObjectMapper plain = new ObjectMapper();

        for (Object value : List.<Object>of(searchResult(20), searchResult(0),
                new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 5), 2), new PageImpl<>(new ArrayList<>(List.of(3L, 4L)), PageRequest.of(3, 2), 99))) {
            byte[] stored = now.writeValueAsBytes(value);
            Object byNow = now.readValue(stored, Object.class);
            Object byLegacy = legacy.readValue(stored, Object.class);
            assertThat(byNow).hasSameClassAs(byLegacy);
            assertThat(plain.readTree(now.writeValueAsBytes(byNow))).as(value.getClass().getSimpleName())
                    .isEqualTo(plain.readTree(now.writeValueAsBytes(byLegacy)));
        }
    }
}
