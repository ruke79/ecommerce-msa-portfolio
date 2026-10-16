package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** CouponRollbackRequest ↔ Avro. */
public class CouponRollbackRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.CouponRollbackRequest, com.project.common.avro.CouponRollbackRequest> {

    @Override
    public Class<com.project.common.message.dto.request.CouponRollbackRequest> dtoType() {
        return com.project.common.message.dto.request.CouponRollbackRequest.class;
    }

    @Override
    public com.project.common.avro.CouponRollbackRequest toRecord(com.project.common.message.dto.request.CouponRollbackRequest d) {
        return com.project.common.avro.CouponRollbackRequest.newBuilder()
                .setUserId(d.getUserId()).setCouponName(d.getCouponName()).setOrderId(d.getOrderId()).build();
    }

    @Override
    public com.project.common.message.dto.request.CouponRollbackRequest toDto(com.project.common.avro.CouponRollbackRequest r) {
        return com.project.common.message.dto.request.CouponRollbackRequest.builder()
                .userId(r.getUserId()).couponName(r.getCouponName()).orderId(r.getOrderId()).build();
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.CouponRollbackRequest> encoder() {
        return com.project.common.avro.CouponRollbackRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.CouponRollbackRequest> decoder() {
        // v1(orderId 없음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 order 파드가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.CouponRollbackRequest.createDecoder(AvroSchemaHistory.store(AvroSchemaHistory.COUPON_ROLLBACK));
    }
}
