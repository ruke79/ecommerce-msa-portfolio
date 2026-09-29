package com.project.catalog_service.message.producer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.model.StockShortage;
import com.project.catalog_service.repository.jpa.data.StockShortageRepository;
import com.project.catalog_service.service.ProductService.ShortLine;

import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 부족을 아웃박스에 적는다 — 재고 차감과 같은 트랜잭션 안에서 불린다(docs/53 §초과 판매).
 *
 * <p>주문 id 는 차감 이벤트의 멱등 키({@code order-<id>})에서 읽는다. 차감 메시지(Avro)에 주문 id 필드를 더하면
 * 스키마 진화 두 단계가 필요한데(docs/55 §4), 키에 이미 들어 있다. 키가 없거나 모양이 다르면(옛 발행자) 누구에게
 * 돌려줘야 할지 알 수 없다 — 운영자에게 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockShortageRecorder {

    private static final Pattern ORDER_EVENT = Pattern.compile("order-(\\d+)");
    static final String METRIC = "catalog.stock.shortage";

    private final StockShortageRepository repository;
    private final ObjectMapper objectMapper;

    public void record(String deductionEventId, List<ShortLine> lines) {
        int units = lines.stream().mapToInt(ShortLine::shortQty).sum();
        Long orderId = orderIdOf(deductionEventId);
        Metrics.counter(METRIC, "resolved", orderId == null ? "false" : "true").increment(units);

        if (orderId == null) {
            log.error("재고 부족인데 어느 주문인지 알 수 없다(eventId={}) — 수동 확인 필요. 모자란 줄 {}개, {}개. "
                    + "결제된 주문을 찾아 부족분을 환불할 것", deductionEventId, lines.size(), units);
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        String eventId = "shortage-" + deductionEventId;
        event.put("eventId", eventId);
        event.put("orderId", orderId);
        event.put("lines", lines.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", l.productId());
            m.put("colorId", l.colorId());
            m.put("size", l.size());
            m.put("orderedQty", l.orderedQty());
            m.put("shortQty", l.shortQty());
            return m;
        }).toList());
        event.put("occurredAt", LocalDateTime.now().toString());
        try {
            repository.save(new StockShortage(eventId, orderId, objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            // 재고 차감까지 되돌린다 — 부족을 적지 못한 채 커밋하면 아무도 모르는 예전 상태로 돌아간다.
            throw new IllegalStateException("재고 부족 기록을 만들지 못했다: orderId=" + orderId, e);
        }
        log.warn("재고 부족: 결제된 주문의 일부를 보낼 수 없다 — order 가 부족분을 자동 환불한다. orderId={} 줄 {}개, {}개",
                orderId, lines.size(), units);
    }

    static Long orderIdOf(String eventId) {
        if (eventId == null) {
            return null;
        }
        Matcher m = ORDER_EVENT.matcher(eventId);
        return m.matches() ? Long.valueOf(m.group(1)) : null;
    }
}
