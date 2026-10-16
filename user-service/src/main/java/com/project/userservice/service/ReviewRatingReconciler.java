package com.project.userservice.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.repository.jpa.ReviewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 평점 대사 — 매일 리뷰에서 평균을 다시 계산해 catalog 에 보낸다 (docs/57 §13).
 *
 * <p>평점은 리뷰가 바뀔 때 {@code ReviewService.publishRating} 이 보낸다. 그 호출이 실패하면 리뷰는 남기고 경고만 남기므로
 * (docs/57 §3), <b>그 뒤로 리뷰가 없는 상품은 평점이 어긋난 채 남는다.</b> 평점은 리뷰에서 언제든 다시 계산할 수 있는 파생
 * 값이라, 주기적으로 전부 다시 보내는 것이 가장 단순한 복구다.
 *
 * <p>레플리카가 여럿이어도 회차 임대로 한 파드만 돈다(SchedulerLease). 🐛 예전에는 파드마다 같은 시각에 돌아 catalog 에 상품마다 같은
 * 값을 두 번 보냈다 — 멱등이라 결과는 같았지만 호출이 파드 수만큼 늘었다. 리뷰가 하나도 남지 않은 상품은
 * 여기서 보이지 않는다 — 마지막 리뷰를 지울 때 0 을 보내고(ReviewService.deleteReview), 그것이 실패한 경우만 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRatingReconciler {

    private final ReviewRepository reviewRepository;
    private final CatalogServiceClient catalogServiceClient;
    private final SchedulerLease lease;

    /**
     * 회차 임대(V17 users.scheduler_lock) — 레플리카가 여럿이어도 하루 한 번은 한 파드만 돈다. <b>끝나도 풀지 않는다</b>: 풀면 시계가
     * 몇 초 늦은 다른 파드가 같은 회차를 다시 돈다. 한 시간 뒤 저절로 풀리고, 다음 회차는 하루 뒤라 겹치지 않는다.
     */
    // [JA] 回のリース — レプリカが複数でも一日一回は一つの Pod だけ。終わっても解放しない(時計が数秒遅い Pod が同じ回を再実行しないように)。
    static final String LEASE = "review-rating-reconcile";
    static final java.time.Duration LEASE_FOR = java.time.Duration.ofHours(1);

    @Value("${review.rating-reconcile.enabled:true}")
    private boolean enabled = true;

    public record Result(int sent, int failed) {}

    @Scheduled(cron = "${review.rating-reconcile.cron:0 40 18 * * *}", zone = "UTC")
    public void scheduled() {
        if (!enabled || !acquired()) {
            return;
        }
        try {
            Result r = reconcile();
            if (r.failed() > 0) {
                log.warn("평점 대사: 보냄={} 실패={} — 다음 회차에 다시 보낸다", r.sent(), r.failed());
            } else {
                log.info("평점 대사: 보냄={}", r.sent());
            }
        } catch (Exception e) {
            // 예외로 스케줄러 스레드가 죽으면 이후 회차가 사라진다.
            log.error("평점 대사 실패: {}", e.toString());
        }
    }

    /** 임대를 못 잡거나(다른 파드가 이 회차를 돈다) 임대 확인이 실패하면 건너뛴다 — 다음 회차가 같은 일을 한다. */
    private boolean acquired() {
        try {
            if (lease.tryAcquire(LEASE, LEASE_FOR)) {
                return true;
            }
            log.info("평점 대사 — 다른 파드가 이 회차를 돈다. 건너뛴다.");
        } catch (Exception e) {
            log.warn("평점 대사 임대를 확인하지 못했다 — 이번 회차는 건너뛴다: {}", e.toString());
        }
        return false;
    }

    /** 리뷰가 있는 모든 상품의 평균을 catalog 에 보낸다. 한 상품의 실패가 나머지를 막지 않는다. */
    public Result reconcile() {
        List<Object[]> rows = reviewRepository.averageRatingByProduct();
        int sent = 0;
        int failed = 0;
        for (Object[] row : rows) {
            Long productId = (Long) row[0];
            float rating = row[1] == null ? 0F : ((Number) row[1]).floatValue();
            try {
                catalogServiceClient.updateRating(productId, rating);
                sent++;
            } catch (RuntimeException e) {
                failed++;
                log.debug("평점 대사 실패: productId={} ({})", productId, e.getClass().getSimpleName());
            }
        }
        return new Result(sent, failed);
    }
}
