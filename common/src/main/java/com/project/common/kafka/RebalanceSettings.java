package com.project.common.kafka;

import java.util.List;

import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.springframework.core.env.Environment;

/**
 * 컨슈머 그룹 리밸런스 설정 — 한 곳에서.
 *
 * <p><b>왜 협력적(cooperative) 리밸런스인가.</b> 기본 {@link RangeAssignor} 는 <i>eager</i> 프로토콜이다:
 * 멤버가 하나 들어오거나 나갈 때마다 <b>그룹 전원이 모든 파티션을 내려놓고</b> 다시 받는다. 그 사이
 * 소비가 멈추고(stop-the-world), 파티션이 바뀐 멤버는 처리 중이던 배치를 잃는다. 파드 하나가
 * 롤링 배포로 교체될 때마다 같은 그룹의 나머지 파드까지 멈추는 셈이다.
 * {@link CooperativeStickyAssignor} 는 <b>옮겨야 하는 파티션만</b> 옮기고 나머지는 계속 소비한다.
 *
 * <p><b>왜 바로 바꾸지 않고 두 단계인가.</b> eager 와 cooperative 는 프로토콜이 다르다. 한 그룹에
 * 옛 멤버(Range 만 아는)와 새 멤버가 섞이면 그룹이 깨진다. Kafka 가 정한 안전한 순서는:
 * <ol>
 *   <li>모든 멤버를 {@code [cooperative-sticky, range]} 로 배포한다 — 둘 다 알므로 그룹은 아직
 *       eager 로 돌지만 어느 멤버가 먼저 바뀌어도 안전하다. ({@code kafka.rebalance.protocol=eager}, 기본)</li>
 *   <li>전원이 1단계를 지난 뒤 {@code [cooperative-sticky]} 만 남긴다.
 *       ({@code kafka.rebalance.protocol=cooperative})</li>
 * </ol>
 * 기본값을 1단계로 둔 이유: 지금 배포된 컨슈머는 전부 Range 다. 이 커밋을 배포하는 첫 롤링에서
 * 옛 파드와 새 파드가 같은 그룹에 섞이는데, 그때 새 파드가 cooperative 만 알면 그룹이 깨진다.
 * 1단계가 전부 나간 뒤 yml 한 줄로 2단계로 넘어간다.
 */
public final class RebalanceSettings {

    public static final String PROTOCOL_PROPERTY = "kafka.rebalance.protocol";

    private RebalanceSettings() {
    }

    /** {@code partition.assignment.strategy} 에 넣을 어사이너 목록. */
    public static List<String> assignors(Environment env) {
        String protocol = env.getProperty(PROTOCOL_PROPERTY, "eager").trim().toLowerCase();
        return switch (protocol) {
            case "cooperative" -> List.of(CooperativeStickyAssignor.class.getName());
            case "eager" -> List.of(CooperativeStickyAssignor.class.getName(), RangeAssignor.class.getName());
            default -> throw new IllegalArgumentException(
                    PROTOCOL_PROPERTY + " 는 eager 또는 cooperative 여야 한다: " + protocol);
        };
    }
}
