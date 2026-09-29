package com.project.common.kafka;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

/**
 * 리밸런스마다 <b>커밋하고, 기록한다</b>.
 *
 * <p><b>커밋.</b> 파티션을 내려놓기 직전({@link #onPartitionsRevokedBeforeCommit})에 지금까지
 * 처리한 오프셋을 동기 커밋한다. 컨테이너도 BATCH 모드에서 커밋을 시도하지만, 여기서 한 번 더
 * 하는 이유는 <i>타이밍</i>이다 — 리밸런스 중에 커밋이 실패하면 그 파티션을 넘겨받은 멤버가 같은
 * 배치를 다시 읽는다. 결제·재고 같은 소비자는 멱등성 원장이 막아 주지만, 그건 마지막 방어선이지
 * 첫 번째가 아니다.
 *
 * <p><b>기록.</b> 어느 그룹이 어느 파티션을 받고 놓았는지, 받을 때 얼마나 밀려 있었는지(lag) 를
 * 한 줄로 남긴다. 리밸런스 폭풍(파드가 죽었다 살았다 하며 그룹이 계속 흔들리는 상태)은 이
 * 로그가 없으면 "소비가 느리다"로만 보인다.
 *
 * <p><b>lost.</b> {@link #onPartitionsLost} 는 커밋 없이 파티션을 빼앗긴 경우다(세션 타임아웃 등).
 * 커밋할 수 없으므로 하지 않고 — 하면 예외다 — 경고만 남긴다. 그 파티션을 받은 멤버가 마지막
 * 커밋 지점부터 다시 읽는다.
 */
public class LoggingRebalanceListener implements ConsumerAwareRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingRebalanceListener.class);

    @Override
    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        String group = groupOf(consumer);
        try {
            consumer.commitSync();
            log.info("리밸런스 — 그룹 '{}' 이 파티션 {} 을 내려놓기 전에 오프셋을 커밋했다", group, partitions);
        } catch (RuntimeException e) {
            // 여기서 예외를 올리면 리밸런스 자체가 실패한다. 커밋을 못 했을 뿐 파티션은 넘어가야 한다 —
            // 넘겨받은 쪽이 마지막 커밋 지점부터 다시 읽고, 멱등성 원장이 중복을 막는다.
            log.warn("리밸런스 — 그룹 '{}' 파티션 {} 의 커밋 실패, 넘겨받는 멤버가 재처리한다: {}",
                    group, partitions, e.toString());
        }
    }

    @Override
    public void onPartitionsRevokedAfterCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        // 커밋은 위에서 했다. 기록만.
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        String group = groupOf(consumer);
        Map<String, Long> lag = new TreeMap<>();
        try {
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            for (TopicPartition tp : partitions) {
                Long end = ends.get(tp);
                if (end != null) {
                    lag.put(tp.toString(), Math.max(0, end - consumer.position(tp)));
                }
            }
        } catch (RuntimeException e) {
            log.debug("lag 계산 생략: {}", e.toString());
        }
        log.info("리밸런스 — 그룹 '{}' 이 파티션 {} 을 받았다. 받을 때 lag={}", group, partitions, lag);
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        log.warn("리밸런스 — 그룹 '{}' 이 파티션 {} 을 커밋 없이 잃었다(세션 타임아웃/펜싱). "
                + "넘겨받는 멤버가 마지막 커밋 지점부터 재처리한다", groupOf(consumer), partitions);
    }

    private static String groupOf(Consumer<?, ?> consumer) {
        try {
            return consumer.groupMetadata().groupId();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
