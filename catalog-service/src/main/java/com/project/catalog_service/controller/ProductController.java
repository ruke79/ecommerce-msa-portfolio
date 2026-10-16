package com.project.catalog_service.controller;

import com.project.common.exception.ClientMessage;

import java.io.IOException;
import java.io.ObjectInputFilter.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.project.common.constants.StatusMessages;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.catalog.request.CartProductInfoRequest;
import com.project.common.dto.catalog.request.ProductAddRequest;
import com.project.common.dto.catalog.request.ProductRequest;
import com.project.common.response.MessageResponse;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.service.ProductImportService;
import com.project.catalog_service.service.ProductService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductService productService;
    private final ProductImportService productImportService;
    /** 손님에게 보이는 남은 수량에서 결제 전 예약을 뺀다(docs/59 §5). 관리자 목록·편집에는 쓰지 않는다. */
    private final com.project.catalog_service.service.StockAvailability stockAvailability;

    /**
     * CSV 일괄 등록 — 예전 경로.
     *
     * <p>예전에는 여기서 {@code jobLauncher.run} 을 요청 스레드에서 그대로 돌렸다.
     * 부트 기본 런처는 동기라 응답이 잡이 끝나야 나갔고, 이미지 60장을 올리는 이 잡은
     * 게이트웨이 타임아웃에 먼저 걸렸다 — 관리자는 504 를 보고 다시 눌러 상품을
     * 두 벌 만들었다. 지금은 {@link ProductImportService} 가 비동기로 돌리고 중복을 막는다.
     *
     * <p>진행 상황은 {@code GET /api/content/admin/product-import} 로 묻는다.
     * 새 화면은 그쪽(관리자 접두사 아래)을 쓰고, 이 경로는 기존 호출자를 위해 남긴다.
     */
    @PostMapping("/products/load")
    public ResponseEntity<?> syncProducts(
            @RequestParam(name = "reset", defaultValue = "false") boolean reset) {
        return ProductImportSupport.launch(productImportService, reset);
    }

    @GetMapping(value = "/product/frontpage")
    ResponseEntity<?> loadProductsinFrontPage() {

        try {
            List<ProductDto> response = productService.warmUpProductCaches(4); // 페이지 크기를 4으로 설정

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    
    @GetMapping(value = "/product/products")
    ResponseEntity<?> getProducts() {

        try {
            List<ProductDto> response = productService.warmUpProductCaches(20); // 페이지 크기를 20으로 설정

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    

    @GetMapping(value = "/product/products/page")
    ResponseEntity<?> getProducts(@RequestParam("category") String category, @RequestParam("cursorId") String cursorId, @RequestParam("pageSize") int pageSize) {

        try {
           
            // 상한 — 🐛 공개 경로라 pageSize=1000000 이면 카테고리 전체를 읽어 캐시에 넣었고, 크기마다 키가 새로 생겼다.
            int size = Math.max(1, Math.min(pageSize, ProductService.MAX_PUBLIC_PAGE_SIZE));
            List<ProductDto> response = productService.getProductsByCategory(category, Long.parseLong(cursorId), size);
           
            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    @GetMapping("/{productId}")
    ResponseEntity<?> getParentProduct(@PathVariable("productId") String productId) {

        try {

            ProductDto dto = productService.getProductById(Long.parseLong(productId));

            return new ResponseEntity<>(dto, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }
   

    /** 한 요청에 담을 수 있는 id 수. 장바구니는 수십 개 단위다 — 그 이상은 잘못된 호출이다. */
    public static final int MAX_BATCH_IDS = 100;

    /**
     * 여러 상품을 한 번에 — {@code GET /api/products/info?ids=1&ids=2}.
     *
     * <p>cart-service 가 장바구니 항목마다 {@code /api/{productId}} 를 직렬로 부르던 것을
     * 대체한다. 찾지 못한 id 는 목록에서 빠질 뿐 실패가 아니다 — 부분 성공을 200 으로 돌려주고
     * 호출자가 id 로 대조한다. 빈 목록이나 상한 초과는 400 이다.
     */
    @GetMapping("/products/info")
    public ResponseEntity<?> getProductsInfo(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("ids is empty"));
        }
        if (ids.size() > MAX_BATCH_IDS) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("too many ids: " + ids.size() + " > " + MAX_BATCH_IDS));
        }
        return ResponseEntity.ok(productService.getProductsByIds(ids));
    }

    @GetMapping("/product/{slug}")
    public ResponseEntity<?> getProductInfo(@PathVariable(value="slug", required = true) String slug
            ) {
        try {
            List<ProductDto> dto = productService.getProductsBySlug(slug);
            // slug 로 못 찾았고 숫자면 상품 id 로 본다 — 장바구니·체크아웃·주문 품목은 slug 를 모르고 상품 id 만 안다.
            // 🐛 체크아웃은 이름을 slug 자리에 넣었다("Italian-Olive-Oil") — 이름과 slug 가 다른 상품은 전부 "상품 없음" 400.
            if (dto.isEmpty() && slug.chars().allMatch(Character::isDigit) && !slug.isEmpty() && slug.length() < 19) {
                dto = productService.getProductsByIds(List.of(Long.parseLong(slug)));
            }

            return new ResponseEntity<>(stockAvailability.apply(dto.get(0)), HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.PRODUCT_IS_EMPTY));
        }
    }

    @GetMapping("/cart/{product_id}")
    public ResponseEntity<?> getCartProductInfo(@PathVariable(value="product_id", required = true) String product_id,
            @RequestParam("style") int style, @RequestParam("size") int size)

    {
        try {
            ProductInfoDto dto = stockAvailability.apply(
                    productService.getCartProductInfo(Long.parseLong(product_id), style, size));
            // 🐛 null(품절로 내림·없는 상품·없는 스타일)이 200 빈 본문으로 나갔다. 화면은 수량이 없으니 "모자라지 않다" 로
            //    읽고 그대로 장바구니에 담았다 — 관리자가 품절 처리한 상품이 담겼다.
            if (dto == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new MessageResponse(StatusMessages.PRODUCT_NOT_AVAILABLE));
            }

            return new ResponseEntity<>(dto, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.PRODUCT_IS_EMPTY));
        }

    }

    /**
     * 장바구니 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 한 번에 —
     * {@code GET /api/cart/info?items=1:0:0&items=2:1:0}.
     *
     * <p>{@code GET /api/cart/{productId}?style=&size=} 를 항목마다 부르던 것의 대체다.
     * GET 인 이유는 {@code CartProductInfoRequest} 참조 — 서비스 간 호출에는 {@code User-Id} 가
     * 없어 POST 는 {@code AdminWriteInterceptor} 에 막힌다. 응답은 {@code Map<key, ProductInfoDto>}
     * 이고 못 찾은 항목은 맵에서 빠질 뿐 200 이다. 빈 목록·상한 초과·형식 오류는 400.
     */
    @GetMapping("/cart/info")
    public ResponseEntity<?> getCartProductsInfo(@RequestParam("items") List<String> items) {
        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("items is empty"));
        }
        if (items.size() > MAX_BATCH_IDS) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("too many items: " + items.size() + " > " + MAX_BATCH_IDS));
        }
        List<CartProductInfoRequest.Item> parsed = new ArrayList<>(items.size());
        try {
            for (String key : items) {
                parsed.add(CartProductInfoRequest.Item.parse(key));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
        // 트랜잭션(getCartProductsInfo) 이 끝난 뒤에 뺀다 — StockAvailability 참조.
        return ResponseEntity.ok(stockAvailability.apply(productService.getCartProductsInfo(parsed)));
    }

    /**
     * 장바구니 항목들의 지금 재고 — {@code GET /api/stock?items=1:5:M&items=2:6:270}(상품id:색상id:사이즈).
     *
     * <p>장바구니 화면은 담을 때 받은 수량을 들고 있다 — 며칠 전 값일 수 있다. 화면에 들어올 때·돌아올 때 이것으로 새로 본다.
     * 응답은 {@code {키: {quantity, onHold, status}}} 이고 {@code quantity} 는 다른 주문의 결제 전 예약을 뺀 값이다(docs/59 §5).
     * 없는 옵션은 맵에서 빠진다. 형식 오류·상한 초과는 400, 재고를 읽지 못하면 <b>503</b> — 빈 맵(=모두 없음)과 구분돼야
     * 화면이 가진 값을 지우지 않는다.
     */
    @GetMapping("/stock")
    public ResponseEntity<?> getStock(@RequestParam("items") List<String> items) {
        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("items is empty"));
        }
        if (items.size() > MAX_BATCH_IDS) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("too many items: " + items.size() + " > " + MAX_BATCH_IDS));
        }
        try {
            return ResponseEntity.ok(stockAvailability.optionStock(items));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            log.warn("장바구니 재고 조회 실패 ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new MessageResponse("재고를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요."));
        }
    }

    /**
     * 색상 여러 개를 한 번에 — {@code GET /api/colors/info?ids=1&ids=2}. 주문 상품·장바구니
     * 항목마다 {@code /api/color/{id}} 를 부르던 것의 대체다. 못 찾은 id 는 빠질 뿐 200.
     */
    @GetMapping("/colors/info")
    public ResponseEntity<?> getColorsInfo(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("ids is empty"));
        }
        if (ids.size() > MAX_BATCH_IDS) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("too many ids: " + ids.size() + " > " + MAX_BATCH_IDS));
        }
        return ResponseEntity.ok(productService.getColorsByIds(ids));
    }

    @GetMapping("/color/{colorId}")
    public ResponseEntity<?> getColorInfo(@PathVariable("colorId") String colorId) {

        log.info("Feign :" + colorId);

        try {
            ProductColorDto dto = productService.getColorAttributeInfo(Long.parseLong(colorId));

            return new ResponseEntity<>(dto, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.COLOR_ATTRIBUTE_NOT_FOUND));
        }

    }

     @PostMapping("/product")
    ResponseEntity<?> addProduct(
            @RequestPart("product") ProductRequest request,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "colorImage", required = false) MultipartFile colorImage) {

        try {

            ProductSku skuProject = productService.addProduct(request, images, colorImage);
            // 엔티티를 그대로 직렬화하지 않는다 — 지연 프록시가 터질 수 있고, 화면의
            // 다음 단계(색상 변형 이어서 등록)에 필요한 productId 는 @JsonBackReference
            // 에 잘려 응답에 없었다. 필요한 값만 명시적으로 준다.
            Product owner = skuProject.getProduct();
            return new ResponseEntity<>(new com.project.common.dto.catalog.response.ProductCreatedResponse(
                    skuProject.getSku(),
                    owner != null ? owner.getProductId() : null,
                    owner != null ? owner.getSlug() : null), HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            // 잘못된 입력(없는 카테고리·숫자 아닌 배송비·이미지 누락…)은 전부 이 예외로
            // 온다. 예전에는 서비스가 null 을 돌려줘 **200 OK + 빈 본문**이 나갔다 —
            // 화면은 "등록 완료"를 띄우는데 실제로는 아무것도 안 만들어진 상태였다.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));

        }

    }
    

    // ── 수정·상태·삭제 — 전부 쓰기 메서드라 AdminWriteInterceptor 가 관리자만 통과시킨다 ──

    @org.springframework.web.bind.annotation.PutMapping("/product/{productId}")
    ResponseEntity<?> updateProduct(
            @org.springframework.web.bind.annotation.PathVariable Long productId,
            @org.springframework.web.bind.annotation.RequestBody com.project.common.dto.catalog.request.ProductEditRequest request) {
        try {
            return ResponseEntity.ok(productService.updateProduct(productId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 품절(SOLD_OUT)·복귀(ACTIVE). DELETED 도 받지만 아래 DELETE 가 의도를 더 잘 드러낸다. */
    @org.springframework.web.bind.annotation.PatchMapping("/product/{productId}/status")
    ResponseEntity<?> updateProductStatus(
            @org.springframework.web.bind.annotation.PathVariable Long productId,
            @org.springframework.web.bind.annotation.RequestParam String status) {
        try {
            return ResponseEntity.ok(productService.updateStatus(productId, status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 소프트 삭제. 행은 남지만(SKU·주문 이력·리뷰가 참조한다) 모든 조회에서 사라진다.
     * ⚠️ 애플리케이션에서는 되돌릴 수 없다 — V8 마이그레이션 주석의 SQL 로만 복구된다.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/product/{productId}")
    ResponseEntity<?> deleteProduct(@org.springframework.web.bind.annotation.PathVariable Long productId) {
        try {
            productService.updateStatus(productId, "DELETED");
            return ResponseEntity.ok(new MessageResponse("deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    // Create Products
    @PostMapping("/products")
    ResponseEntity<?> addProducts(@RequestPart("products") List<ProductAddRequest> products, @RequestPart("image")
    List<MultipartFile> images, @RequestPart("colorImage") List<MultipartFile> colorImages
    ) {

        
        try {
            List<ProductSku> response = productService.load(products, images, colorImages);

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));

        }
    }


    /**
     * 별점 — 내부 전용(AdminWriteInterceptor). user-service 가 리뷰 평균을 보낸다. 범위를 벗어나거나 NaN 이면 거절한다 —
     * 예전 user-service 는 빈 목록을 나눠 NaN 을 보낸 적이 있다(ReviewService.publishRating 주석).
     */
    @PutMapping("/product/rating")
    public ResponseEntity<?> updateRating(@RequestParam("id") Long id, @RequestParam("rating") float rating) {
        if (!Float.isFinite(rating) || rating < 0f || rating > 5f) {
            return ResponseEntity.badRequest().body(new MessageResponse("별점은 0 이상 5 이하여야 합니다."));
        }
        productService.updateRating(id, rating);
        return ResponseEntity.ok().build();
    }
    
}
