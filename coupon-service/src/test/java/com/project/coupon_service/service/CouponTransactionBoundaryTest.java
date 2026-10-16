package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 🔴 CP2 — 원격 호출(cart · catalog · user-service)을 하는 견적 · 적용 · 기획전 받기는 트랜잭션을 열지 않는다.
 *
 * <p>🐛 예전에는 읽기 · 쓰기 트랜잭션 안에서 불렀다 — cart 가 느리면(읽기 제한 5초씩) 그동안 DB 커넥션(풀 5개)을 쥐었고, 겹치면 결제 확정
 * 처리(쿠폰 USED)의 커넥션이 모자라 payment.DLT 로 갔다. 쿠폰 행은 저장소 호출(짧은 트랜잭션)이나 발급의 TransactionTemplate 으로 다룬다.
 *
 * <p>[JA] 遠隔呼び出しをする見積もり · 適用 · 企画展受け取りはトランザクションを開かない。
 */
@DisplayName("coupon — 원격 호출은 트랜잭션 밖")
class CouponTransactionBoundaryTest {

    @Test
    @DisplayName("🔴 quote · applyCoupon · claimEventCoupon 은 NOT_SUPPORTED")
    void remoteCallingMethodsDoNotOpenATransaction() {
        for (String name : new String[] { "quote", "applyCoupon", "claimEventCoupon" }) {
            Method[] methods = Arrays.stream(CouponService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    .toArray(Method[]::new);
            assertThat(methods).as(name).isNotEmpty();
            for (Method m : methods) {
                Transactional tx = m.getAnnotation(Transactional.class);
                assertThat(tx).as("%s 는 트랜잭션을 명시적으로 끈다", m).isNotNull();
                assertThat(tx.propagation()).as("%s", m).isEqualTo(Propagation.NOT_SUPPORTED);
            }
        }
        assertThat(CouponService.class.getAnnotation(Transactional.class)).as("클래스 레벨 트랜잭션이 없다").isNull();
    }
}
