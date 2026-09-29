package com.project.common.kafka.avro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

/** OutgoingMailRequest ↔ Avro. */
public class OutgoingMailRequestAvroMapping implements AvroMapping<com.project.common.message.dto.request.OutgoingMailRequest, com.project.common.avro.OutgoingMailRequest> {

    @Override
    public Class<com.project.common.message.dto.request.OutgoingMailRequest> dtoType() {
        return com.project.common.message.dto.request.OutgoingMailRequest.class;
    }

    @Override
    public com.project.common.avro.OutgoingMailRequest toRecord(com.project.common.message.dto.request.OutgoingMailRequest d) {
        return com.project.common.avro.OutgoingMailRequest.newBuilder()
                .setSourceEventId(d.getSourceEventId()).setToEmail(d.getToEmail())
                .setRecipientUserId(d.getRecipientUserId()).setKind(d.getKind())
                .setParams(d.getParams() == null ? new HashMap<>() : new HashMap<>(d.getParams()))
                .setLocale(d.getLocale()).build();
    }

    @Override
    public com.project.common.message.dto.request.OutgoingMailRequest toDto(com.project.common.avro.OutgoingMailRequest r) {
        return com.project.common.message.dto.request.OutgoingMailRequest.builder()
                .sourceEventId(r.getSourceEventId()).toEmail(r.getToEmail())
                .recipientUserId(r.getRecipientUserId()).kind(r.getKind())
                .params(r.getParams() == null ? new HashMap<>() : new HashMap<>(r.getParams()))
                .locale(r.getLocale()).build();
    }

    @Override
    public BinaryMessageEncoder<com.project.common.avro.OutgoingMailRequest> encoder() {
        return com.project.common.avro.OutgoingMailRequest.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<com.project.common.avro.OutgoingMailRequest> decoder() {
        return com.project.common.avro.OutgoingMailRequest.getDecoder();
    }
}
