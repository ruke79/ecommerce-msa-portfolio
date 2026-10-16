package com.project.catalog_service.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.repository.jpa.data.EventRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSkuDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 진행 중인 기획전 할인을 상품 조회 결과에 <b>합성</b>한다.
 *
 * <p><b>왜 합성인가.</b> 가장 쉬운 구현은 기획전이 시작될 때 {@code ProductSku.discount} 를
 * 덮어쓰고 끝날 때 되돌리는 것이다. 그러면 되돌릴 원래 값을 어딘가 적어 둬야 하는데,
 * 진행 중에 관리자가 그 상품의 상시 할인을 고치면 종료 시 복원이 그 수정을 덮는다.
 * 관리자는 자기가 방금 한 수정이 왜 사라졌는지 알 수 없다. 기획전 둘이 같은 상품을 겹쳐
 * 잡으면 먼저 끝나는 쪽이 다른 쪽의 할인까지 지운다.
 *
 * <p>그래서 DB 의 할인율은 언제나 "기획전과 무관한 상시 할인" 하나의 뜻만 갖고, 기획전
 * 할인은 <b>조회할 때</b> 얹는다.
 *
 * <p><b>왜 맵을 들고 있나.</b> 상품 목록을 그릴 때마다 상품 수만큼 "이 상품에 걸린 기획전이
 * 있나"를 묻는 것은 정확히 N+1 이다. 진행 중인 기획전의 대상은 많아야 수천 건이므로 전부
 * 메모리에 두고 1분마다 통째로 갈아 끼운다({@code EventScheduler} 가 같은 주기로 상태를
 * 넘긴다).
 *
 * <p>1분의 지연이 허용되는 이유: 기획전의 경계는 사람이 정한 시각이고 초 단위 정확도를
 * 요구하지 않는다. 그리고 <b>양쪽 모두</b> 같은 맵을 보므로 화면에 보이는 가격과 장바구니에
 * 담기는 가격이 어긋나지 않는다 — 그쪽이 정확도보다 중요하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPricing {

    private final EventRepository eventRepository;

    /**
     * 카테고리 대상을 상품으로 펼치는 데 쓴다.
     *
     * <p>펼친 결과를 저장하지 않는 이유: 저장하면 그 순간 목록이 굳어, 기획전이 시작된 뒤
     * 그 카테고리에 등록된 상품이 빠진다. 관리자는 카테고리를 걸었다고 생각하는데
     * 신상품만 정가로 남는다(EventCategory 주석).
     */
    private final ProductRepository productRepository;

    /**
     * productId → 기획전 할인율(퍼센트).
     *
     * <p>{@code volatile} 이고 통째로 교체한다 — 읽는 쪽(요청 스레드)이 갱신 도중의 반쪽짜리
     * 맵을 보지 않게 하려면 원소를 하나씩 고치면 안 된다.
     */
    private volatile Map<Long, Integer> discounts = Map.of();

    /** 진행 중인 기획전의 할인을 다시 읽는다. 스케줄러가 1분마다 부른다. */
    /**
     * 기동하면서 한 번 읽는다 — 첫 스케줄 실행(10초 뒤)을 기다리지 않는다. ProductService 가 이 빈을 받기 전에 끝나므로 기동 워밍업
     * (ProductWarmUpService)도 할인이 얹힌 값으로 공유 캐시를 채운다.
     *
     * <p>🐛 예전에는 기동 뒤 10초 동안 맵이 비어 있었다 — 그 사이 워밍업 · 요청이 기획전 할인 없는 가격을 공유 Redis 에 넣었고, 다른
     * 인스턴스는 바뀐 것이 없으니 비우지 않아 캐시 만료(최대 60분)까지 정가가 나갔다. DB 를 못 읽어도 기동은 계속한다(스케줄이 다시 읽는다).
     *
     * <p>[JA] 起動時に一度読む — 以前は起動後 10 秒間マップが空で、その間のウォームアップ · 要求が割引なしの価格を共有キャッシュに入れた。
     */
    @jakarta.annotation.PostConstruct
    void loadOnStartup() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("기동 시 기획전 할인을 읽지 못했다 — 스케줄러가 다시 읽는다 ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * @return 할인 맵이 바뀌었는가 — 바뀌었으면 호출자(EventScheduler)가 공유 캐시를 비운다.
     *         [JA] 割引マップが変わったか — 変わっていれば呼び出し側が共有キャッシュを消す。
     */
    @Transactional(readOnly = true)
    public boolean refresh() {
        Map<Long, Integer> next = new HashMap<>();

        // 같은 상품이 여러 곳에 걸려 있으면 **높은 쪽**을 쓴다. 더하면(혹은 곱하면)
        // 아무도 의도하지 않은 깊은 할인이 나온다 — 30% 기획전 둘이 겹쳐 51% 가 되는
        // 식이다. 관리자는 두 기획전을 각각 30% 로 만들었을 뿐이다.
        //
        // 카테고리와 상품 지정이 겹칠 때도 같은 규칙이다. "카테고리 전체 20%, 그중 이
        // 상품만 40%" 가 자연스럽게 표현된다 — 순서를 신경 쓸 필요가 없다.
        for (Object[] row : eventRepository.activeProductDiscounts()) {
            next.merge((Long) row[0], ((Number) row[1]).intValue(), Math::max);
        }
        expandCategories(next);

        Map<Long, Integer> before = discounts;
        discounts = Map.copyOf(next);

        boolean changed = !before.equals(discounts);
        if (changed) {
            log.info("기획전 할인 갱신: 대상 {}건 (이전 {}건)", next.size(), before.size());
        }
        return changed;
    }

    /**
     * 카테고리 대상을 상품으로 펼친다.
     *
     * <p>카테고리 수만큼 질의하지 않는다 — 한 번에 묶어 읽는다. 이 갱신은 1분마다
     * 반복되므로 왕복 수가 그대로 곱해진다.
     */
    private void expandCategories(Map<Long, Integer> into) {
        List<Object[]> categoryRows = eventRepository.activeCategoryDiscounts();
        if (categoryRows.isEmpty()) {
            return;
        }

        Map<Long, Integer> byCategory = new HashMap<>();
        for (Object[] row : categoryRows) {
            byCategory.merge((Long) row[0], ((Number) row[1]).intValue(), Math::max);
        }

        for (Object[] row : productRepository.findProductIdsByCategoryIds(byCategory.keySet())) {
            Long categoryId = (Long) row[0];
            Long productId = (Long) row[1];
            Integer rate = byCategory.get(categoryId);
            if (rate != null) {
                into.merge(productId, rate, Math::max);
            }
        }
    }

    /**
     * 실제로 적용할 할인율.
     *
     * <p><b>상시 할인과 기획전 할인 중 높은 쪽을 쓴다.</b> 두 가지 다른 선택지가 있었다:
     *
     * <ul>
     *   <li><b>기획전이 덮어쓴다</b> — 상시 40% 인 상품을 20% 기획전에 넣으면 <b>가격이
     *       오른다.</b> 관리자는 세일을 추가했는데 고객은 비싸진 것을 본다.</li>
     *   <li><b>누적</b> — 40% 와 20% 가 52% 가 된다. 아무도 그 숫자를 의도하지 않았고,
     *       기획전에 상품을 담는 것이 위험한 행동이 된다.</li>
     * </ul>
     *
     * <p>높은 쪽을 쓰면 "기획전에 넣으면 최소한 그만큼은 싸진다"가 되고, 그것이 관리자가
     * 기대하는 유일한 동작이다.
     */
    public int effectiveDiscount(Long productId, int baseDiscount) {
        if (productId == null) {
            return baseDiscount;
        }
        Integer eventRate = discounts.get(productId);
        return eventRate == null ? baseDiscount : Math.max(baseDiscount, eventRate);
    }

    /** 이 상품에 지금 걸린 기획전 할인. 없으면 0. */
    public int eventDiscountOf(Long productId) {
        return productId == null ? 0 : discounts.getOrDefault(productId, 0);
    }

    /**
     * 상품 DTO 의 SKU 할인율을 합성된 값으로 바꾼다.
     *
     * <p>DTO 를 <b>고쳐서</b> 돌려주는 이유: 이 값은 화면이 "-30%" 배지를 그리고 가격을
     * 계산하는 데 쓰는 바로 그 숫자다. 별도 필드로 내려주면 화면마다 "둘 중 뭘 쓸지"를
     * 정해야 하고, 한 군데라도 옛 필드를 쓰면 그 화면만 정가를 보여 준다.
     */
    public ProductDto apply(ProductDto dto) {
        if (dto == null || dto.getSkus() == null || dto.getId() == null) {
            return dto;
        }
        Long productId;
        try {
            productId = Long.valueOf(dto.getId());
        } catch (NumberFormatException e) {
            return dto;
        }

        int eventRate = eventDiscountOf(productId);
        if (eventRate <= 0) {
            // 대상이 아니면 손대지 않는다. 캐시에서 온 DTO 를 공유할 수 있으므로 필요 없는
            // 쓰기를 하지 않는 편이 낫다.
            return dto;
        }

        for (ProductSkuDto sku : dto.getSkus()) {
            if (sku != null) {
                sku.setDiscount(Math.max(sku.getDiscount(), eventRate));
            }
        }
        return dto;
    }

    /** 목록 전체에 적용한다. */
    public List<ProductDto> apply(List<ProductDto> products) {
        if (products != null) {
            products.forEach(this::apply);
        }
        return products;
    }

    /** 지금 할인이 걸린 상품 수 — 운영 확인용. */
    public int activeProductCount() {
        return discounts.size();
    }
}
