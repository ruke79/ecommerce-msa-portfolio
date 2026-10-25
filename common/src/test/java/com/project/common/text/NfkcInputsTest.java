package com.project.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.project.common.dto.order.OrderAddressDto;

/**
 * 공통 DTO 가 받을 때 NFKC 로 맞추는 값 — 검색어(캐시 키 · ES · DB 가 같은 글자를 본다)와 주문 배송지의 우편번호 · 전화번호.
 *
 * <p>[JA] 共通 DTO が受け取り時に NFKC で揃える値 — 検索語と注文配送先の郵便番号 · 電話番号。
 */
@DisplayName("공통 DTO — 검색어 · 배송지 번호는 받을 때 NFKC")
class NfkcInputsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("🔴 검색어 — 전각 · 반각 카나 · 전각 공백이 같은 검색이 된다(캐시 키도 하나)")
    void searchTerm() throws Exception {
        SearchParamsRequest wide = mapper.readValue("{\"search\":\" ＮＩＫＥ　ｼｭｰｽﾞ \"}", SearchParamsRequest.class);
        SearchParamsRequest plain = mapper.readValue("{\"search\":\"NIKE シューズ\"}", SearchParamsRequest.class);
        assertThat(wide.getSearch()).isEqualTo("NIKE シューズ");
        assertThat(wide.toString()).as("캐시 키(toString)도 같다").isEqualTo(plain.toString());
    }

    @Test
    @DisplayName("🔴 주문 배송지 — 우편번호 · 전화번호만 반각, 주소는 그대로")
    void orderAddressNumbers() throws Exception {
        OrderAddressDto a = mapper.readValue("""
                {"lastname":"山田","address1":"港区①","city":"港区","state":"東京都",
                 "zipCode":"１０５－００１１","phoneNumber":"０３－１２３４－５６７８"}""", OrderAddressDto.class);
        assertThat(a.getZipCode()).isEqualTo("105-0011");
        assertThat(a.getPhoneNumber()).isEqualTo("03-1234-5678");
        assertThat(a.getAddress1()).isEqualTo("港区①");
    }
}
