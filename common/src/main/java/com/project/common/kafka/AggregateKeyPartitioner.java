package com.project.common.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 집합체(aggregate) id 로 파티션을 고르는 파티셔너.
 *
 * <p><b>왜 기본 파티셔너로는 부족한가.</b> 기본 파티셔너는 키 바이트의 murmur2 다. 그 자체는
 * 좋은데, 이 저장소의 키는 토픽마다 모양이 다르다 — {@code order} 토픽은 {@code "123"},
 * {@code product-update} 는 {@code "order-123"}, 앞으로 생길 것들은 {@code "payment-9"} 같은 식일
 * 것이다. 바이트가 다르니 같은 주문이라도 토픽마다 다른 파티션에 앉는다. 파티션 수가 같아도
 * 토픽 사이에 <b>공동 파티셔닝(co-partitioning)</b>이 안 되고, 나중에 스트림 조인이나 파티션
 * 단위 재처리를 하려면 그때 가서 키를 전부 바꿔야 한다.
 *
 * <p><b>무엇을 하는가.</b> 키에서 {@code <접두사>-} 를 벗겨 집합체 id 만 남기고, 그 id 를
 * <b>기본 파티셔너와 똑같은 해시</b>({@code murmur2})로 파티션에 보낸다. 그래서:
 * <ul>
 *   <li>{@code "123"}, {@code "order-123"}, {@code "payment-123"} 은 같은 파티션이다.</li>
 *   <li>접두사 없는 키({@code "123"})는 기본 파티셔너와 <b>정확히 같은</b> 파티션이다. Debezium
 *       CDC 릴레이는 Connect 워커의 기본 파티셔너를 쓰고 키가 {@code aggregate_id}(= 접두사
 *       없는 id)이므로, 스케줄러 릴레이와 CDC 릴레이가 같은 파티션에 쓴다(docs/38 §1.15).</li>
 * </ul>
 *
 * <p><b>키가 없으면.</b> 사용 가능한 파티션 하나를 무작위로 고른다(없으면 전체에서). 토픽당 한 번 경고를 남긴다.
 * {@code KafkaProducerKeyGuardrailTest} 가 소스에서 막고, 이 경고는 런타임에서 한 번 더 잡는 그물이다.
 *
 * <p>🐛 예전에는 {@link RecordMetadata#UNKNOWN_PARTITION}(-1)을 돌려 "내장 스티키 파티셔닝에 맡긴다" 고 했지만, 그것은 내장
 * 파티셔너일 때의 이야기다 — {@code KafkaProducer} 는 <b>커스텀</b> 파티셔너가 음수를 돌려주면 {@code IllegalArgumentException}
 * 으로 발행을 거절한다(kafka-clients 3.9). DLT 템플릿도 이 파티셔너를 써서, 키 없이 들어온 레코드(e2e · kcat)가 처리에 실패하면
 * DLT 로 옮기는 발행이 매번 실패하고 오류 처리기가 같은 레코드를 끝없이 다시 시도해 <b>그 파티션 전체가 멈췄다</b>(docs/03 F-2 의
 * 재현).
 *
 * <p>[JA] 集約(aggregate) id でパーティションを選ぶパーティショナー。
 *
 * <p><b>なぜデフォルトパーティショナーでは足りないのか。</b>デフォルトパーティショナーはキーバイトの murmur2 である。それ自体は
 * 良いのだが、このリポジトリのキーはトピックごとに形が異なる — {@code order} トピックは {@code "123"}、
 * {@code product-update} は {@code "order-123"}、今後増えるものは {@code "payment-9"} のような形に
 * なるだろう。バイトが違うので、同じ注文でもトピックごとに別のパーティションに載る。パーティション数が同じでも
 * トピック間で<b>コパーティショニング(co-partitioning)</b>が成り立たず、後でストリーム結合やパーティション
 * 単位の再処理をしようとすれば、その時点でキーを全部変えなければならない。
 *
 * <p><b>何をするのか。</b>キーから {@code <接頭辞>-} を剥がして集約 id だけを残し、その id を
 * <b>デフォルトパーティショナーとまったく同じハッシュ</b>({@code murmur2})でパーティションに振り分ける。したがって:
 * <ul>
 *   <li>{@code "123"}、{@code "order-123"}、{@code "payment-123"} は同じパーティションになる。</li>
 *   <li>接頭辞のないキー({@code "123"})はデフォルトパーティショナーと<b>完全に同じ</b>パーティションになる。Debezium
 *       CDC リレーは Connect ワーカーのデフォルトパーティショナーを使い、キーが {@code aggregate_id}(= 接頭辞
 *       なしの id)なので、スケジューラリレーと CDC リレーが同じパーティションに書く(docs/38 §1.15)。</li>
 * </ul>
 *
 * <p><b>キーがない場合。</b>利用可能なパーティションを一つ無作為に選ぶ(なければ全体から)。トピックごとに一度警告を残す。
 * 以前は {@link RecordMetadata#UNKNOWN_PARTITION}(-1)を返していたが、{@code KafkaProducer} はカスタムパーティショナーの負の値を
 * 拒否するため、キーなしのレコードが DLT へ移せずパーティション全体が止まっていた。
 * {@code KafkaProducerKeyGuardrailTest} がソース上で防ぎ、この警告はランタイムでもう一度拾う
 * セーフティネットである。
 */
public class AggregateKeyPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(AggregateKeyPartitioner.class);

    /** 벗겨 낼 접두사. 새 토픽이 새 접두사를 쓰면 여기에 더한다 — 테스트가 함께 지킨다. */
    // [JA] 剥がす接頭辞。新しいトピックが新しい接頭辞を使うならここに追加する — テストが併せて守る。
    static final Set<String> AGGREGATE_PREFIXES = Set.of("order-", "payment-", "user-", "room-", "product-");

    private final Set<String> warnedTopics = ConcurrentHashMap.newKeySet();

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionsForTopic(topic).size();
        if (keyBytes == null) {
            if (warnedTopics.add(topic)) {
                log.warn("키 없는 레코드가 '{}' 토픽으로 나간다 — 파티션이 둘 이상이면 같은 집합체의 순서가 깨진다. "
                        + "발행 지점에 키를 실을 것(docs/38 §1.15)", topic);
            }
            List<PartitionInfo> available = cluster.availablePartitionsForTopic(topic);
            List<PartitionInfo> pool = available.isEmpty() ? cluster.partitionsForTopic(topic) : available;
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size())).partition();
        }
        byte[] normalized = key instanceof String s ? normalize(s).getBytes(StandardCharsets.UTF_8) : keyBytes;
        return Utils.toPositive(Utils.murmur2(normalized)) % numPartitions;
    }

    /** {@code "order-123"} → {@code "123"}. 아는 접두사가 없으면 그대로. */
    // [JA] {@code "order-123"} → {@code "123"}。既知の接頭辞がなければそのまま。
    static String normalize(String key) {
        for (String prefix : AGGREGATE_PREFIXES) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                return key.substring(prefix.length());
            }
        }
        return key;
    }

    @Override
    public void close() {
        // 상태 없음
        // [JA] 状態なし
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // 설정 없음
        // [JA] 設定なし
    }
}
