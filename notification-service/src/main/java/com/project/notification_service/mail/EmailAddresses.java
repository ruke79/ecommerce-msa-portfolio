package com.project.notification_service.mail;

import java.util.regex.Pattern;

/**
 * 메일 주소 검증과 마스킹.
 *
 * <p><b>왜 검증을 여기서 또 하나.</b> 보내는 쪽(user-service)이 이미 검증했더라도, 알림 서비스는
 * 여러 서비스에서 명령을 받는다. 주소 하나가 잘못돼 SMTP 서버가 거부하면 그 자체는 괜찮지만,
 * <b>잘못된 주소로 반복 발송하면 발신 도메인의 평판이 떨어진다.</b> 수신 서버들은 존재하지 않는
 * 주소로 오는 메일(하드 바운스) 비율을 스팸 판정에 쓴다 — 비율이 높으면 정상 메일까지 스팸함으로
 * 간다. 그래서 명백히 틀린 주소는 SMTP 를 부르기 전에 걸러 낸다.
 *
 * <p><b>왜 RFC 5322 완전 구현이 아닌가.</b> RFC 5322 를 정확히 따르는 정규식은 유명할 만큼 길고
 * 읽을 수 없으며, 그렇게까지 해도 "문법적으로 유효하지만 존재하지 않는 주소"는 여전히 통과한다.
 * 실제로 걸러야 하는 것은 오타와 명백한 쓰레기값이고, 최종 판정은 어차피 SMTP 서버가 한다.
 * 그래서 <b>확실히 틀린 것만</b> 막는다 — 정당한 주소를 거부하는 쪽이 더 나쁘기 때문이다.
 */
public final class EmailAddresses {

    /**
     * 주소 최대 길이. RFC 5321 §4.5.3.1.3 의 경로 상한이다.
     *
     * <p>DB 컬럼도 이 길이에 맞춰 {@code varchar(320)} 이다 — 컬럼이 더 짧으면 유효한 주소를
     * 저장하다 잘리고, 그러면 메일이 엉뚱한 곳으로 간다.
     */
    public static final int MAX_LENGTH = 320;

    /**
     * local@domain, 도메인에 점이 최소 하나. 공백·연속 점·앞뒤 점을 막는다.
     *
     * <p>TLD 를 목록으로 검사하지 않는다 — 새 TLD 는 계속 생기고, 목록은 반드시 낡는다.
     */
    private static final Pattern BASIC = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$");

    private EmailAddresses() {
    }

    /** 보낼 수 있는 형태인가. */
    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return false;
        }
        return BASIC.matcher(trimmed).matches();
    }

    /**
     * 로그용 마스킹. {@code alice@example.com} → {@code a***@example.com}.
     *
     * <p>도메인은 남긴다 — 장애를 볼 때 "특정 도메인으로만 실패한다"는 것이 가장 중요한 단서인데
     * (수신 서버가 우리를 차단했다는 신호다), 도메인까지 가리면 그걸 볼 수 없다. 반대로 로컬
     * 파트는 개인 식별자라 남길 이유가 없다.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
