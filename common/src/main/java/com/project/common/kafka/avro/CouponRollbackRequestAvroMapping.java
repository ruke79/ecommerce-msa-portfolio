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
                .setUserId(d.getUserId()).setCouponName(d.getCouponName()).build();
    }

    @Override
    public com.project.common.message.dto.request.CouponRollbackRequest toDto(com.project.common.avro.CouponRollbackRequest r) {
        return com.project.common.message.dto.request.CouponRollbackRequest.builder()
                .userId(r.getUserId()).couponName(r.getCouponName()).build();
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.CouponRollbackRequest> encoder() {
        return com.project.common.avro.CouponRollbackRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.CouponRollbackRequest> decoder() {
        return com.project.common.avro.CouponRollbackRequest.getDecoder();
    }
}
