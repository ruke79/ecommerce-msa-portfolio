package com.project.notification_service.mail;

/**
 * 메일 발송 결과.
 *
 * <p>{@link com.project.notification_service.sms.SmsResult} 와 같은 삼분법이다 —
 * <b>접수 / 재시도 가능 / 영구 실패</b>. 이 구분이 흐려지면 두 방향 모두로 망가진다:
 * 일시적 실패를 영구로 적으면 그 메일은 다시는 나가지 않고(사용자는 가입을 완료할 수 없다),
 * 영구 실패를 계속 재시도하면 <b>하드 바운스가 쌓여 발신 도메인이 스팸으로 분류된다.</b>
 *
 * <p>메일에서는 SMTP 응답 코드가 이 구분을 직접 알려 준다:
 * <ul>
 *   <li><b>4xx</b> — 일시(사서함 가득 참, 속도 제한, 그레이리스팅). 다시 시도한다</li>
 *   <li><b>5xx</b> — 영구(주소 없음, 도메인 없음, 수신 거부). 재시도해도 같다</li>
 * </ul>
 *
 * <p>⚠️ {@code SENT} 는 "우리 MTA 가 접수했다"까지만 뜻한다. 수신함에 도착했는지는
 * <b>바운스 메일과 피드백 루프</b>를 받아야 알 수 있다 — SMS 의 DLR 과 같은 구조이고, 같이
 * 로드맵에 남겨 둔다. 이 구분을 흐려 두면 "보냈다고 나오는데 안 왔다"를 추적할 수 없다.
 */
public record MailResult(Status status, String detail) {

    public enum Status { SENT, RETRYABLE, PERMANENT }

    public static MailResult sent() {
        return new MailResult(Status.SENT, null);
    }

    public static MailResult retryable(String detail) {
        return new MailResult(Status.RETRYABLE, detail);
    }

    public static MailResult permanent(String detail) {
        return new MailResult(Status.PERMANENT, detail);
    }

    public boolean isSent() {
        return status == Status.SENT;
    }
}
