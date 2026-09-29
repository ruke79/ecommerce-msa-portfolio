package com.project.catalog_service.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공지사항.
 *
 * <p><b>{@code publishedAt} 이 {@code createdAt} 과 별개인 이유.</b> 점검 공지는 보통 미리
 * 써 두고 나중에 공개한다. 작성 시각을 그대로 노출하면 "3일 전에 쓴 글이 오늘 올라왔다"가
 * 되어 목록 정렬이 어긋난다. 공개할 때 찍고, 그 값으로 정렬한다.
 *
 * <p><b>{@code pinned}.</b> 점검 공지처럼 최신이 아니어도 맨 위에 있어야 하는 글이 있다.
 * 정렬이 "고정 먼저, 그다음 최신순"이다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notice", schema = "content")
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long noticeId;

    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 본문. 길이를 예측할 수 없어 text 다 — varchar(n) 으로 잡으면 언젠가 n 을 늘리는
     * 마이그레이션을 하게 되고, 그동안 운영자는 글자 수를 세야 한다.
     */
    @Lob
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Builder.Default
    @Column(nullable = false)
    private boolean pinned = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 공개 여부를 바꾼다.
     *
     * <p>공개로 <b>처음</b> 전환할 때만 {@code publishedAt} 을 찍는다. 오타를 고치려고
     * 비공개→공개를 반복할 때마다 시각을 갱신하면 그때마다 목록 맨 위로 올라온다.
     */
    public void setPublishedState(boolean next) {
        if (next && publishedAt == null) {
            publishedAt = LocalDateTime.now();
        }
        this.published = next;
    }
}
