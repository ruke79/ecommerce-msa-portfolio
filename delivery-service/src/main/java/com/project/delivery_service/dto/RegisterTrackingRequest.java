package com.project.delivery_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송장 등록 (판매자·물류).
 *
 * @param trackingNumber 송장번호. 테스트 택배사(SIMULATED)면 비워도 된다 — {@code SIM-<주문>-<상자>} 를 만든다(정상 진행 각본).
 *                       다른 각본을 보려면 SIMFAIL-·SIMEXC-·SIMINTL- 로 직접 적는다
 * @param sequenceNo 어느 상자인가. null 이면 송장이 없는 첫 상자에 붙이고, 그런 상자도 없으면
 *                   <b>새 상자를 만든다</b> — 분할 배송이 이 경로로 생긴다
 */
public record RegisterTrackingRequest(
        @NotBlank @Size(max = 30) String carrierCode,
        @Size(max = 60) String trackingNumber,
        Integer sequenceNo,
        /** 국제 배송의 개인통관고유부호. 개인정보이므로 응답에는 마스킹해서 내려간다. */
        @Size(max = 40) String customsClearanceNo,
        @Size(max = 40) String customsDeclarationNo) {
}
