package com.project.delivery_service.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.delivery_service.domain.AddressChange;
import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.domain.ShipmentItem;
import com.project.delivery_service.dto.AddressChangeRequest;
import com.project.delivery_service.dto.CreateDeliveryCommand;
import com.project.delivery_service.dto.RegisterTrackingRequest;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.CarrierRepository;
import com.project.delivery_service.repository.DeliveryRepository;
import com.project.delivery_service.repository.ShipmentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 생성·송장 등록·주소 변경·취소.
 *
 * <p>폴링과 상태 전이는 {@link TrackingPoller} 가 맡는다 — 외부 API 호출이 이 서비스의
 * 트랜잭션에 끼어들면 주문 이벤트 소비가 그만큼 밀린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final ShipmentRepository shipmentRepository;
    private final CarrierRepository carrierRepository;
    private final DeliveryStatusPublisher publisher;

    /** 발송국. 이 나라 밖으로 가면 국제 배송으로 보고 통관 단계를 넣는다. */
    @Value("${delivery.origin-country:KR}")
    private String originCountry;

    /**
     * 결제 완료 이벤트로 배송을 만든다.
     *
     * <p>같은 주문으로 두 번 호출돼도 배송은 하나만 생긴다 — Kafka 는 at-least-once 다.
     * {@code order_id} UNIQUE 가 최종 방어선이고, 조회로 먼저 걸러 예외 비용을 줄인다.
     */
    @Transactional
    public Optional<Delivery> createFromOrder(CreateDeliveryCommand command) {
        Optional<Delivery> existing = deliveryRepository.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            // debug 로 두면 운영에서 보이지 않는다 — "배송이 안 생긴다" 를 조사할 때
            // 가장 먼저 배제해야 하는 갈래가 바로 이것이다.
            log.info("이미 배송이 있는 주문, 건너뜀: orderId={}", command.orderId());
            return Optional.empty();
        }

        Delivery delivery = Delivery.builder()
                .orderId(command.orderId())
                .customerId(command.customerId())
                .reorderedFromOrderId(command.reorderedFromOrderId())
                .recipientName(command.recipientName())
                .recipientPhone(command.recipientPhone())
                .postalCode(command.postalCode())
                .addressLine1(command.addressLine1())
                .addressLine2(command.addressLine2())
                .city(command.city())
                .state(command.state())
                .country(command.country())
                .deliveryMemo(command.deliveryMemo())
                .build();

        // 상자를 하나 미리 만든다. 분할이 필요하면 송장 등록 시 늘어난다 — 상자가 하나뿐인
        // 흔한 경우를 특수 케이스로 두지 않는 편이 화면·로직 양쪽에서 단순하다.
        boolean international = delivery.isInternational(originCountry);
        Shipment shipment = delivery.addShipment(international);
        command.items().forEach(item -> shipment.addItem(new ShipmentItem(
                shipment, item.orderProductId(), item.productId(),
                item.productName(), item.imageUrl(), item.quantity(), item.skuCode())));

        try {
            Delivery saved = deliveryRepository.saveAndFlush(delivery);
            log.info("배송 생성: orderId={} deliveryId={} 국제={}",
                    saved.getOrderId(), saved.getDeliveryId(), international);
            publisher.publish(saved, DeliveryStatus.PENDING);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // 조회와 저장 사이에 다른 레플리카가 먼저 넣었다. UNIQUE 가 막아 준 정상 흐름이다.
            log.debug("동시 생성 감지, 건너뜀: orderId={}", command.orderId());
            return Optional.empty();
        }
    }

    /**
     * 등록할 송장번호. 테스트 택배사는 비워 두면 {@code SIM-<주문>-<상자>}(정상 진행 각본)를 만든다 — 관리자가 매번 SIM… 을 지어
     * 적을 필요가 없다. 실제 택배사는 번호가 있어야 한다(null).
     */
    static String trackingNumberFor(String carrierCode, String given, Long orderId, int sequenceNo) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        if (com.project.delivery_service.carrier.SimulatedCarrierAdapter.CODE.equals(carrierCode)) {
            return "SIM-" + orderId + "-" + sequenceNo;
        }
        return null;
    }

    /**
     * 송장번호를 등록한다. 분할 배송이면 상자를 지정하거나 새로 만든다.
     *
     * <p>이 시점부터 폴링이 시작되고 주문은 {@code DISPATCHED} 로 넘어간다.
     */
    @Transactional
    public Optional<Shipment> registerTracking(Long orderId, RegisterTrackingRequest request) {
        Optional<Delivery> found = deliveryRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = found.get();

        Carrier carrier = carrierRepository.findById(request.carrierCode()).orElse(null);
        if (carrier == null || !carrier.isActive()) {
            log.warn("알 수 없거나 비활성인 택배사: {}", request.carrierCode());
            return Optional.empty();
        }

        // 취소된 배송에는 송장을 달지 않는다. 🐛 예전에는 막지 않아, 손님이 배송을 취소해 주문이 PAID 로 돌아간 뒤 송장을 등록하면
        // 주문이 다시 DISPATCHED 가 돼 환불이 막혔다(환불된 주문이면 "발송되었습니다" 알림 · 폴링까지).
        // [JA] キャンセル済みの配送には送り状を付けない。以前は止めず、キャンセル後の登録で注文が再び DISPATCHED になり返金が止まった。
        if (delivery.overallStatus() == DeliveryStatus.CANCELLED) {
            throw new TrackingRejectedException("취소된 배송에는 송장을 등록할 수 없습니다");
        }

        // 상자를 고른다. sequenceNo 를 주면 그 상자에, 없으면 아직 송장이 없는 첫 상자에,
        // 그것도 없으면 새 상자를 만든다 — 분할 배송이 이 경로로 생긴다.
        // 고를 수 있는 것은 아직 발송 전(대기 · 접수)인 상자뿐이다. 🐛 예전에는 상태를 보지 않아 sequenceNo 로 배달 완료 · 이동 중인
        // 상자를 접수로 되돌려 썼고, 송장 없는 첫 상자로 취소된 상자를 골랐다.
        Shipment shipment;
        if (request.sequenceNo() != null) {
            shipment = delivery.getShipments().stream()
                    .filter(s -> s.getSequenceNo() == request.sequenceNo())
                    .findFirst()
                    .orElseGet(() -> delivery.addShipment(delivery.isInternational(originCountry)));
            if (!shipment.getStatus().isBeforeDispatch()) {
                throw new TrackingRejectedException("이미 발송됐거나 취소된 상자에는 송장을 등록할 수 없습니다");
            }
        } else {
            shipment = delivery.getShipments().stream()
                    .filter(s -> s.getTrackingNumber() == null && s.getStatus().isBeforeDispatch())
                    .findFirst()
                    .orElseGet(() -> delivery.addShipment(delivery.isInternational(originCountry)));
        }

        DeliveryStatus overallBefore = delivery.overallStatus();
        String trackingNumber = trackingNumberFor(carrier.getCarrierCode(), request.trackingNumber(), orderId, shipment.getSequenceNo());
        if (trackingNumber == null) {
            log.warn("송장번호가 비었다(테스트 택배사가 아님): orderId={} 택배사={}", orderId, carrier.getCarrierCode());
            return Optional.empty();
        }
        shipment.assignTracking(carrier.getCarrierCode(), trackingNumber,
                carrier.isSupportsTracking());
        if (request.customsClearanceNo() != null || request.customsDeclarationNo() != null) {
            shipment.setCustomsInfo(request.customsClearanceNo(), request.customsDeclarationNo());
        }

        deliveryRepository.saveAndFlush(delivery);
        log.info("송장 등록: orderId={} 상자={} 택배사={} 송장={}",
                orderId, shipment.getSequenceNo(), carrier.getCarrierCode(), trackingNumber);

        // 주문 전체 상태가 바뀌었을 때만 — 두 번째 상자의 송장은 주문을 다시 "발송" 으로 돌리지 않는다.
        publisher.publishIfOverallChanged(delivery, overallBefore);
        return Optional.of(shipment);
    }

    /**
     * 배송지를 변경한다 — <b>발송 전에만</b>.
     *
     * <p>집화된 뒤에는 실물이 택배사 손에 있어 우리가 목적지를 바꿀 수 없다. 바꿨다고 기록만
     * 하면 실물과 기록이 어긋나 분쟁 때 오히려 불리한 증거가 된다.
     *
     * @return 변경 이력. 권한이 없거나 변경 불가 상태면 비어 있음
     */
    @Transactional
    public Optional<AddressChange> changeAddress(Long customerId, Long orderId,
                                                 AddressChangeRequest request) {
        Optional<Delivery> found = deliveryRepository.findByOrderId(orderId);
        if (found.isEmpty() || !found.get().getCustomerId().equals(customerId)) {
            // 남의 배송이면 없는 것처럼 취급한다 — 403 은 "그 주문은 존재한다"를 알려주는 셈이다.
            return Optional.empty();
        }
        Delivery delivery = found.get();

        AddressChange change = delivery.changeAddress(
                customerId, request.recipientName(), request.recipientPhone(),
                request.postalCode(), request.addressLine1(), request.addressLine2(),
                request.city(), request.state(), request.country(),
                request.deliveryMemo(), request.reason());

        if (change == null) {
            log.info("발송 후라 주소를 변경할 수 없음: orderId={}", orderId);
            return Optional.empty();
        }
        deliveryRepository.saveAndFlush(delivery);
        log.info("배송지 변경: orderId={} by={}", orderId, customerId);
        return Optional.of(change);
    }

    /**
     * 발송 전 배송 취소.
     *
     * <p>취소된 배송을 재사용해 재주문을 붙이지 않는다 — 재주문은 새 주문이므로 새 배송이
     * 생기고, {@code reorderedFromOrderId} 로 연결된다. 재사용하면 취소 이력과 새 배송
     * 이력이 한 행에 뒤섞여 무엇이 언제 일어났는지 알 수 없게 된다.
     */
    @Transactional
    public boolean cancel(Long customerId, Long orderId) {
        Optional<Delivery> found = deliveryRepository.findByOrderId(orderId);
        if (found.isEmpty() || !found.get().getCustomerId().equals(customerId)) {
            return false;
        }
        Delivery delivery = found.get();
        if (!delivery.cancel()) {
            log.info("발송 후라 취소할 수 없음: orderId={} — 반품 절차가 필요하다", orderId);
            return false;
        }
        deliveryRepository.saveAndFlush(delivery);
        publisher.publish(delivery, DeliveryStatus.CANCELLED);
        log.info("배송 취소: orderId={}", orderId);
        return true;
    }

    /**
     * 결제가 취소·환불됐을 때의 취소 — 손님이 아니라 <b>시스템</b>이 부른다.
     *
     * <p>{@link #cancel(Long, Long)} 과 같은 규칙(발송 전에만)이지만 소유권 검사가 없다.
     * 결제 이벤트에는 부르는 사람이 없다. 배송이 없거나 이미 집화됐으면 조용히 false —
     * 재전달돼도, 반품 절차로 넘어갔어도 해가 없다.
     */
    @Transactional
    public boolean cancelByOrder(Long orderId) {
        Optional<Delivery> found = deliveryRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            return false;
        }
        Delivery delivery = found.get();
        if (delivery.overallStatus() == DeliveryStatus.CANCELLED) {
            return false;
        }
        if (!delivery.cancel()) {
            log.info("결제가 취소됐지만 이미 발송된 배송이라 취소하지 않음: orderId={} — 반품 절차", orderId);
            return false;
        }
        deliveryRepository.saveAndFlush(delivery);
        publisher.publish(delivery, DeliveryStatus.CANCELLED);
        return true;
    }

    /**
     * 송장을 기다리는 배송 — 관리자 화면의 기본 목록. 오래된 것부터.
     *
     * <p>"기다린다" 는 것은 상자 중 하나라도 송장 없이 {@code PENDING} 인 것이다.
     * 분할 배송에서 첫 상자만 보냈어도 여기 남는다.
     */
    @Transactional(readOnly = true)
    public List<Delivery> findAwaitingTracking(int size) {
        return deliveryRepository.findAwaitingTracking(PageRequest.of(0, Math.min(Math.max(size, 1), 100)));
    }

    /** 최근 배송 전체 — 관리자가 등록한 송장을 확인하거나 진행을 볼 때. */
    @Transactional(readOnly = true)
    public List<Delivery> findRecent(int size) {
        return deliveryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(size, 1), 100)));
    }

    @Transactional(readOnly = true)
    public Optional<Delivery> findByOrder(Long customerId, Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .filter(d -> d.getCustomerId().equals(customerId));
    }

    @Transactional(readOnly = true)
    public List<Delivery> findByCustomer(Long customerId, int size) {
        return deliveryRepository.findByCustomerIdOrderByCreatedAtDesc(
                customerId, PageRequest.of(0, Math.min(size, 50)));
    }

    @Transactional(readOnly = true)
    public List<Carrier> activeCarriers() {
        return carrierRepository.findByActiveTrue();
    }
}
