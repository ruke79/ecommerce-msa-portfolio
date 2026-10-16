package com.project.order_service.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.common.constants.OrderStatus;
import com.project.order_service.repository.jpa.OrderRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 대사 — 결제는 확인 · 기록됐는데(제공자 결제 참조가 붙었다) 결제 응답이 오지 않아 {@value #GRACE_MINUTES}분이 넘도록 결제 전인
 * 주문을 센다(게이지 {@code order_payment_unconfirmed}, 경보 OrderPaymentUnconfirmed). 고치지는 않는다 — 사람이 본다.
 *
 * <p>🐛 예전에는 이 상태를 아무도 몰랐다. 결제 요청 아웃박스 · pay-service · 결제 응답 소비(payment) 중 어디서 멈춰도 고객은 돈을 냈는데
 * 주문은 결제 전이고, 결제창 재고 예약(30분)이 풀려 남에게 팔리고, 응답이 DLT 로 가도 조용했다. 정상이면 몇 초 안에 PAID 가 된다.
 *
 * <p>모든 파드가 같은 수를 낸다(읽기 한 번) — 경보는 max 로 본다.
 *
 * <p>[JA] 決済照合 — 決済は確認 · 記録されたのに決済応答が来ず、一定時間を過ぎても決済前の注文を数える。直さない — 人が見る。
 */
@Slf4j
@Component
public class UnconfirmedPaymentWatch {

    static final long GRACE_MINUTES = 10;
    public static final String METRIC = "order.payment.unconfirmed";

    private final OrderRepository orders;
    private final AtomicLong unconfirmed = new AtomicLong();
    private Clock clock = Clock.systemDefaultZone();

    @Autowired
    public UnconfirmedPaymentWatch(OrderRepository orders, MeterRegistry meters) {
        this.orders = orders;
        Gauge.builder(METRIC, unconfirmed, AtomicLong::get)
                .description("결제는 기록됐는데 결제 응답이 오지 않아 결제 전에 머문 주문 수")
                .register(meters);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order.payment-watch.check-ms:300000}", initialDelay = 90_000)
    public void check() {
        try {
            LocalDateTime before = LocalDateTime.now(clock).minus(Duration.ofMinutes(GRACE_MINUTES));
            long n = orders.countUnconfirmedPayments(OrderStatus.NOT_PROCESSED, before);
            unconfirmed.set(n);
            if (n > 0) {
                List<Long> sample = orders.findUnconfirmedPayments(OrderStatus.NOT_PROCESSED, before, PageRequest.of(0, 20));
                log.error("결제 대사 — 결제는 기록됐는데 {}분 넘게 결제 전인 주문 {}건(앞 20건 orderId={}). payment-request 아웃박스 · "
                        + "pay-service · payment.DLT 를 확인한다", GRACE_MINUTES, n, sample);
            }
        } catch (RuntimeException e) {
            // 대사가 실패했다고 서비스가 흔들리면 안 된다 — 다음 실행에서 다시 본다(게이지는 마지막 값).
            log.warn("결제 대사 실패 — 다음 실행에서 다시 본다 ({})", e.getClass().getSimpleName());
        }
    }

    long unconfirmed() {
        return unconfirmed.get();
    }
}
