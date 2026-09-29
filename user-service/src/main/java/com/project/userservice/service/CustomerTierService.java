package com.project.userservice.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.project.common.constants.event.CustomerTier;
import com.project.common.dto.order.PurchaseStatDto;
import com.project.userservice.client.OrderServiceClient;
import com.project.userservice.config.TierProperties;
import com.project.userservice.repository.jpa.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 등급 산정 배치.
 *
 * <p>order-service 에서 고객별 구매 집계를 커서로 훑어 오면서, 각 사람의 등급과 구매
 * 이력을 users 테이블에 찍는다. 마지막에 이번 회차에 등장하지 않은 등급 보유자를
 * 일반으로 내린다.
 *
 * <h3>🔴 회차 시작 시각을 왜 먼저 붙잡는가</h3>
 *
 * <p>강등은 "이번 회차에 손대지 않은 행" 을 고르는 방식이다({@code tierComputedAt <
 * startedAt}). 그런데 기준 시각을 <b>마지막에</b> 읽으면, 배치가 도는 동안 갱신한 행들이
 * 전부 그 시각보다 과거가 되어 <b>방금 승급시킨 사람까지 도로 강등된다</b>. 그래서
 * 시작 시각을 맨 처음에 한 번 잡고 끝까지 그 값을 쓴다.
 *
 * <h3>실패했을 때</h3>
 *
 * <p>중간에 order-service 호출이 실패하면 <b>거기서 멈춘다.</b> 이미 갱신한 사람은
 * 그대로 두고, 강등은 <b>하지 않는다.</b> 절반만 받은 목록으로 강등을 돌리면 아직 받지
 * 못한 구간의 고객이 전부 일반 등급으로 떨어진다 — 통신 장애 하나로 전 고객 등급이
 * 날아가는 사고다. 다음 회차가 정상적으로 끝나면 저절로 맞춰진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerTierService {

    /**
     * 한 회차가 훑을 최대 고객 수.
     *
     * <p>무한 루프 방지장치다. 커서가 어떤 이유로든 전진하지 않으면 이 배치는 영원히
     * 돈다 — 야간에, 아무도 보지 않는 동안. 상한이 있으면 최악의 경우에도 로그 한 줄을
     * 남기고 끝난다.
     */
    static final int MAX_CUSTOMERS_PER_RUN = 200_000;

    private final OrderServiceClient orderServiceClient;
    private final UserRepository userRepository;
    private final TierProperties tierProperties;

    /**
     * 전 고객의 등급을 다시 매긴다.
     *
     * @return 갱신한 사람 수와 강등한 사람 수
     */
    public Result recalculateAll() {
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);

        int months = Math.max(1, tierProperties.getMonths());
        int size = Math.max(1, tierProperties.getBatchSize());

        long cursor = Long.MIN_VALUE;
        int updated = 0;
        int seen = 0;
        boolean drained = false;

        while (true) {
            if (seen >= MAX_CUSTOMERS_PER_RUN) {
                // 상한에 걸렸다 = 아직 안 본 고객이 남았다. 실패와 똑같이 다룬다 —
                // 여기서 강등을 돌리면 못 본 구간이 통째로 일반 등급이 된다.
                log.warn("등급 배치 중단 — 한 회차 상한({})에 도달했다. batch-size 를 올려야 한다.",
                        MAX_CUSTOMERS_PER_RUN);
                return new Result(updated, 0, false);
            }

            List<PurchaseStatDto> page = fetch(months, cursor, size);
            if (page == null) {
                // 호출 실패. 강등하지 않고 멈춘다 — 위 주석 참조.
                log.warn("등급 배치 중단 — 구매 집계를 가져오지 못했다. 처리한 고객={}", seen);
                return new Result(updated, 0, false);
            }
            if (page.isEmpty()) {
                drained = true;
                break;
            }

            for (PurchaseStatDto stat : page) {
                if (stat == null || stat.customerId() == null) {
                    continue;
                }
                cursor = Math.max(cursor, stat.customerId());
                seen++;
                updated += apply(stat, startedAt);
            }

            // 🔴 "받은 행이 요청한 크기보다 적으면 끝" 이라는 지름길을 쓰지 않는다.
            //
            // order-service 는 응답 하나의 크기에 자체 상한(500)이 있어서, 여기서 그보다
            // 큰 size 를 요청하면 **말없이 잘라서** 돌려준다. 그러면 첫 페이지부터
            // "덜 찼으니 끝" 으로 읽히고, 아직 보지도 않은 나머지 고객이 전부 강등된다.
            // 저쪽 상한과 이쪽 설정이 조용히 어긋나는 순간 터지는 종류의 버그다.
            //
            // 빈 페이지를 받을 때까지 간다. 하루 한 번 도는 배치에서 요청 한 번 더 보내는
            // 값으로, 두 서비스 사이의 숫자 약속에 기대는 일을 통째로 없앤다.
        }

        if (!drained) {
            return new Result(updated, 0, false);
        }

        int demoted = userRepository.demoteStale(startedAt, LocalDateTime.now(ZoneOffset.UTC));
        log.info("등급 배치 완료 — 갱신={} 강등={}", updated, demoted);
        return new Result(updated, demoted, true);
    }

    /**
     * 집계 한 건을 등급으로 바꿔 저장한다.
     *
     * <p>{@code updateTier} 가 0 을 돌려주는 것은 정상이다 — order-service 에는 주문이
     * 있는데 user-service 에는 그 계정이 없는 경우(탈퇴 후 익명화, 테스트 데이터)다.
     * 예외로 만들지 않는다. 한 건 때문에 배치 전체를 멈출 이유가 없다.
     */
    private int apply(PurchaseStatDto stat, LocalDateTime computedAt) {
        CustomerTier tier = tierProperties.tierFor(stat.recentAmount());
        return userRepository.updateTier(stat.customerId(), tier,
                stat.lifetimeOrderCount(), stat.recentAmount(), computedAt);
    }

    /** 실패는 {@code null} 이다. 빈 목록(=끝)과 구분해야 강등 여부가 갈린다. */
    private List<PurchaseStatDto> fetch(int months, long cursor, int size) {
        try {
            Map<String, List<PurchaseStatDto>> body =
                    orderServiceClient.purchaseStats(months, cursor, size);
            if (body == null) {
                return null;
            }
            List<PurchaseStatDto> stats = body.get("stats");
            return stats == null ? List.of() : stats;
        } catch (Exception e) {
            log.warn("구매 집계 조회 실패: {}", e.toString());
            return null;
        }
    }

    /**
     * 한 회차의 결과.
     *
     * @param updated   등급·구매 이력을 갱신한 사람 수
     * @param demoted   일반 등급으로 내린 사람 수
     * @param completed 끝까지 돌았는가. {@code false} 면 강등을 건너뛴 것이다.
     */
    public record Result(int updated, int demoted, boolean completed) {
    }
}
