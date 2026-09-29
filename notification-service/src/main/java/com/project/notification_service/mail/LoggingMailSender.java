package com.project.notification_service.mail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제로 보내지 않고 로그에만 남기는 발송기. <b>기본값</b>이다.
 *
 * <p>{@link com.project.notification_service.sms.LoggingSmsSender} 와 같은 이유로 기본값이지만,
 * 근거는 조금 다르다. SMS 는 건당 과금이라 "모르는 사이 요금이 나가는" 것이 문제였다. 메일은
 * 대개 공짜지만 <b>되돌릴 수 없고 평판이 남는다</b> — 설정이 덜 된 상태로 배포돼 잘못된 주소나
 * 테스트 데이터로 메일이 나가면, 그 하드 바운스가 발신 도메인 평판에 그대로 쌓인다. 한 번
 * 스팸으로 분류된 도메인을 되돌리는 데는 몇 주가 걸린다.
 *
 * <p>실제 발송은 {@code notification.mail.provider=smtp} 로 명시해야 켜진다.
 *
 * <p>테스트에서도 쓴다 — 보낸 내역을 메모리에 쌓아 두므로 "이 이벤트가 이 주소로 이 제목의
 * 메일을 보냈는가"를 SMTP 서버 없이 검증할 수 있다.
 */
@Slf4j
public class LoggingMailSender implements MailSender {

    /** 테스트 검증용 발송 내역. 운영에서는 무한히 쌓이지 않도록 상한을 둔다. */
    private static final int MAX_RECORDED = 500;

    private final List<Sent> recorded = Collections.synchronizedList(new ArrayList<>());

    public record Sent(String to, String subject, String text, String html) {}

    @Override
    public MailResult send(String to, String subject, String text, String html) {
        // ⚠️ 본문을 로그에 남기지 않는다. 확인 링크·재설정 링크가 본문에 들어 있기 때문이다 —
        //    dry-run 이라 해도 로그에 찍히면 실제 발송과 같은 노출이 된다.
        //    주소도 마스킹한다(EmailAddresses.mask 참조).
        log.info("[MAIL:dry-run] to={} subject={} textLen={} htmlLen={}",
                EmailAddresses.mask(to), subject,
                text == null ? 0 : text.length(),
                html == null ? 0 : html.length());

        synchronized (recorded) {
            recorded.add(new Sent(to, subject, text, html));
            if (recorded.size() > MAX_RECORDED) {
                recorded.remove(0);
            }
        }
        return MailResult.sent();
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public String name() {
        return "logging";
    }

    /** 테스트용. 보낸 내역 사본. */
    public List<Sent> sent() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    public void clear() {
        recorded.clear();
    }
}
