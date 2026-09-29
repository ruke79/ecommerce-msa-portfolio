package com.project.notification_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.sms.HttpSmsSender;
import com.project.notification_service.sms.LoggingSmsSender;
import com.project.notification_service.sms.SmsSender;

import lombok.extern.slf4j.Slf4j;

/**
 * 발송기 선택.
 *
 * <p><b>기본값이 로깅 발송기인 것은 의도적이다.</b> SMS 는 건당 과금이고 되돌릴 수 없다.
 * 설정이 덜 된 채 배포됐을 때 "아무 문자도 안 나간다"가 "모르는 사이 요금이 나간다"보다 낫다.
 * 실제 발송은 {@code notification.sms.provider=http} 로 명시해야 켜진다.
 */
@Slf4j
@Configuration
public class SmsConfig {

    @Value("${notification.sms.provider:logging}")
    private String provider;

    @Value("${notification.sms.http.url:}")
    private String url;

    @Value("${notification.sms.http.auth-header:Authorization}")
    private String authHeader;

    @Value("${notification.sms.http.auth-value:}")
    private String authValue;

    @Value("${notification.sms.http.from:}")
    private String from;

    @Value("${notification.sms.http.to-field:to}")
    private String toField;

    @Value("${notification.sms.http.text-field:text}")
    private String textField;

    @Value("${notification.sms.http.from-field:from}")
    private String fromField;

    @Value("${notification.sms.http.timeout-ms:5000}")
    private long timeoutMs;

    @Bean
    public SmsSender smsSender(ObjectMapper objectMapper) {
        if (!"http".equalsIgnoreCase(provider)) {
            log.warn("SMS 발송기: logging (실제로 발송하지 않습니다). "
                    + "실제 발송은 notification.sms.provider=http 로 켜세요.");
            return new LoggingSmsSender();
        }

        // 설정이 반쯤 된 상태로 실제 발송을 켜면, 매 발송이 실패하며 알림만 쌓인다.
        // 여기서 막고 이유를 분명히 알린다.
        if (url.isBlank() || from.isBlank()) {
            throw new IllegalStateException(
                    "notification.sms.provider=http 이지만 url 또는 from 이 비어 있습니다. "
                    + "한국에서는 from 이 사전 등록된 발신번호여야 합니다.");
        }

        // 위 검사는 **비어 있는 값**만 잡는다. 안 채운 값(REPLACE_ME…)은 통과한다 —
        // 메일 쪽에서 실제로 그렇게 새서 가입 확인 메일이 조용히 버려졌다(Placeholders 참조).
        // 여기서는 요금이 걸려 있어 더 나쁘다: 게이트웨이 주소가 자리 표시자면 매 발송이
        // 실패하며 알림만 쌓이고, 주소가 우연히 유효하면 엉뚱한 곳으로 나간다.
        Placeholders.rejectUnfilled("notification.sms.provider=http", Placeholders.pairs(
                "notification.sms.http.url (SMS_API_URL)", url,
                "notification.sms.http.auth-value (SMS_AUTH_VALUE)", authValue,
                "notification.sms.http.from (SMS_FROM)", from));

        log.info("SMS 발송기: http → {} (실제 발송, 요금이 발생합니다)", url);
        return new HttpSmsSender(
                new HttpSmsSender.HttpConfig(url, authHeader, authValue, from,
                        toField, textField, fromField, Duration.ofMillis(timeoutMs)),
                objectMapper);
    }
}
