package com.project.chat_service.migration;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.project.chat_service.model.ChatMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * 대화 기록(몽고) 정비 두 가지 — 기동을 붙잡지 않게 <b>백그라운드</b>에서 돈다(docs/62 §18).
 *
 * <ol>
 *   <li><b>방 기준 인덱스.</b> 방의 대화는 {@code roomId} 로 거르고 숫자 id 순으로 정렬한다(ChatMessageRepository 의
 *       {@code numericOrdering} collation). 그 인덱스가 없어 메시지가 쌓일수록 매 조회가 컬렉션 전체를 읽었다. 인덱스는 조회와
 *       <b>같은 collation</b> 이어야 쓰인다. 코드의 {@code @CompoundIndex} 로 선언하면 자동 인덱스 생성(auto-index-creation)이
 *       기동 중에 만들고, 큰 컬렉션이면 그 동안 기동이 멈춰 liveness 에 걸려 재시작 → 다시 생성을 되풀이할 수 있다. 그래서 여기서
 *       뒤로 미뤄 만든다. 이미 있으면 아무 일도 하지 않는다.</li>
 *   <li><b>옛 메시지 시각 보정(+9시간).</b> 예전 코드는 {@code LocalDateTime.now()} 를 서울 시간으로 해석했는데 컨테이너 JVM 은
 *       UTC 라 모든 메시지 시각이 9시간 일렀다(MessageDto.setSendTimeAndSender). 고친 코드가 처음 뜬 순간을 기준 시각(cutoff)으로
 *       한 번 정해 몽고에 적고, 그보다 이르게 적힌 문서를 +9시간 옮기면서 {@code tzFixed} 표시를 단다. 표시가 있는 문서는 다시
 *       옮기지 않으므로 몇 번 돌아도 결과가 같다. 롤링 배포 중 옛 파드가 늦게 쓴 문서(역시 9시간 이르다)는 15분 뒤의 두 번째
 *       실행이나 다음 기동에서 같은 기준으로 잡힌다. 고친 코드가 쓴 문서는 기준 시각 뒤라 건드리지 않는다.</li>
 * </ol>
 *
 * <p>[JA] 会話記録(Mongo)の整備 — ルーム基準のインデックスと、旧メッセージ時刻の +9 時間補正。起動を止めないよう
 * バックグラウンドで実行する。補正は最初に起動した修正版の時刻を基準に一度だけ決め、表示(tzFixed)で冪等にする。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "chat.message-maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class ChatMessageMaintenance implements ApplicationRunner {

    public static final String INDEX_NAME = "room_numeric_id_desc";
    static final String MARKER_COLLECTION = "migration_marker";
    static final String TIME_MARKER_ID = "message-time-utc";
    public static final String FIXED_FLAG = "tzFixed";
    static final long NINE_HOURS_MS = 9L * 60 * 60 * 1000;

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public ChatMessageMaintenance(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    ChatMessageMaintenance(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-message-maintenance");
            t.setDaemon(true);
            return t;
        });
        worker.execute(() -> {
            quietly("인덱스", this::ensureRoomIndex);
            quietly("시각 보정", this::fixLegacyTimes);
        });
        worker.schedule(() -> quietly("시각 보정(2차)", this::fixLegacyTimes), 15, TimeUnit.MINUTES);
        worker.shutdown();   // 예약된 두 번째 실행까지 마치면 스레드가 끝난다
    }

    private static void quietly(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 실패해도 서비스는 계속한다 — 인덱스가 없으면 느릴 뿐이고, 보정은 다음 기동에 다시 돈다.
            log.warn("대화 기록 정비({}) 실패 — 다음 기동에 다시 돈다: {}", what, e.getMessage());
        }
    }

    /** 방 기준 인덱스 — 조회와 같은 collation 으로. 이미 있으면 그대로. */
    public void ensureRoomIndex() {
        mongoTemplate.indexOps(ChatMessage.class).ensureIndex(
                new CompoundIndexDefinition(new Document("roomId", 1).append("_id", -1))
                        .named(INDEX_NAME)
                        .collation(Collation.of("en").numericOrdering(true)));
    }

    /**
     * 기준 시각보다 이르게 적힌(그리고 아직 보정하지 않은) 문서를 +9시간 옮긴다.
     *
     * @return 옮긴 문서 수
     */
    public long fixLegacyTimes() {
        long cutoff = cutoff();
        Document notANumber = new Document("$convert", new Document("input", "$createdAt").append("to", "long")
                .append("onError", Long.MAX_VALUE).append("onNull", Long.MAX_VALUE));
        Document filter = new Document(FIXED_FLAG, new Document("$exists", false))
                .append("$expr", new Document("$and", List.of(
                        new Document("$gt", List.of(notANumber, 0L)),
                        new Document("$lte", List.of(notANumber, cutoff)))));
        Document shift = new Document("$set", new Document("createdAt",
                new Document("$toString", new Document("$add", List.of(new Document("$toLong", "$createdAt"), NINE_HOURS_MS))))
                .append(FIXED_FLAG, true));

        long moved = mongoTemplate.getCollection(mongoTemplate.getCollectionName(ChatMessage.class))
                .updateMany(filter, List.of(shift)).getModifiedCount();
        if (moved > 0) {
            log.info("옛 메시지 시각 보정: {}건을 +9시간 옮겼다(기준 시각 {})", moved, cutoff);
        }
        return moved;
    }

    /** 기준 시각 — 처음 부른 파드가 정하고, 그 뒤로는 모두 같은 값을 읽는다(원자적 upsert). */
    long cutoff() {
        Document marker = mongoTemplate.getCollection(MARKER_COLLECTION).findOneAndUpdate(
                new Document("_id", TIME_MARKER_ID),
                Updates.setOnInsert("cutoff", clock.millis()),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return marker.get("cutoff", Number.class).longValue();
    }
}
