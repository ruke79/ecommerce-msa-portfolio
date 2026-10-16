package com.project.catalog_service.grpc;

/** 다른 패키지의 테스트(컨테이너)가 잠금 대기 판정을 쓰기 위한 다리. */
public final class StockReservationGrpcServiceAccess {

    private StockReservationGrpcServiceAccess() {
    }

    public static boolean isLockWait(Throwable e) {
        return StockReservationGrpcService.isLockWait(e);
    }
}
