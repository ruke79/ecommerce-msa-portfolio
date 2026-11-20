package com.project.notification_service.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 전체 알림에 대한 <b>개인별</b> 상태 — 읽었는가, 지웠는가.
 *
 * <p>행은 <b>사용자가 실제로 무언가를 했을 때만</b> 생긴다. 안 읽은 상태는 행이 없는 것으로
 * 표현하므로, 공지 하나를 올려도 즉시 생기는 행은 0개다(BroadcastNotification 주석).
 *
 * <p>복합 키({@code broadcastId} + {@code recipientId})라 같은 사람이 같은 공지에 대해
 * 두 행을 가질 수 없다. 읽음 처리가 여러 번 들어와도 결과가 같다.
 *
 * <p>[JA] 全体通知に対する<b>個人別</b>の状態 — 読んだか、消したか。
 * 行は<b>ユーザーが実際に何かをしたときだけ</b>できる。未読状態は行がないことで表すので、
 * お知らせを一つ上げても即座にできる行は 0 個である(BroadcastNotification のコメント)。
 * 複合キー({@code broadcastId} + {@code recipientId})なので同じ人が同じお知らせについて
 * 二行を持てない。既読処理が何度来ても結果は同じである。
 */
@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(BroadcastRead.Key.class)
@Table(name = "broadcast_read", schema = "notification",
       indexes = { @Index(columnList = "recipient_id", name = "idx_broadcast_read_recipient") })
public class BroadcastRead implements Persistable<BroadcastRead.Key> {

    @Id
    @Column(name = "broadcast_id")
    private Long broadcastId;

    @Id
    @Column(name = "recipient_id")
    private Long recipientId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * 사용자가 목록에서 지웠는가.
     *
     * <p>공지 자체는 지울 수 없다 — 모두가 공유하는 행이다. "이 사람에게는 보이지 않는다"로
     * 표현한다. 읽음과 따로 두는 이유: 읽었지만 목록에 남겨 두는 것과, 치워 버린 것은
     * 사용자에게 다른 동작이다.
     *
     * <p>[JA] ユーザーが一覧から消したか。
     * お知らせ自体は消せない — 皆が共有する行である。「この人には見えない」で表す。
     * 既読と別にする理由: 読んだが一覧に残しておくことと、片付けてしまうことはユーザーにとって別の動作である。
     */
    @Column(nullable = false)
    private boolean dismissed;

    @PrePersist
    void onCreate() {
        if (readAt == null) {
            readAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // 키를 코드가 배정한다(공지 id + 사용자 id). 그래서 save() 시점에 id 가 이미 null 이
    // 아니고, SimpleJpaRepository 는 그것만 보고 persist() 가 아니라 **merge()** 를 고른다.
    // merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던지는데, 바로 앞 줄에서
    // findByBroadcastIdAndRecipientId 로 같은 행을 이미 확인한 뒤라 완전히 같은 조회가 두
    // 번 나간다 — 알림을 하나 읽을 때마다.
    //
    // 읽음 처리는 사용자가 종을 눌러 목록을 훑는 동안 연달아 일어나는 동작이라, 한 번에
    // 하나씩 늘어나는 이 헛SELECT 가 그대로 쌓인다.
    //
    // (common 의 EntityPersistenceGuardrailTest 가 이 조건을 강제한다.)
    // [JA] ── なぜ Persistable を実装するか ──
    //      
    //      キーをコードが割り当てる(お知らせ id + ユーザー id)。だから save() の時点で id が既に null ではなく、
    //      SimpleJpaRepository はそれだけを見て persist() ではなく **merge()** を選ぶ。
    //      merge は付ける対象を探そうと先に SELECT を投げるが、直前の行で findByBroadcastIdAndRecipientId で
    //      同じ行を既に確認した後なので、まったく同じ照会が二度出る — 通知を一つ読むたびに。
    //      
    //      既読処理はユーザーがベルを押して一覧を眺める間に連続して起きる動作なので、
    //      一回ごとに一つずつ増えるこの無駄な SELECT がそのまま積もる。
    //      
    //      (common の EntityPersistenceGuardrailTest がこの条件を強制する。)
    @Transient
    private boolean persisted;

    @Override
    public Key getId() {
        return new Key(broadcastId, recipientId);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    /**
     * 불러온 행은 신규가 아니다.
     *
     * <p>{@code @PostLoad} 를 빼면 이미 있는 읽음 행을 다시 저장할 때 UPDATE 가 아니라
     * INSERT 를 시도해 PK 충돌로 죽는다 — 같은 공지를 두 번 읽음 처리하는 흔한 경로가
     * 그대로 500 이 된다.
     *
     * <p>[JA] 読み込んだ行は新規ではない。
     * {@code @PostLoad} を外すと、既存の既読行を再保存するときに UPDATE ではなく INSERT を試みて PK 衝突で落ちる —
     * 同じお知らせを二度既読処理するよくある経路がそのまま 500 になる。
     */
    @PostPersist
    @PostLoad
    private void markPersisted() {
        this.persisted = true;
    }

    /** 복합 키. JPA 가 요구한다. */
    // [JA] 複合キー。JPA が要求する。
    public static class Key implements Serializable {
        private Long broadcastId;
        private Long recipientId;

        public Key() {
        }

        public Key(Long broadcastId, Long recipientId) {
            this.broadcastId = broadcastId;
            this.recipientId = recipientId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(broadcastId, other.broadcastId)
                    && Objects.equals(recipientId, other.recipientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(broadcastId, recipientId);
        }
    }
}
