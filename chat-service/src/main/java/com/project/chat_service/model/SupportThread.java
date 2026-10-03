package com.project.chat_service.model;

import java.time.Instant;

import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.constants.SupportTopic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객 ↔ 고객지원팀 상담 스레드 (docs/58, V7).
 *
 * <p>메시지 본문은 친구 채팅과 같은 MongoDB 컬렉션에 {@link #roomId()} 로 쌓인다. 여기는 상태·담당자·목록 요약이다.
 * 회원은 id 로만 든다 — 연관을 걸면 목록 한 번에 회원 행을 줄줄이 읽는다.
 *
 * <p>🐛 {@code @DynamicUpdate} — 바뀐 컬럼만 UPDATE 한다. 예전에는 Hibernate 가 <b>모든 컬럼</b>을 다시 썼다. 고객의 메시지
 * ({@link #recordMessage})가 스레드를 읽은 뒤 커밋하기 전에 상담원이 조건부 UPDATE 로 맡으면({@code claim}), 메시지 쪽 커밋이
 * 읽어 둔 {@code status=WAITING · agent_id=null} 을 그대로 다시 써 <b>배정이 사라졌다</b> — 상담원에게는 "맡았다", 고객에게는
 * "상담원이 들어왔다" 안내까지 갔는데 스레드는 대기열로 돌아갔다. 내부 메모와 메시지 요약도 서로를 덮었다.
 * [JA] 変更した列だけを UPDATE する。以前は全列を書き戻し、メッセージ受信と同時に担当者が引き受ける(claim)と、
 * 読み込んだ時点の WAITING・担当者なしで上書きして割り当てが消えていた。
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "support_thread", schema = "chat")
public class SupportThread {

    /** 상담 메시지의 방 id 접두사. 친구 채팅방의 id 는 UUID 라 겹치지 않는다. */
    public static final String ROOM_PREFIX = "support-";

    /** 목록에 보일 마지막 메시지 요약의 길이(V7 의 컬럼 길이). */
    static final int PREVIEW_LENGTH = 200;

    /** 평가 의견 · 내부 메모의 최대 길이(V8 의 컬럼 길이). */
    public static final int RATING_COMMENT_LENGTH = 500;
    public static final int NOTE_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "thread_id")
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "agent_id")
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SupportThreadStatus status;

    @Column(name = "awaiting_reply", nullable = false)
    private boolean awaitingReply;

    @Column(name = "last_message_preview", length = PREVIEW_LENGTH)
    private String lastMessagePreview;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 문의 유형(V8). 연 뒤에는 바뀌지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private SupportTopic topic = SupportTopic.OTHER;

    /** 만족도(1~5) — 종료된 상담에 고객이 한 번(V8). */
    @Column(name = "rating")
    private Short rating;

    @Column(name = "rating_comment", length = RATING_COMMENT_LENGTH)
    private String ratingComment;

    @Column(name = "rated_at")
    private Instant ratedAt;

    /** 🔒 상담원끼리만 보는 메모(V8) — 고객 응답에 싣지 않는다. */
    @Column(name = "internal_note", length = NOTE_LENGTH)
    private String internalNote;

    @Column(name = "note_updated_at")
    private Instant noteUpdatedAt;

    public static SupportThread open(Long customerId, Instant now) {
        return open(customerId, SupportTopic.OTHER, now);
    }

    public static SupportThread open(Long customerId, SupportTopic topic, Instant now) {
        SupportThread t = new SupportThread();
        t.customerId = customerId;
        t.status = SupportThreadStatus.WAITING;
        t.topic = topic == null ? SupportTopic.OTHER : topic;
        t.createdAt = now;
        return t;
    }

    /** 이 스레드의 메시지가 쌓이는 방 id — STOMP 구독 주소 {@code /sub/chat/room/<roomId>} 에도 쓰인다. */
    public String roomId() {
        return ROOM_PREFIX + id;
    }

    /**
     * 방 id 가 상담 스레드의 것이면 스레드 번호, 아니면 null.
     *
     * <p>🐛 {@link #roomId()} 가 만드는 모양({@code support-<번호>}) 그대로일 때만 번호다. 예전에는 {@code support-+7} ·
     * {@code support-007} 도 7 로 읽었다 — 접근 검사는 7번 스레드로 통과하고, 메시지는 다른 방 id 로 저장 · 전송돼 상담 기록과
     * 구독 화면 어디에도 나타나지 않았다. [JA] roomId() と同じ形のときだけ番号として扱う。
     */
    public static Long threadIdOf(String roomId) {
        if (roomId == null || !roomId.startsWith(ROOM_PREFIX)) {
            return null;
        }
        try {
            long id = Long.parseLong(roomId.substring(ROOM_PREFIX.length()));
            return roomId.equals(ROOM_PREFIX + id) ? id : -1L;
        } catch (NumberFormatException e) {
            return -1L;   // 접두사는 맞는데 번호가 아니다 — 없는 스레드로 다룬다(구독·조회가 거절된다)
        }
    }

    /** 방 id 가 상담 스레드의 것인가(번호가 올바르지 않아도 참 — 공개 방 경로로 새면 안 된다). */
    public static boolean isSupportRoom(String roomId) {
        return roomId != null && roomId.startsWith(ROOM_PREFIX);
    }

    public boolean isOpen() {
        return status != SupportThreadStatus.CLOSED;
    }

    public boolean isCustomer(Long userId) {
        return userId != null && userId.equals(customerId);
    }

    public void close(Instant now) {
        this.status = SupportThreadStatus.CLOSED;
        this.closedAt = now;
        this.awaitingReply = false;
    }

    /** 평가가 끝났는가. */
    public boolean isRated() {
        return rating != null;
    }

    /** 만족도를 남긴다 — 검증(종료 · 본인 · 한 번 · 범위)은 서비스가 먼저 한다. */
    public void rate(int score, String comment, Instant now) {
        this.rating = (short) score;
        this.ratingComment = comment == null || comment.isBlank() ? null : comment.strip();
        this.ratedAt = now;
    }

    /** 내부 메모를 바꾼다. 빈 글이면 지운다. */
    public void updateNote(String note, Instant now) {
        this.internalNote = note == null || note.isBlank() ? null : note.strip();
        this.noteUpdatedAt = now;
    }

    /**
     * 상담원이 상담을 반납한다 — 다시 대기열로(담당자 없음). V7 의 CHECK(대기 중이면 담당자 없음)를 지킨다.
     * "답변 필요" 는 그대로 둔다 — 다음 상담원이 고객의 마지막 말에 답해야 하는지 알아야 한다.
     */
    public void release() {
        this.status = SupportThreadStatus.WAITING;
        this.agentId = null;
        this.assignedAt = null;
    }

    /** 메시지가 오갔다 — 목록 요약과 "답변 필요" 를 갱신한다. */
    public void recordMessage(String content, boolean fromCustomer, Instant now) {
        String text = content == null ? "" : content.strip();
        // 코드 포인트 경계에서 자른다 — char 로 자르면 이모지(서로게이트 쌍)가 반으로 갈라져 목록에 깨진 글자가 찍혔다.
        this.lastMessagePreview = text.length() > PREVIEW_LENGTH ? truncate(text, PREVIEW_LENGTH - 1) + "…" : text;
        this.lastMessageAt = now;
        this.awaitingReply = fromCustomer;
    }

    /** 앞에서부터 최대 {@code maxChars} 개의 char 안에 들어가는 코드 포인트까지. */
    static String truncate(String text, int maxChars) {
        int end = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), maxChars));
        while (end > maxChars) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end);
    }
}
