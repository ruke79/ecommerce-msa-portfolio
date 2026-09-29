package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** CartEmptyRequest ↔ Avro. */
public class CartEmptyRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.CartEmptyRequest, com.project.common.avro.CartEmptyRequest> {

    @Override
    public Class<com.project.common.message.dto.request.CartEmptyRequest> dtoType() {
        return com.project.common.message.dto.request.CartEmptyRequest.class;
    }

    @Override
    public com.project.common.avro.CartEmptyRequest toRecord(com.project.common.message.dto.request.CartEmptyRequest d) {
        java.util.List<com.project.common.avro.CartEmptyItem> items = new java.util.ArrayList<>();
        for (com.project.common.message.dto.request.CartEmptyItem i : d.getItems()) {
            items.add(com.project.common.avro.CartEmptyItem.newBuilder()
                    .setProductId(i.getProductId()).setSize(i.getSize())
                    .setColorId(i.getColorId()).setQty(i.getQty()).build());
        }
        return com.project.common.avro.CartEmptyRequest.newBuilder()
                .setUserId(d.getUserId()).setEmptyCart(d.isEmptyCart())
                .setItems(items).build();
    }

    @Override
    public com.project.common.message.dto.request.CartEmptyRequest toDto(com.project.common.avro.CartEmptyRequest r) {
        java.util.List<com.project.common.message.dto.request.CartEmptyItem> items = new java.util.ArrayList<>();
        if (r.getItems() != null) {
            for (com.project.common.avro.CartEmptyItem i : r.getItems()) {
                items.add(new com.project.common.message.dto.request.CartEmptyItem(
                        i.getProductId(), i.getSize() == null ? null : i.getSize().toString(),
                        i.getColorId(), i.getQty()));
            }
        }
        return new com.project.common.message.dto.request.CartEmptyRequest(
                r.getUserId(), r.getEmptyCart(), items);
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.CartEmptyRequest> encoder() {
        return com.project.common.avro.CartEmptyRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.CartEmptyRequest> decoder() {
        // v1(authorizationToken 있음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 프로듀서가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.CartEmptyRequest.createDecoder(AvroSchemaHistory.store(AvroSchemaHistory.CART_EMPTY));
    }
}
