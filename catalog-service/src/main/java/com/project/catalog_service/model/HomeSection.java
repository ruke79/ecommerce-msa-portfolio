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
 * 홈 화면의 한 섹션.
 *
 * <p>예전에는 이 정보가 프론트엔드 코드에 있었다 — 카테고리 다섯 개가 두 파일에 각각
 * 다섯 줄씩 박혀 있었고, 어드민에서 카테고리를 만들어도 홈에는 나오지 않았다.
 *
 * <p><b>노출 판정이 {@code published} 하나가 아닌 이유는 배너와 같다.</b> 기획전 섹션은
 * 기간이 정해져 있고, 사람이 시각에 맞춰 체크박스를 켜고 끄면 반드시 놓친다.
 * {@code startsAt}/{@code endsAt} 이 둘 다 null 이면 "기간 제한 없음"이다.
 *
 * <p><b>카테고리를 이름으로 가리킨다.</b> 대상이 사라지면 그 섹션만 조용히 빠지고 나머지
 * 홈은 그대로 뜬다 — 첫 화면에서는 그쪽이 맞다. 대신 어드민 화면이 "가리키는 카테고리가
 * 없다"를 표시한다(V10 주석 참조).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "home_section", schema = "content")
public class HomeSection {

    /** 무엇을 기준으로 상품을 고르는가. */
    public enum Kind {
        CATEGORY, SUBCATEGORY
    }

    /** 화면에서의 생김새. GRID 는 상단 격자 카드, SWIPER 는 가로 스와이퍼. */
    public enum Layout {
        GRID, SWIPER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "section_id")
    private Long sectionId;

    /** 화면에 보이는 제목. 카테고리 이름과 달라도 된다("가전" → "새로 나온 가전"). */
    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    /** {@code kind=SUBCATEGORY} 일 때만 쓴다. */
    @Column(name = "subcategory_name", length = 100)
    private String subcategoryName;

    @Column(nullable = false, length = 20)
    private String layout;

    @Column(name = "item_limit", nullable = false)
    private int itemLimit;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
     * 이 시각에 노출되는가. 경계는 <b>시작 포함, 종료 포함</b>이다(배너와 같은 규칙).
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

    public boolean isSubcategorySection() {
        return Kind.SUBCATEGORY.name().equals(kind)
                && subcategoryName != null && !subcategoryName.isBlank();
    }
}
