package com.project.notification_service.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제로 보내지 않고 로그에만 남기는 발송기. <b>기본값</b>이다.
 *
 * <p>기본값으로 둔 이유: SMS 는 건당 과금이고 되돌릴 수 없다. 설정이 덜 된 상태로 배포됐을 때
 * "아무 일도 안 일어난다"가 "모르는 사이에 요금이 나간다"보다 안전하다. 실제 발송은
 * {@code notification.sms.provider} 를 명시적으로 바꿔야 켜진다.
 *
 * <p>테스트에서도 쓴다 — 보낸 내역을 메모리에 쌓아 두므로 "이 이벤트가 이 번호로 이 문구를
 * 보냈는가"를 검증할 수 있다. 실제 게이트웨이 없이 알림 로직 전체를 검사할 수 있다는 뜻이다.
 */
@Slf4j
public class LoggingSmsSender implements SmsSender {

    /** 테스트 검증용 발송 내역. 운영에서는 무한히 쌓이지 않도록 상한을 둔다. */
    private static final int MAX_RECORDED = 500;

    private final List<Sent> recorded = Collections.synchronizedList(new ArrayList<>());

    public record Sent(String e164, String message) {}

    @Override
    public SmsResult send(String e164, String message) {
        // 번호는 마스킹해서 남긴다 — 로그 열람 권한이 곧 연락처 열람 권한이 되면 안 된다.
        //
        // 🔒 본문은 남기지 않는다. 예전에는 body={} 로 전문을 INFO 로 찍었는데, 이 발송기가
        //    **기본값**이라(k8s 도 provider 를 바꾸지 않는다) 운영에서 휴대폰 인증 문자의
        //    인증번호가 그대로 로그에 남았다. 로그를 읽을 수 있는 사람이면 남의 번호로 인증을
        //    끝낼 수 있다. 인증번호는 어떤 경로로도 로그에 남지 않는다는 규칙을 어긴 자리였다
        //    (docs/38 §1.7 이 지적했지만 남아 있었다).
        //
        //    전역 마스킹(common 의 SensitiveDataMasker)은 여섯 자리 숫자를 인증번호로 알아볼 수
        //    없다 — 주문 번호·금액과 구별이 안 된다. 그래서 여기서 애초에 넘기지 않는다.
        //    문구를 확인해야 하는 테스트는 sent() 로 메모리의 기록을 본다.
        log.info("[SMS:dry-run] to={} chars={} single={}",
                PhoneNumbers.mask(e164), message.length(),
                SmsMessage.fitsInSingleSms(message));

        synchronized (recorded) {
            recorded.add(new Sent(e164, message));
            if (recorded.size() > MAX_RECORDED) {
                recorded.remove(0);
            }
        }
        return SmsResult.sent("dry-run-" + UUID.randomUUID());
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
