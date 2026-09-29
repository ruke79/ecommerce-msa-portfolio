package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** CouponUseRequest ↔ Avro. */
public class CouponUseRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.CouponUseRequest, com.project.common.avro.CouponUseRequest> {

    @Override
    public Class<com.project.common.message.dto.request.CouponUseRequest> dtoType() {
        return com.project.common.message.dto.request.CouponUseRequest.class;
    }

    @Override
    public com.project.common.avro.CouponUseRequest toRecord(com.project.common.message.dto.request.CouponUseRequest d) {
        return com.project.common.avro.CouponUseRequest.newBuilder()
                .setUserId(d.getUserId()).setDiscount(d.getDiscount())
                .setTotalAfterDiscount(d.getTotalAfterDiscount() == null ? null : d.getTotalAfterDiscount().toPlainString())
                .build();
    }

    @Override
    public com.project.common.message.dto.request.CouponUseRequest toDto(com.project.common.avro.CouponUseRequest r) {
        return com.project.common.message.dto.request.CouponUseRequest.builder()
                .userId(r.getUserId()).discount(r.getDiscount())
                .totalAfterDiscount(r.getTotalAfterDiscount() == null ? null : new BigDecimal(r.getTotalAfterDiscount()))
                .build();
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.CouponUseRequest> encoder() {
        return com.project.common.avro.CouponUseRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.CouponUseRequest> decoder() {
        // v1(authorizationToken 있음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 프로듀서가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.CouponUseRequest.createDecoder(AvroSchemaHistory.store(AvroSchemaHistory.COUPON_USE));
    }
}
