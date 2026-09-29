package com.project.userservice.model;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name="review_style", schema="users",
         indexes = {
          @Index(columnList = "color, image", name = "idx_review_style") }       
)
public class ReviewStyle extends BaseEntity {

    @Id
    @Tsid
    // @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rstyle_id")
    private Long rstyleId;

    private String color;

    private String image;

}
