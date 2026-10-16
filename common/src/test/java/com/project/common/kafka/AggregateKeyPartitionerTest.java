package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AggregateKeyPartitioner")
class AggregateKeyPartitionerTest {

    private static Cluster clusterWith(String topic, int partitions) {
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> infos = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            infos.add(new PartitionInfo(topic, i, node, new Node[]{node}, new Node[]{node}));
        }
        return new Cluster("test", List.of(node), infos, Set.of(), Set.of());
    }

    private static int partitionOf(AggregateKeyPartitioner p, String topic, String key, Cluster cluster) {
        byte[] bytes = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
        return p.partition(topic, key, bytes, null, null, cluster);
    }

    @Test
    @DisplayName("같은 집합체 id 는 접두사가 달라도(order-/payment-/없음) 같은 파티션이다")
    void sameAggregateId_landsOnSamePartition_acrossPrefixes() {
        AggregateKeyPartitioner p = new AggregateKeyPartitioner();
        Cluster cluster = clusterWith("t", 12);

        for (String id : List.of("1", "123", "884667635780022288")) {
            int plain = partitionOf(p, "t", id, cluster);
            assertThat(partitionOf(p, "t", "order-" + id, cluster)).as("order-%s", id).isEqualTo(plain);
            assertThat(partitionOf(p, "t", "payment-" + id, cluster)).as("payment-%s", id).isEqualTo(plain);
            assertThat(partitionOf(p, "t", "user-" + id, cluster)).as("user-%s", id).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("접두사 없는 키는 기본 파티셔너(murmur2)와 정확히 같은 파티션이다 — CDC 릴레이와 공동 파티셔닝")
    void plainKey_matchesDefaultPartitioner() {
        AggregateKeyPartitioner p = new AggregateKeyPartitioner();
        Cluster cluster = clusterWith("payment", 6);

        for (String key : List.of("123", "9", "abc", "order")) {   // "order" 는 접두사 "order-" 가 아니다
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            int expected = Utils.toPositive(Utils.murmur2(bytes)) % 6;  // DefaultPartitioner 의 계산
            assertThat(partitionOf(p, "payment", key, cluster)).as(key).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("🔴 키가 없어도 실제 파티션 번호(0 이상)를 돌려준다 — -1 이면 KafkaProducer 가 발행을 거절해 DLT 로 못 옮기고 파티션이 멈춘다")
    void nullKey_picksARealPartition() {
        AggregateKeyPartitioner p = new AggregateKeyPartitioner();
        Cluster cluster = clusterWith("t", 3);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            int partition = partitionOf(p, "t", null, cluster);
            assertThat(partition).isNotEqualTo(RecordMetadata.UNKNOWN_PARTITION).isBetween(0, 2);
            seen.add(partition);
        }
        assertThat(seen).as("한 파티션에 몰리지 않는다").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("키가 없으면 리더가 있는 파티션 중에서 고른다")
    void nullKey_prefersAvailablePartitions() {
        Node node = new Node(0, "localhost", 9092);
        Cluster cluster = new Cluster("test", List.of(node), List.of(
                new PartitionInfo("t", 0, null, new Node[]{node}, new Node[]{}),     // 리더 없음
                new PartitionInfo("t", 1, node, new Node[]{node}, new Node[]{node})), Set.of(), Set.of());
        AggregateKeyPartitioner p = new AggregateKeyPartitioner();
        for (int i = 0; i < 50; i++) {
            assertThat(partitionOf(p, "t", null, cluster)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("결정적이고 고르게 퍼진다 — 파티션 12개에 id 1..1200 을 보내면 어느 파티션도 비지 않는다")
    void deterministicAndSpread() {
        AggregateKeyPartitioner p = new AggregateKeyPartitioner();
        Cluster cluster = clusterWith("t", 12);
        Set<Integer> seen = new HashSet<>();
        for (int i = 1; i <= 1200; i++) {
            int a = partitionOf(p, "t", "order-" + i, cluster);
            int b = partitionOf(p, "t", "order-" + i, cluster);
            assertThat(a).isEqualTo(b).isBetween(0, 11);
            seen.add(a);
        }
        assertThat(seen).hasSize(12);
    }

    @Test
    @DisplayName("접두사만 있는 키('order-')는 벗기지 않는다 — 빈 키로 무너지지 않게")
    void prefixOnlyKey_isKeptAsIs() {
        assertThat(AggregateKeyPartitioner.normalize("order-")).isEqualTo("order-");
        assertThat(AggregateKeyPartitioner.normalize("order-7")).isEqualTo("7");
        assertThat(AggregateKeyPartitioner.normalize("7")).isEqualTo("7");
    }
}
