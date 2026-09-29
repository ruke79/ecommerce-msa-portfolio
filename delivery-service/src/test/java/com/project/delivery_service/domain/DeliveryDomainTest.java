package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 배송 도메인 규칙 — <b>분할 배송·국제 배송·주소 변경·취소</b>.
 *
 * <p>이 네 가지는 "나중에 넣자"가 되기 쉬운데, 각각 스키마와 상태 규칙을 건드리므로 나중에
 * 끼워 넣으면 마이그레이션과 화면을 함께 뜯어야 한다. 규칙을 여기서 고정한다.
 */
@DisplayName("배송 도메인 규칙")
class DeliveryDomainTest {

    private Delivery delivery(String country) {
        return Delivery.builder()
                .orderId(1L).customerId(10L)
                .recipientName("홍길동").recipientPhone("+821012345678")
                .postalCode("06236").addressLine1("서울 강남구 테헤란로 1")
                .city("서울").country(country)
                .build();
    }

    @Nested
    @DisplayName("분할 배송")
    class SplitShipment {

        @Test
        @DisplayName("한 주문이 여러 상자로 나뉘고, 상자마다 택배사·송장이 다를 수 있다")
        void 상자마다_독립적이다() {
            Delivery d = delivery("KR");
            Shipment first = d.addShipment(false);
            Shipment second = d.addShipment(false);

            first.assignTracking("KOREA_POST", "1111", true);
            second.assignTracking("SIMULATED", "SIM-2222", true);

            assertThat(d.getShipments()).hasSize(2);
            assertThat(first.getSequenceNo()).isEqualTo(1);
            assertThat(second.getSequenceNo()).isEqualTo(2);
            assertThat(first.getCarrierCode()).isNotEqualTo(second.getCarrierCode());
        }

        @Test
        @DisplayName("전체 진행도는 **가장 뒤처진 상자**를 따른다")
        void 가장_뒤처진_상자를_따른다() {
            Delivery d = delivery("KR");
            Shipment first = d.addShipment(false);
            Shipment second = d.addShipment(false);
            first.assignTracking("SIMULATED", "SIM-1", true);
            second.assignTracking("SIMULATED", "SIM-2", true);

            first.advanceTo(DeliveryStatus.DELIVERED, LocalDateTime.now(ZoneOffset.UTC));
            second.advanceTo(DeliveryStatus.IN_TRANSIT, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(d.overallStatus())
                    .as("2개 중 1개만 도착했는데 '배달완료'로 표시하면 사용자가 나머지를 신고할 수 없다")
                    .isEqualTo(DeliveryStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("상자 하나에 문제가 생기면 전체가 예외로 보인다 — 문제는 묻히면 안 된다")
        void 예외가_우선한다() {
            Delivery d = delivery("KR");
            Shipment ok = d.addShipment(false);
            Shipment broken = d.addShipment(false);
            ok.assignTracking("SIMULATED", "SIM-1", true);
            broken.assignTracking("SIMULATED", "SIM-2", true);

            ok.advanceTo(DeliveryStatus.DELIVERED, LocalDateTime.now(ZoneOffset.UTC));
            broken.advanceTo(DeliveryStatus.EXCEPTION, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(d.overallStatus()).isEqualTo(DeliveryStatus.EXCEPTION);
        }

        @Test
        @DisplayName("전체 예상 도착일은 가장 늦게 오는 상자 기준")
        void 예상_도착일은_가장_늦은_상자() {
            Delivery d = delivery("KR");
            Shipment first = d.addShipment(false);
            Shipment second = d.addShipment(false);
            LocalDateTime soon = LocalDateTime.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime later = LocalDateTime.now(ZoneOffset.UTC).plusDays(3);
            first.setEstimatedAt(soon);
            second.setEstimatedAt(later);

            assertThat(d.overallEstimatedAt())
                    .as("사용자가 알고 싶은 것은 '언제 다 오나'다")
                    .isEqualTo(later);
        }
    }

    @Nested
    @DisplayName("국제 배송")
    class International {

        @Test
        @DisplayName("목적지가 발송국과 다르면 국제 배송이다")
        void 국제_판정() {
            assertThat(delivery("JP").isInternational("KR")).isTrue();
            assertThat(delivery("KR").isInternational("KR")).isFalse();
        }

        @Test
        @DisplayName("통관 보류는 알림 대상이다 — 사용자가 서류를 내야 풀린다")
        void 통관_보류는_알림_대상() {
            assertThat(DeliveryStatus.CUSTOMS_HOLD.isWorthNotifying())
                    .as("통관 보류를 조용히 두면 사용자는 왜 며칠째 멈춰 있는지 모른다")
                    .isTrue();
            assertThat(DeliveryStatus.CUSTOMS_HOLD.stage()).isEqualTo(DeliveryStatus.Stage.CUSTOMS);
        }

        @Test
        @DisplayName("통관 단계는 이동 중 다음, 배달 출발 앞에 온다")
        void 통관_단계의_순서() {
            // 순서가 곧 진행도다. 통관이 배달출발 뒤에 오면 통관 완료가 진행으로 인정되지 않는다.
            assertThat(DeliveryStatus.CUSTOMS_HOLD.isProgressFrom(DeliveryStatus.IN_TRANSIT)).isTrue();
            assertThat(DeliveryStatus.CUSTOMS_CLEARED.isProgressFrom(DeliveryStatus.CUSTOMS_HOLD)).isTrue();
            assertThat(DeliveryStatus.OUT_FOR_DELIVERY.isProgressFrom(DeliveryStatus.CUSTOMS_CLEARED)).isTrue();
        }
    }

    @Nested
    @DisplayName("배송 전 주소 변경")
    class AddressChangeRules {

        @Test
        @DisplayName("발송 전에는 변경할 수 있고 이력이 남는다")
        void 발송_전에는_변경_가능() {
            Delivery d = delivery("KR");
            d.addShipment(false);

            AddressChange change = d.changeAddress(10L, "김철수", "+821099998888",
                    "13494", "경기 성남시 분당구 판교로 1", null, "성남", "경기", "KR", null, "이사");

            assertThat(change).isNotNull();
            assertThat(d.getAddressLine1()).isEqualTo("경기 성남시 분당구 판교로 1");
            assertThat(change.getPreviousAddress())
                    .as("분쟁 때 '무엇을 무엇으로 바꿨는지'가 필요하다")
                    .contains("테헤란로");
            assertThat(change.getNewAddress()).contains("판교로");
        }

        @Test
        @DisplayName("상자가 하나라도 집화되면 변경할 수 없다")
        void 발송_후에는_변경_불가() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);
            s.advanceTo(DeliveryStatus.PICKED_UP, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(d.canChangeAddress())
                    .as("실물이 택배사 손에 있으면 우리가 목적지를 바꿀 수 없다")
                    .isFalse();
            assertThat(d.changeAddress(10L, null, null, "13494", "다른 주소", null,
                    null, null, "KR", null, null)).isNull();
            assertThat(d.getAddressLine1())
                    .as("변경이 거부됐으면 주소는 그대로여야 한다")
                    .isEqualTo("서울 강남구 테헤란로 1");
        }

        @Test
        @DisplayName("송장 등록 전(상자만 있는 상태)에도 변경 가능")
        void 송장_등록_전에도_가능() {
            Delivery d = delivery("KR");
            d.addShipment(false);
            assertThat(d.canChangeAddress()).isTrue();
        }
    }

    @Nested
    @DisplayName("취소와 재주문")
    class CancelAndReorder {

        @Test
        @DisplayName("발송 전에는 모든 상자가 취소된다")
        void 발송_전_취소() {
            Delivery d = delivery("KR");
            d.addShipment(false);
            d.addShipment(false);

            assertThat(d.cancel()).isTrue();
            assertThat(d.getShipments())
                    .allMatch(s -> s.getStatus() == DeliveryStatus.CANCELLED);
            assertThat(d.overallStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        }

        @Test
        @DisplayName("하나라도 발송됐으면 취소되지 않는다 — 그건 반품이다")
        void 발송_후에는_취소_불가() {
            Delivery d = delivery("KR");
            Shipment a = d.addShipment(false);
            d.addShipment(false);
            a.assignTracking("SIMULATED", "SIM-1", true);
            a.advanceTo(DeliveryStatus.PICKED_UP, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(d.cancel())
                    .as("움직이는 물건을 '취소'로 표시하면 실물과 기록이 어긋난다")
                    .isFalse();
        }

        @Test
        @DisplayName("취소된 배송은 폴링 대상에서 빠진다")
        void 취소되면_폴링_중단() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);
            assertThat(s.getNextPollAt()).isNotNull();

            s.cancel();
            assertThat(s.getNextPollAt())
                    .as("끝난 배송을 계속 조회하는 것이 API 쿼터 낭비의 주범이다")
                    .isNull();
        }

        @Test
        @DisplayName("재주문은 새 배송이고, 원래 주문과 연결된다")
        void 재주문은_새_배송() {
            Delivery reorder = Delivery.builder()
                    .orderId(2L).customerId(10L).reorderedFromOrderId(1L)
                    .recipientName("홍길동").addressLine1("서울 강남구 테헤란로 1")
                    .country("KR").build();

            assertThat(reorder.getReorderedFromOrderId()).isEqualTo(1L);
            assertThat(reorder.getOrderId())
                    .as("취소된 배송을 재사용하면 취소 이력과 새 배송 이력이 한 행에 뒤섞인다")
                    .isNotEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("상태 전이")
    class Transitions {

        @Test
        @DisplayName("뒤로 가지 않는다 — 택배사 이력은 순서가 뒤바뀌어 올 수 있다")
        void 역행하지_않는다() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);
            s.advanceTo(DeliveryStatus.OUT_FOR_DELIVERY, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(s.advanceTo(DeliveryStatus.IN_TRANSIT, LocalDateTime.now(ZoneOffset.UTC)))
                    .as("'배달출발'이던 배송이 '이동중'으로 되돌아가면 사용자가 혼란스럽다")
                    .isFalse();
            assertThat(s.getStatus()).isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("예외는 언제든 될 수 있다 — 실제로 사고가 났으므로")
        void 예외는_언제든() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);
            s.advanceTo(DeliveryStatus.OUT_FOR_DELIVERY, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(s.advanceTo(DeliveryStatus.EXCEPTION, LocalDateTime.now(ZoneOffset.UTC))).isTrue();
        }

        @Test
        @DisplayName("배달 완료 뒤에는 어떤 전이도 없다")
        void 종료_상태는_되돌리지_않는다() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);
            s.advanceTo(DeliveryStatus.DELIVERED, LocalDateTime.now(ZoneOffset.UTC));

            assertThat(s.advanceTo(DeliveryStatus.EXCEPTION, LocalDateTime.now(ZoneOffset.UTC))).isFalse();
            assertThat(s.getNextPollAt()).as("폴링도 멈춘다").isNull();
        }

        @Test
        @DisplayName("연속 실패는 지수 백오프로 물러서되 상한이 있다")
        void 실패_백오프() {
            Delivery d = delivery("KR");
            Shipment s = d.addShipment(false);
            s.assignTracking("SIMULATED", "SIM-1", true);

            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
            for (int i = 0; i < 10; i++) {
                s.recordSyncFailure();
            }
            assertThat(s.getNextPollAt())
                    .as("무한히 물러서면 API 가 복구된 뒤에도 한참 뒤에야 다시 조회한다")
                    .isBefore(before.plusHours(13));
            assertThat(s.getPollFailures()).isEqualTo(10);
        }
    }
}
