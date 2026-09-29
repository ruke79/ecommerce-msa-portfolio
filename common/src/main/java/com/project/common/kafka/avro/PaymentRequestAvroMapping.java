package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

import com.project.common.avro.PaymentRequest;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;

/**
 * order → pay 결제 요청 (토픽 {@code order}).
 *
 * <h3>필드를 뺄 때(v4, paypalOrderId) — 다음 스키마를 먼저 알린다</h3>
 *
 * <p>가운데 필드를 빼면 뒤 필드의 위치가 밀리므로 아래 "옛 작성 스키마로 쓴다" 가 통하지 않는다. 그래서 앞 릴리스(C1)는
 * 생성 클래스를 v3 로 둔 채 v4 를 디코더 저장소에 미리 넣었고, 이번 릴리스(C2)부터 v4 로 쓴다 — 롤링 중 옛 파드는 C1 이라
 * v4 를 안다(C1 을 건너뛰면 order 의 마이그레이션이 기동을 막는다, docs/49 §3).
 *
 * <h3>v3 는 두 릴리스에 걸쳐 들어왔다 — 읽는 쪽 먼저</h3>
 *
 * <p>v3 는 제공자 중립 필드(paymentProvider·providerPaymentId)를 더했다(docs/49 §3). 단일 객체 인코딩은 작성 스키마의
 * <b>지문</b>을 싣고, 읽는 쪽은 아는 지문만 읽는다. 배포는 모든 서비스를 거의 동시에 교체하므로(ops/28), 곧바로 v3 로
 * 썼다면 롤링 중 옛 pay 는 결제 요청을 DLT 로 보내고 옛 saga-projection 은 스트림이 멈췄다. 그래서 앞 릴리스는 모든 읽는
 * 쪽이 v3 를 알게 하면서 쓰기는 v2 작성 스키마로 했고, 이번 릴리스부터 v3 로 쓴다 — 롤링 중 옛 파드는 앞 릴리스라 v3 를
 * 안다(앞 릴리스를 건너뛰면 order V18 이 기동을 막는다). 다음에 필드를 더할 때도 같은 두 단계를 밟는다: 새 필드는 끝에
 * 붙이고, 한 릴리스 동안 옛 작성 스키마로 쓴다({@code new BinaryMessageEncoder<>(SpecificData.getForClass(..), 옛스키마)}).
 */
public class PaymentRequestAvroMapping
        implements AvroMapping<com.project.common.message.dto.request.PaymentRequest, PaymentRequest> {

    @Override
    public Class<com.project.common.message.dto.request.PaymentRequest> dtoType() {
        return com.project.common.message.dto.request.PaymentRequest.class;
    }

    @Override
    public PaymentRequest toRecord(com.project.common.message.dto.request.PaymentRequest d) {
        return PaymentRequest.newBuilder()
                .setOrderId(d.getOrderId())
                .setCustomerId(d.getCustomerId())
                .setTrackingId(d.getTrackingId())
                .setCouponName(d.getCouponName())
                .setAmounts(d.getAmounts() == null ? null : d.getAmounts().toPlainString())
                .setOrderStatus(d.getOrderStatus() == null ? null
                        : com.project.common.avro.OrderStatus.valueOf(d.getOrderStatus().name()))
                .setPaymentType(d.getPaymentType() == null ? null
                        : com.project.common.avro.PaymentType.valueOf(d.getPaymentType().name()))
                .setPaymentStatus(d.getPaymentStatus() == null ? null
                        : com.project.common.avro.PaymentStatus.valueOf(d.getPaymentStatus().name()))
                .setCreatedAt(d.getCreatedAt() == null ? null : d.getCreatedAt().toInstant(ZoneOffset.UTC))
                .setPaymentProvider(d.getPaymentProvider() == null ? null
                        : com.project.common.avro.PaymentProvider.valueOf(d.getPaymentProvider().name()))
                .setProviderPaymentId(d.getProviderPaymentId())
                .build();
    }

    @Override
    public com.project.common.message.dto.request.PaymentRequest toDto(PaymentRequest r) {
        Instant created = r.getCreatedAt();
        return com.project.common.message.dto.request.PaymentRequest.builder()
                .orderId(r.getOrderId())
                .customerId(r.getCustomerId())
                .trackingId(r.getTrackingId())
                .couponName(r.getCouponName())
                .amounts(r.getAmounts() == null ? null : new BigDecimal(r.getAmounts()))
                .orderStatus(r.getOrderStatus() == null ? null : OrderStatus.valueOf(r.getOrderStatus().name()))
                .paymentType(r.getPaymentType() == null ? null : PaymentType.valueOf(r.getPaymentType().name()))
                .paymentStatus(r.getPaymentStatus() == null ? null : PaymentStatus.valueOf(r.getPaymentStatus().name()))
                .createdAt(created == null ? null : LocalDateTime.ofInstant(created, ZoneOffset.UTC))
                .paymentProvider(r.getPaymentProvider() == null ? null
                        : PaymentProvider.valueOf(r.getPaymentProvider().name()))
                .providerPaymentId(r.getProviderPaymentId())
                .build();
    }

    @Override
    public BinaryMessageEncoder<PaymentRequest> encoder() {
        return PaymentRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<PaymentRequest> decoder() {
        // v1(authorizationToken 있음)·v2(제공자 중립 필드 없음)·v3(paypalOrderId 있음)로 쓴 레코드도 읽는다 — 앞
        // 릴리스들의 order 가 쓴 것이 토픽·DLT 보존 기간 동안 남는다(AvroSchemaHistory).
        return PaymentRequest.createDecoder(AvroSchemaHistory.store(AvroSchemaHistory.PAYMENT_REQUEST));
    }
}
