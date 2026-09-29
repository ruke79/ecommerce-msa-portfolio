package com.project.delivery_service.carrier;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.project.delivery_service.domain.DeliveryStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * 우체국(대한민국) 배송 추적.
 *
 * <p>첫 연동으로 우체국을 고른 이유는 <b>공개된 공공 API 가 있기 때문</b>이다. 사설 택배사
 * API 는 대부분 계약이 선행되어 저장소만 보고는 붙일 수 없다. 국제우편(EMS)까지 같은 계열이라
 * 나중에 해외 배송으로 넓히기도 쉽다.
 *
 * <p><b>⚠️ 정직하게 — 이 어댑터는 실제 API 키 없이 실호출을 검증하지 못했다.</b>
 * 그래서 두 가지를 했다:
 * <ul>
 *   <li><b>필드 이름을 설정으로 뺐다.</b> 공공데이터포털 API 는 같은 서비스라도 버전에 따라
 *       필드명이 다르다. 코드가 아니라 yml 을 고쳐 맞출 수 있게 했다.</li>
 *   <li><b>매핑 로직만 따로 테스트한다.</b> HTTP 호출과 파싱을 분리해, 기록된 샘플 응답으로
 *       "이 응답이 오면 이 이벤트가 나온다"를 검증한다. 키를 받으면 필드명만 맞추면 된다.</li>
 * </ul>
 * 실호출 검증은 키가 생긴 뒤 {@code ops/} 스크립트로 한다.
 *
 * <p>상태 매핑은 <b>부분 문자열 포함</b>으로 한다. 택배사가 "배달완료", "배달완료(홍길동)",
 * "배달 완료" 처럼 표기를 조금씩 바꾸기 때문이다. 정확히 일치로 짜면 문구가 바뀌는 순간
 * 모든 배송이 상태 갱신을 멈추는데, 그 실패는 아무 오류도 내지 않아 알아채기 어렵다.
 */
@Slf4j
public class KoreaPostCarrierAdapter implements CarrierAdapter {

    public static final String CODE = "KOREA_POST";

    /**
     * 원문 상태 → 표준 상태. 우체국이 쓰는 표현을 기준으로 하되, 흔한 변형을 함께 넣는다.
     *
     * <p>순서가 중요하다. "배달완료" 가 "배달" 보다 먼저 와야 한다 — 뒤에 오면 배달완료가
     * OUT_FOR_DELIVERY 로 잘못 매핑된다.
     */
    private static final List<Map.Entry<String, DeliveryStatus>> STATUS_RULES = List.of(
            Map.entry("배달완료", DeliveryStatus.DELIVERED),
            Map.entry("배달 완료", DeliveryStatus.DELIVERED),
            Map.entry("배송완료", DeliveryStatus.DELIVERED),
            Map.entry("수취인부재", DeliveryStatus.ATTEMPT_FAILED),
            Map.entry("미배달", DeliveryStatus.ATTEMPT_FAILED),
            Map.entry("배달준비", DeliveryStatus.OUT_FOR_DELIVERY),
            Map.entry("배달출발", DeliveryStatus.OUT_FOR_DELIVERY),
            Map.entry("배달중", DeliveryStatus.OUT_FOR_DELIVERY),
            Map.entry("통관보류", DeliveryStatus.CUSTOMS_HOLD),
            Map.entry("통관대기", DeliveryStatus.CUSTOMS_HOLD),
            Map.entry("통관진행", DeliveryStatus.CUSTOMS_HOLD),
            Map.entry("통관완료", DeliveryStatus.CUSTOMS_CLEARED),
            Map.entry("반송", DeliveryStatus.RETURNED),
            Map.entry("분실", DeliveryStatus.EXCEPTION),
            Map.entry("파손", DeliveryStatus.EXCEPTION),
            Map.entry("접수", DeliveryStatus.INFO_RECEIVED),
            Map.entry("집화", DeliveryStatus.PICKED_UP),
            Map.entry("발송", DeliveryStatus.IN_TRANSIT),
            Map.entry("도착", DeliveryStatus.IN_TRANSIT),
            Map.entry("이동중", DeliveryStatus.IN_TRANSIT));

    /** 공공데이터포털이 쓰는 흔한 시각 형식들. 어느 것이 올지 확정할 수 없어 순서대로 시도한다. */
    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    /** 응답 필드 이름. 버전마다 다르므로 설정으로 뺀다. */
    public record FieldMapping(
            String eventsPath,      // 이벤트 배열까지의 경로. '.' 로 구분
            String timeField,
            String statusField,
            String locationField,
            String descriptionField) {

        public static FieldMapping defaults() {
            return new FieldMapping("response.body.items.item",
                    "dlvyDate", "dlvySttus", "nowLc", "dlvyNm");
        }
    }

    private final String baseUrl;
    private final String serviceKey;
    private final FieldMapping mapping;
    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public KoreaPostCarrierAdapter(String baseUrl, String serviceKey, FieldMapping mapping,
                                   Duration timeout, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.mapping = mapping == null ? FieldMapping.defaults() : mapping;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String carrierCode() {
        return CODE;
    }

    @Override
    public TrackingResult track(String trackingNumber, boolean international) {
        if (serviceKey == null || serviceKey.isBlank()) {
            // 키가 없으면 조용히 빈 결과를 주지 않는다 — 그러면 "왜 갱신이 안 되지"가 된다.
            return TrackingResult.permanent("우체국 API 서비스키가 설정되지 않았습니다 "
                    + "(DELIVERY_KOREAPOST_SERVICE_KEY)");
        }
        String url = baseUrl
                + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
                + "&rgist=" + URLEncoder.encode(trackingNumber, StandardCharsets.UTF_8)
                + "&_type=json";

        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int code = response.statusCode();
            if (code == 429 || code >= 500) {
                return TrackingResult.retryable("HTTP " + code);
            }
            if (code == 401 || code == 403) {
                return TrackingResult.permanent("인증 실패 (HTTP " + code + ") — 서비스키를 확인하세요");
            }
            if (code != 200) {
                return TrackingResult.permanent("HTTP " + code);
            }
            return parse(response.body());

        } catch (java.net.http.HttpTimeoutException e) {
            return TrackingResult.retryable("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TrackingResult.retryable("interrupted");
        } catch (Exception e) {
            log.warn("우체국 조회 실패: tracking={} {}", trackingNumber, e.getMessage());
            return TrackingResult.retryable(e.getMessage());
        }
    }

    /**
     * 응답 본문 → 표준 이벤트. <b>HTTP 와 분리해 둔 이유가 여기 있다</b> — 이 메서드만
     * 기록된 샘플로 테스트할 수 있어야 키 없이도 매핑을 검증할 수 있다.
     */
    TrackingResult parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode node = root;
            for (String segment : mapping.eventsPath().split("\\.")) {
                node = node.path(segment);
            }
            if (node.isMissingNode() || node.isNull()) {
                return TrackingResult.notFound();
            }
            // 항목이 하나면 배열이 아니라 객체로 오는 경우가 있다 — 공공 API 의 흔한 함정이다.
            List<JsonNode> items = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(items::add);
            } else if (node.isObject()) {
                items.add(node);
            }
            if (items.isEmpty()) {
                return TrackingResult.notFound();
            }

            List<TrackingEvent> events = new ArrayList<>();
            for (JsonNode item : items) {
                String rawStatus = text(item, mapping.statusField());
                LocalDateTime occurredAt = parseTime(text(item, mapping.timeField()));
                if (rawStatus == null || occurredAt == null) {
                    // 시각이나 상태가 없으면 타임라인에 놓을 수 없다. 버리되 이유를 남긴다.
                    log.debug("필드가 없어 건너뛴 이벤트: {}", item);
                    continue;
                }
                events.add(new TrackingEvent(
                        mapStatus(rawStatus),
                        occurredAt,
                        text(item, mapping.locationField()),
                        text(item, mapping.descriptionField()),
                        rawStatus,
                        item.toString()));
            }
            if (events.isEmpty()) {
                return TrackingResult.notFound();
            }
            return TrackingResult.ok(events, null);

        } catch (Exception e) {
            // 파싱 실패는 재시도해도 같다 — 응답 형식이 예상과 다르다는 뜻이다.
            log.error("우체국 응답 파싱 실패: {}", e.getMessage());
            return TrackingResult.permanent("응답 파싱 실패: " + e.getMessage());
        }
    }

    /**
     * 원문 상태 → 표준 상태.
     *
     * <p>매칭되지 않으면 {@code IN_TRANSIT} 로 둔다. 알 수 없는 상태를 EXCEPTION 으로 두면
     * 택배사가 문구를 하나 바꿨을 뿐인데 정상 배송이 전부 "문제 있음"으로 표시된다.
     * 진행 중으로 두면 최소한 사용자에게 잘못된 경보를 주지 않는다 — 대신 로그로 남긴다.
     */
    DeliveryStatus mapStatus(String rawStatus) {
        String normalized = rawStatus.replaceAll("\\s+", "");
        for (Map.Entry<String, DeliveryStatus> rule : STATUS_RULES) {
            if (normalized.contains(rule.getKey().replaceAll("\\s+", ""))) {
                return rule.getValue();
            }
        }
        log.info("매핑되지 않은 우체국 상태: '{}' — IN_TRANSIT 으로 처리", rawStatus);
        return DeliveryStatus.IN_TRANSIT;
    }

    LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(cleaned, format);
            } catch (DateTimeParseException ignored) {
                // 다음 형식으로
            }
        }
        // 날짜만 온 경우 (yyyyMMdd / yyyy-MM-dd)
        try {
            String digits = cleaned.replaceAll("[^0-9]", "");
            if (digits.length() == 8) {
                return LocalDateTime.of(
                        Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6)),
                        Integer.parseInt(digits.substring(6, 8)), 0, 0);
            }
        } catch (RuntimeException ignored) {
            // 아래에서 null 을 돌려준다
        }
        log.debug("시각 형식을 해석하지 못함: '{}'", value);
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
