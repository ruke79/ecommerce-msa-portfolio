package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 상품 캐시 직렬화기 — 배포를 넘어 남은 캐시 값을 읽을 수 있는가.
 *
 * <p>{@code products} · {@code product_cache} 등은 {@link RedisCacheConfig#permanentValueSerializer()}, 그 밖의 캐시는
 * {@link RedisCacheConfig#cacheValueSerializer()} 를 쓴다. 둘 다 예전에는 모르는 필드에서 실패해, {@code ProductDto} 등에 필드를
 * 더한 배포의 롤링 중 옛 파드가 새 파드가 쓴 값을 읽다 상품 조회가 깨졌다(DTO 두 개에만 {@code @JsonIgnoreProperties} 가 있었다).
 *
 * <p>[JA] 商品キャッシュのシリアライザ — デプロイをまたいで残ったキャッシュ値を読めるか。以前はどちらも未知のフィールドで失敗し、
 * 商品 DTO にフィールドを足したデプロイのローリング中に商品照会が壊れていた。
 */
@DisplayName("상품 캐시 직렬화기 — 다음 릴리스가 쓴 캐시 값을 옛 파드가 읽는다")
class CacheSerializerCompatibilityTest {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static GenericJackson2JsonRedisSerializer serializer(String which) {
        return "permanent".equals(which) ? RedisCacheConfig.permanentValueSerializer() : RedisCacheConfig.cacheValueSerializer();
    }

    private static ProductDto product() {
        ProductSizeDto size = ProductSizeDto.builder().id("1").size("M").skuCode("FAS-5-M").quantity(3).price(new BigDecimal("9.90")).build();
        ProductSkuDto sku = ProductSkuDto.builder().sku("FAS-5").sizes(new ArrayList<>(List.of(size))).build();
        return ProductDto.builder().id("5").name("셔츠").slug("shirt").skus(new ArrayList<>(List.of(sku))).build();
    }

    /** 맨 위 객체와 skus[0] 에 모르는 필드를 더한다(기본 타이핑의 [타입, [요소…]] 모양을 따라). */
    private static byte[] withFieldsFromNextRelease(byte[] stored) throws Exception {
        ObjectNode tree = (ObjectNode) PLAIN.readTree(stored);
        tree.put("addedInNextRelease", "x");
        JsonNode skus = tree.get("skus");
        ((ObjectNode) skus.get(1).get(0)).put("alsoNew", 1);
        return PLAIN.writeValueAsBytes(tree);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"permanent", "cache"})
    @DisplayName("🔴 ProductDto · 안의 ProductSkuDto 에 모르는 필드가 있어도 읽히고, 아는 값은 그대로다")
    void readsValueWrittenByNextRelease(String which) throws Exception {
        GenericJackson2JsonRedisSerializer s = serializer(which);

        Object back = s.deserialize(withFieldsFromNextRelease(s.serialize(product())));

        assertThat(back).isInstanceOf(ProductDto.class);
        ProductDto read = (ProductDto) back;
        assertThat(read.getName()).isEqualTo("셔츠");
        assertThat(read.getSkus()).singleElement().satisfies(sku ->
                assertThat(sku.getSizes()).singleElement().satisfies(sz -> assertThat(sz.getSkuCode()).isEqualTo("FAS-5-M")));
    }

    @Test
    @DisplayName("영구 캐시의 저장 모양은 예전 기본 직렬화기와 같다 — 이 배포의 롤링 중에도 서로 읽는다")
    void permanentKeepsTheStoredShape() throws Exception {
        byte[] before = new GenericJackson2JsonRedisSerializer().serialize(product());
        byte[] now = RedisCacheConfig.permanentValueSerializer().serialize(product());

        assertThat(PLAIN.readTree(now)).isEqualTo(PLAIN.readTree(before));
        assertThat(RedisCacheConfig.permanentValueSerializer().deserialize(before)).isInstanceOf(ProductDto.class);
    }
}
