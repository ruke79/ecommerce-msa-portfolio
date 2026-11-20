package com.project.order_service.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 주문 캐시를 비운다 — 주문 상태를 {@code OrderCacheService} 바깥에서 바꾸는 쪽이 부른다.
 *
 * <h3>🐛 목록은 PAID, 상세는 NOT_PROCESSED</h3>
 *
 * <p>주문 상세({@code order} 캐시, 60분)는 Redis 에서 나온다. 그런데 결제 응답을 받아 상태를
 * PAID 로 바꾸는 것은 사가({@code OrderSaga})고, 사가는 캐시를 비우지 않았다. 목록(커서 조회,
 * 캐시 없음)은 PAID 인데 상세는 한 시간 동안 NOT_PROCESSED 였다. 그 위에서:
 *
 * <ul>
 *   <li>결제 확인 뒤 확정을 기다리는 화면이 같은 상세를 14번 다시 읽어도 늘 옛 값 — 시한을
 *       넘겨 "확정이 늦어진다" 고 말했다(실제로는 이미 PAID).</li>
 *   <li>상세가 미결제로 보이니 결제 버튼이 다시 떴고, 누르면 "이미 결제된 주문입니다".</li>
 * </ul>
 *
 * <p>상태를 바꾸는 곳은 반드시 여기를 부른다. 범위가 넓은 이유는 {@code OrderCacheService}
 * 의 다른 쓰기와 같다 — 상태 하나가 목록·상세·페이지 캐시 전부에 들어 있다.
 *
 * <p>별도 빈인 이유: {@code @CacheEvict} 는 프록시를 거쳐야 듣는다(같은 클래스 안의 호출은
 * 무시된다). 그리고 사가가 {@code OrderCacheService} 를 들면 순환 의존이 된다.
 *
 * <p>[JA] 注文キャッシュを空にする — 注文状態を {@code OrderCacheService} の外で変える側が呼ぶ。
 * <b>🐛 一覧は PAID、詳細は NOT_PROCESSED</b>:
 * 注文詳細({@code order} キャッシュ、60 分)は Redis から出る。ところが決済応答を受けて状態を
 * PAID に変えるのはサーガ({@code OrderSaga})で、サーガはキャッシュを空にしていなかった。一覧(カーソル照会、
 * キャッシュなし)は PAID なのに詳細は 1 時間 NOT_PROCESSED だった。その上で:
 * 決済確認後に確定を待つ画面が同じ詳細を 14 回読み直しても常に古い値 — 期限を
 * 過ぎて「確定が遅れている」と言った(実際には既に PAID)。
 * 詳細が未決済に見えるので決済ボタンが再び出て、押すと「既に決済された注文です」。
 * 状態を変える所は必ずここを呼ぶ。範囲が広い理由は {@code OrderCacheService}
 * の他の書き込みと同じ — 一つの状態が一覧・詳細・ページキャッシュのすべてに入っている。
 * 別 Bean である理由: {@code @CacheEvict} はプロキシを通らないと効かない(同じクラス内の呼び出しは
 * 無視される)。そしてサーガが {@code OrderCacheService} を持つと循環依存になる。
 */
@Slf4j
@Component
public class OrderCacheEvictor {

    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public void evictOrderCaches(Long orderId, String reason) {
        log.debug("주문 캐시 비움: orderId={} reason={}", orderId, reason);
    }
}
