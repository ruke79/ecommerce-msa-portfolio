package com.project.delivery_service.controller;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.dto.AddressChangeRequest;
import com.project.delivery_service.dto.AdminDeliveryResponse;
import com.project.delivery_service.dto.DeliveryResponse;
import com.project.delivery_service.dto.RegisterTrackingRequest;
import com.project.delivery_service.service.DeliveryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 배송 조회·주소 변경·취소, 그리고 송장 등록.
 *
 * <p>사용자 식별은 게이트웨이가 JWT 를 검증한 뒤 넣어 주는 {@code User-Id} 헤더로 한다.
 * 남의 배송을 조회하려는 시도는 <b>404 로</b> 끊는다 — 403 은 "그 주문은 존재한다"를
 * 알려주는 셈이라 주문번호를 훑는 데 쓰인다.
 */
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final com.project.delivery_service.service.DeliveryBackfillService deliveryBackfill;

    /**
     * 관리자인가.
     *
     * <p>역할 표기가 두 가지로 섞여 있다 — JWT 의 role 클레임은 {@code ROLE_ADMIN} 이지만
     * 다른 곳에서는 {@code ADMIN} 만 쓰기도 한다. 둘 다 받는다. 한쪽만 보면 실제 관리자가
     * 거부당하는데, 그 이유가 로그에도 화면에도 드러나지 않아 원인을 찾기 어렵다.
     */
    private static boolean isAdmin(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.trim().toUpperCase();
        return normalized.equals("ADMIN") || normalized.equals("ROLE_ADMIN");
    }

    private Map<String, Carrier> carrierIndex() {
        return deliveryService.activeCarriers().stream()
                .collect(Collectors.toMap(Carrier::getCarrierCode, Function.identity()));
    }

    /** 내 배송 목록. 주문 목록 화면의 "배송 조회" 진입점. */
    @GetMapping
    public ResponseEntity<List<DeliveryResponse>> list(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "20") int size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Carrier> carriers = carrierIndex();
        return ResponseEntity.ok(deliveryService.findByCustomer(userId, size).stream()
                .map(d -> DeliveryResponse.from(d, carriers))
                .toList());
    }

    /** 주문 하나의 배송 상세 — 아마존식 타임라인 화면이 쓰는 엔드포인트. */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<DeliveryResponse> byOrder(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long orderId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return deliveryService.findByOrder(userId, orderId)
                .map(d -> ResponseEntity.ok(DeliveryResponse.from(d, carrierIndex())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 배송지 변경 — 발송 전에만.
     *
     * <p>409 를 돌려주는 경우가 중요하다: 요청이 잘못된 게 아니라 <b>지금은 할 수 없는</b>
     * 것이므로 400 이 아니다. 프론트는 이 구분으로 "이미 발송되어 변경할 수 없습니다"를 띄운다.
     */
    @PatchMapping("/order/{orderId}/address")
    public ResponseEntity<Map<String, Object>> changeAddress(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long orderId,
            @Valid @RequestBody AddressChangeRequest request) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (deliveryService.findByOrder(userId, orderId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return deliveryService.changeAddress(userId, orderId, request)
                .<ResponseEntity<Map<String, Object>>>map(change -> ResponseEntity.ok(Map.of(
                        "changed", true,
                        "previousAddress", change.getPreviousAddress(),
                        "newAddress", change.getNewAddress())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "changed", false,
                        "message", "이미 발송되어 배송지를 변경할 수 없습니다")));
    }

    /** 발송 전 배송 취소. 발송 후에는 반품 절차여서 여기서 처리하지 않는다. */
    @PostMapping("/order/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long orderId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (deliveryService.findByOrder(userId, orderId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return deliveryService.cancel(userId, orderId)
                ? ResponseEntity.ok(Map.of("cancelled", true))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "cancelled", false,
                        "message", "이미 발송되어 취소할 수 없습니다. 반품을 신청해 주세요"));
    }

    /**
     * 관리자 목록 — 기본은 <b>송장을 기다리는 배송</b>, {@code awaiting=false} 면 최근 전체.
     *
     * <p>🔒 송장 등록과 같은 무게로 관리자만. 여러 고객의 수취인·주소·연락처가 한 화면에
     * 나온다. 역할 헤더가 없으면 거부한다 — 게이트웨이를 우회한 요청이 그대로 들어온다.
     * 소유권 개념이 없는 목록이라 다른 데서 막을 곳이 없다.
     */
    @GetMapping("/admin")
    public ResponseEntity<List<AdminDeliveryResponse>> adminList(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole,
            @RequestParam(defaultValue = "true") boolean awaiting,
            @RequestParam(defaultValue = "50") int size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, Carrier> carriers = carrierIndex();
        List<com.project.delivery_service.domain.Delivery> rows =
                awaiting ? deliveryService.findAwaitingTracking(size) : deliveryService.findRecent(size);
        return ResponseEntity.ok(rows.stream().map(d -> AdminDeliveryResponse.from(d, carriers)).toList());
    }

    /**
     * 결제는 됐는데 배송이 없는 주문의 <b>배송을 만든다</b> — 관리자 복구용.
     *
     * <p>정상 경로는 결제 이벤트가 만든다. 그런데 그 경로가 막혀 있던 동안(나라 이름이
     * {@code varchar(2)} 를 넘겨 결제 레코드가 DLT 로 갔다, docs/25 §11.5) 결제된 주문들이
     * 배송 없이 남았다. 그 레코드는 다시 오지 않으므로 손으로 이어 줄 자리가 필요하다.
     *
     * <p>주소·품목은 <b>주문에서 다시 읽는다</b> — 요청 본문으로 받지 않는다. 관리자라도
     * 배송지를 임의로 지정할 수 있으면 그 자체가 물건을 빼돌리는 길이 된다.
     *
     * <p>이미 배송이 있으면 200 에 {@code created: false} 다. 두 번 눌러도 안전하다.
     */
    @PostMapping("/admin/order/{orderId}")
    public ResponseEntity<Map<String, Object>> adminCreateFromOrder(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole,
            @PathVariable Long orderId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "created", false, "message", "권한이 없습니다"));
        }
        return deliveryBackfill.createFromOrder(orderId)
                .<ResponseEntity<Map<String, Object>>>map(d -> ResponseEntity.ok(Map.of(
                        "created", true, "deliveryId", d.getDeliveryId(), "orderId", d.getOrderId())))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "created", false,
                        "message", "이미 배송이 있거나 주문에서 배송지를 읽지 못했습니다")));
    }

    /** 지원 택배사 목록. 프론트의 송장 등록 화면과 조회 링크에 쓰인다. */
    @GetMapping("/carriers")
    public ResponseEntity<List<Map<String, Object>>> carriers() {
        return ResponseEntity.ok(deliveryService.activeCarriers().stream()
                .map(c -> Map.<String, Object>of(
                        "code", c.getCarrierCode(),
                        "name", c.getNameKo(),
                        "supportsTracking", c.isSupportsTracking()))
                .toList());
    }

    /**
     * 송장 등록 (판매자·물류 전용).
     *
     * <p>🔒 <b>이전 버전은 인증만 확인하고 역할을 보지 않았다.</b> 주석으로 "운영 전에
     * 관리자 검사를 넣어야 한다"고만 적어 두었는데, 그 사이 이 엔드포인트는 <b>로그인한
     * 아무나</b> 남의 주문에 임의의 택배사·송장번호를 붙일 수 있는 상태였다. 그러면
     * 주문이 발송된 것처럼 바뀌고(폴링 시작 → DISPATCHED), 주소 변경과 취소가 막히며,
     * 고객에게는 오지 않을 물건의 배송 알림이 나간다.
     *
     * <p>이제 게이트웨이가 넘겨준 역할을 확인한다. 역할 헤더가 없으면 <b>거부</b>한다 —
     * 없을 때 통과시키면 게이트웨이를 우회한 요청이 그대로 들어온다.
     */
    @PostMapping("/order/{orderId}/tracking")
    public ResponseEntity<Map<String, Object>> registerTracking(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole,
            @PathVariable Long orderId,
            @Valid @RequestBody RegisterTrackingRequest request) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "registered", false,
                    "message", "송장 등록 권한이 없습니다"));
        }
        if ((request.trackingNumber() == null || request.trackingNumber().isBlank())
                && !com.project.delivery_service.carrier.SimulatedCarrierAdapter.CODE.equals(request.carrierCode())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "registered", false,
                    "message", "송장번호를 입력해 주세요"));
        }
        try {
            return deliveryService.registerTracking(orderId, request)
                .<ResponseEntity<Map<String, Object>>>map(shipment -> ResponseEntity.ok(Map.of(
                        "registered", true,
                        "shipmentId", shipment.getShipmentId(),
                        "sequenceNo", shipment.getSequenceNo(),
                        "status", shipment.getStatus().name())))
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of(
                        "registered", false,
                        "message", "주문 또는 택배사를 찾을 수 없습니다")));
        } catch (com.project.delivery_service.service.TrackingRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "registered", false, "message", e.reason()));
        }
    }
}
