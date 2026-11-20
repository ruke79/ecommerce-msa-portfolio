package com.project.order_service.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import com.project.common.dto.ProductColorDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.order_service.config.FeignConfig;
//import com.project.cart_service.dto.ProductDto;

/**
 * 마이크로서비스 간의 호출을 위한 feignclient
 *
 * <p>[JA] マイクロサービス間の呼び出しのための FeignClient
 */
@FeignClient(name = "catalog-service", url="${feign.catalog-url}",  configuration = FeignConfig.class)
public interface CatalogServiceClient {

    // @RequestMapping(method = RequestMethod.GET, value = "/api/{productId}")
    // public ResponseEntity<ProductDto> getProductInfo(@PathVariable("productId") String productId);

    /**
     * 색상 여러 개를 한 번에 — 주문 상품마다 {@link #getColorInfo} 를 부르면 주문 목록 한 페이지가
     * 색상 조회 수십 번이 된다(docs/38 §1.13). 못 찾은 id 는 목록에서 빠진다.
     *
     * <p>[JA] 色を複数まとめて — 注文商品ごとに {@link #getColorInfo} を呼ぶと注文一覧 1 ページが
     * 色の照会数十回になる(docs/38 §1.13)。見つからない id は一覧から抜ける。
     */
    @GetMapping("/api/colors/info")
    public ResponseEntity<List<ProductColorDto>> getColorsInfo(@RequestParam("ids") List<Long> ids);

    @GetMapping("/api/color/{colorId}")
    public ResponseEntity<?> getColorInfo(@PathVariable("colorId") String colorId);

    /**
     * 결제 전 재고 예약 — 잡기·다시 잡기(연장) (docs/59). catalog 의 내부 전용 경로다 — 이 Feign 은 신원 헤더(User-*)를 싣지
     * 않으므로 내부 호출로 인정된다(FeignConfig 는 Authorization 만 옮긴다). 모자라도 200 — held=false.
     *
     * <p>[JA] 決済前の在庫の仮押さえ — 確保 · 再確保(延長)(docs/59)。catalog の内部専用パスだ — この Feign は身元ヘッダー(User-*)を載せないので
     * 内部呼び出しと認められる(FeignConfig は Authorization だけを移す)。足りなくても 200 — held=false。
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/internal/stock/reservations")
    com.project.common.dto.stock.StockReservationResult reserveStock(
            @org.springframework.web.bind.annotation.RequestBody com.project.common.dto.stock.StockReservationRequest request);

    /** 예약 풀기 — 여러 번 불러도 같다. */
    // [JA] 仮押さえの解除 — 何度呼んでも同じ。
    @org.springframework.web.bind.annotation.PostMapping("/api/internal/stock/reservations/{orderId}/release")
    void releaseStock(@PathVariable("orderId") Long orderId);
}
