package com.project.userservice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 리뷰 id 는 <b>문자열</b>로 나간다.
 *
 * <p>🐛 TSID(18~19자리)를 숫자로 내보냈다. JS 숫자는 2^53(약 9×10^15)까지만 정확해서 브라우저가 끝자리를 반올림했고, 좋아요가
 * 없는 리뷰 id 로 가서 404 — 화면은 낙관적으로 올린 1 을 0 으로 되돌렸다(작성자·운영자 모두).
 */
@DisplayName("리뷰 id — JSON 에서 문자열")
class ReviewIdJsonTest {

    private static final long TSID = 891579158660068589L;   // 2^53 을 넘는 실제 크기의 id
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("🔴 목록의 reviewId 는 따옴표로 — 반올림되지 않는다")
    void reviewDtoWritesIdAsString() throws Exception {
        String json = mapper.writeValueAsString(ReviewDto.builder().reviewId(TSID).build());
        assertThat(json).contains("\"reviewId\":\"891579158660068589\"");
    }

    @Test
    @DisplayName("🔴 좋아요 응답의 reviewId 도 문자열 — 화면이 목록의 id 와 같은 값으로 맞춘다")
    void likeDtoWritesIdAsString() throws Exception {
        String json = mapper.writeValueAsString(new ReviewLikeDto(TSID, 1, true));
        assertThat(json).contains("\"reviewId\":\"891579158660068589\"").contains("\"likeCount\":1").contains("\"likedByMe\":true");
    }
}
