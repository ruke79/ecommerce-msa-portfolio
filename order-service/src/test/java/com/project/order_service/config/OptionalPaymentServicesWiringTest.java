package com.project.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.payment.portone.PortOneGateway;
import com.project.order_service.payment.stripe.StripeCheckoutGateway;
import com.project.order_service.payment.stripe.StripeWebhookVerifier;
import com.project.order_service.service.BankTransferService;
import com.project.order_service.service.OrderCacheService;
import com.project.order_service.service.OrderDataService;
import com.project.order_service.service.PortOnePaymentService;
import com.project.order_service.service.StripePaymentService;

/**
 * 선택 결제수단의 서비스 빈은 <b>스위치를 따라간다.</b>
 *
 * <h3>🐛 끄면 기동이 실패했다</h3>
 *
 * <p>{@code StripeConfig}·{@code PortOneConfig} 는 {@code *.enabled=true} 일 때만 게이트웨이를
 * 만든다. 그런데 그 게이트웨이를 생성자로 받는 {@code StripePaymentService}·
 * {@code PortOnePaymentService} 에는 조건이 없었다. 결과: 둘 다 끈 클러스터 기본값에서
 * order-service 가 "No qualifying bean of type StripeCheckoutGateway" 로 <b>기동조차 못
 * 했고</b>, 배포 스크립트는 롤아웃 타임아웃 → 자동 복귀 → 옛 파드가 Running 이라 성공처럼
 * 보였다. 단위 테스트는 전부 통과했다 — 이 종류의 버그는 <b>컨텍스트를 띄워야만</b> 드러난다.
 *
 * <p>여기서는 전체 컨텍스트 대신 서비스 둘만 올려 조건을 고정한다. 의존은 전부 모의다 —
 * 검증 대상은 배선이지 동작이 아니다.
 */
@DisplayName("선택 결제수단 서비스 빈의 조건")
class OptionalPaymentServicesWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OrderCacheService.class, () -> mock(OrderCacheService.class))
            .withBean(OrderDataService.class, () -> mock(OrderDataService.class))
            .withBean(BankTransferService.class, () -> mock(BankTransferService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("frontend.url=https://shop.example")
            .withUserConfiguration(StripePaymentService.class, PortOnePaymentService.class);

    /** 🔴 핵심 가드 — 클러스터 기본값(둘 다 꺼짐)에서 컨텍스트가 뜬다. */
    @Test
    @DisplayName("둘 다 꺼진 기본값이면 서비스 빈이 없고 컨텍스트는 뜬다")
    void disabledByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(StripePaymentService.class);
            assertThat(ctx).doesNotHaveBean(PortOnePaymentService.class);
        });
    }

    @Test
    @DisplayName("stripe.enabled=true 면 Stripe 서비스만 생긴다")
    void stripeOn() {
        runner.withPropertyValues("stripe.enabled=true")
                .withBean(StripeCheckoutGateway.class, () -> mock(StripeCheckoutGateway.class))
                .withBean(StripeWebhookVerifier.class, () -> mock(StripeWebhookVerifier.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(StripePaymentService.class);
                    assertThat(ctx).doesNotHaveBean(PortOnePaymentService.class);
                });
    }

    @Test
    @DisplayName("portone.enabled=true 면 포트원 서비스만 생긴다 — V1 은 웹훅 검증기 없이")
    void portOneOn() {
        runner.withPropertyValues("portone.enabled=true")
                .withBean(PortOneGateway.class, () -> mock(PortOneGateway.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(PortOnePaymentService.class);
                    assertThat(ctx).doesNotHaveBean(StripePaymentService.class);
                });
    }

    /** 켰는데 게이트웨이가 없으면 — 그건 설정 오류이고 기동이 막혀야 한다(반쯤 켜진 상태 금지). */
    @Test
    @DisplayName("켰는데 게이트웨이가 없으면 기동이 막힌다")
    void enabledWithoutGatewayFails() {
        runner.withPropertyValues("stripe.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
