package com.project.chat_service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Collation;
import com.project.chat_service.model.ChatMessage;

/** 대화 기록 정비 — 방 기준 인덱스와 옛 메시지 시각 보정을 실제 MongoDB 에서. */
@Tag("containers")
@Testcontainers
@DisplayName("대화 기록 정비 (MongoDB)")
class ChatMessageMaintenanceTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    static MongoClient client;
    static MongoTemplate template;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(mongo.getConnectionString());
        template = new MongoTemplate(client, "chat");
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @BeforeEach
    void clean() {
        template.getCollection("chat").drop();
        template.getCollection(ChatMessageMaintenance.MARKER_COLLECTION).drop();
    }

    private static final long CUTOFF = Instant.parse("2026-10-04T00:00:00Z").toEpochMilli();
    private static final long NINE_H = 9L * 3600 * 1000;

    private ChatMessageMaintenance at(long millis) {
        return new ChatMessageMaintenance(template, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    private void doc(String id, String createdAt) {
        template.getCollection("chat").insertOne(new Document("_id", id).append("roomId", "r").append("createdAt", createdAt));
    }

    private String createdAt(String id) {
        return template.getCollection("chat").find(new Document("_id", id)).first().getString("createdAt");
    }

    @Test
    @DisplayName("기준 시각 전의 문서만 +9시간 — 한 번만, 고친 코드가 쓴 문서와 형식이 깨진 문서는 그대로")
    void shiftsLegacyMessagesOnce() {
        long old = CUTOFF - 60_000;
        long older = CUTOFF - 10 * 3600_000L;   // 9시간 넘게 이른 문서 — 옮긴 뒤에도 기준 전이라, 표시가 없으면 또 옮겨진다
        doc("1", Long.toString(old));
        doc("5", Long.toString(older));
        doc("2", Long.toString(CUTOFF + 1));   // 고친 코드가 기준 뒤에 쓴 것
        doc("3", "not-a-number");
        doc("4", null);

        assertThat(at(CUTOFF).fixLegacyTimes()).isEqualTo(2);
        assertThat(createdAt("1")).isEqualTo(Long.toString(old + NINE_H));
        assertThat(createdAt("5")).isEqualTo(Long.toString(older + NINE_H));
        assertThat(createdAt("2")).isEqualTo(Long.toString(CUTOFF + 1));
        assertThat(createdAt("3")).isEqualTo("not-a-number");

        // 다시 돌아도(다른 파드 · 다음 기동) 두 번 옮기지 않는다 — 기준 시각도 처음 정한 값 그대로다.
        assertThat(at(CUTOFF + NINE_H * 3).fixLegacyTimes()).isZero();
        assertThat(createdAt("1")).isEqualTo(Long.toString(old + NINE_H));
        assertThat(createdAt("5")).isEqualTo(Long.toString(older + NINE_H));
        assertThat(createdAt("2")).isEqualTo(Long.toString(CUTOFF + 1));
    }

    @Test
    @DisplayName("롤링 배포 중 옛 파드가 늦게 쓴 문서(9시간 이른 값)도 다음 실행이 같은 기준으로 잡는다")
    void laterRunCatchesRolloutStragglers() {
        at(CUTOFF).fixLegacyTimes();   // 기준 시각을 정한다
        long straggler = CUTOFF + 120_000 - NINE_H;   // 기준 2분 뒤, 옛 파드가 쓴 값
        doc("9", Long.toString(straggler));

        assertThat(at(CUTOFF + 900_000).fixLegacyTimes()).isEqualTo(1);
        assertThat(createdAt("9")).isEqualTo(Long.toString(straggler + NINE_H));
    }

    @Test
    @DisplayName("방 기준 인덱스가 조회와 같은 collation 으로 만들어지고, 방의 최신 메시지 조회가 그 인덱스를 쓴다")
    void roomIndexIsUsedByTheNumericQuery() {
        for (int i = 1; i <= 30; i++) {
            doc(Integer.toString(i), "1");
        }
        at(CUTOFF).ensureRoomIndex();
        at(CUTOFF).ensureRoomIndex();   // 두 번 불러도 된다

        Document plan = template.getCollection(template.getCollectionName(ChatMessage.class))
                .find(new Document("roomId", "r"))
                .sort(new Document("_id", -1))
                .collation(Collation.builder().locale("en").numericOrdering(true).build())
                .limit(20)
                .explain();
        assertThat(plan.toJson()).contains("IXSCAN").contains(ChatMessageMaintenance.INDEX_NAME);
        assertThat(List.copyOf(template.getCollection("chat").listIndexes().into(new java.util.ArrayList<>())))
                .anySatisfy(ix -> assertThat(ix.getString("name")).isEqualTo(ChatMessageMaintenance.INDEX_NAME));
    }
}
