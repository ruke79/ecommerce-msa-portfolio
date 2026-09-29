package com.project.delivery_service.carrier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.project.common.i18n.ClientText;
import com.project.delivery_service.domain.Carrier;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryEvent;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.dto.DeliveryResponse;

/**
 * 🔴 테스트배송의 이력 문구·택배사 이름은 모두 일본어가 있다 (docs/60 §10).
 *
 * <p>이력은 한국어로 저장되고 조회 응답에서 바뀐다. 시뮬레이터에 단계나 장소를 더하고 카탈로그를 빠뜨리면 일본어 화면의
 * 배송 조회에 한국어가 남는다 — 한국어 화면에서는 멀쩡해 보인다.
 */
@DisplayName("배송 이력 문구 — 일본어")
class SimulatedCarrierTextsTest {

    private static final Locale JA = Locale.JAPANESE;

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("🔴 모든 장소·설명·택배사 이름이 일본어로 바뀐다 — 한글이 남지 않는다")
    void everyTextHasJapanese() {
        for (String text : SimulatedCarrierTexts.FORMATS) {
            assertThat(ClientText.localize(text, JA)).as("번역이 없는 배송 문구: %s", text)
                    .doesNotContainPattern("[\\uac00-\\ud7a3]");
        }
    }

    @Test
    @DisplayName("택배사가 만드는 상태마다 설명이 있다 — 새 상태를 더하면 enum 이름이 화면에 나가지 않게")
    void everyStatusHasDescription() {
        for (DeliveryStatus status : DeliveryStatus.values()) {
            // 발송 전(PENDING)과 우리 쪽 취소(CANCELLED)는 택배사 이력이 아니다
            if (status == DeliveryStatus.PENDING || status == DeliveryStatus.CANCELLED) {
                continue;
            }
            assertThat(SimulatedCarrierTexts.DESCRIPTIONS).as("설명이 없는 상태: %s", status).containsKey(status);
        }
        assertThat(SimulatedCarrierTexts.descriptionFor(DeliveryStatus.DELIVERED)).isEqualTo("배달이 완료되었습니다");
        assertThat(SimulatedCarrierTexts.locationFor(99)).isEqualTo("수원");
    }

    @Test
    @DisplayName("🔴 조회 응답 — 일본어 요청이면 장소·설명·택배사를 바꾸고, 저장값은 그대로")
    void responseIsLocalizedOnRead() {
        DeliveryEvent event = sampleEvent();

        withRequest(JA);
        var ja = shipmentOf(event);
        assertThat(ja.carrierName()).isEqualTo("テスト配送");
        assertThat(ja.events().get(0).location()).isEqualTo("大田ハブ");
        assertThat(ja.events().get(0).description()).isEqualTo("輸送中です");
        // 중복 제거 키에 쓰이는 저장값은 바뀌지 않는다
        assertThat(event.getLocation()).isEqualTo("대전허브");

        withRequest(Locale.KOREAN);
        var ko = shipmentOf(event);
        assertThat(ko.carrierName()).isEqualTo("테스트배송");
        assertThat(ko.events().get(0).description()).isEqualTo("이동 중입니다");
    }

    @Test
    @DisplayName("🔴 우체국 상태 낱말(어댑터가 알아보는 것 전부)은 일본어가 있다 — 받는 사람 이름은 그대로")
    void koreaPostStatusWords() throws Exception {
        java.lang.reflect.Field f = KoreaPostCarrierAdapter.class.getDeclaredField("STATUS_RULES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map.Entry<String, ?>> rules = (java.util.List<java.util.Map.Entry<String, ?>>) f.get(null);
        java.util.List<String> words = rules.stream().map(java.util.Map.Entry::getKey).toList();
        assertThat(words).isNotEmpty();
        for (String w : words) {
            assertThat(ClientText.localize(w, JA)).as("번역이 없는 우체국 상태: %s", w).doesNotContainPattern("[\\uac00-\\ud7a3]");
        }
        assertThat(ClientText.localize("배달완료(홍길동)", JA)).isEqualTo("配達完了（홍길동）");
    }

    @Test
    @DisplayName("실제 택배사가 보낸 문구(카탈로그에 없음)는 원문 그대로")
    void carrierDataPassesThrough() {
        assertThat(ClientText.localize("간선 상차", JA)).isEqualTo("간선 상차");
    }

    private static Shipment holder;

    private static DeliveryEvent sampleEvent() {
        Delivery delivery = Delivery.builder().orderId(1L).customerId(2L)
                .recipientName("X").addressLine1("Y").country("KR").build();
        holder = delivery.addShipment(false);
        holder.assignTracking(SimulatedCarrierAdapter.CODE, "SIM-1", true);
        DeliveryEvent e = new DeliveryEvent(holder, DeliveryStatus.IN_TRANSIT, LocalDateTime.of(2026, 9, 26, 10, 0),
                SimulatedCarrierTexts.locationFor(2), SimulatedCarrierTexts.descriptionFor(DeliveryStatus.IN_TRANSIT),
                "SIM:IN_TRANSIT", "{}");
        holder.addEvent(e);
        return e;
    }

    private static DeliveryResponse.ShipmentView shipmentOf(DeliveryEvent event) {
        Carrier carrier = new Carrier(SimulatedCarrierAdapter.CODE, "테스트배송", "Simulated Carrier", null, true);
        return DeliveryResponse.from(holder.getDelivery(), Map.of(SimulatedCarrierAdapter.CODE, carrier))
                .shipments().get(0);
    }

    private static void withRequest(Locale locale) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        LocaleContextHolder.setLocale(locale);
    }
}
