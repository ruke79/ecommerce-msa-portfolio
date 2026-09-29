package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** CartRollbackRequest ↔ Avro. */
public class CartRollbackRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.CartRollbackRequest, com.project.common.avro.CartRollbackRequest> {

    @Override
    public Class<com.project.common.message.dto.request.CartRollbackRequest> dtoType() {
        return com.project.common.message.dto.request.CartRollbackRequest.class;
    }

    @Override
    public com.project.common.avro.CartRollbackRequest toRecord(com.project.common.message.dto.request.CartRollbackRequest d) {
        return com.project.common.avro.CartRollbackRequest.newBuilder()
                .setUserId(d.getUserId()).setQty(d.getQty()).build();
    }

    @Override
    public com.project.common.message.dto.request.CartRollbackRequest toDto(com.project.common.avro.CartRollbackRequest r) {
        return new com.project.common.message.dto.request.CartRollbackRequest(
                r.getUserId(), r.getQty());
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.CartRollbackRequest> encoder() {
        return com.project.common.avro.CartRollbackRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.CartRollbackRequest> decoder() {
        // v1(authorizationToken 있음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 프로듀서가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.CartRollbackRequest.createDecoder(AvroSchemaHistory.store(AvroSchemaHistory.CART_ROLLBACK));
    }
}
