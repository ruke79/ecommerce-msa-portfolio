package com.project.userservice.dto;

import java.util.List;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ReviewDto {

    /**
     * 지금까지 클라이언트에는 리뷰를 <b>가리킬 방법이 없었다</b>. 목록의 배열 인덱스를
     * key 로 쓰고 있었을 뿐이라(Table.jsx), "이 리뷰에 좋아요"처럼 한 건을 지목하는 동작은
     * 만들 수가 없었다. 좋아요의 전제 조건이다.
     */
    // 🐛 TSID(18~19자리)는 JS 숫자(2^53)를 넘는다 — 숫자로 내보내면 브라우저가 끝자리를 반올림해, 좋아요가 없는 리뷰 id 로 가서
    // 404 → 화면이 되돌렸다("눌렀더니 1 → 0"). 문자열로 내보낸다(받는 쪽은 경로에 그대로 싣는다).
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long reviewId;

    private String size;
    private ReviewStyleDto style;
    private String fit;
    private float rating;
    private String review;

    private List<String> images;

    private ReviewerDto reviewedBy;

    /** 이 리뷰의 총 좋아요 수. */
    private long likeCount;

    /** 지금 보고 있는 사람이 눌렀는지. 비로그인 조회면 항상 false. */
    private boolean likedByMe;

    /**
     * 조회한 사람이 쓴 리뷰인가. 화면은 이것으로 "삭제" 버튼을 그릴지 정한다 — 예전에는 리뷰가 없어도 버튼이 늘 있었고,
     * 누르면 서버가 성공이라고 답해 "삭제되었습니다" 가 떴다.
     */
    private boolean mine;

}
