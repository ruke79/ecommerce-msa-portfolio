package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** UserCreatedRequest ↔ Avro. */
public class UserCreatedRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.UserCreatedRequest, com.project.common.avro.UserCreatedRequest> {

    @Override
    public Class<com.project.common.message.dto.request.UserCreatedRequest> dtoType() {
        return com.project.common.message.dto.request.UserCreatedRequest.class;
    }

    @Override
    public com.project.common.avro.UserCreatedRequest toRecord(com.project.common.message.dto.request.UserCreatedRequest d) {
        return com.project.common.avro.UserCreatedRequest.newBuilder()
                .setUserId(d.getUserId()).setImage(d.getImage()).setUsername(d.getUsername())
                .setNickname(d.getNickname()).build();
    }

    @Override
    public com.project.common.message.dto.request.UserCreatedRequest toDto(com.project.common.avro.UserCreatedRequest r) {
        return com.project.common.message.dto.request.UserCreatedRequest.builder()
                .userId(r.getUserId()).image(r.getImage()).username(r.getUsername())
                .nickname(r.getNickname()).build();
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.UserCreatedRequest> encoder() {
        return com.project.common.avro.UserCreatedRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.UserCreatedRequest> decoder() {
        // v1(email 있음)으로 쓴 레코드도 읽는다 — 배포 도중 옛 user-service 가 쓴 것(AvroSchemaHistory).
        return com.project.common.avro.UserCreatedRequest.createDecoder(
                AvroSchemaHistory.store(AvroSchemaHistory.USER_CREATED));
    }
}
