package com.project.delivery_service.carrier;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조회 결과.
 *
 * <p>SMS 발송과 같은 이유로 실패를 <b>재시도 가능/불가능</b>으로 나눈다(docs/21).
 * 일시적 실패를 영구 실패로 처리하면 그 배송은 다시는 갱신되지 않고, 영구 실패를 계속
 * 재시도하면 쿼터만 태운다.
 *
 * <ul>
 *   <li>{@code OK}         — 이벤트 목록을 받았다 (빈 목록일 수도 있다: 아직 스캔 전)</li>
 *   <li>{@code NOT_FOUND}  — 택배사가 그런 송장번호를 모른다. 등록 직후에는 정상이고,
 *                            며칠째 계속이면 송장번호가 잘못됐다는 뜻이다</li>
 *   <li>{@code RETRYABLE}  — 네트워크 오류·5xx·쿼터 초과</li>
 *   <li>{@code PERMANENT}  — 인증 실패, 지원하지 않는 요청. 사람이 봐야 한다</li>
 * </ul>
 */
public record TrackingResult(Status status, List<TrackingEvent> events,
                             LocalDateTime estimatedAt, String detail) {

    public enum Status { OK, NOT_FOUND, RETRYABLE, PERMANENT }

    public static TrackingResult ok(List<TrackingEvent> events, LocalDateTime estimatedAt) {
        return new TrackingResult(Status.OK, events, estimatedAt, null);
    }

    public static TrackingResult notFound() {
        return new TrackingResult(Status.NOT_FOUND, List.of(), null, "송장번호를 찾을 수 없습니다");
    }

    public static TrackingResult retryable(String detail) {
        return new TrackingResult(Status.RETRYABLE, List.of(), null, detail);
    }

    public static TrackingResult permanent(String detail) {
        return new TrackingResult(Status.PERMANENT, List.of(), null, detail);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
