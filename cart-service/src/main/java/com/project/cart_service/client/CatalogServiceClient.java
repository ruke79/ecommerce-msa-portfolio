package com.project.cart_service.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.cart_service.config.FeignConfig;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;


/**
 * 마이크로서비스 간의 호출을 위한 feignclient
 *
 * <p>[JA] マイクロサービス間の呼び出しのための FeignClient
 */
@FeignClient(name = "catalog-service", url = "${feign.catalog-url}", configuration = FeignConfig.class)
public interface CatalogServiceClient {

    @RequestMapping(method = RequestMethod.GET, value = "/api/{productId}")
    public ResponseEntity<ProductDto> getProductInfo(@PathVariable("productId") String productId);

    /**
     * 여러 상품을 한 번에. 장바구니 항목마다 {@link #getProductInfo} 를 부르면 항목 수만큼
     * 왕복이 생긴다 — 이쪽은 한 번이다(docs/38 §1.13). 찾지 못한 id 는 목록에서 빠진다.
     *
     * <p>[JA] 複数の商品を一度に。カート項目ごとに {@link #getProductInfo} を呼ぶと項目数だけ
     *      往復が生じる — こちらは 1 回である(docs/38 §1.13)。見つからない id は一覧から抜ける。
     */
    @GetMapping("/api/products/info")
    public ResponseEntity<List<ProductDto>> getProductsInfo(@RequestParam("ids") List<Long> ids);

    @GetMapping("/api/cart/{product_id}")
    public ResponseEntity<?> getCartProductInfo(@PathVariable("product_id") String product_id,
            @RequestParam("style") int style, @RequestParam("size") int size);

    /**
     * 장바구니 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 한 번에. 항목은
     * {@code CartProductInfoRequest.Item.key()} 형식({@code productId:style:sizeIndex})이고 응답 맵의
     * 키도 같다. 못 찾은 항목은 맵에서 빠진다. GET 인 이유는 {@code CartProductInfoRequest} 참조.
     *
     * <p>[JA] カート項目複数の(商品, スタイル, サイズ)情報を一度に。項目は
     *      {@code CartProductInfoRequest.Item.key()} 形式({@code productId:style:sizeIndex})で、応答マップの
     *      キーも同じ。見つからない項目はマップから抜ける。GET である理由は {@code CartProductInfoRequest} 参照。
     */
    @GetMapping("/api/cart/info")
    public ResponseEntity<Map<String, ProductInfoDto>> getCartProductsInfo(@RequestParam("items") List<String> items);

    @GetMapping("/api/color/{colorId}")
    public ResponseEntity<?> getColorInfo(@PathVariable("colorId") String colorId);

    /** 색상 여러 개를 한 번에 — 장바구니 항목마다 {@link #getColorInfo} 를 부르던 것의 대체. */
    // [JA] 色の複数件を一度に — カート項目ごとに {@link #getColorInfo} を呼んでいたものの代替。
    @GetMapping("/api/colors/info")
    public ResponseEntity<List<ProductColorDto>> getColorsInfo(@RequestParam("ids") List<Long> ids);
}
