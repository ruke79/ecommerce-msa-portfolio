package com.project.catalog_service.config.redis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
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

    /**
     * 파서에서 바로 읽는다 — 트리를 만들지 않는다.
     *
     * <p>🐛 2026-10-24 CPU 프로파일(docs/83 §5, 게이트웨이 4 회차): 캐시 값 풀기(catalog CPU 11.7%)의 절반이 이 클래스 안이었다. 예전에는
     * 페이지 전체를 {@code readTree} 로 트리로 만든 뒤 원소마다 {@code treeToValue} 로 다시 객체로 읽었다 — 검색 결과 바이트의 대부분인
     * 상품 목록을 두 번 읽은 셈이다({@code SinglePassJsonRedisSerializer} 가 없앤 이중 읽기의 안쪽). 저장 모양 · 읽은 값은 같다
     * (원소는 여전히 {@code @class} 로 복원) — 캐시를 비우지 않아도 된다.
     *
     * <p>[JA] パーサから直接読む — ツリーを作らない。以前はページ全体をツリーにしてから要素ごとに読み直していた(キャッシュ復元の半分)。
     */
    static class PageImplDeserializer extends JsonDeserializer<PageImpl<?>> {

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public PageImpl<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            List<Object> content = new ArrayList<>();
            int number = 0;
            int size = 0;
            Long total = null;

            // @class 를 읽은 타입 역직렬화기가 넘기면 다음 필드 이름에, 타입 정보 없이 오면 '{' 에 서 있다
            JsonToken t = p.currentToken();
            if (t == JsonToken.START_OBJECT) {
                t = p.nextToken();
            }
            for (; t == JsonToken.FIELD_NAME; t = p.nextToken()) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                boolean present = value != JsonToken.VALUE_NULL;
                switch (name) {
                    case "content" -> readContent(p, ctxt, content);
                    case "number" -> number = present ? p.getValueAsInt() : 0;
                    case "size" -> size = present ? p.getValueAsInt() : 0;
                    case "totalElements" -> total = present ? p.getValueAsLong() : null;
                    default -> p.skipChildren();   // pageable · sort · last … — 다시 셀 수 있는 값이다
                }
            }

            // size 가 0이면 PageRequest.of 가 IllegalArgumentException 을 던지므로 보정한다.
            int pageSize = size > 0 ? size : Math.max(content.size(), 1);

            return new PageImpl(content, PageRequest.of(number, pageSize), total != null ? total : content.size());
        }

        /**
         * 기본 타입 정보가 켜져 있으면 컬렉션은 배열에 프로퍼티를 넣을 수 없어
         *   ["java.util.ArrayList", [ {...}, {...} ]]
         * 형태의 wrapper array 로 직렬화된다. 그 경우 실제 원소 배열은 두 번째 항목이다. 원소 타입은 직렬화 시 기록된 타입 정보(@class)로 복원된다.
         */
        private static void readContent(JsonParser p, DeserializationContext ctxt, List<Object> content) throws IOException {
            if (p.currentToken() != JsonToken.START_ARRAY) {
                p.skipChildren();
                return;
            }
            JsonToken t = p.nextToken();
            if (t == JsonToken.VALUE_STRING) {
                String first = p.getText();
                t = p.nextToken();
                if (t == JsonToken.START_ARRAY) {
                    readElements(p, ctxt, content, p.nextToken());
                    // 바깥 배열의 끝까지(예전처럼 두 항목 모양만 wrapper 로 본다 — 그 뒤는 없다)
                    for (t = p.nextToken(); t != JsonToken.END_ARRAY && t != null; t = p.nextToken()) {
                        p.skipChildren();
                    }
                    return;
                }
                content.add(first);   // 문자열 원소의 평범한 배열
            }
            readElements(p, ctxt, content, t);
        }

        private static void readElements(JsonParser p, DeserializationContext ctxt, List<Object> content, JsonToken t)
                throws IOException {
            for (; t != JsonToken.END_ARRAY && t != null; t = p.nextToken()) {
                content.add(t == JsonToken.VALUE_NULL ? null : ctxt.readValue(p, Object.class));
            }
        }
    }
}
