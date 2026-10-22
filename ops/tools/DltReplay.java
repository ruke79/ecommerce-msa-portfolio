// DLT 재처리 — ops/56-dlt-replay.sh 가 Kafka 브로커 파드 안에서 `java -cp '/usr/share/java/kafka/*' DltReplay.java …` 로 돌린다.
// [JA] DLT 再処理 — ops/56 がブローカー Pod 内で単一ファイル Java として実行する(ブローカー自身の kafka-clients を使う)。
//
// 왜 콘솔 도구가 아닌가: kafka-console-consumer · producer 는 값을 '줄' 로 다룬다. Avro(바이너리) 값에는 줄바꿈 · 0 바이트가
// 들어 있어 옮기다 깨진다(2026-10-22 실습 게임데이 poison-message, docs/67 §9.2). 여기서는 byte[] 그대로 읽고 그대로 보낸다.
//
// 지키는 것:
//   · 값 · 키 · 헤더를 바이트 그대로. 빼는 것은 Spring 의 DLT 헤더(kafka_dlt-*)뿐, 더하는 것은 dlt-replayed-from(어디서 왔나).
//   · 원래 파티션(kafka_dlt-original-partition)으로 — 프로듀서는 키 접두사를 벗겨 파티션을 정한다(AggregateKeyPartitioner).
//     기본 해시로 보내면 같은 주문의 다른 메시지와 다른 파티션에 떨어져 순서가 어긋난다. 그 파티션이 없으면 키 해시.
//   · 값은 출력하지 않는다 — 주문 · 주소 같은 개인정보가 있다. 크기와 원인 · 자리만.
//   · 보내기는 apply 일 때만. 아니면 무엇을 보낼지만 보인다.
//
// Java 11 (cp-kafka 7.6 의 Zulu 11) — record · switch 식 · 텍스트 블록을 쓰지 않는다.
//
// 사용: DltReplay list   <bootstrap> <dlt 토픽> <파티션마다 최근 몇 건>
//       DltReplay replay <bootstrap> <dlt 토픽> <파티션> <시작 오프셋> <끝 오프셋> <apply 0|1> <보낼 토픽 | ->
// 종료: 0 정상 · 2 보낼 곳을 모른다(원래 토픽 헤더 없음 · TO 없음) · 3 그 오프셋의 레코드가 없다 · 64 사용법

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

public class DltReplay {

    static final String DLT_PREFIX = "kafka_dlt-";
    static final String REPLAYED_FROM = "dlt-replayed-from";

    public static void main(String[] args) throws Exception {
        if (args.length >= 4 && "list".equals(args[0])) {
            list(args[1], args[2], Integer.parseInt(args[3]));
        } else if (args.length >= 8 && "replay".equals(args[0])) {
            System.exit(replay(args[1], args[2], Integer.parseInt(args[3]), Long.parseLong(args[4]), Long.parseLong(args[5]),
                    "1".equals(args[6]), "-".equals(args[7]) ? null : args[7]));
        } else {
            System.err.println("사용: list <bootstrap> <dlt> <n> | replay <bootstrap> <dlt> <p> <from> <to> <apply> <to-topic|->");
            System.exit(64);
        }
    }

    static KafkaConsumer<byte[], byte[]> consumer(String bootstrap) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // 그룹 없이 assign — 어느 컨슈머 그룹의 오프셋도 건드리지 않는다
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new KafkaConsumer<>(p);
    }

    static String header(ConsumerRecord<byte[], byte[]> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Spring 은 원래 파티션 · 오프셋을 4 · 8 바이트 정수로 싣는다(2.x 이후). 글자로 실린 옛 모양도 읽는다. */
    static Long numberHeader(ConsumerRecord<byte[], byte[]> r, String name) {
        Header h = r.headers().lastHeader(name);
        if (h == null || h.value() == null) {
            return null;
        }
        byte[] v = h.value();
        if (v.length == 4) {
            return (long) java.nio.ByteBuffer.wrap(v).getInt();
        }
        if (v.length == 8) {
            return java.nio.ByteBuffer.wrap(v).getLong();
        }
        try {
            return Long.parseLong(new String(v, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String printableKey(byte[] key) {
        if (key == null) {
            return "(없음)";
        }
        String s = new String(key, StandardCharsets.UTF_8);
        boolean plain = key.length <= 64 && s.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        return plain ? s : "<" + key.length + "바이트>";
    }

    static String oneLine(String s, int max) {
        if (s == null) {
            return "-";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    static void list(String bootstrap, String topic, int perPartition) {
        try (KafkaConsumer<byte[], byte[]> c = consumer(bootstrap)) {
            List<PartitionInfo> parts = c.partitionsFor(topic, Duration.ofSeconds(10));
            if (parts == null || parts.isEmpty()) {
                System.out.println("토픽이 없습니다: " + topic);
                return;
            }
            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo pi : parts) {
                tps.add(new TopicPartition(topic, pi.partition()));
            }
            Collections.sort(tps, (a, b) -> Integer.compare(a.partition(), b.partition()));
            Map<TopicPartition, Long> begin = c.beginningOffsets(tps);
            Map<TopicPartition, Long> end = c.endOffsets(tps);
            long total = 0;
            for (TopicPartition tp : tps) {
                total += end.get(tp) - begin.get(tp);
            }
            System.out.println(topic + " — 레코드 " + total + "건(보존 안), 파티션 " + tps.size());
            for (TopicPartition tp : tps) {
                long from = Math.max(begin.get(tp), end.get(tp) - perPartition);
                long to = end.get(tp);
                if (from >= to) {
                    continue;
                }
                c.assign(Collections.singletonList(tp));
                c.seek(tp, from);
                long next = from;
                long deadline = System.currentTimeMillis() + 15_000;
                while (next < to && System.currentTimeMillis() < deadline) {
                    for (ConsumerRecord<byte[], byte[]> r : c.poll(Duration.ofMillis(500))) {
                        next = r.offset() + 1;
                        System.out.println(String.format("  %d:%d  %s  키=%s  값=%d바이트  원래=%s-%s-%s  그룹=%s%s",
                                r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), printableKey(r.key()),
                                r.value() == null ? 0 : r.value().length,
                                header(r, DLT_PREFIX + "original-topic"), numberHeader(r, DLT_PREFIX + "original-partition"),
                                numberHeader(r, DLT_PREFIX + "original-offset"), header(r, DLT_PREFIX + "original-consumer-group"),
                                header(r, REPLAYED_FROM) == null ? "" : "  (이미 한 번 다시 넣었던 것: " + header(r, REPLAYED_FROM) + ")"));
                        System.out.println("        예외 " + oneLine(header(r, DLT_PREFIX + "exception-fqcn"), 120) + ": "
                                + oneLine(header(r, DLT_PREFIX + "exception-message"), 200));
                    }
                }
            }
        }
    }

    static int replay(String bootstrap, String dlt, int partition, long from, long to, boolean apply, String toTopic)
            throws Exception {
        TopicPartition tp = new TopicPartition(dlt, partition);
        List<ConsumerRecord<byte[], byte[]>> picked = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> c = consumer(bootstrap)) {
            c.assign(Collections.singletonList(tp));
            long end = c.endOffsets(Collections.singletonList(tp)).get(tp);
            long begin = c.beginningOffsets(Collections.singletonList(tp)).get(tp);
            if (from < begin || from >= end || to < from) {
                System.err.println("그 오프셋의 레코드가 없습니다: " + dlt + ":" + partition + " " + from + "-" + to
                        + " (지금 있는 범위 " + begin + " ~ " + (end - 1) + ")");
                return 3;
            }
            long last = Math.min(to, end - 1);
            c.seek(tp, from);
            long deadline = System.currentTimeMillis() + 20_000;
            long next = from;
            while (next <= last && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> r : c.poll(Duration.ofMillis(500))) {
                    next = r.offset() + 1;
                    if (r.offset() <= last) {
                        picked.add(r);
                    }
                }
            }
        }
        if (picked.isEmpty()) {
            System.err.println("그 오프셋의 레코드를 읽지 못했습니다(트랜잭션 표지만 있는 자리일 수 있다): " + dlt + ":" + partition + " " + from);
            return 3;
        }

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        int sent = 0;
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(p)) {
            for (ConsumerRecord<byte[], byte[]> r : picked) {
                String dest = toTopic != null ? toTopic : header(r, DLT_PREFIX + "original-topic");
                if (dest == null) {
                    System.err.println(r.partition() + ":" + r.offset() + " — 원래 토픽 헤더(" + DLT_PREFIX
                            + "original-topic)가 없습니다. TO=<토픽> 으로 정하세요");
                    return 2;
                }
                Long origPartition = numberHeader(r, DLT_PREFIX + "original-partition");
                Integer destPartition = null;
                if (origPartition != null) {
                    List<PartitionInfo> parts = producer.partitionsFor(dest);
                    if (origPartition >= 0 && origPartition < parts.size()) {
                        destPartition = origPartition.intValue();
                    }
                }
                RecordHeaders headers = new RecordHeaders();
                for (Header h : r.headers()) {
                    if (!h.key().startsWith(DLT_PREFIX) && !REPLAYED_FROM.equals(h.key())) {
                        headers.add(h.key(), h.value());
                    }
                }
                headers.add(REPLAYED_FROM, (dlt + ":" + r.partition() + ":" + r.offset()).getBytes(StandardCharsets.UTF_8));
                String where = dest + (destPartition == null ? "(키 해시)" : ":" + destPartition);
                String what = String.format("%d:%d → %s  키=%s  값=%d바이트  헤더 %d개(DLT 헤더 뺌)  실패한 그룹=%s",
                        r.partition(), r.offset(), where, printableKey(r.key()), r.value() == null ? 0 : r.value().length,
                        headers.toArray().length, header(r, DLT_PREFIX + "original-consumer-group"));
                if (!apply) {
                    System.out.println("  [보내지 않음] " + what);
                    continue;
                }
                RecordMetadata m = producer.send(new ProducerRecord<>(dest, destPartition, r.key(), r.value(), headers)).get();
                System.out.println("  보냄 " + what + "  → 오프셋 " + m.offset());
                sent++;
            }
            producer.flush();
        }
        System.out.println(apply ? "다시 넣음: " + sent + "건" : "보내지 않았습니다 — 보내려면 APPLY=1");
        return 0;
    }
}
