package com.project.catalog_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.EventDtos.EventDetailView;
import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductQADto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 상품 목록 응답(검색 · 첫 화면 · 기획전)은 카드가 읽는 필드만 — docs/83 §5(2026-10-24 프로파일: 응답 JSON 쓰기 17.6%, 보내는 양이 비용).
 *
 * <p>지키는 것: ① 목록 세 응답에서 상품은 허용 목록만(설명 · 상세 · 문답 · 분류 · 사이즈 코드가 없다). ② 화면(ProductCard · stock ·
 * pricing)과 k6(purchasable)가 읽는 필드는 남는다. ③ 상품 상세 등 다른 응답은 전체 그대로. ④ 설정이 실제 Jackson 변환기에 건다.
 *
 * <p>[JA] 商品一覧の応答はカードが読むフィールドだけ。画面と k6 が読むフィールドは残り、商品詳細など他の応答は全体のまま。
 */
@DisplayName("상품 목록 응답 — 카드 필드만, 상세는 그대로")
class ProductCardResponsesTest {

    private static ProductDto product(String id) {
        CategoryDto cat = CategoryDto.builder().id("3").name("Fashion").slug("fashion").build();
        ProductSizeDto held = ProductSizeDto.builder().id("s1").size("M").skuCode("FAS-" + id + "-M").quantity(2).price(new BigDecimal("39.90")).build();
        held.setOnHold(1);
        ProductSkuDto sku = ProductSkuDto.builder().id("k" + id).sku("FAS-" + id).discount(10).sold(7)
                .images(new ArrayList<>(List.of("https://img/" + id + ".jpg")))
                .color(ProductColorDto.builder().id("9").color("#aa3344").colorImage("https://img/c9.jpg").build())
                .sizes(new ArrayList<>(List.of(held,
                        ProductSizeDto.builder().id("s2").size("L").skuCode("FAS-" + id + "-L").quantity(5).price(new BigDecimal("41.00")).build())))
                .build();
        return ProductDto.builder().id(id).name("셔츠 " + id).slug("shirt-" + id).status("ACTIVE")
                .description("긴 설명").brand("Acme").category(cat).subCategories(new ArrayList<>())
                .details(new ArrayList<>(List.of(ProductDetailsDto.builder().name("소재").value("면").build())))
                .questions(new ArrayList<>(List.of(ProductQADto.builder().question("q").answer("a").build())))
                .skus(new ArrayList<>(List.of(sku))).refundPolicy("30 days").rating(4.5f).shipping("0.00").createdAt("2026-01-01")
                .build();
    }

    private static MappingJackson2HttpMessageConverter converter() {
        ObjectMapper base = Jackson2ObjectMapperBuilder.json().build();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(base);
        ProductCardResponses.register(converter, ProductCardResponses.cardMapper(base));
        return converter;
    }

    private static JsonNode write(MappingJackson2HttpMessageConverter converter, Object body) throws Exception {
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        converter.write(body, MediaType.APPLICATION_JSON, out);
        return new ObjectMapper().readTree(out.getBodyAsBytes());
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 상품 하나가 카드 필드만 — 그리고 화면 · k6 가 읽는 값은 그대로. */
    private static void assertCard(JsonNode p, String id) {
        assertThat(keys(p)).as("상품 — 허용 목록만").containsExactlyInAnyOrder("id", "name", "slug", "status", "skus");
        assertThat(p.get("id").asText()).isEqualTo(id);
        assertThat(p.get("slug").asText()).isEqualTo("shirt-" + id);
        assertThat(p.get("status").asText()).as("stockStateOf 의 SOLD_OUT 판정").isEqualTo("ACTIVE");
        JsonNode sku = p.get("skus").get(0);
        assertThat(keys(sku)).containsExactlyInAnyOrder("id", "images", "discount", "color", "sizes");
        assertThat(sku.get("discount").asInt()).isEqualTo(10);
        assertThat(sku.get("images").get(0).asText()).isEqualTo("https://img/" + id + ".jpg");
        assertThat(keys(sku.get("color"))).as("색 견본").containsExactlyInAnyOrder("id", "color", "colorImage");
        JsonNode held = sku.get("sizes").get(0);
        assertThat(keys(held)).as("사이즈 — 가격 · 재고 배지").containsExactlyInAnyOrder("size", "price", "quantity", "onHold");
        assertThat(held.get("price").decimalValue()).isEqualByComparingTo("39.90");
        assertThat(held.get("quantity").asInt()).isEqualTo(2);
        assertThat(held.get("onHold").asInt()).isEqualTo(1);
        assertThat(keys(sku.get("sizes").get(1))).as("잡힌 수 0 은 싣지 않는다(원래 규칙)").containsExactlyInAnyOrder("size", "price", "quantity");
    }

    @Test
    @DisplayName("🔴 검색 결과의 상품은 카드 필드만 — 설명 · 상세 · 문답 · 분류 · 사이즈 코드가 없다, 메뉴 · 개수는 그대로")
    void searchResultCarriesCardsOnly() throws Exception {
        SearchResultDto dto = SearchResultDto.builder()
                .product(new PageImpl<>(new ArrayList<>(List.of(product("1"), product("2"))), PageRequest.of(0, 20), 2))
                .colors(new ArrayList<>(List.of("red"))).brandsDB(new ArrayList<>(List.of("Acme"))).totalProducts(2)
                .build();

        JsonNode json = write(converter(), dto);

        JsonNode content = json.get("product").get("content");
        assertThat(content).hasSize(2);
        assertCard(content.get(0), "1");
        assertCard(content.get(1), "2");
        assertThat(json.get("colors").get(0).asText()).as("메뉴는 상품이 아니다 — 그대로").isEqualTo("red");
        assertThat(json.get("totalProducts").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 첫 화면 · 기획전 상세의 상품도 카드 필드만")
    void homeAndEventCarryCardsOnly() throws Exception {
        MappingJackson2HttpMessageConverter converter = converter();
        HomeView home = new HomeView(List.of(new HomeSectionView(1L, "신상", "GRID", "3", null, List.of(product("7")))));
        JsonNode homeJson = write(converter, home);
        assertCard(homeJson.get("sections").get(0).get("products").get(0), "7");
        assertThat(homeJson.get("sections").get(0).get("title").asText()).isEqualTo("신상");

        JsonNode eventJson = write(converter, new EventDetailView(null, List.of(product("8"))));
        assertCard(eventJson.get("products").get(0), "8");
    }

    @Test
    @DisplayName("상품 상세 등 다른 응답은 전체 그대로 — 설명 · 상세 · 문답 · 사이즈 코드가 있다")
    void otherResponsesStayWhole() throws Exception {
        MappingJackson2HttpMessageConverter converter = converter();
        JsonNode detail = write(converter, product("5"));
        assertThat(keys(detail)).contains("description", "details", "questions", "category", "refundPolicy", "shipping", "rating", "brand");
        assertThat(keys(detail.get("skus").get(0))).contains("sku", "sold");
        assertThat(keys(detail.get("skus").get(0).get("sizes").get(0))).contains("id", "skuCode");

        JsonNode list = write(converter, List.of(product("6")));
        assertThat(keys(list.get(0))).as("목록 타입이 아닌 응답(List<ProductDto>)도 그대로").contains("description", "details");
    }

    @Test
    @DisplayName("설정이 Jackson 변환기에만 건다 — 다른 변환기는 건드리지 않는다")
    void configurerRegistersOnJacksonConverters() throws Exception {
        ObjectMapper base = Jackson2ObjectMapperBuilder.json().build();
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(base);
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(new StringHttpMessageConverter(), json));

        new ProductCardResponses(base).extendMessageConverters(converters);

        for (Class<?> type : ProductCardResponses.CARD_RESPONSES) {
            assertThat(json.getObjectMappersForType(type)).as(type.getSimpleName()).containsKey(MediaType.ALL);
        }
        HomeView home = new HomeView(List.of(new HomeSectionView(1L, "t", "GRID", null, null, List.of(product("3")))));
        assertCard(write(json, home).get("sections").get(0).get("products").get(0), "3");
        assertThat(keys(write(json, product("4")))).as("기본 매퍼는 복사본이라 그대로").contains("description");
    }
}
