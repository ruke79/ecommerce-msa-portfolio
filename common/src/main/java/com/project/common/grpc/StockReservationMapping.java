package com.project.common.grpc;

import java.time.Instant;
import java.util.List;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.common.grpc.stock.v1.Line;
import com.project.common.grpc.stock.v1.ReserveRequest;
import com.project.common.grpc.stock.v1.ReserveResponse;
import com.project.common.grpc.stock.v1.Shortage;

/**
 * 재고 예약의 DTO ↔ proto 변환 — 서버(catalog)와 클라이언트(order)가 <b>같은 변환</b>을 쓴다(docs/66).
 *
 * <p>HTTP 경로와 gRPC 경로가 같은 서비스 메서드({@code StockReservationService.reserve(StockReservationRequest)})에 닿게 하는
 * 것이 목적이다. 그래야 {@code STOCK_RPC_TRANSPORT} 를 바꿔도 결과가 같다 — 전송만 바뀐다.
 *
 * <p>proto3 에는 null 이 없다. 기본값(0 · 빈 문자열)을 어떻게 읽는지가 두 경로의 차이를 만들 수 있는 자리라 여기서 정한다:
 * <ul>
 *   <li>id 0 → null. 실제 id 는 0 이 아니다(TSID · IDENTITY 모두 1 이상).</li>
 *   <li>사이즈 빈 문자열은 그대로 — 사이즈 없는 상품의 이름이 빈 문자열이고, 예약 키도 그 값이다.</li>
 *   <li>만료 0 → null(잡지 못했을 때).</li>
 * </ul>
 */
// [JA] 在庫予約の DTO ↔ proto 変換 — サーバー(catalog)とクライアント(order)が同じ変換を使う。HTTP と gRPC が同じサービス
//      メソッドに届くようにし、STOCK_RPC_TRANSPORT を変えても結果が同じになるようにする。proto3 に null はないので、
//      既定値の読み方(id 0 → null、サイズの空文字列はそのまま、期限 0 → null)をここで決める。
public final class StockReservationMapping {

    private StockReservationMapping() {
    }

    // ── 클라이언트 → 서버 ────────────────────────────────────────────────

    public static ReserveRequest toProto(StockReservationRequest dto) {
        ReserveRequest.Builder b = ReserveRequest.newBuilder()
                .setOrderId(zeroIfNull(dto.getOrderId()))
                .setTtlSeconds(dto.getTtlSeconds());
        if (dto.getLines() != null) {
            for (StockReservationRequest.Line l : dto.getLines()) {
                b.addLines(Line.newBuilder()
                        .setProductId(zeroIfNull(l.getProductId()))
                        .setColorId(zeroIfNull(l.getColorId()))
                        .setSize(l.getSize() == null ? "" : l.getSize())
                        .setQty(l.getQty()));
            }
        }
        return b.build();
    }

    public static StockReservationRequest fromProto(ReserveRequest proto) {
        return StockReservationRequest.builder()
                .orderId(nullIfZero(proto.getOrderId()))
                .ttlSeconds(proto.getTtlSeconds())
                .lines(proto.getLinesList().stream()
                        .map(l -> StockReservationRequest.Line.builder()
                                .productId(nullIfZero(l.getProductId()))
                                .colorId(nullIfZero(l.getColorId()))
                                .size(l.getSize())
                                .qty(l.getQty())
                                .build())
                        .toList())
                .build();
    }

    // ── 서버 → 클라이언트 ────────────────────────────────────────────────

    public static ReserveResponse toProto(StockReservationResult dto) {
        ReserveResponse.Builder b = ReserveResponse.newBuilder()
                .setHeld(dto.isHeld())
                .setExpiresAtEpochMillis(dto.getExpiresAt() == null ? 0L : dto.getExpiresAt().toEpochMilli());
        if (dto.getShortages() != null) {
            for (StockReservationResult.Shortage s : dto.getShortages()) {
                b.addShortages(Shortage.newBuilder()
                        .setProductId(zeroIfNull(s.getProductId()))
                        .setColorId(zeroIfNull(s.getColorId()))
                        .setSize(s.getSize() == null ? "" : s.getSize())
                        .setRequested(s.getRequested())
                        .setAvailable(s.getAvailable()));
            }
        }
        return b.build();
    }

    public static StockReservationResult fromProto(ReserveResponse proto) {
        List<StockReservationResult.Shortage> shortages = proto.getShortagesList().stream()
                .map(s -> StockReservationResult.Shortage.builder()
                        .productId(nullIfZero(s.getProductId()))
                        .colorId(nullIfZero(s.getColorId()))
                        .size(s.getSize())
                        .requested(s.getRequested())
                        .available(s.getAvailable())
                        .build())
                .toList();
        return StockReservationResult.builder()
                .held(proto.getHeld())
                .expiresAt(proto.getExpiresAtEpochMillis() == 0L ? null : Instant.ofEpochMilli(proto.getExpiresAtEpochMillis()))
                .shortages(shortages)
                .build();
    }

    private static long zeroIfNull(Long v) {
        return v == null ? 0L : v;
    }

    private static Long nullIfZero(long v) {
        return v == 0L ? null : v;
    }
}
