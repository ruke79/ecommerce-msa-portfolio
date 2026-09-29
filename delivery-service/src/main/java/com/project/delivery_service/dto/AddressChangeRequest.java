package com.project.delivery_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 배송지 변경. 발송 전에만 받아들여진다. */
public record AddressChangeRequest(
        @Size(max = 80) String recipientName,
        @Size(max = 20) String recipientPhone,
        @Size(max = 12) String postalCode,
        @NotBlank @Size(max = 200) String addressLine1,
        @Size(max = 200) String addressLine2,
        @Size(max = 80) String city,
        @Size(max = 80) String state,
        @Size(max = 2) String country,
        @Size(max = 200) String deliveryMemo,
        /** 변경 사유. 분쟁 때 이력과 함께 읽힌다. */
        @Size(max = 200) String reason) {
}
