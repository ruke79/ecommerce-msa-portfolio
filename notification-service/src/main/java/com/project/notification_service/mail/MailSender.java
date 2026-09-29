package com.project.notification_service.mail;

/**
 * 메일 발송 경로. {@link com.project.notification_service.sms.SmsSender} 와 나란히 둔다.
 *
 * <p><b>왜 {@code JavaMailSender} 를 그대로 쓰지 않나.</b> 두 가지다.
 *
 * <p>첫째, {@code JavaMailSender} 는 실패를 {@code MailException} 하나로 뭉뚱그린다. 사서함이
 * 가득 찬 것(다시 보내야 한다)과 주소가 존재하지 않는 것(다시 보내면 안 된다)이 같은 예외로
 * 온다. 이 인터페이스는 그 구분을 {@link MailResult} 로 강제한다.
 *
 * <p>둘째, 운영에서 SMTP 직결은 흔한 선택이 아니다. 발신 도메인 평판·SPF/DKIM/DMARC·바운스
 * 처리 때문에 SES·SendGrid 같은 API 를 쓰게 되는데, 그때 이 인터페이스 뒤 구현만 바꾸면 된다.
 * SMS 를 공급자 교체 가능하게 만든 것과 같은 이유이고, 같은 이유로 <b>기본값은 실제로 보내지
 * 않는 구현</b>이다({@link LoggingMailSender}).
 */
public interface MailSender {

    /**
     * @param to      수신 주소 (호출 전에 {@link EmailAddresses#isValid} 를 통과했다고 가정한다)
     * @param subject 제목
     * @param text    평문 대안 본문 (HTML 을 못 보는 클라이언트용)
     * @param html    HTML 본문
     * @return 발송 결과. 예외를 던지지 않고 결과로 돌려주는 이유는 SMS 와 같다 —
     *         메일 하나가 실패했다고 나머지 배치까지 멈출 이유가 없다
     */
    MailResult send(String to, String subject, String text, String html);

    /** 실제로 메일이 나가는 경로인지. 관리·표시용. */
    boolean isLive();

    /** 설정에서 고를 때 쓰는 이름. */
    String name();
}
