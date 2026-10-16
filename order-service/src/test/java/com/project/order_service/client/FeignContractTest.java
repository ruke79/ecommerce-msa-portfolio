package com.project.order_service.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 서비스 간 호출 계약 가드.
 *
 * <h3>🐛 장바구니 조회가 성립한 적이 없었다</h3>
 *
 * <p>cart-service 의 {@code /api/cart/checkout} 은 보안 강화 때 <b>쿼리 파라미터를 버리고
 * 게이트웨이가 검증한 {@code User-Email} 헤더</b>를 읽도록 바뀌었다. order-service 의 Feign
 * 클라이언트는 그대로 {@code ?userId=} 를 보내고 있었고, 결과는 <b>언제나 401</b> 이었다.
 *
 * <p>왜 오래 안 보였나:
 *
 * <ul>
 *   <li>프론트가 결제 화면을 열 때 하는 호출은 <b>게이트웨이를 지나며 헤더가 붙어</b>
 *       멀쩡했다. 깨진 것은 서비스 간 직접 호출뿐이라 cart-service 로그에는 정상 호출만
 *       보였다.</li>
 *   <li>그 401 을 {@code FeignErrorDecoder} 가 <b>검사 예외</b>로 바꿔 돌려줬고, 프록시가
 *       그것을 메시지 없는 {@code UndeclaredThrowableException} 으로 감쌌다. 화면에는
 *       {@code {"message": null}} 만 떴다(docs/47 §5-2).</li>
 *   <li>order-service 컨트롤러가 모든 예외를 <b>로그 없이</b> 400 으로 바꿨다.</li>
 * </ul>
 *
 * <p>컴파일러는 이런 어긋남을 잡지 못한다 — 양쪽이 다른 모듈이고 계약이 애노테이션에만
 * 있기 때문이다. 그래서 여기서 고정한다.
 */
class FeignContractTest {

    private static final Class<?>[] CLIENTS = {
        CartServiceClient.class, UserServiceClient.class, CatalogServiceClient.class, CouponServiceClient.class
    };

    /**
     * 🔴 <b>토큰은 URL 에 실리면 안 된다.</b>
     *
     * <p>예전에는 네 메서드가 {@code @RequestParam("Authorization")} 이었다. 그러면 액세스
     * 토큰이 쿼리 문자열에 들어가고, <b>URL 은 어디에나 남는다</b> — 액세스 로그, 프록시,
     * 예외 메시지, Referer. 실제로 order-service 의 에러 로그에 토큰 전문이 찍혔다.
     *
     * <p>필요도 없다. {@code FeignConfig} 의 인터셉터가 들어온 요청의 {@code Authorization}
     * <b>헤더</b>를 그대로 이어 붙인다. 헤더로 <b>다시 선언해도 안 된다</b> — 인터셉터와
     * 겹쳐 같은 헤더가 두 번 간다.
     */
    @Test
    @DisplayName("Feign 클라이언트는 토큰을 직접 싣지 않는다 — 인터셉터가 헤더로 넘긴다")
    void noClientCarriesTheTokenItself() {
        for (Class<?> client : CLIENTS) {
            for (Method m : client.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    RequestParam param = p.getAnnotation(RequestParam.class);
                    if (param != null) {
                        assertThat(param.value())
                                .as("%s#%s 가 토큰을 쿼리 파라미터로 보낸다 — URL 은 로그에 남는다",
                                        client.getSimpleName(), m.getName())
                                .isNotEqualToIgnoringCase("Authorization");
                    }

                    RequestHeader header = p.getAnnotation(RequestHeader.class);
                    if (header != null) {
                        assertThat(header.value())
                                .as("%s#%s 가 Authorization 을 직접 선언했다 — 인터셉터와 겹쳐 "
                                        + "헤더가 두 번 간다", client.getSimpleName(), m.getName())
                                .isNotEqualToIgnoringCase("Authorization");
                    }
                }
            }
        }
    }

    /**
     * 🔴 받는 쪽(cart {@code CartController})이 {@code @RequestHeader("User-Id")} 로 신원을 읽는다 — 토큰의 uid 다.
     * 이름이나 타입이 어긋나면 그 순간 401 이다 — 그리고 그 401 은 프론트 경로(게이트웨이가 헤더를 붙인다)에서는
     * 재현되지 않는다. 예전에는 User-Email 이었다(docs/48 §6-⑥). cart 쪽의 짝은 CartIdentityContractTest.
     */
    @Test
    @DisplayName("장바구니 조회는 신원을 User-Id 헤더(사용자 id)로 보낸다")
    void cartLookupSendsTheIdentityHeader() throws Exception {
        Method getCart = CartServiceClient.class.getMethod("getCart", Long.class);

        RequestHeader header = getCart.getParameters()[0].getAnnotation(RequestHeader.class);

        assertThat(header)
                .as("cart-service 는 User-Id 헤더가 없으면 401 을 준다")
                .isNotNull();
        assertThat(header.value()).isEqualTo("User-Id");

        assertThat(Arrays.stream(getCart.getParameters())
                .anyMatch(p -> p.isAnnotationPresent(RequestParam.class)))
                .as("cart-service 는 쿼리 파라미터를 더 이상 읽지 않는다")
                .isFalse();
    }

    /** 전환 릴리스가 끝났다 — User-Email 을 싣는 호출이 되살아나면 이메일 신원도 함께 되살아난다(docs/48 §6-⑥). */
    @Test
    @DisplayName("어떤 클라이언트도 User-Email 을 싣지 않는다")
    void noClientSendsTheEmailHeader() {
        for (Class<?> client : CLIENTS) {
            for (Method m : client.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    RequestHeader header = p.getAnnotation(RequestHeader.class);
                    if (header != null) {
                        assertThat(header.value())
                                .as("%s#%s", client.getSimpleName(), m.getName())
                                .isNotEqualToIgnoringCase("User-Email");
                    }
                }
            }
        }
    }

    /**
     * 🔴 쿠폰 견적은 <b>사용자 id</b> 로 묻는다(coupon {@code CouponController.quote} 의 {@code userId}). 예전에는
     * 이메일을 넘겼고 coupon 이 그 이메일로 user-service 에 id 를 다시 물었다 — 그 호출에는 액세스 토큰이 URL 쿼리로
     * 실렸다.
     */
    @Test
    @DisplayName("쿠폰 견적은 userId 로 묻는다 — 이메일은 싣지 않는다")
    void couponQuoteSendsTheUserId() throws Exception {
        Method quote = CouponServiceClient.class.getMethod("quote", Long.class, String.class, com.project.common.dto.CartDto.class);

        assertThat(Arrays.stream(quote.getParameters())
                .filter(p -> p.getAnnotation(RequestParam.class) != null)
                .map(p -> p.getAnnotation(RequestParam.class).value()))
                .containsExactly("userId", "couponName");
    }

    /**
     * ⚠️ {@code /api/auth/user/id} 는 {@code User-Role} 헤더가 <b>있으면</b> 외부 호출로 보고
     * 관리자만 통과시킨다. 서비스 간 직접 호출에 그 헤더를 실으면 <b>403</b> 이 된다.
     * 인터셉터가 {@code Authorization} 만 넘기는 것은 의도된 것이다.
     */
    @Test
    @DisplayName("사용자 조회에 User-Role 을 싣지 않는다 — 실으면 403 이다")
    void userLookupDoesNotSendTheRoleHeader() {
        for (Method m : UserServiceClient.class.getDeclaredMethods()) {
            for (Parameter p : m.getParameters()) {
                RequestHeader header = p.getAnnotation(RequestHeader.class);
                if (header != null) {
                    assertThat(header.value()).isNotEqualToIgnoringCase("User-Role");
                }
            }
        }
    }
}
