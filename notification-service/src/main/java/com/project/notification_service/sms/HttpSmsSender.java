package com.project.notification_service.sms;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 설정으로 기술하는 범용 HTTP SMS 발송기.
 *
 * <p><b>왜 공급자별 SDK 를 쓰지 않나.</b> 한국(CoolSMS·알리고·NHN Cloud 등)과 일본(Twilio·
 * KDDI Web Communications 등)의 사업자는 다르고, 한 곳이 두 나라를 다 잘 커버하는 경우가
 * 드물다. SDK 를 하나 고르면 그 시점에 나라 하나를 포기하거나 SDK 두 개를 안고 가야 한다.
 *
 * <p>그런데 이들 API 는 실질적으로 모두 같은 모양이다 — <b>토큰을 헤더에 넣고 JSON 을 POST</b>.
 * 그래서 SDK 대신 요청 형태를 설정으로 기술한다. 공급자를 바꿀 때 코드가 아니라 yml 이 바뀐다.
 *
 * <pre>
 * notification:
 *   sms:
 *     provider: http
 *     http:
 *       url: https://api.example.com/messages
 *       auth-header: Authorization        # 예: Bearer xxx, 또는 공급자 고유 헤더
 *       auth-value: ${SMS_API_KEY}
 *       from: "025551234"                 # 한국은 사전 등록된 발신번호여야 한다
 *       to-field: to                      # 공급자마다 필드 이름이 다르다
 *       text-field: text
 *       from-field: from
 * </pre>
 *
 * <p>응답 해석은 <b>HTTP 상태 코드</b>로만 한다. 본문 스키마는 공급자마다 제각각이라 파싱하면
 * 결합이 생긴다. 2xx=접수, 4xx=영구 실패(번호 오류·잔액 부족·발신번호 미등록), 5xx·타임아웃=
 * 재시도. 이 구분이 흐려지면 일시 장애로 알림이 영영 사라지거나, 잘못된 번호로 무한 재시도한다.
 */
@Slf4j
public class HttpSmsSender implements SmsSender {

    private final HttpConfig config;
    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public record HttpConfig(
            String url,
            String authHeader,
            String authValue,
            String from,
            String toField,
            String textField,
            String fromField,
            Duration timeout) {
    }

    public HttpSmsSender(HttpConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .build();
    }

    @Override
    public SmsResult send(String e164, String message) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    config.toField(), e164,
                    config.textField(), message,
                    config.fromField(), config.from()));
        } catch (Exception e) {
            // 직렬화가 실패하면 몇 번을 다시 해도 실패한다.
            return SmsResult.permanent("요청 본문 생성 실패: " + e.getMessage());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (config.authHeader() != null && !config.authHeader().isBlank()) {
            request.header(config.authHeader(), config.authValue());
        }

        try {
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                log.info("[SMS:sent] to={} status={}", PhoneNumbers.mask(e164), status);
                return SmsResult.sent(headerOrNull(response, "X-Message-Id"));
            }

            if (status == 429 || status >= 500) {
                // 속도 제한과 서버 오류는 시간이 해결한다.
                log.warn("[SMS:retryable] to={} status={} body={}",
                        PhoneNumbers.mask(e164), status, truncateForLog(response.body()));
                return SmsResult.retryable("HTTP " + status);
            }

            // 4xx — 번호가 잘못됐거나, 잔액이 없거나, 발신번호가 등록되지 않았다.
            // 재시도해도 같은 결과이므로 영구 실패로 기록하고 사람이 보게 한다.
            log.error("[SMS:permanent] to={} status={} body={}",
                    PhoneNumbers.mask(e164), status, truncateForLog(response.body()));
            return SmsResult.permanent("HTTP " + status + " " + truncateForLog(response.body()));

        } catch (java.net.http.HttpTimeoutException e) {
            // ⚠️ 타임아웃은 "안 갔다"가 아니라 "모른다"이다. 게이트웨이가 이미 접수했을 수 있다.
            // 재시도하면 중복 발송이 될 수 있으므로, 상위에서 멱등키로 막아야 한다
            // (NotificationDispatcher 가 알림 1건당 1회만 시도하도록 상태를 관리한다).
            log.warn("[SMS:timeout] to={} — 접수 여부 불명", PhoneNumbers.mask(e164));
            return SmsResult.retryable("timeout");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SmsResult.retryable("interrupted");

        } catch (Exception e) {
            log.warn("[SMS:error] to={} {}", PhoneNumbers.mask(e164), e.getMessage());
            return SmsResult.retryable(e.getMessage());
        }
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public String name() {
        return "http";
    }

    private static String headerOrNull(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /** 공급자 응답 본문에 개인정보나 키가 섞여 올 수 있어 길이를 제한한다. */
    private static String truncateForLog(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
