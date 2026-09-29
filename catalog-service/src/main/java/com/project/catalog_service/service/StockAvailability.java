package com.project.catalog_service.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 손님에게 보이는 남은 수량 = <b>지금</b> 실물 − 다른 주문이 결제 전에 잡아 둔 수 (docs/59 §5).
 *
 * <h3>왜 응답 직전에 고치나</h3>
 *
 * <p>상품 DTO 는 Redis 에 캐시된다 — 상세(slug 키), 검색 결과, 홈의 카테고리 목록. 예약은 분 단위로 생겼다 사라진다 — 캐시된
 * 값에 넣으면 예약이 풀려도 캐시가 바뀔 때까지 "0개" 가 남는다. 게다가 카테고리 목록 캐시는 만료가 없고 재고가 바뀌어도 비우지
 * 않아서, 그 안의 <b>실물 수량부터</b> 언제 적 것인지 모른다. 그래서 응답할 때마다 한 묶음(상세 하나, 목록 한 페이지)에 대해
 * 두 번 읽는다 — 사이즈별 지금 수량 한 번, 살아 있는 예약 합 한 번. 둘 다 인덱스 조회다(V14·V15). 캐시된 객체는 건드리지 않고
 * 복사본을 고친다 — 캐시가 메모리 캐시로 바뀌어도 빼기가 쌓이지 않게.
 *
 * <h3>실패하면</h3>
 *
 * <p>읽기가 실패하면 캐시의 값을 그대로 보여 준다(두 읽기는 따로 실패한다). 재고 표시 때문에 상품 화면이 깨지면 안 된다 — 주문
 * 단계의 예약이 어차피 정확히 거른다. 이 클래스는 트랜잭션 밖(컨트롤러)에서 부른다 — 트랜잭션 안에서 조회가 실패하면 삼켜도 그
 * 트랜잭션이 rollback-only 가 된다.
 *
 * <p>🔒 관리자 화면(상품 목록·편집)에는 쓰지 않는다. 뺀 값을 편집 화면이 받아 저장하면 실물 재고가 줄어든다.
 */
@Slf4j
@Component
public class StockAvailability {

    record Option(Long productId, Long colorId, String size) {
    }

    private final StockReservationRepository reservations;
    private final ProductSizeRepository sizes;
    private Clock clock = Clock.systemUTC();

    public StockAvailability(StockReservationRepository reservations, ProductSizeRepository sizes) {
        this.reservations = reservations;
        this.sizes = sizes;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 한 응답에 쓸 재고 — 상품 id 묶음에 대해 지금 수량과 예약 합을 한 번씩 읽어 둔 것. 홈처럼 여러 목록을 한 응답에 담을 때는
     * 모든 상품 id 로 하나를 만들어 나눠 쓴다.
     */
    public final class Snapshot {
        private final Map<Long, Integer> liveBySizeId;
        private final Map<Option, Integer> held;

        private Snapshot(Map<Long, Integer> liveBySizeId, Map<Option, Integer> held) {
            this.liveBySizeId = liveBySizeId;
            this.held = held;
        }

        /** 옵션마다 수량을 지금 값 − 예약으로 바꾼 <b>복사본</b>. 원본(캐시된 DTO)은 그대로다. */
        public ProductDto apply(ProductDto product) {
            Long productId = parse(product == null ? null : product.getId());
            if (productId == null || product.getSkus() == null || (liveBySizeId.isEmpty() && held.isEmpty())) {
                return product;
            }
            List<ProductSkuDto> skus = new ArrayList<>(product.getSkus().size());
            for (ProductSkuDto sku : product.getSkus()) {
                if (sku == null || sku.getSizes() == null) {
                    skus.add(sku);
                    continue;
                }
                Long colorId = parse(sku.getColor() == null ? null : sku.getColor().getId());
                List<ProductSizeDto> out = new ArrayList<>(sku.getSizes().size());
                for (ProductSizeDto size : sku.getSizes()) {
                    out.add(adjust(size, productId, colorId));
                }
                skus.add(sku.toBuilder().sizes(out).build());
            }
            return product.toBuilder().skus(skus).build();
        }

        public List<ProductDto> apply(List<ProductDto> products) {
            if (products == null) {
                return null;
            }
            List<ProductDto> out = new ArrayList<>(products.size());
            for (ProductDto p : products) {
                out.add(apply(p));
            }
            return out;
        }

        private ProductSizeDto adjust(ProductSizeDto size, Long productId, Long colorId) {
            if (size == null) {
                return null;
            }
            Long sizeId = parse(size.getId());
            Integer live = sizeId == null ? null : liveBySizeId.get(sizeId);
            int quantity = live != null ? live : size.getQuantity();
            int onHold = colorId == null ? 0 : onHold(quantity, held.get(new Option(productId, colorId, size.getSize())));
            if (quantity == size.getQuantity() && onHold == 0) {
                return size;
            }
            return size.toBuilder().quantity(quantity - onHold).onHold(onHold).build();
        }
    }

    /** 이 상품들의 재고를 읽는다. {@code live} 면 사이즈별 지금 수량도 — 캐시의 수량을 믿을 수 없을 때(상세·목록). */
    public Snapshot snapshot(Collection<Long> productIds, boolean live) {
        if (productIds == null || productIds.isEmpty()) {
            return new Snapshot(Map.of(), Map.of());
        }
        return new Snapshot(live ? liveFor(productIds) : Map.of(), heldFor(productIds));
    }

    /** 상품 상세 한 건. */
    public ProductDto apply(ProductDto product) {
        Long productId = parse(product == null ? null : product.getId());
        if (productId == null) {
            return product;
        }
        return snapshot(List.of(productId), true).apply(product);
    }

    /** 목록 한 페이지 — 상품 id 를 모아 한 번에 읽는다. */
    public List<ProductDto> apply(List<ProductDto> products) {
        if (products == null || products.isEmpty()) {
            return products;
        }
        return snapshot(idsOf(products), true).apply(products);
    }

    /** 목록 여럿에 걸친 상품 id(중복 없이, 순서 유지). */
    public static Set<Long> idsOf(Collection<ProductDto> products) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ProductDto p : products) {
            Long id = parse(p == null ? null : p.getId());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 장바구니 항목 하나 — 방금 DB 에서 만든 DTO 라 수량은 지금 값이다. 예약만 빼고, 그 자리에서 고친다. */
    public ProductInfoDto apply(ProductInfoDto info) {
        if (info != null) {
            apply(Map.of("_", info));
        }
        return info;
    }

    /** 장바구니 항목 여러 개 — 상품 id 를 모아 한 번에 읽는다. */
    public Map<String, ProductInfoDto> apply(Map<String, ProductInfoDto> infos) {
        if (infos == null || infos.isEmpty()) {
            return infos;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (ProductInfoDto info : infos.values()) {
            Long id = parse(info == null ? null : info.getId());
            if (id != null) {
                ids.add(id);
            }
        }
        Map<Option, Integer> held = heldFor(ids);
        if (held.isEmpty()) {
            return infos;
        }
        for (ProductInfoDto info : infos.values()) {
            if (info == null || info.getColor() == null) {
                continue;
            }
            Option key = new Option(parse(info.getId()), parse(info.getColor().getId()), info.getSize());
            int onHold = onHold(info.getQuantity(), held.get(key));
            if (onHold > 0) {
                info.setQuantity(info.getQuantity() - onHold);
                info.setOnHold(onHold);
            }
        }
        return infos;
    }

    /**
     * 옵션 하나의 지금 재고 — 장바구니 화면용. {@code quantity} 는 이미 {@code onHold} 를 뺀 값이다. {@code status} 는 상품
     * 상태(ACTIVE·SOLD_OUT·DELETED).
     */
    public record OptionStock(int quantity, int onHold, String status) {
    }

    /**
     * 장바구니 항목들의 지금 재고 — 키는 {@code 상품id:색상id:사이즈}. 없는 옵션은 결과에 없다.
     *
     * <p>목록·상세와 달리 <b>실패를 삼키지 않고 던진다.</b> 장바구니는 이미 들고 있는 값이 있다 — 빈 결과를 돌려주면 "재고가
     * 없다" 와 구분되지 않는다. 부르는 쪽이 실패로 알리고 화면은 가진 값을 그대로 둔다.
     */
    public Map<String, OptionStock> optionStock(Collection<String> keys) {
        Map<String, Option> wanted = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            wanted.put(key, parseKey(key));
        }
        Set<Long> ids = new LinkedHashSet<>();
        wanted.values().forEach(o -> ids.add(o.productId()));

        Map<Option, int[]> live = new HashMap<>();
        Map<Long, String> status = new HashMap<>();
        for (Object[] row : sizes.liveOptions(ids)) {
            Option o = new Option(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), (String) row[2]);
            live.merge(o, new int[] {((Number) row[3]).intValue()}, (a, b) -> new int[] {a[0] + b[0]});
            status.put(o.productId(), (String) row[4]);
        }
        Map<Option, Integer> held = new HashMap<>();
        for (Object[] row : reservations.sumHeldByProducts(ids, Instant.now(clock))) {
            held.put(new Option(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), (String) row[2]),
                    ((Number) row[3]).intValue());
        }

        Map<String, OptionStock> out = new java.util.LinkedHashMap<>();
        wanted.forEach((key, o) -> {
            int[] q = live.get(o);
            if (q == null) {
                return;
            }
            int quantity = Math.max(0, q[0]);
            int onHold = onHold(quantity, held.get(o));
            out.put(key, new OptionStock(quantity - onHold, onHold, status.get(o.productId())));
        });
        return out;
    }

    /** {@code 상품id:색상id:사이즈} — 사이즈는 마지막이라 ':' 가 들어 있어도 된다. */
    static Option parseKey(String key) {
        String[] parts = key == null ? new String[0] : key.split(":", 3);
        if (parts.length != 3 || parts[2].isEmpty()) {
            throw new IllegalArgumentException("형식이 틀린 항목입니다: " + key);
        }
        try {
            return new Option(Long.valueOf(parts[0].trim()), Long.valueOf(parts[1].trim()), parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("형식이 틀린 항목입니다: " + key);
        }
    }

    /** 실물 중 잡힌 수 — 실물보다 많이 잡혀 있어도(재고를 관리자가 줄였다) 실물까지만. */
    private static int onHold(int quantity, Integer held) {
        if (held == null || held <= 0 || quantity <= 0) {
            return 0;
        }
        return Math.min(quantity, held);
    }

    Map<Option, Integer> heldFor(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Option, Integer> held = new HashMap<>();
            for (Object[] row : reservations.sumHeldByProducts(productIds, Instant.now(clock))) {
                Option key = new Option(((Number) row[0]).longValue(), ((Number) row[1]).longValue(), (String) row[2]);
                held.put(key, ((Number) row[3]).intValue());
            }
            return held;
        } catch (RuntimeException e) {
            log.warn("예약 수 조회 실패 — 예약을 빼지 않고 보여 준다 ({})", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    Map<Long, Integer> liveFor(Collection<Long> productIds) {
        try {
            Map<Long, Integer> live = new HashMap<>();
            for (Object[] row : sizes.liveQuantities(productIds)) {
                live.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
            }
            return live;
        } catch (RuntimeException e) {
            log.warn("지금 수량 조회 실패 — 캐시의 수량을 그대로 보여 준다 ({})", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static Long parse(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
