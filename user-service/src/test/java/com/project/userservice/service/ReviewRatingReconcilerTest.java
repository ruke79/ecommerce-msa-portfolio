package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.repository.jpa.ReviewRepository;

/**
 * 평점 대사 — 리뷰에서 평균을 다시 계산해 보낸다. 반영이 한 번 실패한 상품도 다음 날에는 맞는다(docs/57 §13).
 */
@DisplayName("평점 대사")
class ReviewRatingReconcilerTest {

    @Test
    @DisplayName("🔴 모든 상품의 평균을 보내고, 한 상품의 실패가 나머지를 막지 않는다")
    void sendsAllAndSurvivesFailure() {
        ReviewRepository repo = mock(ReviewRepository.class);
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        when(repo.averageRatingByProduct()).thenReturn(List.of(
                new Object[] { 1L, 4.5d }, new Object[] { 2L, 3.0d }, new Object[] { 3L, 5.0d }));
        doThrow(new RuntimeException("catalog down")).when(catalog).updateRating(eq(2L), eq(3.0F));

        ReviewRatingReconciler.Result r = new ReviewRatingReconciler(repo, catalog).reconcile();

        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        verify(catalog).updateRating(1L, 4.5F);
        verify(catalog).updateRating(3L, 5.0F);
    }
}
