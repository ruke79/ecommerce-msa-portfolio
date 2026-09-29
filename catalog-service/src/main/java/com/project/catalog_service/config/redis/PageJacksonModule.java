package com.project.catalog_service.config.redis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Redis 캐시가 {@link PageImpl} 을 되읽을 수 있게 해 주는 Jackson 모듈.
 *
 * <p>배경(docs/16 B-27): 검색 결과 DTO 는 {@code Page<ProductDto>} 를 담고 있고
 * {@code @Cacheable("search_cache")} 로 Redis 에 JSON 직렬화된다. 그런데 {@code PageImpl} 에는
 * 기본 생성자도 {@code @JsonCreator} 도 없어 <b>역직렬화가 불가능</b>했다. 결과적으로
 *
 * <pre>
 *   1회차 검색 → 캐시 미스 → 정상 응답 + 캐시 저장
 *   2회차 검색 → 캐시 히트 → SerializationException → HTTP 500
 * </pre>
 *
 * 즉 <b>같은 검색어는 TTL 동안 계속 500</b> 이 되는 상태였다. 실제 Elasticsearch 를 붙여
 * 두 번 호출해 봐야만 드러나는 결함이다.
 *
 * <p>응답 형태(wire format)를 바꾸지 않기 위해 DTO 를 고치는 대신 역직렬화기를 제공한다.
 */
public class PageJacksonModule extends SimpleModule {

    public PageJacksonModule() {
        addDeserializer(PageImpl.class, new PageImplDeserializer());
    }

    private static class PageImplDeserializer extends JsonDeserializer<PageImpl<?>> {

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public PageImpl<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            JsonNode node = mapper.readTree(p);

            List<Object> content = new ArrayList<>();
            JsonNode contentNode = node.get("content");
            // 기본 타입 정보가 켜져 있으면 컬렉션은 배열에 프로퍼티를 넣을 수 없어
            //   ["java.util.ArrayList", [ {...}, {...} ]]
            // 형태의 wrapper array 로 직렬화된다. 그 경우 실제 원소 배열은 두 번째 항목이다.
            if (contentNode != null && contentNode.isArray()
                    && contentNode.size() == 2
                    && contentNode.get(0).isTextual()
                    && contentNode.get(1).isArray()) {
                contentNode = contentNode.get(1);
            }
            if (contentNode != null && contentNode.isArray()) {
                for (JsonNode element : contentNode) {
                    // 원소 타입은 직렬화 시 기록된 타입 정보(@class)로 복원된다.
                    content.add(mapper.treeToValue(element, Object.class));
                }
            }

            int number = node.hasNonNull("number") ? node.get("number").asInt() : 0;
            int size = node.hasNonNull("size") ? node.get("size").asInt() : 0;
            long total = node.hasNonNull("totalElements")
                    ? node.get("totalElements").asLong()
                    : content.size();

            // size 가 0이면 PageRequest.of 가 IllegalArgumentException 을 던지므로 보정한다.
            int pageSize = size > 0 ? size : Math.max(content.size(), 1);

            return new PageImpl(content, PageRequest.of(number, pageSize), total);
        }
    }
}
