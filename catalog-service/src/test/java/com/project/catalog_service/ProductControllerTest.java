package com.project.catalog_service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.controller.ProductController;
import com.project.catalog_service.service.ProductImportService;
import com.project.catalog_service.service.ProductService;
import com.project.common.dto.ProductColorDto; // 이 import 추가
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto; // 이 import 추가
import com.project.common.response.MessageResponse; // 이 import 추가
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

// 아래의 Mockito static import를 추가하세요.
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong; // 이 import 추가
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// 아래의 MockMvcRequestBuilders static import를 추가하세요.
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put; // 이 import 추가

// 아래의 MockMvcResultMatchers static import를 추가하세요.
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



@WebMvcTest(ProductController.class)
@org.springframework.context.annotation.Import(com.project.catalog_service.service.StockAvailability.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // 컨트롤러가 의존하는 서비스를 가짜(Mock) 객체로 주입합니다.
    @MockBean
    private ProductService productService;

    // ProductController 는 CSV 일괄 등록(/products/load)을 ProductImportService 에 넘긴다.
    // @WebMvcTest 는 웹 계층 빈만 올리므로 이게 없으면 컨텍스트 로딩부터 실패해 이 클래스의
    // 테스트가 전부 깨진다. 예전에는 JobLauncher 와 Job 빈을 직접 주입받아서, 여기서도
    // 그 둘을 가짜로 세워 줘야 했다.
    @MockBean
    private ProductImportService productImportService;

    // 남은 수량에서 결제 전 예약을 빼는 StockAvailability 는 진짜를 쓰고 저장소만 가짜로 — 예약이 없으면(빈 목록) 응답이
    // 서비스가 준 그대로인지, 있으면 빠지는지를 컨트롤러 경로에서 본다(docs/59 §5).
    @MockBean
    private com.project.catalog_service.repository.jpa.data.StockReservationRepository stockReservations;

    // 지금 수량(캐시를 믿지 않는다) — 빈 목록이면 캐시의 수량 그대로.
    @MockBean
    private com.project.catalog_service.repository.jpa.data.ProductSizeRepository productSizes;

    @Autowired
    private ObjectMapper objectMapper;

      @Test
    @DisplayName("전체 상품 목록 조회 API는 200 OK를 반환해야 한다")
    void getProducts_shouldReturn200Ok() throws Exception {
        // given
        when(productService.warmUpProductCaches(20)).thenReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/api/product/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("페이지네이션 API는 200 OK와 상품 목록을 반환해야 한다")
    void getProductsWithPagination_shouldReturnProducts() throws Exception {
        // given
        ProductDto mockProduct = ProductDto.builder().id("1").name("Test Product").build();
        when(productService.getProductsByCategory(anyString(), anyLong(), anyInt()))
                .thenReturn(Collections.singletonList(mockProduct));

        // when & then
        mockMvc.perform(get("/api/product/products/page")
                .param("category", "test")
                .param("cursorId", "0")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(Collections.singletonList(mockProduct))));
    }

    @Test
    @DisplayName("productId로 상품 조회 API는 200 OK를 반환해야 한다")
    void getProductById_shouldReturnProductDto() throws Exception {
        // given
        ProductDto mockProduct = ProductDto.builder().id("1").name("Test Product").build();
        when(productService.getProductById(anyLong())).thenReturn(mockProduct);

        // when & then
        mockMvc.perform(get("/api/{productId}", 1))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(mockProduct)));
    }
    
    @Test
    @DisplayName("유효하지 않은 productId에 대해 400 Bad Request를 반환해야 한다")
    void getProductById_shouldReturn400_whenIdIsInvalid() throws Exception {
        // given
        when(productService.getProductById(anyLong())).thenThrow(new RuntimeException("Product not found"));
        
        // when & then
        mockMvc.perform(get("/api/{productId}", 999))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(objectMapper.writeValueAsString(new MessageResponse("Product not found"))));
    }

    // ── 배치 조회 /api/products/info — cart-service 가 항목마다 /api/{id} 를 부르던 것의 대체 ──

    @Test
    @DisplayName("ids 여러 개를 한 요청으로 받아 찾은 것만 목록으로 돌려준다 (못 찾은 id 는 빠질 뿐 실패가 아니다)")
    void getProductsInfo_shouldReturnFoundProducts_inOneCall() throws Exception {
        List<ProductDto> found = List.of(
                ProductDto.builder().id("1").name("A").build(),
                ProductDto.builder().id("2").name("B").build());
        // 요청은 1,2,999 인데 999 는 없다 — 그래도 200 이고 둘만 돌아온다.
        when(productService.getProductsByIds(List.of(1L, 2L, 999L))).thenReturn(found);

        mockMvc.perform(get("/api/products/info").param("ids", "1", "2", "999"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(found)));
    }

    @Test
    @DisplayName("ids 가 비었거나 상한(100)을 넘으면 400")
    void getProductsInfo_shouldReject_emptyOrOversized() throws Exception {
        mockMvc.perform(get("/api/products/info").param("ids", ""))
                .andExpect(status().isBadRequest());

        String[] tooMany = new String[ProductController.MAX_BATCH_IDS + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = Integer.toString(i + 1);
        }
        mockMvc.perform(get("/api/products/info").param("ids", tooMany))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/cart/info?items=… — 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 키로 돌려준다")
    void getCartProductsInfo_shouldReturnMapKeyedByItem() throws Exception {
        java.util.Map<String, ProductInfoDto> found = java.util.Map.of(
                "1:0:0", ProductInfoDto.builder().id("1").style(0).size("M").build(),
                "2:1:0", ProductInfoDto.builder().id("2").style(1).size("L").build());
        // 세 항목을 물었는데 셋째(3:0:0)는 없다 — 그래도 200 이고 둘만 돌아온다.
        when(productService.getCartProductsInfo(org.mockito.ArgumentMatchers.argThat(items ->
                items.size() == 3
                        && items.get(0).getProductId() == 1L && items.get(0).getStyle() == 0 && items.get(0).getSize() == 0
                        && items.get(1).getProductId() == 2L && items.get(1).getStyle() == 1
                        && items.get(2).getProductId() == 3L)))
                .thenReturn(found);

        // GET 인 이유: AdminWriteInterceptor 가 POST 에 User-Id 를 요구하는데 서비스 간 호출에는 없다.
        // 이 테스트가 POST 였을 때 정확히 그 이유로 401 이 났다.
        mockMvc.perform(get("/api/cart/info").param("items", "1:0:0", "2:1:0", "3:0:0"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(found)));
    }

    @Test
    @DisplayName("GET /api/cart/info — 빈 items 나 형식이 틀린 항목은 400")
    void getCartProductsInfo_shouldReject_emptyOrMalformed() throws Exception {
        mockMvc.perform(get("/api/cart/info").param("items", ""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/cart/info").param("items", "1:0"))          // 세 조각이 아니다
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/cart/info").param("items", "a:0:0"))        // 숫자가 아니다
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/colors/info?ids=… — 색상 여러 개를 한 번에, 못 찾은 id 는 빠질 뿐 200")
    void getColorsInfo_shouldReturnFoundColors_inOneCall() throws Exception {
        List<ProductColorDto> found = List.of(
                ProductColorDto.builder().id("1").color("red").build(),
                ProductColorDto.builder().id("2").color("blue").build());
        when(productService.getColorsByIds(List.of(1L, 2L, 999L))).thenReturn(found);

        mockMvc.perform(get("/api/colors/info").param("ids", "1", "2", "999"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(found)));

        mockMvc.perform(get("/api/colors/info").param("ids", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("/api/products/info 는 /api/{productId} 단건 라우트에 삼켜지지 않는다")
    void getProductsInfo_isNotShadowedBySingleIdRoute() throws Exception {
        // 두 세그먼트라 {productId} 한 세그먼트 패턴과 겹치지 않아야 한다. 겹치면 "products" 를
        // Long 으로 파싱하다 400 이 난다 — 그 경우를 단건 400 과 구분하기 위해 서비스 호출로 확인한다.
        when(productService.getProductsByIds(List.of(7L))).thenReturn(List.of());
        mockMvc.perform(get("/api/products/info").param("ids", "7"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("slug로 상품 조회 API는 200 OK를 반환해야 한다")
    void getProductInfo_shouldReturnProductInfo() throws Exception {
        // given
        ProductDto mockProduct = ProductDto.builder().slug("test-slug").build();
        when(productService.getProductsBySlug(anyString())).thenReturn(Collections.singletonList(mockProduct));

        // when & then
        mockMvc.perform(get("/api/product/{slug}", "test-slug"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(mockProduct)));
    }

    @Test
    @DisplayName("🔴 담을 수 없는 상품(품절로 내림)은 200 빈 본문이 아니라 409 와 이유 — 화면이 그대로 담았다")
    void cartInfoUnavailableIs409() throws Exception {
        when(productService.getCartProductInfo(1L, 0, 0)).thenReturn(null);

        mockMvc.perform(get("/api/cart/{product_id}", "1").param("style", "0").param("size", "0"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("🔴 slug 로 못 찾고 숫자면 상품 id 로 찾는다 — 체크아웃·주문은 상품 id 만 안다")
    void productDetailFallsBackToId() throws Exception {
        when(productService.getProductsBySlug("1")).thenReturn(Collections.emptyList());
        when(productService.getProductsByIds(List.of(1L)))
                .thenReturn(Collections.singletonList(ProductDto.builder().id("1").slug("event-much-mention").build()));

        mockMvc.perform(get("/api/product/{slug}", "1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.slug").value("event-much-mention"));
    }

    @Test
    @DisplayName("slug 로 못 찾고 숫자도 아니면 여전히 '상품 없음'")
    void productDetailUnknownSlug() throws Exception {
        when(productService.getProductsBySlug("Italian-Olive-Oil")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/product/{slug}", "Italian-Olive-Oil"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(productService, org.mockito.Mockito.never()).getProductsByIds(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("🔴 상세의 남은 수량은 다른 주문의 결제 전 예약을 뺀 값이고, 캐시된 DTO 는 건드리지 않는다(docs/59 §5)")
    void productDetailSubtractsHolds() throws Exception {
        com.project.common.dto.ProductSizeDto m = com.project.common.dto.ProductSizeDto.builder().size("M").quantity(3).build();
        com.project.common.dto.ProductSizeDto l = com.project.common.dto.ProductSizeDto.builder().size("L").quantity(1).build();
        ProductDto cached = ProductDto.builder().id("1").slug("tee").skus(List.of(
                com.project.common.dto.ProductSkuDto.builder().color(ProductColorDto.builder().id("5").build())
                        .sizes(List.of(m, l)).build())).build();
        when(productService.getProductsBySlug("tee")).thenReturn(Collections.singletonList(cached));
        when(stockReservations.sumHeldByProducts(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] {1L, 5L, "M", 2L}, new Object[] {1L, 5L, "L", 4L}));

        mockMvc.perform(get("/api/product/{slug}", "tee"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[0].quantity").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[0].onHold").value(2))
                // 실물보다 많이 잡혀 있어도 음수가 아니라 0, 잡힌 수는 실물까지만
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[1].quantity").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[1].onHold").value(1));

        org.assertj.core.api.Assertions.assertThat(m.getQuantity()).as("캐시된 DTO").isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(m.getOnHold()).isZero();
    }

    @Test
    @DisplayName("🔴 상세의 수량은 캐시가 아니라 지금 값이다 — 캐시에 3 이 남아 있어도 지금 0 이면 0")
    void productDetailUsesLiveQuantity() throws Exception {
        ProductDto cached = ProductDto.builder().id("1").slug("tee").skus(List.of(
                com.project.common.dto.ProductSkuDto.builder().color(ProductColorDto.builder().id("5").build())
                        .sizes(List.of(com.project.common.dto.ProductSizeDto.builder().id("11").size("M").quantity(3).build())).build())).build();
        when(productService.getProductsBySlug("tee")).thenReturn(Collections.singletonList(cached));
        when(productSizes.liveQuantities(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {11L, 0}));

        mockMvc.perform(get("/api/product/{slug}", "tee"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[0].quantity").value(0));
    }

    @Test
    @DisplayName("예약 조회가 실패하면 실물 수량 그대로 — 상품 화면이 깨지지 않는다")
    void productDetailFailOpen() throws Exception {
        ProductDto cached = ProductDto.builder().id("1").slug("tee").skus(List.of(
                com.project.common.dto.ProductSkuDto.builder().color(ProductColorDto.builder().id("5").build())
                        .sizes(List.of(com.project.common.dto.ProductSizeDto.builder().size("M").quantity(3).build())).build())).build();
        when(productService.getProductsBySlug("tee")).thenReturn(Collections.singletonList(cached));
        when(stockReservations.sumHeldByProducts(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        mockMvc.perform(get("/api/product/{slug}", "tee"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[0].quantity").value(3))
                // 0 이면 싣지 않는다 — 이 필드를 모르는 옛 파드가 캐시를 읽다 깨지지 않게
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skus[0].sizes[0].onHold").doesNotExist());
    }

    @Test
    @DisplayName("장바구니 배치 조회도 예약을 뺀다 — 상품 id 를 모아 한 번에 읽는다")
    void cartInfoSubtractsHolds() throws Exception {
        ProductInfoDto a = ProductInfoDto.builder().id("1").size("M").quantity(3)
                .color(ProductColorDto.builder().id("5").build()).build();
        ProductInfoDto b = ProductInfoDto.builder().id("2").size("L").quantity(2)
                .color(ProductColorDto.builder().id("6").build()).build();
        when(productService.getCartProductsInfo(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("1:0:0", a, "2:0:0", b)));
        when(stockReservations.sumHeldByProducts(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] {1L, 5L, "M", 1L}));

        mockMvc.perform(get("/api/cart/info").param("items", "1:0:0", "2:0:0"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['1:0:0'].quantity").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['1:0:0'].onHold").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['2:0:0'].quantity").value(2));
        org.mockito.Mockito.verify(stockReservations, org.mockito.Mockito.times(1))
                .sumHeldByProducts(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    // ── 장바구니 화면의 지금 재고 GET /api/stock?items=상품:색상:사이즈 (docs/59 §5) ──

    @Test
    @DisplayName("🔴 장바구니 항목의 지금 재고 — 수량은 예약을 뺀 값, 상품 상태도 함께, 없는 옵션은 빠진다")
    void cartStock() throws Exception {
        when(productSizes.liveOptions(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.<Object[]>of(
                new Object[] {1L, 5L, "M", 3, "ACTIVE"},
                new Object[] {2L, 6L, "270", 0, "DELETED"}));
        when(stockReservations.sumHeldByProducts(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] {1L, 5L, "M", 2L}));

        mockMvc.perform(get("/api/stock").param("items", "1:5:M", "2:6:270", "3:7:L"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['1:5:M'].quantity").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['1:5:M'].onHold").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['1:5:M'].status").value("ACTIVE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['2:6:270'].status").value("DELETED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$['3:7:L']").doesNotExist());
    }

    @Test
    @DisplayName("장바구니 재고 — 형식이 틀리면 400, 읽지 못하면 503(빈 결과와 구분 — 화면이 가진 값을 지우지 않게)")
    void cartStockErrors() throws Exception {
        mockMvc.perform(get("/api/stock").param("items", "1:5")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/stock").param("items", "a:5:M")).andExpect(status().isBadRequest());

        when(productSizes.liveOptions(org.mockito.ArgumentMatchers.anyCollection()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        mockMvc.perform(get("/api/stock").param("items", "1:5:M")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("cart 상품 정보 API는 200 OK를 반환해야 한다")
    void getCartProductInfo_shouldReturn200Ok() throws Exception {
        // given
        ProductInfoDto mockDto = ProductInfoDto.builder().id("1").build();
        when(productService.getCartProductInfo(anyLong(), anyInt(), anyInt())).thenReturn(mockDto);

        // when & then
        mockMvc.perform(get("/api/cart/{product_id}", 1)
                .param("style", "0")
                .param("size", "0"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("색상 정보 API는 200 OK를 반환해야 한다")
    void getColorInfo_shouldReturn200Ok() throws Exception {
        // given
        ProductColorDto mockDto = ProductColorDto.builder().id("1").build();
        when(productService.getColorAttributeInfo(anyLong())).thenReturn(mockDto);

        // when & then
        mockMvc.perform(get("/api/color/{colorId}", 1))
                .andExpect(status().isOk());
    }

    // 이 두 테스트는 짝이다. 별점은 **로그인만** 요구하는 유일한 쓰기 경로라서
    // (AdminWriteInterceptor 의 명시적 예외), "관리자가 아니어도 된다"와 "아무나 되는 것은
    // 아니다"를 둘 다 고정해야 의미가 있다. 하나만 두면 예외가 너무 넓어지거나
    // 너무 좁아지는 변경을 잡지 못한다.
    //
    // 신원은 헤더로 온다. 이 서비스에는 Spring Security 가 없고, 게이트웨이가 검증한
    // 신원을 User-Id/User-Role 로 넘긴다(외부에서 넣은 같은 이름의 헤더는 게이트웨이가
    // 지운다 — AdminWriteInterceptor javadoc 참조).

    @Test
    @DisplayName("🔴 로그인한 일반 사용자는 별점을 직접 바꿀 수 없다 — 403 (docs/57 §3)")
    void updateRating_userIsForbidden() throws Exception {
        mockMvc.perform(put("/api/product/rating")
                .header("User-Id", "42")
                .header("User-Role", "ROLE_USER")
                .param("id", "1")
                .param("rating", "5"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("내부 호출(신원 헤더 없음 — user-service 의 리뷰 평균)은 200")
    void updateRating_internalOk() throws Exception {
        mockMvc.perform(put("/api/product/rating")
                .param("id", "1")
                .param("rating", "4.5"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("범위를 벗어나거나 NaN 인 별점은 400")
    void updateRating_outOfRange() throws Exception {
        mockMvc.perform(put("/api/product/rating").param("id", "1").param("rating", "6"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/product/rating").param("id", "1").param("rating", "NaN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/product/rating").param("id", "1").param("rating", "-1"))
                .andExpect(status().isBadRequest());
    }

    // 여기에 있던 "카테고리/서브카테고리 API" 테스트는 삭제했다.
    // GET /api/product/products/by-subcategory 를 호출하고 200 을 기대했지만
    // ProductController 에 그런 매핑이 없어 항상 404 였다 — 존재하지 않는 API 를 검증하는
    // 테스트였다(docs/14 B-16). 실제로 productService.getProductsByCategoryAndSubcategory
    // 는 HTTP 로 노출된 적이 없고 캐시 워밍업 경로에서만 쓰인다.
    // 페이지네이션 조회는 위의 "페이지네이션 API" 테스트가 실제 엔드포인트로 커버한다.
}