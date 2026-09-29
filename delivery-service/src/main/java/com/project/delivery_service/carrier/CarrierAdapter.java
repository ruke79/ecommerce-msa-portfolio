package com.project.delivery_service.carrier;

/**
 * 택배사 연동. <b>확장 지점</b>이다(docs/25 §4).
 *
 * <p>이 인터페이스의 책임은 <b>하나뿐</b>이다: 송장번호 → 표준 이벤트 목록.
 * 상태 판정·중복 제거·주문 연동·알림 발송은 전부 도메인 쪽에 있다. 그래서 새 택배사를
 * 붙일 때 필요한 것은:
 *
 * <ol>
 *   <li>이 인터페이스를 구현한 클래스 하나 (HTTP 호출 + 응답 매핑만 안다)</li>
 *   <li>{@code carrier} 마스터 테이블의 행 하나</li>
 * </ol>
 *
 * <p>스케줄러·상태 전이·화면은 손대지 않는다. 그것이 이 추상화의 존재 이유다 —
 * 택배사가 늘어날 때마다 도메인 코드가 바뀌면 추상화한 의미가 없다.
 *
 * <p>구현체는 예외를 던지지 않고 {@link TrackingResult} 로 결과를 돌려준다. 배송 하나의
 * 조회 실패가 배치 전체를 멈출 이유가 없기 때문이다.
 */
public interface CarrierAdapter {

    /** {@code carrier} 테이블의 코드와 일치해야 한다. 이 값으로 어댑터가 선택된다. */
    String carrierCode();

    /**
     * @param trackingNumber 송장번호
     * @param international  국제 배송인가. 택배사에 따라 국내/국제 조회 엔드포인트가 다르다
     */
    TrackingResult track(String trackingNumber, boolean international);

    /**
     * 이 택배사의 데이터가 바뀌는 최소 주기. 비어 있으면 상태별 기본 주기({@code DeliveryStatus})만 쓴다.
     *
     * <p>기본 주기는 실제 택배사의 리듬(접수 뒤 6시간, 이동 중 3시간)에 맞춘 것이다. 그런데
     * 시뮬레이터는 1분마다 단계를 밟는데 폴러가 6시간 뒤에나 다시 물으면, 그때 이력 전체를
     * 받아 <b>곧장 배달 완료로 뛴다</b> — 중간 단계도 그 알림도 건너뛴다. 실제로 그래서
     * 배송 완료를 시험할 수 없었다(docs/25 §11.10). 택배사가 자기 리듬을 아는 것이 맞다.
     * 상자는 기본 주기와 이 값 중 <b>짧은 쪽</b>을 쓴다 — 늘리는 데는 쓰지 않는다.
     */
    default java.util.Optional<java.time.Duration> pollIntervalCap() {
        return java.util.Optional.empty();
    }
}
