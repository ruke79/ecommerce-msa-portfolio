package com.project.userservice.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;


/**
 * 🐛 인자 없는 생성자가 없었다({@code @SuperBuilder} 만). JPA 는 DB 에서 읽은 행으로 엔티티를 만들 때 그 생성자를 쓴다 — 쓰기(빌더로
 * 만들어 저장)는 되고 <b>읽기</b>가 전부 실패했다. 상품에 첫 리뷰가 저장되는 순간부터 그 상품의 리뷰 목록이 400
 * ("리뷰를 불러오지 못했습니다")이 됐고, 같은 상품에 리뷰를 다시 쓰거나 고치는 것도 막혔다(B-69).
 */
@Setter
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 인덱스는 Flyway 가 소유한다 — 예전 @Index(rating, review, size, fit)는 쓰는 쿼리가 없었고, 긴 리뷰를 B-tree 한도로 막았다(V16).
@Table(name="review", schema="users",
         indexes = {
          @Index(columnList = "product_id", name = "idx_review_product") }
)
public class Review extends BaseEntity{
    
    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long reviewId;

    @Builder.Default
    private float rating = 0F;

    /** 화면·서버 검증(ReviewService.validate)이 2,000자까지 받는다 — 컬럼은 255자였다(V14). */
    @Column(length = 2000)
    private String review;

    private String size;

    private String fit;

    private List<String> images;
    
    @OneToOne(fetch=FetchType.LAZY, cascade=CascadeType.PERSIST, targetEntity=ReviewStyle.class)
    @JoinColumn(name="rstyle_id", referencedColumnName = "rstyle_id", nullable = false)
    private ReviewStyle style;

    
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="user_id", referencedColumnName = "user_id", nullable = false)    
    @JsonBackReference
    private User reviewedBy;    
    

    //@JsonBackReference
    // @ManyToOne(fetch=FetchType.LAZY)
    // @JoinColumn(name="product_id", referencedColumnName = "product_id", nullable=false)
    // private Product product;
    private Long productId;
    

}
