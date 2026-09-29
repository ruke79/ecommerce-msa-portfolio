package com.project.catalog_service.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 홈 화면 배너.
 *
 * <p><b>노출 판정이 published 하나가 아닌 이유.</b> 기획전 배너는 "금요일 0시부터 일요일
 * 자정까지"처럼 기간이 정해져 있다. 사람이 시각에 맞춰 체크박스를 켜고 끄는 방식은 반드시
 * 놓친다. {@link #isVisibleAt} 이 서버 시각으로 판정한다.
 *
 * <p>{@code startsAt}/{@code endsAt} 이 둘 다 null 이면 "기간 제한 없음"이다 — 상시 배너는
 * published 만 켜면 되고, 기간 개념을 몰라도 쓸 수 있다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "banner", schema = "content")
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "banner_id")
    private Long bannerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "image_url", nullable = false, length = 1000)
    private String imageUrl;

    /** 클릭했을 때 갈 곳. 비워 두면 클릭되지 않는 배너다(공지성 이미지). */
    @Column(name = "link_url", length = 1000)
    private String linkUrl;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 이 시각에 노출되는가.
     *
     * <p>경계는 <b>시작 포함, 종료 포함</b>이다. "23:59:59 까지"를 운영자가 직접 계산하지
     * 않아도 되도록, 종료 시각을 자정으로 넣으면 그 자정까지 보인다.
     */
    public boolean isVisibleAt(LocalDateTime now) {
        if (!published) {
            return false;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || !now.isAfter(endsAt);
    }
}
