package com.project.notification_service.mail;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * 실제 SMTP 발송기.
 *
 * <p>핵심은 <b>{@code MailException} 을 재시도 가능/불가능으로 나누는 것</b>이다. 스프링의 메일
 * 추상화는 "사서함이 가득 찼다"(다시 보내야 한다)와 "그런 주소는 없다"(다시 보내면 안 된다)를
 * 같은 예외 계층으로 던진다. 이걸 뭉개면 두 방향 모두로 망가진다:
 *
 * <ul>
 *   <li>전부 재시도 → 존재하지 않는 주소로 계속 보낸다. 하드 바운스가 쌓이면 수신 서버들이
 *       <b>발신 도메인을 스팸으로 학습</b>한다. 정상 메일까지 스팸함으로 간다.</li>
 *   <li>전부 포기 → 메일 서버가 잠깐 흔들린 것뿐인데 사용자는 가입 확인 메일을 영영 못 받는다.</li>
 * </ul>
 *
 * <p>판정 순서는 <b>확실한 신호부터</b>다. SMTP 응답 코드가 있으면 그게 가장 정확하고(4xx/5xx),
 * 없으면 잘못된 주소 목록을 보고, 그것도 없으면 원인 예외의 종류로 판단한다.
 */
@Slf4j
public class SmtpMailSender implements MailSender {

    private final JavaMailSender delegate;
    private final String from;
    private final String fromName;

    public SmtpMailSender(JavaMailSender delegate, String from, String fromName) {
        this.delegate = delegate;
        this.from = from;
        this.fromName = fromName;
    }

    @Override
    public MailResult send(String to, String subject, String text, String html) {
        MimeMessage message;
        try {
            message = delegate.createMimeMessage();
            // multipart=true → 같은 메일에 평문과 HTML 을 함께 싣는다(multipart/alternative).
            // HTML 만 보내면 텍스트 대안이 없다는 이유로 스팸 점수가 오르고, HTML 을 표시하지
            // 않는 클라이언트에서는 본문이 통째로 비어 보인다.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            setFrom(helper);
            helper.setSubject(subject);
            helper.setText(text == null ? "" : text, html == null ? "" : html);

        } catch (jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            // 메시지를 구성하지 못했다. 같은 입력으로 다시 해도 같은 결과다.
            // ⚠️ 접두사 [MAIL: 를 붙인다. 발송 결과를 훑는 표준 grep 이 이것이다 —
            //    빠진 줄은 "아무 시도도 없었다" 로 보인다(아래 인증 실패에서 실제로 겪었다).
            log.error("[MAIL:permanent] to={} 메일 구성 실패: {}",
                    EmailAddresses.mask(to), describe(e));
            return MailResult.permanent("compose_failed: " + describe(e));
        }

        try {
            delegate.send(message);
            // ⚠️ 제목은 남기지 않는다 — 로그인 확인 메일은 제목에 6자리 확인 코드가 들어간다(MailTemplates). 길이만.
            // [JA] 件名は残さない — ログイン確認メールは件名に 6 桁の確認コードが入る。長さだけ。
            log.info("[MAIL:sent] to={} subjectLen={}", EmailAddresses.mask(to), subject == null ? 0 : subject.length());
            return MailResult.sent();

        } catch (MailAuthenticationException e) {
            // 인증 정보가 틀렸다. 재시도하면 계정이 잠길 수 있다 — 사람이 봐야 한다.
            // 🐛 이 줄에 [MAIL: 접두사가 없어서, 운영 진단 때 `grep "\[MAIL:"` 이
            //    아무것도 잡지 못했다. 발송 시도가 통째로 없는 것처럼 보여 엉뚱한 곳
            //    (스케줄러·백오프)을 한참 뒤졌다. 결과를 남기는 줄은 전부 같은 접두사를
            //    달아야 한 번의 grep 으로 끝난다.
            log.error("[MAIL:permanent] to={} SMTP 인증 실패 — 설정을 확인해야 합니다: {}",
                    EmailAddresses.mask(to), describe(e));
            return MailResult.permanent("auth_failed");

        } catch (MailParseException | MailPreparationException e) {
            return MailResult.permanent("prepare_failed: " + describe(e));

        } catch (MailSendException e) {
            return classify(to, e);

        } catch (org.springframework.mail.MailException e) {
            // 알 수 없는 메일 예외. 재시도 쪽으로 둔다 — 상한이 있으므로 무한히 시도하지 않고,
            // 보낼 수 있는 메일을 영구 실패로 버리는 쪽이 더 나쁘다.
            log.warn("[MAIL:error] to={} {}", EmailAddresses.mask(to), describe(e));
            return MailResult.retryable(describe(e));
        }
    }

    /**
     * 발송 실패를 재시도 가능/불가능으로 가른다.
     *
     * <p>{@link MailSendException#getFailedMessages()} 안에 원인 예외가 들어 있다.
     */
    private MailResult classify(String to, MailSendException e) {
        for (Exception cause : e.getFailedMessages().values()) {
            MailResult decided = classifyOne(to, cause);
            if (decided != null) {
                return decided;
            }
        }
        // failedMessages 가 비어 있으면 연결 단계에서 실패한 것이다(서버에 닿지도 못했다).
        MailResult decided = classifyOne(to, e);
        if (decided != null) {
            return decided;
        }
        log.warn("[MAIL:retryable] to={} 분류 불가 — 재시도합니다: {}",
                EmailAddresses.mask(to), describe(e));
        return MailResult.retryable(describe(e));
    }

    /** @return 판정했으면 결과, 이 예외로는 알 수 없으면 null */
    private MailResult classifyOne(String to, Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {

            // 1) SMTP 응답 코드가 있으면 그게 가장 정확하다. 4xx = 일시, 5xx = 영구.
            Integer code = smtpReturnCode(t);
            if (code != null) {
                if (code >= 500) {
                    log.error("[MAIL:permanent] to={} smtp={} {}",
                            EmailAddresses.mask(to), code, describe(t));
                    return MailResult.permanent("smtp " + code);
                }
                if (code >= 400) {
                    log.warn("[MAIL:retryable] to={} smtp={} {}",
                            EmailAddresses.mask(to), code, describe(t));
                    return MailResult.retryable("smtp " + code);
                }
            }

            // 2) 코드가 없으면 주소 목록을 본다. 서버가 "이 주소는 유효하지 않다"고 말한 것이므로
            //    다시 보내도 같다.
            if (t instanceof SendFailedException sfe) {
                // ⚠️ Address[] 로 받는다. 선언 타입이 Address[] 라 InternetAddress[] 로 캐스팅하면
                //    런타임에 ClassCastException 이 날 수 있고, 그러면 분류 자체가 실패한다.
                Address[] invalid = sfe.getInvalidAddresses();
                if (invalid != null && invalid.length > 0) {
                    log.error("[MAIL:permanent] to={} 유효하지 않은 주소", EmailAddresses.mask(to));
                    return MailResult.permanent("invalid_address");
                }
                // 유효하지만 못 보낸 주소만 있다면 서버 사정이다 — 다시 시도한다.
                Address[] unsent = sfe.getValidUnsentAddresses();
                if (unsent != null && unsent.length > 0) {
                    return MailResult.retryable("valid_unsent");
                }
            }

            // 3) 네트워크 계층 실패는 명백히 일시적이다.
            if (t instanceof ConnectException
                    || t instanceof SocketTimeoutException
                    || t instanceof UnknownHostException) {
                log.warn("[MAIL:retryable] to={} 메일 서버에 연결하지 못했습니다: {}",
                        EmailAddresses.mask(to), describe(t));
                return MailResult.retryable("connect: " + t.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * SMTP 응답 코드를 꺼낸다. 없으면 null.
     *
     * <p><b>왜 리플렉션인가.</b> 코드를 담고 있는 것은 {@code SMTPSendFailedException} /
     * {@code SMTPAddressFailedException} 인데, 이들은 jakarta.mail <b>API</b> 가 아니라
     * 구현체(Angus Mail)의 클래스다. 컴파일 시점에 참조하면 구현체를 갈아끼울 수 없게 되고,
     * 구현체가 없는 환경에서는 클래스 로딩부터 실패한다.
     *
     * <p>반대로 이 정보를 포기하면 4xx(일시)와 5xx(영구)를 구분할 가장 정확한 근거를 잃는다.
     * 그래서 <b>있으면 쓰고 없으면 다음 근거로 넘어가는</b> 형태로 읽는다.
     */
    /**
     * 로그 · {@code mail_message.detail} 에 남길 예외 설명 — 예외 종류와 SMTP 응답 코드만.
     *
     * <p>🐛 예전에는 예외 메시지를 그대로 남겼다. SMTP 서버 응답 · 주소 예외의 메시지에는 받는 주소 원문이 들어가므로
     * ({@code 550 5.1.1 <alice@…> User unknown}), 마스킹한 {@code to=} 옆에 원문 주소가 찍혔고 같은 문구가 detail 열에 저장돼
     * 영구 실패 로그(MailDispatcher)로 한 번 더 나갔다.
     *
     * <p>[JA] ログ・detail に残す例外の説明 — 例外の種類と SMTP 応答コードだけ。以前は例外メッセージをそのまま残し、
     * SMTP 応答に含まれる宛先の生アドレスがマスクした to= の隣に出ていた。
     */
    static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        Integer code = smtpReturnCode(t);
        for (Throwable c = t.getCause(); code == null && c != null && c != c.getCause(); c = c.getCause()) {
            code = smtpReturnCode(c);
        }
        return t.getClass().getSimpleName() + (code == null ? "" : " smtp=" + code);
    }

    private static Integer smtpReturnCode(Throwable t) {
        try {
            Method m = t.getClass().getMethod("getReturnCode");
            Object value = m.invoke(t);
            return value instanceof Integer i && i > 0 ? i : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 발신자를 설정한다.
     *
     * <p>표시 이름을 함께 넣는 이유: 수신함에서 {@code no-reply@...} 만 보이는 것보다 서비스명이
     * 보이는 편이 열람률이 높고, 무엇보다 <b>사용자가 피싱과 구분할 수 있다.</b>
     */
    private void setFrom(MimeMessageHelper helper)
            throws jakarta.mail.MessagingException, UnsupportedEncodingException {
        if (fromName == null || fromName.isBlank()) {
            helper.setFrom(from);
        } else {
            helper.setFrom(from, fromName);
        }
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public String name() {
        return "smtp";
    }
}
