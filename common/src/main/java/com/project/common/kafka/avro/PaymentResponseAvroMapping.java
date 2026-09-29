package com.project.common.kafka.avro;

import java.math.BigDecimal;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

import com.project.common.avro.PaymentResponse;
import com.project.common.constants.PaymentStatus;

/** pay → order 결제 응답 (토픽 {@code payment}). */
public class PaymentResponseAvroMapping
        implements AvroMapping<com.project.common.message.dto.response.PaymentResponse, PaymentResponse> {

    @Override
    public Class<com.project.common.message.dto.response.PaymentResponse> dtoType() {
        return com.project.common.message.dto.response.PaymentResponse.class;
    }

    @Override
    public PaymentResponse toRecord(com.project.common.message.dto.response.PaymentResponse d) {
        return PaymentResponse.newBuilder()
                .setPaymentId(d.getPaymentId())
                .setOrderId(d.getOrderId())
                .setCustomerId(d.getCustomerId())
                .setCouponName(d.getCouponName())
                .setAmounts(d.getAmounts() == null ? null : d.getAmounts().toPlainString())
                .setPaymentStatus(d.getPaymentStatus() == null ? null
                        : com.project.common.avro.PaymentStatus.valueOf(d.getPaymentStatus().name()))
                .build();
    }

    @Override
    public com.project.common.message.dto.response.PaymentResponse toDto(PaymentResponse r) {
        return com.project.common.message.dto.response.PaymentResponse.builder()
                .paymentId(r.getPaymentId())
                .orderId(r.getOrderId())
                .customerId(r.getCustomerId())
                .couponName(r.getCouponName())
                .amounts(r.getAmounts() == null ? null : new BigDecimal(r.getAmounts()))
                .paymentStatus(r.getPaymentStatus() == null ? null : PaymentStatus.valueOf(r.getPaymentStatus().name()))
                .build();
    }

    @Override
    public BinaryMessageEncoder<PaymentResponse> encoder() {
        return PaymentResponse.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<PaymentResponse> decoder() {
        return PaymentResponse.getDecoder();
    }
}
