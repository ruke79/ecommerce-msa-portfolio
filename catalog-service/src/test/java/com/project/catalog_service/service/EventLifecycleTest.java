package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.project.catalog_service.dto.EventDtos.EventAdminView;
import com.project.catalog_service.dto.EventDtos.EventProductRequest;
import com.project.catalog_service.dto.EventDtos.EventRequest;
import com.project.catalog_service.message.producer.EventStartedProducer;
import com.project.catalog_service.model.Event;
import com.project.catalog_service.model.EventStatus;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.EventRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.service.EventService.Advanced;

/**
 * 기획전의 생명주기 — 확정 · 시작 · 종료.
 *
 * <p>여기서 지키는 것은 대부분 <b>되돌릴 수 없는 실수를 막는 규칙</b>이다. 진행 중인 세일을
 * 지우면 할인이 조용히 사라지고 기록도 안 남는다. 끝난 기획전을 고치면 아무 효과가 없는데
 * 관리자는 저장이 됐다고 본다. 인스턴스가 둘일 때 시작 알림이 두 번 나가면 모든 사용자가
 * 같은 알림을 두 개 받는다.
 */
@DisplayName("기획전 생명주기")
class EventLifecycleTest {

    private EventRepository eventRepository;
    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private EventService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        productRepository = mock(ProductRepository.class);
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.existsBySlug(anyString())).thenReturn(false);
        categoryRepository = mock(CategoryRepository.class);
        service = new EventService(eventRepository, productRepository, categoryRepository);
    }

    /** 이 id 의 상품이 존재한다고 해 둔다 — 대상 검증이 통과해야 한다. */
    private void productsExist(Long... ids) {
        // @SuperBuilder 는 와일드카드 타입의 빌더를 돌려준다. 타입 증인을 명시하지 않으면
        // List<capture of ? extends Product> 가 되어 thenReturn 이 받지 못한다.
        List<Product> products = java.util.Arrays.stream(ids)
                .<Product>map(id -> Product.builder().productId(id).build())
                .toList();
        when(productRepository.findAllById(any())).thenReturn(products);
    }

    private static EventRequest request(Boolean publish, EventProductRequest... products) {
        return new EventRequest("가을 세일", null, "설명", null, null,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(3),
                publish, List.of(products), List.of(), null, null, null, null);
    }

    /** 제목만 정하고 나머지는 비운 요청 — 여러 테스트가 각자 조금씩만 바꿔 쓴다. */
    private static EventRequest minimal(String title) {
        return new EventRequest(title, null, null, null, null, null, null, null,
                List.of(), List.of(), null, null, null, null);
    }

    private Event existing(EventStatus status) {
        Event event = Event.builder()
                .eventId(7L).title("가을 세일").slug("autumn").status(status).build();
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));
        return event;
    }

    @Nested
    @DisplayName("만들기")
    class Creating {

        @Test
        @DisplayName("확정하지 않으면 초안이다 — 저장하자마자 세일이 시작되면 안 된다")
        void defaultsToDraft() {
            productsExist(42L);

            EventAdminView view = service.create(request(null, new EventProductRequest(42L, 30)));

            assertThat(view.status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("확정하면 예약 상태가 되고, 시작은 스케줄러가 한다")
        void publishSchedules() {
            productsExist(42L);

            EventAdminView view = service.create(request(true, new EventProductRequest(42L, 30)));

            // ACTIVE 가 아니다 — 시작 시각이 오기 전에 할인이 걸리면 안 된다.
            assertThat(view.status()).isEqualTo("SCHEDULED");
        }

        /**
         * 없는 상품을 그냥 저장하면 증상이 "그 상품만 할인이 안 걸린다"로 나타나는데,
         * 화면의 대상 목록에는 멀쩡히 들어 있어 무엇이 틀렸는지 알 수 없다.
         */
        @Test
        @DisplayName("없는 상품이 대상에 있으면 거절한다")
        void rejectsUnknownProducts() {
            productsExist(42L);   // 99 는 없다

            assertThatThrownBy(() -> service.create(
                    request(true, new EventProductRequest(42L, 30), new EventProductRequest(99L, 30))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("같은 상품이 두 번 오면 하나로 합친다 — 어느 할인율이 적용될지가 순서에 달리면 안 된다")
        void deduplicatesProducts() {
            productsExist(42L);

            EventAdminView view = service.create(
                    request(true, new EventProductRequest(42L, 30), new EventProductRequest(42L, 50)));

            assertThat(view.products()).hasSize(1);
            // 뒤에 온 값을 쓴다 — 화면에서 고친 값이 뒤에 온다.
            assertThat(view.products().get(0).discountRate()).isEqualTo(50);
        }

        @Test
        @DisplayName("상품이 너무 많으면 거절한다")
        void rejectsTooManyProducts() {
            List<EventProductRequest> many = java.util.stream.IntStream
                    .rangeClosed(1, EventService.MAX_PRODUCTS + 1)
                    .mapToObj(i -> new EventProductRequest((long) i, 10))
                    .toList();

            assertThatThrownBy(() -> service.create(new EventRequest(
                    "큰 세일", null, null, null, null, null, null, true, many,
                    List.of(), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("slug 를 비우면 제목에서 만든다")
        void derivesSlugFromTitle() {
            productsExist(42L);

            EventAdminView view = service.create(minimal("Autumn Sale 2026"));

            assertThat(view.slug()).isEqualTo("autumn-sale-2026");
        }

        /**
         * 제목이 한글이면 slugify 후 남는 글자가 없다. 빈 slug 는 {@code /event/} 가 되어
         * 목록 경로와 충돌한다.
         */
        @Test
        @DisplayName("한글 제목이어도 빈 slug 가 나오지 않는다")
        void koreanTitleStillGetsASlug() {
            EventAdminView view = service.create(minimal("가을 대잔치"));

            assertThat(view.slug()).isNotBlank();
        }

        @Test
        @DisplayName("slug 가 겹치면 뒤에 숫자를 붙인다")
        void appendsSuffixOnCollision() {
            when(eventRepository.existsBySlug("autumn")).thenReturn(true);

            EventAdminView view = service.create(minimal("autumn"));

            assertThat(view.slug()).isEqualTo("autumn-2");
        }
    }

    @Nested
    @DisplayName("고치기 · 끝내기 · 지우기")
    class Modifying {

        /**
         * 끝난 기획전의 할인율을 바꾸는 것은 아무 효과가 없는 동작이다(할인은 ACTIVE 일
         * 때만 합성된다). 저장을 허용하면 관리자는 가격이 왜 안 바뀌는지 알 수 없다.
         */
        @Test
        @DisplayName("끝난 기획전은 고칠 수 없다")
        void endedEventsAreFrozen() {
            existing(EventStatus.ENDED);

            assertThatThrownBy(() -> service.update(7L, request(null)))
                    .isInstanceOf(com.project.common.domain.exception.StateConflictException.class);
        }

        /**
         * 진행 중인 기획전의 배너 이미지만 고치는 요청이 그것을 취소시키면 안 된다.
         */
        @Test
        @DisplayName("publish 를 생략한 수정은 상태를 바꾸지 않는다")
        void omittedPublishKeepsStatus() {
            Event event = existing(EventStatus.ACTIVE);
            productsExist(42L);

            service.update(7L, request(null, new EventProductRequest(42L, 30)));

            assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
        }

        /**
         * SCHEDULED 로 되돌리면 스케줄러가 다시 시작시키며 시작 알림이 한 번 더 나간다.
         */
        @Test
        @DisplayName("진행 중인 것을 다시 확정해도 예약으로 되돌아가지 않는다")
        void republishingAnActiveEventIsANoOp() {
            Event event = existing(EventStatus.ACTIVE);
            productsExist(42L);

            service.update(7L, request(true, new EventProductRequest(42L, 30)));

            assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
        }

        @Test
        @DisplayName("확정을 취소하면 초안으로 돌아간다")
        void unpublishingReturnsToDraft() {
            Event event = existing(EventStatus.SCHEDULED);

            service.update(7L, request(false));

            assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        }

        /**
         * publish=false 로 진행 중인 것을 끄면 진행 기록이 사라진다. 종료는 endNow() 다.
         */
        @Test
        @DisplayName("진행 중인 것은 publish=false 로 꺼지지 않는다 — 그건 '종료'다")
        void unpublishDoesNotEndAnActiveEvent() {
            Event event = existing(EventStatus.ACTIVE);

            service.update(7L, request(false));

            assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
        }

        @Test
        @DisplayName("즉시 종료하면 종료 시각이 남는다 — 언제 끝났는지 알 수 있어야 한다")
        void endNowStampsTheTime() {
            Event event = existing(EventStatus.ACTIVE);

            service.endNow(7L);

            assertThat(event.getStatus()).isEqualTo(EventStatus.ENDED);
            assertThat(event.getEndsAt()).isNotNull();
        }

        @Test
        @DisplayName("진행 중이 아닌 것은 종료할 수 없다")
        void onlyActiveEventsCanBeEnded() {
            existing(EventStatus.SCHEDULED);

            assertThatThrownBy(() -> service.endNow(7L)).isInstanceOf(com.project.common.domain.exception.StateConflictException.class);
        }

        /**
         * 진행 중인 것을 지우면 할인이 조용히 사라지고, "어제 30% 였는데요" 라는 문의에
         * 답할 근거도 없어진다.
         */
        @Test
        @DisplayName("진행 중인 기획전은 지울 수 없다")
        void activeEventsCannotBeDeleted() {
            existing(EventStatus.ACTIVE);

            assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(com.project.common.domain.exception.StateConflictException.class);
            verify(eventRepository, never()).delete(any());
        }

        @Test
        @DisplayName("초안과 끝난 것은 지울 수 있다")
        void draftAndEndedCanBeDeleted() {
            existing(EventStatus.DRAFT);
            service.delete(7L);

            existing(EventStatus.ENDED);
            service.delete(7L);

            verify(eventRepository, times(2)).delete(any());
        }
    }

    @Nested
    @DisplayName("공개 조회")
    class PublicAccess {

        /**
         * 403 을 주면 "그 slug 에 기획전이 있긴 하다"가 새어 나가, 준비 중인 기획전의
         * 이름과 시작 시점을 미리 알 수 있다.
         */
        @Test
        @DisplayName("예약된 기획전은 404 다 — 준비 중인 세일의 존재가 새면 안 된다")
        void scheduledEventsAreHidden() {
            when(eventRepository.findBySlug("autumn")).thenReturn(Optional.of(
                    Event.builder().slug("autumn").status(EventStatus.SCHEDULED).build()));

            assertThatThrownBy(() -> service.publicOne("autumn"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("끝난 기획전도 404 다 — 눌러 들어가도 할인이 없다")
        void endedEventsAreHidden() {
            when(eventRepository.findBySlug("autumn")).thenReturn(Optional.of(
                    Event.builder().slug("autumn").status(EventStatus.ENDED).build()));

            assertThatThrownBy(() -> service.publicOne("autumn"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("진행 중인 것만 보인다")
        void activeEventsAreVisible() {
            when(eventRepository.findBySlug("autumn")).thenReturn(Optional.of(
                    Event.builder().slug("autumn").status(EventStatus.ACTIVE).build()));

            assertThat(service.publicOne("autumn")).isNotNull();
        }
    }

    @Nested
    @DisplayName("스케줄러")
    class Scheduling {

        private Event due(long id, EventStatus status) {
            return Event.builder().eventId(id).title("세일 " + id)
                    .slug("sale-" + id).status(status).build();
        }

        @Test
        @DisplayName("시작할 때가 된 것을 시작시키고 알린다")
        void startsDueEvents() {
            when(eventRepository.dueToStart(any())).thenReturn(List.of(due(1L, EventStatus.SCHEDULED)));
            when(eventRepository.dueToEnd(any())).thenReturn(List.of());
            when(eventRepository.transition(anyLong(), any(), any())).thenReturn(1);

            Advanced advanced = service.advanceDueEvents(LocalDateTime.now());

            assertThat(advanced.started()).hasSize(1);
            assertThat(advanced.changed()).isTrue();
        }

        /**
         * 🔴 인스턴스가 둘이면 스케줄러도 둘이다. 조건부 UPDATE 가 0 을 돌려준다는 것은
         * "다른 인스턴스가 먼저 바꿨다"는 뜻이고, 그쪽이 알린다. 여기서도 알리면 모든
         * 사용자가 같은 알림을 두 개 받는다.
         */
        @Test
        @DisplayName("다른 인스턴스가 먼저 시작시켰으면 알리지 않는다")
        void losingTheRaceMeansNoNotification() {
            when(eventRepository.dueToStart(any())).thenReturn(List.of(due(1L, EventStatus.SCHEDULED)));
            when(eventRepository.dueToEnd(any())).thenReturn(List.of());
            when(eventRepository.transition(anyLong(), any(), any())).thenReturn(0);

            Advanced advanced = service.advanceDueEvents(LocalDateTime.now());

            assertThat(advanced.started()).isEmpty();
            assertThat(advanced.changed()).isFalse();
        }

        /**
         * 종료도 캐시를 비워야 한다. 빠뜨리면 기획전이 끝난 뒤에도 캐시에 남은 할인가가
         * TTL 이 지날 때까지 보이고, <b>그 가격으로 장바구니에 담긴다.</b>
         */
        @Test
        @DisplayName("종료만 있어도 '바뀐 것이 있다'로 친다 — 캐시를 비워야 한다")
        void endingAloneCountsAsAChange() {
            when(eventRepository.dueToStart(any())).thenReturn(List.of());
            when(eventRepository.dueToEnd(any())).thenReturn(List.of(due(1L, EventStatus.ACTIVE)));
            when(eventRepository.transition(anyLong(), any(), any())).thenReturn(1);

            Advanced advanced = service.advanceDueEvents(LocalDateTime.now());

            assertThat(advanced.started()).isEmpty();
            assertThat(advanced.ended()).isEqualTo(1);
            assertThat(advanced.changed()).isTrue();
        }

        @Test
        @DisplayName("종료는 알리지 않는다 — '세일이 끝났습니다'는 쓸모가 없다")
        void endingIsNotAnnounced() {
            when(eventRepository.dueToStart(any())).thenReturn(List.of());
            when(eventRepository.dueToEnd(any())).thenReturn(List.of(due(1L, EventStatus.ACTIVE)));
            when(eventRepository.transition(anyLong(), any(), any())).thenReturn(1);

            assertThat(service.advanceDueEvents(LocalDateTime.now()).started()).isEmpty();
        }

        @Test
        @DisplayName("바뀐 것이 없으면 캐시를 비우지 않는다 — 1분마다 전체 캐시를 날리면 안 된다")
        void quietTickDoesNotEvict() {
            EventService eventService = mock(EventService.class);
            EventPricing pricing = mock(EventPricing.class);
            EventStartedProducer producer = mock(EventStartedProducer.class);
            CacheManager cacheManager = mock(CacheManager.class);
            Cache cache = mock(Cache.class);
            when(cacheManager.getCache(anyString())).thenReturn(cache);
            when(eventService.advanceDueEvents(any())).thenReturn(new Advanced(List.of(), 0));

            new EventScheduler(eventService, pricing, producer, cacheManager).tick();

            verify(cache, never()).clear();
            // 상태가 안 바뀌어도 할인 맵은 갱신한다 — 다른 인스턴스가 바꾼 것과 관리자가
            // 고친 대상 상품이 여기서 반영된다.
            verify(pricing).refresh();
        }

        @Test
        @DisplayName("시작이 있으면 캐시를 비우고 알림을 보낸다")
        void startEvictsAndPublishes() {
            EventService eventService = mock(EventService.class);
            EventPricing pricing = mock(EventPricing.class);
            EventStartedProducer producer = mock(EventStartedProducer.class);
            CacheManager cacheManager = mock(CacheManager.class);
            Cache cache = mock(Cache.class);
            when(cacheManager.getCache(anyString())).thenReturn(cache);
            Event started = due(1L, EventStatus.ACTIVE);
            when(eventService.advanceDueEvents(any())).thenReturn(new Advanced(List.of(started), 0));

            new EventScheduler(eventService, pricing, producer, cacheManager).tick();

            verify(cache, times(EventScheduler.PRODUCT_CACHES.size())).clear();
            verify(producer).publish(started);
        }

        /**
         * 스케줄러 스레드에서 예외가 올라가면 <b>이후 실행이 멈춘다.</b> 기획전 하나 때문에
         * 전체 일정이 죽으면, 그 뒤의 모든 세일이 시작되지 않는다.
         */
        @Test
        @DisplayName("전환에 실패해도 스케줄러가 죽지 않는다")
        void failureDoesNotKillTheScheduler() {
            EventService eventService = mock(EventService.class);
            CacheManager cacheManager = mock(CacheManager.class);
            when(eventService.advanceDueEvents(any())).thenThrow(new RuntimeException("DB down"));

            new EventScheduler(eventService, mock(EventPricing.class),
                    mock(EventStartedProducer.class), cacheManager).tick();
            // 예외가 밖으로 나오지 않으면 통과다.
        }
    }
}
