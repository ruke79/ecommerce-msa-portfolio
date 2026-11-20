package com.project.delivery_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 배송지 변경. 발송 전에만 받아들여진다. */
// [JA] 配送先の変更。発送前にだけ受け付ける。
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
        // [JA] 変更理由。紛争時に履歴と一緒に読まれる。
        @Size(max = 200) String reason) {
}
