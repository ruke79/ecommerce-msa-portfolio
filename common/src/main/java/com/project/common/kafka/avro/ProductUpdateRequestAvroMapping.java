package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** ProductUpdateRequest ↔ Avro. */
public class ProductUpdateRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.ProductUpdateRequest, com.project.common.avro.ProductUpdateRequest> {

    @Override
    public Class<com.project.common.message.dto.request.ProductUpdateRequest> dtoType() {
        return com.project.common.message.dto.request.ProductUpdateRequest.class;
    }

    @Override
    public com.project.common.avro.ProductUpdateRequest toRecord(com.project.common.message.dto.request.ProductUpdateRequest d) {
        List<com.project.common.avro.OrderProductRequest> items = new ArrayList<>();
        if (d.getOrderProducts() != null) {
            for (com.project.common.message.dto.request.OrderProductRequest p : d.getOrderProducts()) {
                items.add(com.project.common.avro.OrderProductRequest.newBuilder()
                        .setProductId(p.getProductId()).setQty(p.getQty())
                        .setSize(p.getSize()).setColorId(p.getColorId()).build());
            }
        }
        return com.project.common.avro.ProductUpdateRequest.newBuilder()
                .setOrderProducts(items)
                .setEventId(d.getEventId()).setRestock(d.isRestock()).build();
    }

    @Override
    public com.project.common.message.dto.request.ProductUpdateRequest toDto(com.project.common.avro.ProductUpdateRequest r) {
        List<com.project.common.message.dto.request.OrderProductRequest> items = new ArrayList<>();
        for (com.project.common.avro.OrderProductRequest p : r.getOrderProducts()) {
            items.add(new com.project.common.message.dto.request.OrderProductRequest(
                    p.getProductId(), p.getQty(), p.getSize(), p.getColorId()));
        }
        com.project.common.message.dto.request.ProductUpdateRequest dto = new com.project.common.message.dto.request.ProductUpdateRequest();
        dto.setOrderProducts(items);
        dto.setEventId(r.getEventId());
        dto.setRestock(r.getRestock());
        return dto;
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.ProductUpdateRequest> encoder() {
        return com.project.common.avro.ProductUpdateRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.ProductUpdateRequest> decoder() {
        // v1(autorizationToken 있음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 order 가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.ProductUpdateRequest.createDecoder(
                AvroSchemaHistory.store(AvroSchemaHistory.PRODUCT_UPDATE));
    }
}
