package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NullValue;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 캐시 값을 한 번만 읽는다 — docs/83 §5(2026-10-23 CPU 프로파일: 캐시 풀기 13.6%, 그중 같은 JSON 을 다시 읽는 몫 5.2%).
 *
 * <p>둘을 지킨다. ① 한 번만 읽는다 — 파서를 몇 번 여는지 센다(Spring Data Redis 기본은 타입을 찾느라 두 번). ② 저장 모양 · 읽은 값이
 * 예전 직렬화기와 같다 — 옛 파드가 쓴 캐시를 새 파드가 그대로 읽고(그 반대도), 그래서 배포 뒤 캐시를 비우지 않아도 된다.
 *
 * <p>[JA] キャッシュ値を一度だけ読む。パーサを開く回数を数え、保存形式と読んだ値が以前のシリアライザと同じことを確かめる。
 */
@DisplayName("캐시 값 직렬화기 — 한 번만 읽고, 예전 직렬화기와 서로 읽는다")
class SinglePassJsonRedisSerializerTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    /** 바이트로 파서를 열 때마다 센다 — 값 읽기와 타입 찾기의 트리 읽기가 모두 여기를 지난다(공개 createParser 의 모든 겹침이 이리 온다). */
    static final class CountingFactory extends JsonFactory {
        final AtomicInteger opened = new AtomicInteger();

        @Override
        protected JsonParser _createParser(byte[] data, int offset, int len, com.fasterxml.jackson.core.io.IOContext ctxt)
                throws java.io.IOException {
            opened.incrementAndGet();
            return super._createParser(data, offset, len, ctxt);
        }
    }

    private static ProductDto product(String id) {
        ProductSizeDto size = ProductSizeDto.builder().id("1").size("M").skuCode("FAS-" + id + "-M").quantity(3).price(new BigDecimal("9.90")).build();
        ProductSkuDto sku = ProductSkuDto.builder().sku("FAS-" + id).sizes(new ArrayList<>(List.of(size))).build();
        return ProductDto.builder().id(id).name("셔츠 " + id).slug("shirt-" + id).skus(new ArrayList<>(List.of(sku))).build();
    }

    /** search_cache 에 실제로 들어가는 모양 — 상품 쪽(Page) · 메뉴 목록. */
    private static SearchResultDto searchResult() {
        return SearchResultDto.builder()
                .product(new PageImpl<>(new ArrayList<>(List.of(product("1"), product("2"))), PageRequest.of(0, 20), 2))
                .colors(new ArrayList<>(List.of("red", "blue")))
                .brandsDB(new ArrayList<>(List.of("acme")))
                .totalProducts(2)
                .build();
    }

    @Test
    @DisplayName("🔴 search_cache 값을 읽을 때 파서를 한 번만 연다(예전 직렬화기는 두 번)")
    void readsOnce() {
        CountingFactory before = new CountingFactory();
        CountingFactory now = new CountingFactory();
        GenericJackson2JsonRedisSerializer old = new GenericJackson2JsonRedisSerializer(RedisCacheConfig.cacheValueMapper(new ObjectMapper(before)));
        GenericJackson2JsonRedisSerializer single = new RedisCacheConfig.SinglePassJsonRedisSerializer(RedisCacheConfig.cacheValueMapper(new ObjectMapper(now)));
        byte[] stored = old.serialize(searchResult());

        old.deserialize(stored);
        single.deserialize(stored);

        assertThat(before.opened).as("예전: 타입 찾기(readTree) + 값 읽기").hasValue(2);
        assertThat(now.opened).hasValue(1);
    }

    @Test
    @DisplayName("search_cache · products 둘 다 — 예전 직렬화기가 쓴 값을 같은 값으로 읽고, 쓰는 모양도 같다")
    void sameValuesAndShapeAsBefore() throws Exception {
        GenericJackson2JsonRedisSerializer oldCache = new GenericJackson2JsonRedisSerializer(RedisCacheConfig.cacheValueMapper(new ObjectMapper()));
        GenericJackson2JsonRedisSerializer oldPermanent = new GenericJackson2JsonRedisSerializer()
                .configure(m -> m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        List<Object[]> pairs = List.of(
                new Object[] {oldCache, RedisCacheConfig.cacheValueSerializer(), searchResult()},
                new Object[] {oldCache, RedisCacheConfig.cacheValueSerializer(), new ArrayList<>(List.of("red", "blue"))},
                new Object[] {oldPermanent, RedisCacheConfig.permanentValueSerializer(), product("5")},
                new Object[] {oldPermanent, RedisCacheConfig.permanentValueSerializer(), new ArrayList<>(List.of(product("6"), product("7")))},
                new Object[] {oldPermanent, RedisCacheConfig.permanentValueSerializer(), NullValue.INSTANCE});

        for (Object[] p : pairs) {
            GenericJackson2JsonRedisSerializer old = (GenericJackson2JsonRedisSerializer) p[0];
            GenericJackson2JsonRedisSerializer now = (GenericJackson2JsonRedisSerializer) p[1];
            byte[] byOld = old.serialize(p[2]);
            byte[] byNow = now.serialize(p[2]);
            assertThat(PLAIN.readTree(byNow)).as("저장 모양 — %s", p[2].getClass().getSimpleName()).isEqualTo(PLAIN.readTree(byOld));

            Object readByNow = now.deserialize(byOld);
            Object readByOld = old.deserialize(byOld);
            assertThat(readByNow).as("읽은 타입 — %s", p[2].getClass().getSimpleName()).hasSameClassAs(readByOld);
            // 읽은 값을 다시 써서 비교한다 — DTO 에 equals 가 없어도 모든 필드를 본다
            assertThat(PLAIN.readTree(old.serialize(readByNow))).isEqualTo(PLAIN.readTree(old.serialize(readByOld)));
        }
    }
}
