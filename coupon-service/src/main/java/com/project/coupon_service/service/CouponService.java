package com.project.coupon_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.coupon.CouponScope;
import com.project.common.constants.event.AudienceSegment;
import com.project.common.constants.coupon.CouponStatus;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.SharedUserDto;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.CouponUseRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.util.EmailValidator;
import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.dto.CouponDto;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.model.CouponTarget;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;
import com.project.coupon_service.dto.request.CouponApplyRequest;
import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.dto.response.CouponResponse;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponTargetRepository couponTargetRepository;
    private final UserServiceClient userServiceClient;
    private final CartServiceClient cartServiceClient;
    private final CatalogServiceClient catalogServiceClient;
    private final CouponUseProducer couponUseProducer;
    private final CouponIssuedProducer couponIssuedProducer;


    
    // cart · catalog 를 부른다 — 트랜잭션 밖에서(CP2: 예전에는 쓰기 트랜잭션 안에서 불러 그동안 DB 커넥션을 쥐었다).
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    /**
     * @param userId       게이트웨이가 검증한 사용자({@code User-Id} 헤더 = 토큰의 uid). 본문에는 신원 필드가 없다 —
     *                     예전에는 본문의 {@code userEmail} 을 믿어 남의 쿠폰이 적용됐다(컨트롤러 주석 참조).
     */
    public CouponResponse applyCoupon(CouponApplyRequest request, Long userId) {

        Quoted quoted = computeQuote(request.getCouponName(), userId);

        if (quoted.usable()) {
            couponUseProducer.publish(CouponUseRequest.builder()
                    .userId(quoted.userId())
                    .totalAfterDiscount(quoted.response().getTotalAfterDiscount())
                    .discount(quoted.response().getDiscount())
                    .build());
        }

        return quoted.response();
    }

    /**
     * 얼마가 깎이는가 — <b>부수효과 없이</b>.
     *
     * <p>{@link #applyCoupon} 과 계산은 똑같고, 쿠폰 사용 이벤트를 발행하지 않는 것만
     * 다르다. order-service 가 주문 총액을 서버에서 다시 계산할 때 부른다(docs/40 #10).
     *
     * <p>🔴 <b>주문 생성이 쿠폰을 "사용" 처리하면 안 된다.</b> 결제가 실패하거나 사용자가
     * 결제 화면에서 이탈하면 쓰지도 않은 쿠폰이 소모된다. 사용 처리는 원래 적용 시점에
     * 일어나고, 여기서는 금액만 답한다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // cart · catalog 를 부른다(CP2)
    public CouponResponse quote(String couponName, Long userId) {
        if (userId == null) {
            return CouponResponse.builder().message("Failed to get coupons").build();
        }
        return computeQuote(couponName, userId).response();
    }

    /**
     * 주문이 읽은 그 장바구니로 견적을 낸다 — order-service 가 주문을 만들 때(내부 호출).
     *
     * <p>🐛 예전에는 order 가 장바구니를 읽어 품목을 만들고, 여기서는 장바구니를 <b>다시</b> 읽어 할인 후 금액을 계산했다 — order 는 그
     * 금액을 그대로 청구했다. 두 읽기 사이(수십 ms)에 다른 탭에서 비싼 줄을 빼면 $1010 어치 품목이 $9 결제로 만들어졌고, 결제 검증은
     * 주문 총액과만 비교하므로 통과했다. 이제 계산의 근거가 주문의 품목과 같은 장바구니다. 실린 장바구니가 없으면(옛 order) 예전처럼 읽는다.
     *
     * <p>[JA] 注文が読んだそのカートで見積もる。以前はここでカートを読み直していて、二つの読み取りの間に行を外すと品目と決済額がずれた。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)   // catalog 를 부를 수 있다(CP2)
    public CouponResponse quote(String couponName, Long userId, CartDto orderCart) {
        if (userId == null) {
            return CouponResponse.builder().message("Failed to get coupons").build();
        }
        CartDto cart = orderCart == null || orderCart.getProducts() == null || orderCart.getProducts().isEmpty() ? null : orderCart;
        return computeQuote(couponName, userId, cart).response();
    }

    /**
     * 쿠폰 계산 본체.
     *
     * @param couponName   적용할 쿠폰 이름
     * @param userId       <b>게이트웨이가 검증한</b> 신원(토큰의 uid). 본문에서 온 값을 넣으면 안 된다. 예전에는
     *                     이메일을 받아 매번 user-service 에 id 를 다시 물었다(docs/48 §6-⑥).
     */
    private Quoted computeQuote(String couponName, Long userId) {
        return computeQuote(couponName, userId, null);
    }

    /** @param givenCart 계산의 근거로 쓸 장바구니(주문이 읽은 것). null 이면 cart-service 에서 읽는다. */
    private Quoted computeQuote(String couponName, Long userId, CartDto givenCart) {

        Coupon coupon = getCouponNotUsed(couponName, userId);

        if (null == coupon) {
            return Quoted.rejected(CouponResponse.builder().message("Failed to get coupons").build());
        }

        // 🕐 기간 검증. 예전에는 이 검사가 **어디에도 없었다** — startDate/endDate 를
        // 저장만 하고 적용 시점에 보지 않았다. 가입 쿠폰의 31일 유효기간은 장식이었고,
        // 1년 전에 만료된 쿠폰도 그대로 할인됐다.
        if (!coupon.isUsableAt(LocalDateTime.now())) {
            return Quoted.rejected(CouponResponse.builder()
                    .message("사용 기간이 지난 쿠폰입니다.")
                    .build());
        }

        // 🔒 다른 주문이 이 쿠폰으로 결제 중이면 새 주문 · 장바구니 적용에 쓰지 못한다(V7). 예전에는 견적이 USED 만 봐서 같은 쿠폰으로
        //    주문 둘을 만들고 둘 다 결제할 수 있었다 — 두 번째 결제 확정에서 경보만 울리고 할인은 두 번 나갔다.
        //    [JA] 他の注文がこのクーポンで決済中なら、新しい注文 · カート適用には使えない。
        if (coupon.inPaymentByAnotherOrder(null, LocalDateTime.now())) {
            return Quoted.rejected(CouponResponse.builder()
                    .message(IN_PAYMENT_MESSAGE)
                    .build());
        }

        // 🔒 할인의 기준 금액은 **서버가 읽은 장바구니**다. 요청 본문의 금액은 쓰지 않는다
        //    (docs/40 #10). 예전에는 제약 없는 쿠폰이 본문의 totalPrice 를 그대로 믿었고,
        //    그 값은 브라우저가 정했다.
        CartDto cart = givenCart != null ? givenCart : fetchCart(userId);
        if (cart == null) {
            // 장바구니를 못 읽으면 금액을 정할 수 없다. 본문 값으로 대신하지 않는다 —
            // 그러면 장바구니 서비스를 잠시 막는 것만으로 원하는 금액을 부를 수 있다.
            return Quoted.rejected(CouponResponse.builder()
                    .message("장바구니를 확인할 수 없어 쿠폰을 적용하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                    .build());
        }

        CouponScope scope = coupon.scope();
        Set<String> targets = targetValues(coupon.getCouponId(), scope);

        // 줄 목록은 **언제나** 만든다. 제약 없는 쿠폰도 상품 금액을 기준으로 깎아야
        // 하기 때문이다(배송비는 할인하지 않는다 — CouponCalculator 주석).
        //
        // 다만 **카테고리 조회(catalog 왕복)는 제약 있는 쿠폰에서만** 한다. 제약이 없으면
        // 어떤 카테고리인지 알 필요가 없고, 결제 직전 경로에 서비스 간 왕복을 더할 이유가
        // 없다.
        List<CouponCalculator.Line> lines = scope == CouponScope.ALL
                ? plainLines(cart)
                : cartLines(cart);

        CouponCalculator.Result calc = CouponCalculator.calculate(
                coupon.getDiscount(), scope, targets, cartTotal(cart), lines);

        if (scope != CouponScope.ALL && calc.eligibleItemCount() == 0) {
            // 조용히 0원 할인으로 끝내면 "쿠폰을 넣었는데 아무 일도 안 일어난다"가 된다.
            return Quoted.rejected(CouponResponse.builder()
                    .message("이 쿠폰을 적용할 수 있는 상품이 장바구니에 없습니다.")
                    .eligibleSubtotal(BigDecimal.ZERO)
                    .discountAmount(BigDecimal.ZERO)
                    .eligibleItemCount(0)
                    .build());
        }

        return new Quoted(CouponResponse.builder()
                .totalAfterDiscount(calc.totalAfterDiscount())
                .discount(coupon.getDiscount())
                .message("")
                .discountAmount(calc.discountAmount())
                .eligibleSubtotal(calc.eligibleSubtotal())
                .eligibleItemCount(calc.eligibleItemCount())
                .build(), userId, true);
    }

    /**
     * 계산 결과와, 그것이 <b>실제로 쓸 수 있는 할인인지</b>.
     *
     * <p>{@code usable} 이 false 인 응답에는 거절 사유({@code message})만 담긴다. 이 값이
     * 쿠폰 사용 이벤트를 발행할지를 가른다 — 거절된 계산으로 사용 처리를 하면 쓰지도
     * 못한 쿠폰이 소모된다.
     */
    private record Quoted(CouponResponse response, Long userId, boolean usable) {
        static Quoted rejected(CouponResponse response) {
            return new Quoted(response, null, false);
        }
    }

    /**
     * 할인 계산의 기준이 되는 장바구니 총액.
     *
     * <p>cart-service 가 카탈로그 가격으로 직접 계산해 저장한 값이다({@code computeCartTotal}).
     * 배송비가 포함돼 있고, 사용자가 장바구니 화면에서 보는 숫자와 같다.
     *
     * <p>예전에는 제약 있는 쿠폰일 때 <b>줄 금액의 합</b>(배송비 제외)을 기준으로 삼아,
     * 같은 장바구니인데 쿠폰 종류에 따라 기준 총액이 달랐다. 그래서 {@code totalAfterDiscount}
     * 가 "장바구니 총액 − 할인액" 과 맞지 않는 경우가 생겼다.
     */
    private BigDecimal cartTotal(CartDto cart) {
        return cart.getCartTotal() == null ? BigDecimal.ZERO : cart.getCartTotal();
    }

    /** 이 쿠폰의 대상 id 집합. scope 가 ALL 이면 조회하지 않는다. */
    @Transactional(readOnly = true)
    Set<String> targetValues(Long couponId, CouponScope scope) {
        if (scope == CouponScope.ALL) {
            return Set.of();
        }
        return couponTargetRepository.findByCouponId(couponId).stream()
                .filter(t -> t.getTargetType() == scope)
                .map(CouponTarget::getTargetValue)
                .collect(Collectors.toSet());
    }

    /**
     * 장바구니를 서버에서 읽는다 — <b>금액의 유일한 근거</b>다.
     *
     * <p>cart-service 는 카탈로그 가격으로 총액을 직접 계산해 저장하므로, 여기서 읽은
     * {@code cartTotal} 은 브라우저가 손댈 수 없는 값이다. 요청 본문의 금액을 쓰던 경로를
     * 전부 이쪽으로 옮겼다(docs/40 #10).
     *
     * <p>읽지 못하면 {@code null} 이고, 부르는 쪽은 <b>거절한다.</b> 예외를 밖으로 던지지는
     * 않는다 — 쿠폰 실패로 결제 화면이 통째로 날아가면 안 된다. 하지만 본문 금액으로
     * 대신하지도 않는다. 그러면 장바구니 서비스를 잠시 막는 것만으로 원하는 금액을 부를
     * 수 있다.
     *
     * <p>빈 장바구니도 {@code null} 로 본다. 담긴 것이 없으면 할인할 대상도 없다.
     */
    private CartDto fetchCart(Long userId) {
        try {
            CartDto cart = cartServiceClient.getCart(userId).getBody();
            if (cart == null || cart.getProducts() == null || cart.getProducts().isEmpty()) {
                return null;
            }
            return cart;
        } catch (RuntimeException e) {
            log.warn("쿠폰 적용: 장바구니를 읽지 못했다 — 금액을 정할 수 없다. {}", e.toString());
            return null;
        }
    }

    /**
     * 카테고리 없이 줄만 만든다 — 제약 없는 쿠폰용.
     *
     * <p>할인 기준이 되는 것은 {@code price × qty} 뿐이라 카테고리가 필요 없다.
     * catalog 를 부르지 않으므로 결제 직전 경로에 왕복이 늘지 않는다.
     */
    private List<CouponCalculator.Line> plainLines(CartDto cart) {
        List<CouponCalculator.Line> lines = new ArrayList<>();
        for (CartProductDto item : cart.getProducts()) {
            lines.add(new CouponCalculator.Line(
                    item.getProductId(), null, item.getPrice(), item.getQty()));
        }
        return lines;
    }

    /**
     * 각 줄의 카테고리를 catalog 에서 <b>한 번에</b> 채운다.
     *
     * <p>줄마다 상품을 조회하면 항목 수만큼 왕복이 생긴다 — 배치 엔드포인트를 쓴다
     * (cart-service 가 같은 이유로 이미 쓰고 있다). catalog 가 모르는 id 는 응답에서
     * 빠지고, 그 줄은 카테고리 null 로 남아 대상이 아닌 것으로 취급된다.
     */
    private List<CouponCalculator.Line> cartLines(CartDto cart) {
        Map<String, String> categoryByProduct = categoriesOf(cart.getProducts());

        List<CouponCalculator.Line> lines = new ArrayList<>();
        for (CartProductDto item : cart.getProducts()) {
            String productId = item.getProductId();
            lines.add(new CouponCalculator.Line(
                    productId,
                    productId == null ? null : categoryByProduct.get(productId),
                    item.getPrice(),
                    item.getQty()));
        }
        return lines;
    }

    private Map<String, String> categoriesOf(List<CartProductDto> items) {
        List<Long> ids = items.stream()
                .map(CartProductDto::getProductId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(Long::parseLong)
                .collect(Collectors.toList());

        if (ids.isEmpty()) {
            return Map.of();
        }

        try {
            List<ProductDto> products = catalogServiceClient.getProductsInfo(ids).getBody();
            if (products == null) {
                return Map.of();
            }
            Map<String, String> result = new HashMap<>();
            for (ProductDto product : products) {
                if (product.getId() != null && product.getCategory() != null
                        && product.getCategory().getId() != null) {
                    result.put(product.getId(), product.getCategory().getId());
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("쿠폰 적용: 상품 카테고리를 읽지 못했다. {}", e.toString());
            return Map.of();
        }
    }

    /** 결제 확정의 결과. */
    // [JA] 決済確定の結果。
    public enum UseOutcome {
        /** NOT_USED → USED, 이 주문을 적었다. */
        USED,
        /** 이미 이 주문이 썼다 — 같은 결제 응답이 다시 왔다(멱등). */
        ALREADY_THIS_ORDER,
        /** 다른 주문이 이미 썼다 — 한 쿠폰으로 두 주문이 할인을 받았다(이중 사용). */
        USED_BY_ANOTHER_ORDER,
        /** 이 사용자에게 그 이름의 쿠폰이 없다. */
        NOT_FOUND
    }

    /** 다른 주문이 이 쿠폰으로 결제 중일 때 견적 · 결제 시작이 돌려주는 문구. */
    public static final String IN_PAYMENT_MESSAGE =
            "이 쿠폰으로 결제가 진행 중인 다른 주문이 있습니다. 그 결제를 마치거나 취소한 뒤 다시 시도해 주세요.";

    /** 결제 시작의 결과 — order 가 결제창을 열지 정한다. */
    public enum ClaimOutcome {
        /** 이 주문이 쿠폰을 잡았다(또는 이미 이 주문이 썼다). */
        CLAIMED,
        /** 그런 쿠폰이 없다(삭제 · 다른 사용자). */
        NOT_FOUND,
        /** 다른 주문이 이미 썼다. */
        USED,
        /** 다른 주문이 지금 결제 중이다. */
        IN_PAYMENT_ELSEWHERE,
        /** 사용 기간 밖이다 — 주문을 만들 때는 유효했어도 결제하는 지금 지났다(또는 아직 시작 전). */
        EXPIRED
    }

    /** 결제 중 표시의 상한 — order 가 넘기는 시간이 이보다 길면 줄인다(입금 기한 49시간 + 여유). */
    static final java.time.Duration MAX_PAYMENT_HOLD = java.time.Duration.ofHours(72);

    /**
     * 결제를 시작한다 — 이 주문이 쿠폰을 {@code hold} 동안 잡는다(V7). 잠그고 읽는다: 같은 쿠폰으로 두 주문이 동시에 결제를 시작해도
     * 하나만 잡는다. 같은 주문이 다시 부르면(결제창을 다시 열기) 끝을 늘린다. 끝이 지난 다른 주문의 표시는 넘겨받는다.
     *
     * <p>🐛 예전에는 이런 단계가 없어 같은 쿠폰으로 주문 둘을 결제할 수 있었다 — 결제 확정에서 경보(CouponDoubleUse)만 울렸다.
     *
     * <p>[JA] 決済開始 — この注文がクーポンを hold の間押さえる。ロックして読むので、同時に二つの注文が始めても一つだけが押さえる。
     */
    @Transactional
    public ClaimOutcome claimForPayment(Long userId, String couponName, Long orderId, java.time.Duration hold) {
        if (userId == null || couponName == null || couponName.isBlank() || orderId == null || hold == null) {
            return ClaimOutcome.NOT_FOUND;
        }
        Coupon coupon = couponRepository.findForUpdate(couponName, userId).orElse(null);
        if (coupon == null) {
            return ClaimOutcome.NOT_FOUND;
        }
        if (coupon.getCouponStatus() == CouponStatus.USED) {
            return orderId.equals(coupon.getUsedOrderId()) ? ClaimOutcome.CLAIMED : ClaimOutcome.USED;
        }
        LocalDateTime now = LocalDateTime.now();
        // 🔒 결제하는 지금 사용 기간 안인가 — 🐛 예전에는 기간을 견적(주문 생성) 때만 봐서, 만료 직전에 주문하고 며칠 뒤 결제창을 열거나
        //    입금 안내(기한 49시간)를 받으면 만료된 쿠폰 할인으로 결제됐다. 결제 확정(useForOrder)은 막지 않는다 — 돈은 이미 나갔다.
        // [JA] 決済する今、利用期間内か — 以前は見積もり(注文作成)時だけ見ていて、期限切れのクーポン割引で決済できた。
        if (!coupon.isUsableAt(now)) {
            log.info("쿠폰 결제 시작 거절 — 사용 기간 밖. orderId={} endDate={}", orderId, coupon.getEndDate());
            return ClaimOutcome.EXPIRED;
        }
        if (coupon.inPaymentByAnotherOrder(orderId, now)) {
            log.info("쿠폰 결제 시작 거절 — 다른 주문이 결제 중. orderId={} paying={}", orderId, coupon.getPaymentOrderId());
            return ClaimOutcome.IN_PAYMENT_ELSEWHERE;
        }
        java.time.Duration bounded = hold.isNegative() || hold.compareTo(MAX_PAYMENT_HOLD) > 0 ? MAX_PAYMENT_HOLD : hold;
        coupon.setPaymentOrderId(orderId);
        coupon.setPaymentUntil(now.plus(bounded));
        couponRepository.save(coupon);
        return ClaimOutcome.CLAIMED;
    }

    /**
     * 결제 확정 — 쿠폰을 USED 로, 쓴 주문을 적는다. 잠그고 읽는다(동시 결제 둘 중 하나만 NOT_USED 를 본다).
     *
     * <p>🐛 예전에는 NOT_USED 만 찾아 없으면 null — 같은 결제 응답의 재전달과 다른 주문의 이중 사용을 가르지 못했고, 어느 주문이
     * 썼는지도 남지 않아 보상(couponRollback)이 아무 주문의 쿠폰이나 되돌렸다.
     *
     * <p>[JA] 決済確定 — クーポンを USED にし、使った注文を記録する。以前は再配信と二重使用を区別できず、注文も残らなかった。
     */
    @Transactional
    public UseOutcome useForOrder(PaymentResponse response) {
        if (response.getCouponName() == null || response.getCustomerId() == null) {
            return UseOutcome.NOT_FOUND;
        }
        Coupon coupon = couponRepository.findForUpdate(response.getCouponName(), response.getCustomerId()).orElse(null);
        if (coupon == null) {
            return UseOutcome.NOT_FOUND;
        }
        if (coupon.getCouponStatus() == CouponStatus.NOT_USED) {
            if (coupon.inPaymentByAnotherOrder(response.getOrderId(), LocalDateTime.now())) {
                // 결제는 이미 끝났다 — 되돌릴 수 없으니 이 주문이 쓴 것으로 적는다. 결제 중이던 다른 주문은 결제 확정에서 이중 사용이 된다.
                log.warn("결제 중 표시가 다른 주문인데 결제가 확정됐다 — 이 주문이 쓴 것으로 적는다. orderId={} paying={}",
                        response.getOrderId(), coupon.getPaymentOrderId());
            }
            coupon.setCouponStatus(CouponStatus.USED);
            coupon.setUsedOrderId(response.getOrderId());
            coupon.clearPayment();
            couponRepository.save(coupon);
            return UseOutcome.USED;
        }
        if (response.getOrderId() != null && response.getOrderId().equals(coupon.getUsedOrderId())) {
            return UseOutcome.ALREADY_THIS_ORDER;
        }
        return UseOutcome.USED_BY_ANOTHER_ORDER;
    }

    @Transactional
    public boolean delete(String id) {

        Coupon coupon = couponRepository.findById(Long.parseLong(id))
                .orElseThrow(() -> new RuntimeException("Coupon not found. "));

        if (null != coupon) {
            // 대상 행을 먼저 지운다. FK 를 걸지 않았으므로(V5 주석) 여기서 챙기지 않으면
            // 고아 행이 남는다. 지금은 조회 시 쿠폰이 없어 무해하지만, 나중에 같은 TSID 가
            // 재사용될 일이 없더라도 쓰레기를 남길 이유는 없다.
            couponTargetRepository.deleteByCouponId(coupon.getCouponId());
            couponRepository.delete(coupon);
            return true;
        }

        return false;
    }

    public CouponDto update(CouponRequest request) {

        // Long id = Long.parseLong(request.getCoupon().getId());
        // Coupon coupon = couponRepository.findById(id)
        //         .orElseThrow(() -> new RuntimeException("Coupon not found. "));

        // if (null != coupon) {
        //     coupon.setName(request.getCoupon().getName());
        //     coupon.setStartDate(request.getCoupon().getStartDate());
        //     coupon.setEndDate(request.getCoupon().getEndDate());
        //     coupon.setDiscount(request.getCoupon().getDiscount());

        //     couponRepository.save(coupon);

        //     return CouponDto.builder()
        //             .id(Long.toString(coupon.getCouponId()))
        //             .name(coupon.getName())
        //             .startDate(coupon.getStartDate())
        //             .endDate(coupon.getEndDate())
        //             .discount(coupon.getDiscount())
        //             .build();
        // }

        return null;
    }

    

    public List<CouponDto> getCoupons() {

        List<Coupon> coupons = couponRepository.findAll();

        List<CouponDto> result = new ArrayList<CouponDto>();
        for (Coupon coupon : coupons) {

            result.add(CouponDto.convertToDto(coupon));
        }

        return result;
    }

    @Transactional(readOnly=true)
    public boolean existsCoupon(String couponName, Long userId) {

        boolean existCoupon = couponRepository.existsByNameAndUserId(couponName, userId);

        return existCoupon;
    }

    private void publishIssuedAfterCommit(Coupon coupon) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            couponIssuedProducer.publish(coupon);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        couponIssuedProducer.publish(coupon);
                    }
                });
    }

    @Transactional
    public Coupon create(CouponRequest request) {


        // 🐛 <b>이메일로 발급하는 경로는 지금까지 동작한 적이 없다.</b> 중복 검사만 이메일을
        //    숫자 id 로 바꿔 놓고, 정작 쿠폰을 만들 때는 아래에서 다시
        //    `Long.parseLong(request.getUserId())` 를 불렀다 — 이메일을 숫자로 파싱하니
        //    NumberFormatException 이다. 검사는 통과하고 저장에서 터지는 모양이라,
        //    "이메일로 쿠폰을 발급한다"는 코드가 있는데 실제로는 500 만 났다.
        //
        //    해결한 id 를 끝까지 들고 간다. 쿠폰의 주인은 언제나 숫자 id 다.
        Long ownerId;
        if (EmailValidator.isValidEmail(request.getUserId())) {
            ObjectMapper mapper = new ObjectMapper();
            SharedUserDto response = mapper.convertValue(
                    userServiceClient.findUserByEmail(request.getUserId()).getBody(),
                    SharedUserDto.class);
            if (response == null || response.getUserId() == null) {
                // 없는 사용자에게 쿠폰을 만들면 아무도 쓸 수 없는 행이 남는다.
                throw new IllegalArgumentException("쿠폰을 받을 사용자를 찾지 못했습니다.");
            }
            ownerId = response.getUserId();
        } else {
            ownerId = Long.parseLong(request.getUserId());
        }

        // 🔒 할인율 · 기간을 검사한다(CP8) — 예전에는 아무 값이나 받았다. 계산기가 0 ~ 100 으로 잘라 내지만 발급 알림에는 원래 값(예: 150%)이
        //    그대로 실렸고, 시작일이 종료일보다 늦은 쿠폰은 아무도 쓸 수 없는 행으로 남았다.
        // [JA] 割引率 · 期間を検査する。以前は何でも受け付け、通知には 150% のような値がそのまま載った。
        if (request.getDiscount() < 1 || request.getDiscount() > 100) {
            throw new IllegalArgumentException("할인율은 1 ~ 100 사이여야 합니다.");
        }
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getStartDate().isAfter(request.getEndDate())) {
            throw new IllegalArgumentException("쿠폰 시작일이 종료일보다 늦습니다.");
        }

        boolean existCoupon = existsCoupon(request.getName(), ownerId);

        if (!existCoupon) {

            CouponScope scope = request.scopeOrAll();
            List<String> targets = request.targetsOrEmpty();

            if (scope != CouponScope.ALL && targets.isEmpty()) {
                // 대상 없는 제약 쿠폰은 아무 상품에도 적용되지 않는다. 만들어 두면
                // "쿠폰은 있는데 무엇에도 안 먹는다"는 문의만 남는다. 만들지 않는다.
                throw new IllegalArgumentException(
                        "scope 가 " + scope + " 인 쿠폰은 대상(targets)이 하나 이상 있어야 합니다.");
            }
            if (request.getScope() != null && !request.getScope().isBlank()
                    && scope == CouponScope.ALL
                    && !"ALL".equalsIgnoreCase(request.getScope().trim())) {
                log.warn("알 수 없는 쿠폰 scope 값이라 ALL 로 처리한다: {}", request.getScope());
            }

            Coupon newCoupon = Coupon.builder()
                    .name(request.getName())
                    .startDate(request.getStartDate())
                    .endDate(request.getEndDate())
                    .discount(request.getDiscount())
                    .userId(ownerId)
                    .couponStatus(CouponStatus.NOT_USED)
                    .scope(scope)
                    .build();

            newCoupon = couponRepository.save(newCoupon);

            if (scope != CouponScope.ALL) {
                Long couponId = newCoupon.getCouponId();
                // distinct — 같은 대상이 두 번 들어오면 uk_coupon_target 위반으로 생성
                // 전체가 실패한다. 중복은 요청자의 실수일 뿐 거절할 이유가 아니다.
                for (String target : targets.stream().filter(t -> t != null && !t.isBlank())
                        .map(String::trim).distinct().toList()) {
                    couponTargetRepository.save(CouponTarget.builder()
                            .couponId(couponId)
                            .targetType(scope)
                            .targetValue(target)
                            .build());
                }
            }

            // 🐛 지금까지 쿠폰이 발급돼도 아무도 알려 주지 않았다. NotificationType.COUPON_ISSUED
            //    는 메일 제목·SMS 문구·수신 설정까지 다 있는데 만드는 쪽만 없었다.
            //    발행 실패가 발급을 되돌리지는 않는다(CouponIssuedProducer 주석).
            // 커밋된 뒤에 알린다 — 🐛 예전에는 커밋 전에 보내, 동시 수령으로 롤백된 쿠폰(DB 에 없는 쿠폰)의 발급 알림이 나갔다(CP5).
            publishIssuedAfterCommit(newCoupon);

            return newCoupon;
        }
        return null;
    }


    /**
     * 기획전 쿠폰을 받아 간다.
     *
     * <p><b>왜 나눠 주지 않고 받아 가게 하나.</b> 기획전이 시작될 때 전 사용자에게 발급하면
     * 사용자 수만큼 coupon 행이 생긴다 — 공지 알림에서 개인별 행을 만들지 않기로 한 것과
     * 같은 문제이고, 게다가 대부분은 쓰지 않는다. 받아 간 사람만 행이 생기게 두면 쓰기량이
     * 실제 관심에 비례한다.
     *
     * <p><b>중복 수령은 이미 막혀 있다.</b> coupon 테이블의 {@code uk_coupon_name_user_id}
     * 가 한 사람이 같은 이름의 쿠폰을 두 번 갖지 못하게 한다. {@link #create} 가 그 검사를
     * 하고 이미 있으면 null 을 돌려주므로, 여기서 따로 방어를 만들지 않는다 — 만들면
     * 같은 규칙이 두 곳에 생긴다.
     *
     * <p>순서가 중요하다: <b>기획전 조건을 먼저</b> 읽고, 그 다음 고객군을 본다. 반대로 하면
     * 이미 끝난 기획전에 대해서도 사용자 조회가 한 번 나간다.
     *
     * @return 발급된 쿠폰. 이미 갖고 있으면 {@link ClaimResult#alreadyOwned()}
     */
    // catalog · user-service 를 먼저(트랜잭션 밖), 발급만 짧은 트랜잭션으로(CP2).
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ClaimResult claimEventCoupon(String slug, Long userId) {

        CatalogServiceClient.EventCouponOffer offer;
        try {
            offer = catalogServiceClient.getEventCouponOffer(slug).getBody();
        } catch (Exception e) {
            // 404 (진행 중이 아니거나 쿠폰 없음)와 통신 실패를 구분하지 않는다 — 어느
            // 쪽이든 지금은 받을 수 없고, 사용자가 할 수 있는 일도 같다.
            log.info("기획전 쿠폰 조건을 읽지 못했다: slug={}, {}", slug, e.getMessage());
            return ClaimResult.unavailable("지금은 받을 수 없는 쿠폰입니다.");
        }
        if (offer == null || offer.couponName() == null || offer.couponDiscount() <= 0) {
            return ClaimResult.unavailable("이 기획전에는 받을 수 있는 쿠폰이 없습니다.");
        }

        // 🔒 모르는 고객군은 '전체' 가 아니다(CP3) — AudienceSegment.from 은 모르는 값을 ALL 로 읽는다. 새 고객군을 아는 catalog 가
        //    coupon 보다 먼저 올라가면(롤링 중) 그 기획전 쿠폰이 전원에게 발급됐다. 발급은 되돌리기 어렵다 — 여기서는 거절한다.
        //    [JA] 知らない顧客層は「全員」ではない。新しい顧客層を知る catalog が先に上がると全員に発行されていた。
        AudienceSegment audience = strictAudience(offer.audience());
        if (audience == null) {
            log.warn("기획전 쿠폰의 고객군을 모른다 — 발급하지 않는다. slug={} audience={}", slug, offer.audience());
            return ClaimResult.notEligible("이 쿠폰의 대상이 아닙니다.");
        }
        if (audience != AudienceSegment.ALL && !audience.matches(segmentsOf(userId))) {
            // 어떤 세그먼트에 속하는지는 알려주지 않는다 — 가입 시점을 되짚을 수 있다.
            return ClaimResult.notEligible("이 쿠폰의 대상이 아닙니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        CouponRequest request = new CouponRequest(
                offer.couponName(), now, now.plusDays(offer.couponValidDays()),
                offer.couponDiscount(), Long.toString(userId),
                offer.couponScope(),
                offer.couponTargets() == null ? List.of() : offer.couponTargets());

        Coupon issued;
        try {
            issued = inTransaction(() -> create(request));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 두 번 누름 · 두 탭 — 둘 다 "없음" 을 보고 늦은 쪽이 uk_coupon_name_user_id 에 걸렸다(CP5). 🐛 예전에는 500 이었다.
            log.info("기획전 쿠폰 동시 수령 — 앞선 요청이 발급했다. slug={}", slug);
            return ClaimResult.alreadyOwned();
        }
        if (issued == null) {
            return ClaimResult.alreadyOwned();
        }
        log.info("기획전 쿠폰 발급: slug={}, couponId={}", slug, issued.getCouponId());
        return ClaimResult.issued(CouponDto.convertToDto(issued));
    }

    /** 기획전의 고객군 — 비었으면 ALL, 모르는 값이면 null(발급하지 않는다, CP3). */
    static AudienceSegment strictAudience(String raw) {
        if (raw == null || raw.isBlank()) {
            return AudienceSegment.ALL;
        }
        try {
            return AudienceSegment.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 발급 같은 짧은 쓰기 트랜잭션. 세터 — 생성자를 쓰는 테스트가 많다. 없으면(단위 테스트) 그대로 돈다. */
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(status -> work.get());
    }

    /** 이 사용자의 고객군. 판정은 user-service 가 한다(그쪽만 가입일을 갖고 있다). */
    private List<String> segmentsOf(Long userId) {
        try {
            Map<String, List<String>> body = userServiceClient.findUserSegments(userId).getBody();
            List<String> segments = body == null ? null : body.get("segments");
            return segments == null ? List.of() : segments;
        } catch (Exception e) {
            // 판정할 수 없으면 <b>주지 않는다.</b> 세그먼트 조회가 실패했을 때 통과시키면
            // 대상이 아닌 사람에게 쿠폰이 나가고, 그건 되돌리기 어렵다.
            log.warn("고객군을 확인하지 못했다 — 대상이 아닌 것으로 본다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 수령 결과.
     *
     * <p>"이미 갖고 있다"를 실패로 두지 않는 이유: 사용자 입장에서는 성공과 다르지 않다.
     * 버튼을 두 번 눌렀거나 다른 탭에서 이미 받은 것이고, 오류를 띄우면 무엇이 잘못됐는지
     * 찾게 만든다.
     */
    public record ClaimResult(String status, String message, CouponDto coupon) {

        public static ClaimResult issued(CouponDto coupon) {
            return new ClaimResult("ISSUED", "쿠폰을 받았습니다.", coupon);
        }

        public static ClaimResult alreadyOwned() {
            return new ClaimResult("ALREADY_OWNED", "이미 받은 쿠폰입니다.", null);
        }

        public static ClaimResult notEligible(String message) {
            return new ClaimResult("NOT_ELIGIBLE", message, null);
        }

        public static ClaimResult unavailable(String message) {
            return new ClaimResult("UNAVAILABLE", message, null);
        }

        public boolean ok() {
            return "ISSUED".equals(status) || "ALREADY_OWNED".equals(status);
        }
    }

    public Coupon getCouponNotUsed(String couponName, Long userId) {

        Coupon coupon = getCouponByNameAndUserIdAndCouponStatus(couponName, userId, CouponStatus.NOT_USED);

        //log.info("Coupon : {} {}", couponName, userId);        

        return coupon;
        
    }

    
    /** 이 사용자의 쓰지 않은 쿠폰. 사용자 id 로 바로 찾는다 — 예전에는 이메일로 user-service 에 id 를 물었다. */
    public List<CouponDto> getCouponsNotUsed(Long userId) {

        if (null != userId) {

            List<Coupon> coupons = getCouponsByUserIdAndCouponStatus(userId, CouponStatus.NOT_USED);

            if (coupons != null && !coupons.isEmpty()) {

                LocalDateTime now = LocalDateTime.now();
                List<CouponDto> result = new ArrayList<>();
                for (Coupon coupon : coupons) {
                    // 쿠폰 목록은 보통 한두 장이라 대상 조회를 쿠폰마다 해도 무방하다.
                    // 장 수가 늘면 couponId IN (...) 한 번으로 바꾸면 된다.
                    List<String> targets = new ArrayList<>(
                            targetValues(coupon.getCouponId(), coupon.scope()));
                    result.add(CouponDto.convertToDto(coupon, targets, now));
                }

                // 쓸 수 있는 것을 먼저, 그 안에서는 할인율이 큰 것을 먼저. 화면이 목록을
                // 그대로 그리고 첫 번째를 기본 선택으로 쓰기 때문에 순서가 곧 UX 다.
                result.sort(java.util.Comparator
                        .comparing(CouponDto::isUsable).reversed()
                        .thenComparing(java.util.Comparator.comparingInt(CouponDto::getDiscount).reversed()));

                return result;
            }

        }

        // 예전에는 null 을 돌려줬다. 화면은 `coupons?.length > 0` 로 받고 있어 동작은
        // 같지만, "쿠폰이 없다"는 빈 목록이지 값이 없는 것이 아니다.
        return List.of();
    }

    @Transactional(readOnly=true)
    Coupon getCouponByNameAndUserIdAndCouponStatus(String couponName, Long userId, CouponStatus couponStatus) {

        Coupon coupon = couponRepository.findByNameAndUserIdAndCouponStatus(couponName, userId, couponStatus).orElse(null);

        return coupon;
    }

    @Transactional(readOnly=true)
    List<Coupon> getCouponsByUserIdAndCouponStatus(Long userId, CouponStatus couponStatus) {

        List<Coupon> coupons = couponRepository.findByUserIdAndCouponStatus(userId, couponStatus).orElse(null);

        return coupons;
    }

    /**
     * 사가 보상 — 그 주문이 쓴 쿠폰만 되돌린다.
     *
     * <p>🐛 예전에는 이름 · 사용자로 찾은 USED 쿠폰을 무조건 NOT_USED 로 돌렸다. 같은 쿠폰을 고른 주문 A(결제됨) · B(결제 실패)가
     * 있으면 B 의 보상이 A 가 쓴 쿠폰을 되살렸다 — 같은 쿠폰으로 또 할인을 받을 수 있었다.
     *
     * <p>메시지에 주문이 없거나(옛 프로듀서 v1) 쿠폰에 쓴 주문이 없으면(이 변경 전에 쓴 쿠폰) 누구의 것인지 모르므로 예전처럼 되돌린다.
     *
     * <p>[JA] 補償 — その注文が使ったクーポンだけを戻す。以前は別注文の補償が決済済みのクーポンを戻していた。
     *
     * @return 되돌린 쿠폰, 되돌리지 않았으면 null
     */
    @Transactional
    public Coupon couponRollback(CouponRollbackRequest request) {
        if (request.getCouponName() == null || request.getUserId() == null) {
            return null;
        }
        Coupon coupon = couponRepository.findForUpdate(request.getCouponName(), request.getUserId()).orElse(null);
        if (coupon != null && coupon.getCouponStatus() == CouponStatus.NOT_USED
                && request.getOrderId() != null && request.getOrderId().equals(coupon.getPaymentOrderId())) {
            // 결제를 시작했다가 실패 · 취소된 주문 — 결제 중 표시만 푼다(쓰이지 않았다). 예전에는 이 경우가 "되돌릴 것 없음" 이라
            // 재시도 뒤 DLT 로 갔다.
            coupon.clearPayment();
            return couponRepository.save(coupon);
        }
        if (coupon == null || coupon.getCouponStatus() != CouponStatus.USED) {
            return null;
        }
        if (request.getOrderId() != null && coupon.getUsedOrderId() != null
                && !request.getOrderId().equals(coupon.getUsedOrderId())) {
            log.info("쿠폰 보상 건너뜀 — 다른 주문이 쓴 쿠폰이다. orderId={} usedBy={}", request.getOrderId(), coupon.getUsedOrderId());
            return null;
        }
        coupon.setCouponStatus(CouponStatus.NOT_USED);
        coupon.setUsedOrderId(null);
        return couponRepository.save(coupon);
    }


}
