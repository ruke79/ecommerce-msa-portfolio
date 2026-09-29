package com.project.userservice.dto;

/**
 * 좋아요 토글 결과.
 *
 * <p>화면이 필요한 것만 담는다. 클라이언트는 낙관적으로 먼저 그린 뒤 이 응답으로 덮어쓰므로,
 * <b>서버가 센 최종 수치</b>를 그대로 돌려주는 것이 중요하다 — 그 사이 다른 사람이 눌렀다면
 * 클라이언트의 +1/-1 계산은 이미 틀렸다.
 *
 * @param reviewId  대상 리뷰
 * @param likeCount 토글 후 총 좋아요 수
 * @param likedByMe 토글 후 이 사용자가 누른 상태인지
 */
public record ReviewLikeDto(
        // TSID 는 JS 숫자를 넘는다 — 문자열로(ReviewDto.reviewId 주석)
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        Long reviewId,
        long likeCount,
        boolean likedByMe) {
}

