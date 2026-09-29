package com.project.delivery_service.carrier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.delivery_service.domain.DeliveryStatus;

/**
 * 우체국 응답 매핑.
 *
 * <p><b>이 테스트가 존재하는 이유.</b> 실제 API 키가 없어 실호출을 검증할 수 없다. 그래서
 * HTTP 호출과 파싱을 분리하고, 파싱만 기록된 형태의 응답으로 검증한다. 키를 받으면
 * 필드 이름만 yml 에서 맞추면 되고, 매핑 규칙은 이미 고정돼 있다(docs/25 §4.1).
 */
@DisplayName("우체국 응답 매핑")
class KoreaPostCarrierAdapterTest {

    private final KoreaPostCarrierAdapter adapter = new KoreaPostCarrierAdapter(
            "http://unused", "dummy-key",
            KoreaPostCarrierAdapter.FieldMapping.defaults(),
            Duration.ofSeconds(1), new ObjectMapper());

    @ParameterizedTest(name = "'{0}' → {1}")
    @CsvSource({
            // 순서가 중요하다: '배달완료' 가 '배달' 보다 먼저 매칭돼야 한다.
            "배달완료,            DELIVERED",
            "배달완료(홍길동),     DELIVERED",
            "'배달 완료',          DELIVERED",
            "배달출발,            OUT_FOR_DELIVERY",
            "배달준비,            OUT_FOR_DELIVERY",
            "집화완료,            PICKED_UP",
            "발송,               IN_TRANSIT",
            "도착,               IN_TRANSIT",
            "접수,               INFO_RECEIVED",
            "수취인부재,          ATTEMPT_FAILED",
            "통관보류,            CUSTOMS_HOLD",
            "통관완료,            CUSTOMS_CLEARED",
            "반송완료,            RETURNED",
            "분실,               EXCEPTION",
    })
    void 상태_매핑(String raw, DeliveryStatus expected) {
        assertThat(adapter.mapStatus(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("공백이 섞여도 매칭된다 — 택배사는 표기를 조금씩 바꾼다")
    void 공백_무시() {
        assertThat(adapter.mapStatus("배 달 완 료")).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("모르는 상태는 IN_TRANSIT 으로 둔다 — 잘못된 경보를 주지 않기 위해")
    void 미지의_상태() {
        // EXCEPTION 으로 두면 택배사가 문구를 하나 바꿨을 뿐인데 정상 배송이 전부
        // '문제 있음' 으로 표시된다.
        assertThat(adapter.mapStatus("새로운상태문구")).isEqualTo(DeliveryStatus.IN_TRANSIT);
    }

    @ParameterizedTest
    @CsvSource({
            "20260728143000, 2026-07-28T14:30",
            "'2026-07-28 14:30:00', 2026-07-28T14:30",
            "'2026.07.28 14:30', 2026-07-28T14:30",
            "20260728, 2026-07-28T00:00",
    })
    @DisplayName("여러 시각 형식을 받아들인다 — 어느 것이 올지 확정할 수 없다")
    void 시각_파싱(String raw, String expected) {
        assertThat(adapter.parseTime(raw)).hasToString(expected);
    }

    @Test
    @DisplayName("항목이 하나면 배열이 아니라 객체로 온다 — 공공 API 의 흔한 함정")
    void 단일_항목이_객체로_와도_처리한다() {
        String body = """
                {"response":{"body":{"items":{"item":
                  {"dlvyDate":"20260728143000","dlvySttus":"집화완료","nowLc":"서울강남","dlvyNm":"집화"}
                }}}}""";

        TrackingResult result = adapter.parse(body);

        assertThat(result.isOk()).isTrue();
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).status()).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(result.events().get(0).location()).isEqualTo("서울강남");
    }

    @Test
    @DisplayName("이력 전체를 파싱하고 원문을 보존한다")
    void 이력_파싱() {
        String body = """
                {"response":{"body":{"items":{"item":[
                  {"dlvyDate":"20260728090000","dlvySttus":"접수","nowLc":"서울강남","dlvyNm":"접수"},
                  {"dlvyDate":"20260728143000","dlvySttus":"집화완료","nowLc":"서울강남","dlvyNm":"집화"},
                  {"dlvyDate":"20260729080000","dlvySttus":"배달출발","nowLc":"수원","dlvyNm":"배달"}
                ]}}}}""";

        TrackingResult result = adapter.parse(body);

        assertThat(result.events()).hasSize(3);
        assertThat(result.events().get(2).status()).isEqualTo(DeliveryStatus.OUT_FOR_DELIVERY);
        assertThat(result.events().get(2).rawStatus())
                .as("매핑이 틀렸을 때 원문이 없으면 왜 그렇게 판정됐는지 재구성할 수 없다")
                .isEqualTo("배달출발");
        assertThat(result.events().get(2).rawPayload()).contains("수원");
    }

    @Test
    @DisplayName("시각이나 상태가 없는 항목은 건너뛴다 — 타임라인에 놓을 수 없다")
    void 필드가_없는_항목() {
        String body = """
                {"response":{"body":{"items":{"item":[
                  {"dlvySttus":"집화완료"},
                  {"dlvyDate":"20260728143000","dlvySttus":"배달완료","nowLc":"수원"}
                ]}}}}""";

        TrackingResult result = adapter.parse(body);
        assertThat(result.events()).hasSize(1);
    }

    @Test
    @DisplayName("빈 응답은 NOT_FOUND — 등록 직후에는 정상이다")
    void 빈_응답() {
        assertThat(adapter.parse("""
                {"response":{"body":{"items":{}}}}""").status())
                .isEqualTo(TrackingResult.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("파싱 실패는 영구 실패 — 재시도해도 같다")
    void 깨진_응답() {
        assertThat(adapter.parse("이건 JSON 이 아니다").status())
                .isEqualTo(TrackingResult.Status.PERMANENT);
    }

    @Test
    @DisplayName("서비스키가 없으면 조용히 넘어가지 않고 영구 실패로 알린다")
    void 키가_없으면() {
        KoreaPostCarrierAdapter noKey = new KoreaPostCarrierAdapter(
                "http://unused", "", KoreaPostCarrierAdapter.FieldMapping.defaults(),
                Duration.ofSeconds(1), new ObjectMapper());

        TrackingResult result = noKey.track("1234", false);

        assertThat(result.status()).isEqualTo(TrackingResult.Status.PERMANENT);
        assertThat(result.detail())
                .as("조용히 빈 결과를 주면 '왜 갱신이 안 되지'가 된다")
                .contains("서비스키");
    }
}
