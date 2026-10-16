package com.project.catalog_service.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.dto.EventDtos.EventAdminView;
import com.project.catalog_service.dto.EventDtos.EventCategoryRequest;
import com.project.catalog_service.dto.EventDtos.EventCouponOffer;
import com.project.catalog_service.dto.EventDtos.EventProductRequest;
import com.project.catalog_service.dto.EventDtos.EventRequest;
import com.project.catalog_service.model.Event;
import com.project.catalog_service.model.EventCategory;
import com.project.catalog_service.model.EventProduct;
import com.project.catalog_service.model.EventStatus;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.EventRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.common.constants.coupon.CouponScope;
import com.project.common.constants.event.AudienceSegment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이벤트 / 기획전 관리.
 *
 * <p>상태 전환은 여기가 아니라 {@code EventScheduler} 가 한다 — 관리자는 <b>확정</b>까지만
 * 하고, 시작·종료는 시각이 결정한다. 사람이 자정에 맞춰 버튼을 누르는 운영은 반드시
 * 놓친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    /** 한 기획전에 담을 수 있는 상품 수. */
    static final int MAX_PRODUCTS = 500;

    /** 쿠폰 유효기간을 정하지 않았을 때. 받자마자 만료되는 쿠폰이 나가면 안 된다. */
    static final int DEFAULT_COUPON_VALID_DAYS = 14;

    private final EventRepository eventRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public List<EventAdminView> all() {
        return eventRepository.findAllByOrderByEventIdDesc().stream()
                .map(EventAdminView::of)
                .toList();
    }

    public EventAdminView one(Long id) {
        return EventAdminView.of(find(id));
    }

    /** 지금 진행 중인 기획전. 공개 목록이 쓴다. */
    public List<Event> active() {
        return eventRepository.findByStatusOrderByStartsAtDesc(EventStatus.ACTIVE);
    }

    /**
     * 공개 상세.
     *
     * <p>진행 중이 아니면 <b>404</b> 다. 403 을 주면 "그 slug 에 기획전이 있긴 하다"가 새어
     * 나가, 준비 중인 기획전의 존재와 이름을 미리 알 수 있다(공지의 비공개 글과 같은 이유).
     */
    public Event publicOne(String slug) {
        return eventRepository.findBySlug(slug)
                .filter(e -> e.getStatus().isPublic())
                .orElseThrow(() -> new IllegalArgumentException("기획전을 찾을 수 없습니다."));
    }

    @Transactional
    public EventAdminView create(EventRequest request) {
        Event event = Event.builder()
                .title(request.title())
                .slug(uniqueSlug(request.slug(), request.title(), null))
                .description(request.description())
                .bannerImageUrl(request.bannerImageUrl())
                .linkUrl(request.linkUrl())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .status(EventStatus.DRAFT)
                .build();

        event.replaceProducts(toProducts(request.products()));
        event.replaceCategories(toCategories(request.categories()));
        applyCoupon(event, request);
        applyPublishFlag(event, request.publish());

        Event saved = eventRepository.save(event);
        log.info("기획전 생성: id={}, status={}, 상품 {}건, 카테고리 {}건",
                saved.getEventId(), saved.getStatus(),
                saved.getProducts().size(), saved.getCategories().size());
        return EventAdminView.of(saved);
    }

    @Transactional
    public EventAdminView update(Long id, EventRequest request) {
        Event event = find(id);

        // 끝난 기획전은 고칠 수 없다. 고치게 두면 "종료된 기획전의 할인율을 바꾼다"는
        // 아무 효과 없는 동작이 되고, 관리자는 왜 가격이 안 바뀌는지 알 수 없다.
        // 새로 만드는 것이 맞다.
        if (event.getStatus() == EventStatus.ENDED) {
            throw new com.project.common.domain.exception.StateConflictException("이미 종료된 기획전은 수정할 수 없습니다. 새로 만들어 주세요.");
        }

        event.setTitle(request.title());
        event.setSlug(uniqueSlug(request.slug(), request.title(), event));
        event.setDescription(request.description());
        event.setBannerImageUrl(request.bannerImageUrl());
        event.setLinkUrl(request.linkUrl());
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt());
        event.replaceProducts(toProducts(request.products()));
        event.replaceCategories(toCategories(request.categories()));
        applyCoupon(event, request);
        applyPublishFlag(event, request.publish());

        log.info("기획전 수정: id={}, status={}, 상품 {}건, 카테고리 {}건",
                id, event.getStatus(), event.getProducts().size(), event.getCategories().size());
        return EventAdminView.of(event);
    }

    /**
     * 지금 끝낸다.
     *
     * <p>삭제와 다르다 — 진행 중인 기획전을 지우면 그런 세일이 있었다는 기록이 사라진다.
     * 고객 문의("어제 30% 였는데요")에 답할 수 없게 된다.
     */
    @Transactional
    public EventAdminView endNow(Long id) {
        Event event = find(id);
        if (event.getStatus() != EventStatus.ACTIVE) {
            throw new com.project.common.domain.exception.StateConflictException("진행 중인 기획전만 종료할 수 있습니다.");
        }
        event.setStatus(EventStatus.ENDED);
        // 종료 시각을 남긴다. 비워 두면 목록에서 "언제 끝났는지"를 알 수 없다.
        event.setEndsAt(java.time.LocalDateTime.now());
        log.warn("기획전 즉시 종료: id={}", id);
        return EventAdminView.of(event);
    }

    @Transactional
    public void delete(Long id) {
        Event event = find(id);
        // 진행 중인 것을 지우면 할인이 조용히 사라지고 기록도 남지 않는다. 끝내는 것이
        // 맞는 동작이라 그쪽으로 안내한다.
        if (event.getStatus() == EventStatus.ACTIVE) {
            throw new com.project.common.domain.exception.StateConflictException("진행 중인 기획전은 삭제할 수 없습니다. 먼저 종료해 주세요.");
        }
        eventRepository.delete(event);
        log.warn("기획전 삭제: id={}", id);
    }

    /**
     * 시각이 된 기획전의 상태를 넘긴다. 스케줄러가 부른다.
     *
     * <p><b>왜 스케줄러가 아니라 여기에 있나.</b> {@code @Transactional} 은 프록시로
     * 동작한다 — 같은 빈 안에서 자기 메서드를 부르면 프록시를 지나지 않아 애노테이션이
     * 조용히 무시된다. 스케줄러의 {@code tick()} 이 같은 클래스의 {@code advance()} 를
     * 부르면 정확히 그 함정에 빠진다. 빈 경계를 넘겨 둔다.
     *
     * <p>조건부 UPDATE 를 쓰는 이유는 {@code EventRepository.transition} 주석 참조 —
     * 인스턴스가 둘이면 스케줄러도 둘이고, 시작 알림이 두 번 나가면 안 된다.
     *
     * @return 이번에 시작된 기획전과 종료된 건수
     */
    @Transactional
    public Advanced advanceDueEvents(java.time.LocalDateTime now) {
        List<Event> started = new ArrayList<>();
        int ended = 0;

        for (Event event : eventRepository.dueToStart(now)) {
            if (eventRepository.transition(event.getEventId(),
                    EventStatus.SCHEDULED, EventStatus.ACTIVE) > 0) {
                log.info("기획전 시작: id={}, slug={}", event.getEventId(), event.getSlug());
                started.add(event);
            }
        }

        for (Event event : eventRepository.dueToEnd(now)) {
            if (eventRepository.transition(event.getEventId(),
                    EventStatus.ACTIVE, EventStatus.ENDED) > 0) {
                // 종료는 알리지 않는다. "세일이 끝났습니다"는 사용자에게 쓸모가 없고,
                // 기획전 하나당 알림이 두 개가 된다.
                log.info("기획전 종료: id={}, slug={}", event.getEventId(), event.getSlug());
                ended++;
            }
        }

        return new Advanced(started, ended);
    }

    /**
     * 한 번의 전환 결과.
     *
     * <p>시작과 종료를 나눠 돌려주는 이유: 시작은 <b>알림을 발행해야</b> 하고 종료는
     * 아니지만, <b>둘 다 캐시를 비워야</b> 한다. 종료를 빠뜨리면 기획전이 끝난 뒤에도
     * 캐시에 남은 할인가가 TTL 이 지날 때까지 보이고 그 가격으로 장바구니에 담긴다.
     */
    public record Advanced(List<Event> started, int ended) {

        /** 상태가 하나라도 바뀌었는가 — 캐시를 비워야 하는지. */
        public boolean changed() {
            return !started.isEmpty() || ended > 0;
        }
    }

    private Event find(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("기획전을 찾을 수 없습니다."));
    }

    /**
     * 확정/취소.
     *
     * <p>{@code publish} 가 null 이면 상태를 건드리지 않는다 — 진행 중인 기획전의 배너
     * 이미지만 고치는 요청이 그것을 취소시키면 안 된다.
     */
    private void applyPublishFlag(Event event, Boolean publish) {
        if (publish == null) {
            return;
        }
        if (publish) {
            // 이미 진행 중이면 그대로 둔다. SCHEDULED 로 되돌리면 스케줄러가 다시 시작시키며
            // 시작 알림이 한 번 더 나간다.
            if (event.getStatus() == EventStatus.DRAFT) {
                event.setStatus(EventStatus.SCHEDULED);
            }
        } else if (event.getStatus() == EventStatus.SCHEDULED) {
            event.setStatus(EventStatus.DRAFT);
        }
        // ACTIVE 를 publish=false 로 끄지 않는다. 그건 "종료"이고 endNow() 가 한다 —
        // 여기서 DRAFT 로 되돌리면 진행 기록이 사라진다.
    }

    /**
     * 대상 카테고리를 검증해 값 타입으로 바꾼다.
     *
     * <p>없는 카테고리는 <b>거절한다.</b> 상품과 같은 이유다 — 그냥 저장하면 증상이
     * "그 카테고리만 할인이 안 걸린다"로 나타나는데, 화면의 대상 목록에는 멀쩡히 들어
     * 있어 무엇이 틀렸는지 알 수 없다.
     *
     * <p>상한을 따로 두지 않는다. 카테고리 수는 관리자가 만든 것이라 애초에 적고, 상품
     * 상한({@link #MAX_PRODUCTS})처럼 사용자 입력으로 부풀지 않는다.
     */
    private List<EventCategory> toCategories(List<EventCategoryRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }

        // 같은 카테고리가 두 번 들어오면 어느 할인율이 적용되는지가 저장 순서에 달린다.
        Map<Long, Integer> byCategory = new LinkedHashMap<>();
        for (EventCategoryRequest item : requested) {
            if (item != null && item.categoryId() != null) {
                byCategory.put(item.categoryId(), item.discountRate());
            }
        }
        if (byCategory.isEmpty()) {
            return List.of();
        }

        Set<Long> existing = categoryRepository.findAllById(byCategory.keySet()).stream()
                .map(c -> c.getCategoryId())
                .collect(java.util.stream.Collectors.toSet());

        List<Long> missing = byCategory.keySet().stream()
                .filter(id -> !existing.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("없는 카테고리가 포함돼 있습니다: " + missing);
        }

        List<EventCategory> result = new ArrayList<>();
        byCategory.forEach((categoryId, rate) -> result.add(new EventCategory(categoryId, rate)));
        return result;
    }

    /**
     * 딸린 쿠폰과 대상 고객군.
     *
     * <p>이름은 있는데 할인율이 없으면 <b>거절한다.</b> 그대로 두면 0% 쿠폰이 발급되고,
     * 사용자는 "쿠폰 받기"를 눌러 받아 놓고도 결제 때 아무 일이 일어나지 않는 것을 본다.
     * 반대로 할인율만 있고 이름이 없으면 쿠폰을 식별할 키가 없다 — 이름이 곧 키다
     * (coupon 테이블의 {@code uk_coupon_name_user_id}).
     */
    private void applyCoupon(Event event, EventRequest request) {
        // 저장할 때는 모르는 값을 거절한다 — from() 은 "모르면 ALL" 이라 오타가 전원 대상이 됐다.
        // [JA] 保存時は未知の値を拒否する — from() は「未知なら ALL」なので、誤字が全員対象になっていた。
        event.setAudience(AudienceSegment.parseKnown(request.audience()));

        String name = request.couponName() == null ? null : request.couponName().trim();
        boolean hasName = name != null && !name.isEmpty();
        boolean hasDiscount = request.couponDiscount() != null && request.couponDiscount() > 0;

        if (hasName != hasDiscount) {
            throw new IllegalArgumentException(
                    "쿠폰을 붙이려면 이름과 할인율이 모두 있어야 합니다.");
        }

        if (!hasName) {
            event.setCouponName(null);
            event.setCouponDiscount(null);
            event.setCouponValidDays(null);
            // 쿠폰이 없으면 고객군은 의미가 없다. ALL 로 되돌려 두지 않으면 관리자 목록에
            // "신규 가입자 전용" 이라고 보이는데 아무것도 전용이 아닌 상태가 된다.
            event.setAudience(AudienceSegment.ALL);
            return;
        }

        event.setCouponName(name);
        event.setCouponDiscount(request.couponDiscount());
        // 기간을 정하지 않으면 기본값을 쓴다. 0 이나 null 로 두면 받자마자 만료된 쿠폰이
        // 발급된다.
        event.setCouponValidDays(request.couponValidDays() == null || request.couponValidDays() <= 0
                ? DEFAULT_COUPON_VALID_DAYS
                : request.couponValidDays());
    }

    /**
     * 기획전에 딸린 쿠폰 — coupon-service 가 발급 전에 읽는다.
     *
     * <p>진행 중이 아니거나 쿠폰이 없으면 예외다. 상태 판정을 coupon-service 가 다시 하지
     * 않게 한다 — 같은 규칙이 두 곳에 있으면 언젠가 어긋나고, 그때 이미 종료된 기획전의
     * 쿠폰이 계속 나간다.
     *
     * <p>쿠폰의 <b>적용 범위</b>는 기획전의 대상을 그대로 물려받는다. 상품을 지정한
     * 기획전이면 그 상품에만, 카테고리를 지정했으면 그 카테고리에만 쓸 수 있다 — "이
     * 기획전의 쿠폰" 이 기획전과 무관한 상품에 먹으면 관리자가 의도한 할인 폭을 넘는다.
     */
    public EventCouponOffer couponOffer(String slug) {
        Event event = publicOne(slug);
        if (!event.offersCoupon()) {
            throw new IllegalArgumentException("이 기획전에는 받을 수 있는 쿠폰이 없습니다.");
        }

        // 상품 지정이 있으면 상품 범위가 더 좁으므로 그쪽을 쓴다. 둘 다 있으면 상품을
        // 고르는 이유: 카테고리로 넓히면 관리자가 굳이 상품을 고른 의도가 사라진다.
        String scope;
        List<String> targets;
        if (!event.getProducts().isEmpty()) {
            scope = CouponScope.PRODUCT.name();
            targets = event.getProducts().stream()
                    .map(p -> String.valueOf(p.getProductId()))
                    .toList();
        } else if (!event.getCategories().isEmpty()) {
            scope = CouponScope.CATEGORY.name();
            targets = event.getCategories().stream()
                    .map(c -> String.valueOf(c.getCategoryId()))
                    .toList();
        } else {
            // 대상이 없는 기획전의 쿠폰은 전 상품에 쓸 수 있다. 이 경우 기획전은
            // 사실상 쿠폰 배포 수단이다.
            scope = CouponScope.ALL.name();
            targets = List.of();
        }

        return new EventCouponOffer(
                event.getEventId(), event.getSlug(), event.getTitle(),
                event.getAudience().name(),
                event.getCouponName(), event.getCouponDiscount(),
                event.getCouponValidDays() == null ? DEFAULT_COUPON_VALID_DAYS : event.getCouponValidDays(),
                scope, targets);
    }

    /**
     * 대상 상품을 검증해 값 타입으로 바꾼다.
     *
     * <p>없는 상품 id 는 <b>거절한다.</b> 그냥 저장하면 증상이 "그 상품만 할인이 안 걸린다"로
     * 나타나는데, 화면에서는 대상 목록에 멀쩡히 들어 있어 무엇이 틀렸는지 알 수 없다.
     */
    private List<EventProduct> toProducts(List<EventProductRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > MAX_PRODUCTS) {
            throw new IllegalArgumentException(
                    "한 기획전에는 상품을 " + MAX_PRODUCTS + "개까지 담을 수 있습니다.");
        }

        // 같은 상품이 두 번 들어오면 어느 할인율이 적용되는지가 저장 순서에 달린다.
        // 마지막 값을 쓰되(화면에서 고친 값이 뒤에 온다) 중복 자체는 여기서 없앤다.
        Map<Long, Integer> byProduct = new LinkedHashMap<>();
        for (EventProductRequest item : requested) {
            if (item == null || item.productId() == null) {
                continue;
            }
            byProduct.put(item.productId(), item.discountRate());
        }

        Set<Long> existing = Set.copyOf(productRepository.findAllById(byProduct.keySet()).stream()
                .map(p -> p.getProductId())
                .toList());

        List<Long> missing = byProduct.keySet().stream()
                .filter(id -> !existing.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("없는 상품이 포함돼 있습니다: " + missing);
        }

        List<EventProduct> result = new ArrayList<>();
        byProduct.forEach((productId, rate) -> result.add(new EventProduct(productId, rate)));
        return result;
    }

    /**
     * URL 에 쓸 이름을 만든다.
     *
     * <p>관리자가 비워 두면 제목에서 만든다. 제목이 한글이면 남는 글자가 없어 빈 문자열이
     * 되므로, 그때는 시각을 섞은 대체 이름을 쓴다 — slug 가 비면 {@code /event/} 가 되어
     * 목록 경로와 충돌한다.
     *
     * <p>겹치면 뒤에 숫자를 붙인다. DB 의 UNIQUE 가 최종 방어선이지만(두 관리자가 동시에
     * 저장하면 이 검사를 둘 다 통과한다), 흔한 경우는 여기서 조용히 처리하는 편이 낫다.
     */
    private String uniqueSlug(String requested, String title, Event current) {
        String base = slugify(requested != null && !requested.isBlank() ? requested : title);
        if (base.isEmpty()) {
            base = "event-" + System.currentTimeMillis();
        }
        if (current != null && base.equals(current.getSlug())) {
            return base;
        }

        String candidate = base;
        for (int suffix = 2; eventRepository.existsBySlug(candidate); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }
}
