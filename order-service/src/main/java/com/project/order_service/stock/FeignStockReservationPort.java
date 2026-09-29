package com.project.order_service.stock;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.client.CatalogServiceClient;

/** HTTP(Feign) — 지금까지의 경로 그대로. 전송 스위치의 기본값이자 되돌아갈 곳이다(docs/66). */
// [JA] HTTP(Feign) — これまでの経路そのまま。伝送スイッチの既定値であり、戻る先でもある。
public class FeignStockReservationPort implements StockReservationPort {

    private final CatalogServiceClient catalog;

    public FeignStockReservationPort(CatalogServiceClient catalog) {
        this.catalog = catalog;
    }

    @Override
    public StockReservationResult reserve(StockReservationRequest request) {
        return catalog.reserveStock(request);
    }

    @Override
    public void release(Long orderId) {
        catalog.releaseStock(orderId);
    }

    @Override
    public String transport() {
        return "http";
    }
}
