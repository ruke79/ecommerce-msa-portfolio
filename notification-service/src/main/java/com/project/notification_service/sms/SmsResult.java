package com.project.notification_service.sms;

/**
 * 발송 결과.
 *
 * <p>실패를 <b>재시도 가능</b>과 <b>불가능</b>으로 나누는 것이 핵심이다. 아웃박스에서 겪은 것과
 * 같은 이유다(docs/21): 일시적 실패를 영구 실패로 기록하면 그 알림은 다시는 시도되지 않고,
 * 영구 실패를 계속 재시도하면 큐가 막힌다.
 *
 * <ul>
 *   <li>{@code SENT}          — 게이트웨이가 접수했다. 단말 도달은 별개다(§ 아래)</li>
 *   <li>{@code RETRYABLE}     — 네트워크 오류, 5xx, 속도 제한. 다음에 다시 시도한다</li>
 *   <li>{@code PERMANENT}     — 잘못된 번호, 잔액 부족, 발신번호 미등록. 재시도해도 같다</li>
 * </ul>
 *
 * <p>⚠️ {@code SENT} 는 "공급자가 접수했다"까지만 뜻한다. 단말에 실제로 도착했는지는 공급자의
 * 전달 리포트(DLR) 웹훅을 받아야 알 수 있다 — 로드맵에 남겨 둔다. 이 구분을 흐려 두면
 * "보냈다고 나오는데 안 왔다"는 문의를 추적할 수 없다.
 */
public record SmsResult(Status status, String providerMessageId, String detail) {

    public enum Status { SENT, RETRYABLE, PERMANENT }

    public static SmsResult sent(String providerMessageId) {
        return new SmsResult(Status.SENT, providerMessageId, null);
    }

    public static SmsResult retryable(String detail) {
        return new SmsResult(Status.RETRYABLE, null, detail);
    }

    public static SmsResult permanent(String detail) {
        return new SmsResult(Status.PERMANENT, null, detail);
    }

    public boolean isSent() {
        return status == Status.SENT;
    }
}
