package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;

/**
 * 공용 캐시 매퍼({@link RedisConfig#cacheObjectMapper()}) — 배포를 넘어 남은 캐시 값을 읽을 수 있는가.
 *
 * <p>cart-service 의 RedisTemplate · CacheManager 가 이 매퍼로 {@code CartDto} 를 쓰고 읽는다. 맨 {@code ObjectMapper} 는 모르는 필드에서
 * 실패해, DTO 에 필드를 더한 배포의 롤링 중 옛 파드가 새 파드가 쓴 값을 읽다 {@code UnrecognizedPropertyException} 이 났다.
 *
 * <p>[JA] 共用キャッシュマッパー — デプロイをまたいで残ったキャッシュ値を読めるか。素の {@code ObjectMapper} は未知のフィールドで
 * 失敗し、DTO にフィールドを足したデプロイのローリング中に旧 Pod が新 Pod の書いた値を読んで例外になっていた。
 */
@DisplayName("공용 캐시 매퍼 — 다음 릴리스가 쓴 캐시 값을 옛 파드가 읽는다")
class CacheObjectMapperCompatibilityTest {

    private final ObjectMapper mapper = new RedisConfig().cacheObjectMapper();

    @Test
    @DisplayName("🔴 CartDto · 안의 CartProductDto 에 모르는 필드가 있어도 읽히고, 아는 값은 그대로다")
    void readsValueWrittenByNextRelease() throws Exception {
        CartDto cart = CartDto.builder().userId("7").cartTotal(new BigDecimal("12.50"))
                .products(new ArrayList<>(List.of(CartProductDto.builder().id("10").productId("5").skuCode("FAS-5-M").qty(2).build())))
                .build();

        ObjectNode tree = (ObjectNode) mapper.readTree(mapper.writeValueAsString(cart));
        tree.put("addedInNextRelease", "x");
        // products 는 [타입, [요소…]] 모양(기본 타이핑) — 요소에도 새 필드를 더한다
        ObjectNode line = (ObjectNode) ((ArrayNode) tree.get("products").get(1)).get(0);
        line.put("alsoNew", 1);

        Object back = mapper.readValue(mapper.writeValueAsString(tree), Object.class);

        assertThat(back).isInstanceOf(CartDto.class);
        CartDto read = (CartDto) back;
        assertThat(read.getUserId()).isEqualTo("7");
        assertThat(read.getCartTotal()).isEqualByComparingTo("12.50");
        assertThat(read.getProducts()).singleElement().satisfies(p -> {
            assertThat(p.getSkuCode()).isEqualTo("FAS-5-M");
            assertThat(p.getQty()).isEqualTo(2);
        });
    }
}
