package com.project.catalog_service.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.catalog_service.message.producer.EventStartedProducer;
import com.project.catalog_service.service.EventService.Advanced;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기획전을 시각에 맞춰 시작·종료시킨다.
 *
 * <p><b>왜 스케줄러인가.</b> "금요일 0시부터"를 사람이 그 시각에 버튼을 눌러 운영하면
 * 반드시 놓친다. 배너({@code Banner.isVisibleAt})는 조회할 때 판정하는 것으로 충분했지만
 * 기획전은 다르다 — 시작할 때 <b>알림이 나가야</b> 하고, 그건 누군가 시각을 보고 있어야
 * 가능하다.
 *
 * <p><b>인스턴스가 둘이면 스케줄러도 둘이다.</b> 둘 다 "시작할 때가 됐다"를 보고 둘 다
 * 알림을 발행하면 사용자에게 같은 기획전이 두 번 뜬다. {@code EventRepository.transition}
 * 이 {@code where status = 'SCHEDULED'} 조건부 UPDATE 라 <b>먼저 도착한 하나만</b> 1을
 * 받는다. 그 하나만 발행한다.
 *
 * <p>(알림 쪽에도 {@code eventId} 기반 중복 제거가 있지만 그건 최종 방어선이다. 카프카에
 * 나간 뒤에 거르는 것보다 발행 자체를 한 번만 하는 편이 싸다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventScheduler {

    /**
     * 확인 주기.
     *
     * <p>1분이면 충분한 이유: 기획전의 경계는 사람이 정한 시각이고 초 단위 정확도를
     * 요구하지 않는다. 더 중요한 것은 <b>모든 화면이 같은 값을 본다</b>는 것이고, 그건
     * {@link EventPricing} 의 맵을 같은 주기로 갱신해 보장한다.
     */
    static final long INTERVAL_MS = 60_000L;

    /**
     * 기획전 상태가 바뀔 때 비울 상품 캐시.
     *
     * <p>할인은 조회할 때 합성되지만 그 결과가 Redis 에 들어간다. 비우지 않으면 기획전이
     * 끝난 뒤에도 TTL 이 지날 때까지 할인가가 보이고 — 더 나쁘게 — 그 가격으로 장바구니에
     * 담긴다. 상태 전환은 하루에 몇 번뿐이라 전체 비우기로 충분하다.
     */
    static final List<String> PRODUCT_CACHES =
            List.of("products", "product", "search_cache", "products_by_category");

    private final EventService eventService;
    private final EventPricing eventPricing;
    private final EventStartedProducer eventStartedProducer;
    private final CacheManager cacheManager;

    /**
     * 시작할 것을 시작하고, 끝낼 것을 끝낸다.
     *
     * <p>{@code fixedDelay} 다({@code fixedRate} 가 아니다) — 한 번의 실행이 길어졌을 때
     * 다음 실행이 겹쳐 들어오면 같은 기획전을 두 번 처리하려 든다. 조건부 UPDATE 가 막긴
     * 하지만, 겹치지 않게 두는 편이 낫다.
     */
    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = 10_000L)
    public void tick() {
        try {
            // 상태 전환은 EventService 가 한다 — 같은 빈 안에서 부르면 @Transactional 이
            // 프록시를 지나지 않아 조용히 무시된다(EventService.advanceDueEvents 주석).
            Advanced advanced = eventService.advanceDueEvents(LocalDateTime.now());

            // 할인 맵을 **먼저** 갱신한다. 캐시를 비운 뒤에 갱신하면 그 사이에 들어온
            // 요청이 DB 를 다시 읽으면서 옛 할인 맵을 얹어, 방금 비운 캐시에 틀린 값을
            // 다시 채워 넣는다.
            eventPricing.refresh();

            if (advanced.changed()) {
                evictProductCaches();
            }

            // 시작 알림은 캐시까지 정리한 뒤에 보낸다. 알림을 보고 바로 들어온 사용자가
            // 옛 가격을 보면 안 된다.
            advanced.started().forEach(eventStartedProducer::publish);

        } catch (Exception e) {
            // 스케줄러 스레드에서 예외가 올라가면 이후 실행이 멈춘다. 기획전 하나 때문에
            // 전체 일정이 죽으면 안 된다.
            log.error("기획전 스케줄러 실행 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 상품 캐시를 비운다.
     *
     * <p>{@code @CacheEvict} 를 쓰지 않고 직접 부르는 이유: 이 메서드는 같은 빈 안에서
     * 불린다. 자기 호출은 프록시를 지나지 않으므로 애노테이션이 동작하지 않는다 —
     * 캐시가 비워지지 않는데 예외도 나지 않는, 조용히 틀리는 조합이다.
     */
    void evictProductCaches() {
        for (String name : PRODUCT_CACHES) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        log.info("기획전 상태 변경 — 상품 캐시를 비웠다");
    }

    /**
     * 관리자가 기획전을 고쳤을 때 즉시 반영한다.
     *
     * <p>{@code @CacheEvict} 로 캐시를 비우고 할인 맵을 다시 읽는다. 이게 없으면 관리자는
     * 저장한 뒤 최대 1분 동안 자기 변경이 반영되지 않는 것을 보고 저장이 실패했다고
     * 생각한다.
     */
    @CacheEvict(value = {"products", "product", "search_cache", "products_by_category"},
                allEntries = true)
    public void refreshNow() {
        eventPricing.refresh();
    }
}
