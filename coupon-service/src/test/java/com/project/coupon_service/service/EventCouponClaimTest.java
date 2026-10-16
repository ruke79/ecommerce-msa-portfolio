package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.project.common.constants.coupon.CouponScope;
import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.CatalogServiceClient.EventCouponOffer;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;
import com.project.coupon_service.service.CouponService.ClaimResult;

/**
 * 기획전 쿠폰 수령.
 *
 * <p><b>왜 나눠 주지 않고 받아 가게 하나.</b> 기획전이 시작될 때 전 사용자에게 발급하면
 * 사용자 수만큼 coupon 행이 생긴다 — 공지 알림에서 개인별 행을 만들지 않기로 한 것과 같은
 * 문제이고, 게다가 대부분은 쓰지 않는다. 받아 간 사람만 행이 생기게 두면 쓰기량이 실제
 * 관심에 비례한다.
 *
 * <p>여기서 지키는 것의 핵심은 <b>못 받는 경우에 조용히 주지 않는 것</b>이다. 세그먼트를
 * 확인하지 못했을 때 통과시키면 대상이 아닌 사람에게 쿠폰이 나가고, 그건 되돌리기 어렵다.
 */
@DisplayName("기획전 쿠폰 수령")
class EventCouponClaimTest {

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final CouponTargetRepository couponTargetRepository = mock(CouponTargetRepository.class);
    private final UserServiceClient userServiceClient = mock(UserServiceClient.class);
    private final CartServiceClient cartServiceClient = mock(CartServiceClient.class);
    private final CatalogServiceClient catalogServiceClient = mock(CatalogServiceClient.class);
    private final CouponUseProducer couponUseProducer = mock(CouponUseProducer.class);
    private final CouponIssuedProducer couponIssuedProducer = mock(CouponIssuedProducer.class);

    private final CouponService service = new CouponService(
            couponRepository, couponTargetRepository, userServiceClient,
            cartServiceClient, catalogServiceClient, couponUseProducer, couponIssuedProducer);

    private static final String EMAIL = "tester@example.com";
    /** 게이트웨이가 검증한 신원 — 토큰의 uid(User-Id 헤더). */
    private static final Long USER_ID = 7L;

    /** 기획전이 이 조건의 쿠폰을 내건다. */
    private void offers(String audience, String scope, List<String> targets) {
        when(catalogServiceClient.getEventCouponOffer("autumn")).thenReturn(ResponseEntity.ok(
                new EventCouponOffer(7L, "autumn", "가을 세일", audience,
                        "가을 추가 10%", 10, 7, scope, targets)));
    }

    /** user-service 가 이 사용자를 이 세그먼트로 판정한다. */
    private void segments(String... names) {
        when(userServiceClient.findUserSegments(anyLong()))
                .thenReturn(ResponseEntity.ok(Map.of("segments", List.of(names))));
    }

    /** 사용자 조회와 저장이 통하는 상태 — create() 가 끝까지 가도록. */
    private void issuingWorks() {
        when(couponRepository.existsByNameAndUserId(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(false);
        when(couponRepository.save(any(Coupon.class))).thenAnswer(i -> {
            Coupon c = i.getArgument(0);
            c.setCouponId(99L);
            return c;
        });
    }

    @Nested
    @DisplayName("대상 판정")
    class Eligibility {

        @Test
        @DisplayName("전체 대상이면 세그먼트를 묻지 않는다 — 쓸데없는 왕복을 만들지 않는다")
        void allAudienceSkipsTheLookup() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            issuingWorks();

            assertThat(service.claimEventCoupon("autumn", USER_ID).status()).isEqualTo("ISSUED");
            verify(userServiceClient, never()).findUserSegments(anyLong());
        }

        @Test
        @DisplayName("대상 세그먼트면 발급한다")
        void matchingSegmentGetsTheCoupon() {
            offers("NEW_USER", CouponScope.ALL.name(), List.of());
            segments("NEW_USER");
            issuingWorks();

            assertThat(service.claimEventCoupon("autumn", USER_ID).status()).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("대상이 아니면 발급하지 않는다")
        void otherSegmentIsRejected() {
            offers("NEW_USER", CouponScope.ALL.name(), List.of());
            segments("EXISTING_USER");

            ClaimResult result = service.claimEventCoupon("autumn", USER_ID);

            assertThat(result.status()).isEqualTo("NOT_ELIGIBLE");
            assertThat(result.ok()).isFalse();
            verify(couponRepository, never()).save(any());
        }

        /**
         * 🔴 세그먼트를 확인하지 못했을 때 통과시키면 대상이 아닌 사람에게 쿠폰이 나간다.
         * 발급은 되돌리기 어렵다 — 못 주는 쪽으로 틀리는 편이 낫다.
         */
        @Test
        @DisplayName("세그먼트를 확인하지 못하면 주지 않는다")
        void segmentLookupFailureMeansNoCoupon() {
            offers("NEW_USER", CouponScope.ALL.name(), List.of());
            when(userServiceClient.findUserSegments(anyLong()))
                    .thenThrow(new RuntimeException("user-service down"));

            assertThat(service.claimEventCoupon("autumn", USER_ID).status())
                    .isEqualTo("NOT_ELIGIBLE");
            verify(couponRepository, never()).save(any());
        }

        /** 어떤 세그먼트에 속하는지 알려주면 가입 시점을 되짚을 수 있다. */
        @Test
        @DisplayName("왜 대상이 아닌지는 알려주지 않는다")
        void rejectionDoesNotLeakTheSegment() {
            offers("NEW_USER", CouponScope.ALL.name(), List.of());
            segments("EXISTING_USER");

            String message = service.claimEventCoupon("autumn", USER_ID).message();

            assertThat(message).doesNotContain("NEW_USER").doesNotContain("EXISTING_USER");
        }
    }

    @Nested
    @DisplayName("기획전 상태")
    class EventState {

        /**
         * 🔴 상태 판정을 이쪽에서 다시 하지 않는다 — 같은 규칙이 두 곳에 있으면 언젠가
         * 어긋나고, 그때 이미 종료된 기획전의 쿠폰이 계속 나간다. catalog 가 404 를 주면
         * 그것으로 끝이다.
         */
        @Test
        @DisplayName("기획전이 끝났으면(catalog 가 404) 발급하지 않는다")
        void endedEventGivesNothing() {
            when(catalogServiceClient.getEventCouponOffer("autumn"))
                    .thenThrow(new RuntimeException("404 Not Found"));

            ClaimResult result = service.claimEventCoupon("autumn", USER_ID);

            assertThat(result.status()).isEqualTo("UNAVAILABLE");
            verify(couponRepository, never()).save(any());
        }

        @Test
        @DisplayName("쿠폰 없는 기획전이면 발급하지 않는다")
        void eventWithoutACouponGivesNothing() {
            when(catalogServiceClient.getEventCouponOffer("autumn")).thenReturn(ResponseEntity.ok(
                    new EventCouponOffer(7L, "autumn", "가을 세일", "ALL",
                            null, 0, 7, CouponScope.ALL.name(), List.of())));

            assertThat(service.claimEventCoupon("autumn", USER_ID).status())
                    .isEqualTo("UNAVAILABLE");
        }

        /** 조건을 먼저 읽는다 — 반대로 하면 끝난 기획전에도 사용자 조회가 한 번 나간다. */
        @Test
        @DisplayName("기획전을 먼저 확인한다 — 끝난 기획전에 사용자 조회를 낭비하지 않는다")
        void checksTheEventBeforeTheUser() {
            when(catalogServiceClient.getEventCouponOffer("autumn"))
                    .thenThrow(new RuntimeException("404"));

            service.claimEventCoupon("autumn", USER_ID);

            verify(userServiceClient, never()).findUserSegments(anyLong());
        }
    }

    @Nested
    @DisplayName("발급")
    class Issuing {

        /**
         * 쿠폰의 적용 범위는 기획전 대상을 그대로 물려받아야 한다. 넓히면 "이 기획전의
         * 쿠폰" 이 무관한 상품에 먹어 관리자가 의도한 할인 폭을 넘는다.
         */
        @Test
        @DisplayName("기획전이 정한 범위와 대상을 그대로 쓴다")
        void inheritsScopeAndTargets() {
            offers("ALL", CouponScope.PRODUCT.name(), List.of("42", "43"));
            issuingWorks();

            service.claimEventCoupon("autumn", USER_ID);

            ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
            verify(couponRepository).save(saved.capture());
            assertThat(saved.getValue().scope()).isEqualTo(CouponScope.PRODUCT);
            // 대상 행도 함께 저장된다.
            verify(couponTargetRepository, org.mockito.Mockito.times(2)).save(any());
        }

        @Test
        @DisplayName("유효기간은 받은 날부터 센다 — 기획전 종료와 별개다")
        void validityStartsFromTheClaim() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            issuingWorks();

            service.claimEventCoupon("autumn", USER_ID);

            ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
            verify(couponRepository).save(saved.capture());
            assertThat(saved.getValue().getEndDate())
                    .isAfter(saved.getValue().getStartDate().plusDays(6));
        }

        /**
         * 사용자 입장에서 "이미 갖고 있다"는 성공과 다르지 않다 — 버튼을 두 번 눌렀거나
         * 다른 탭에서 이미 받은 것이다. 오류를 띄우면 무엇이 잘못됐는지 찾게 만든다.
         */
        @Test
        @DisplayName("이미 받았으면 오류가 아니다")
        void alreadyOwnedIsNotAnError() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            // 중복 수령은 coupon 테이블의 uk_coupon_name_user_id 가 이미 막는다.
            when(couponRepository.existsByNameAndUserId(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(true);

            ClaimResult result = service.claimEventCoupon("autumn", USER_ID);

            assertThat(result.status()).isEqualTo("ALREADY_OWNED");
            assertThat(result.ok()).isTrue();
            verify(couponRepository, never()).save(any());
        }

        @Test
        @DisplayName("발급되면 알림 이벤트가 나간다")
        void issuingAnnounces() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            issuingWorks();

            service.claimEventCoupon("autumn", USER_ID);

            verify(couponIssuedProducer).publish(any(Coupon.class));
        }

        /** 이름이 곧 키다 — 기획전이 정한 이름 그대로여야 중복 수령이 막힌다. */
        @Test
        @DisplayName("기획전이 정한 이름으로 발급한다")
        void usesTheOfferedName() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            issuingWorks();

            service.claimEventCoupon("autumn", USER_ID);

            ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
            verify(couponRepository).save(saved.capture());
            assertThat(saved.getValue().getName()).isEqualTo("가을 추가 10%");
        }

        /**
         * 🔒 받는 사람은 게이트웨이가 검증한 신원이어야 한다. 경로나 본문에서 받으면
         * 남의 계정으로 쿠폰을 발급시킬 수 있다.
         */
        @Test
        @DisplayName("넘겨받은 신원으로만 발급한다")
        void issuesToTheVerifiedIdentity() {
            offers("ALL", CouponScope.ALL.name(), List.of());
            issuingWorks();

            service.claimEventCoupon("autumn", USER_ID);

            ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
            verify(couponRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
            // 이메일로 id 를 다시 묻지 않는다 — 예전에는 그 호출의 URL 에 액세스 토큰이 실렸다.
            verify(userServiceClient, never()).findUserByEmail(anyString());
        }
    }

    /** 요청 본문이 아니라 기획전 조건이 쿠폰을 정한다 — 그 변환을 한 번 더 못박는다. */
    @Test
    @DisplayName("쿠폰 요청은 기획전 조건에서 만들어진다")
    void requestIsBuiltFromTheOffer() {
        offers("ALL", CouponScope.CATEGORY.name(), List.of("3"));
        issuingWorks();

        service.claimEventCoupon("autumn", USER_ID);

        ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).save(saved.capture());
        assertThat(saved.getValue().getDiscount()).isEqualTo(10);
        assertThat(saved.getValue().scope()).isEqualTo(CouponScope.CATEGORY);
    }

    /** 컴파일 시점에 CouponRequest 의 모양이 바뀌면 여기서 먼저 드러난다. */
    @Test
    @DisplayName("기획전 쿠폰은 제약 있는 요청으로 만들어진다")
    void offerMapsToAConstrainedRequest() {
        CouponRequest request = new CouponRequest("쿠폰", java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now().plusDays(7), 10, EMAIL,
                CouponScope.PRODUCT.name(), List.of("42"));

        assertThat(request.scopeOrAll()).isEqualTo(CouponScope.PRODUCT);
        assertThat(request.targetsOrEmpty()).containsExactly("42");
    }

    @Nested
    @DisplayName("리뷰 수정(2026-10-15)")
    class ReviewFixes {

        /** 🔴 CP3 — 모르는 고객군은 '전체' 가 아니다. 롤링 중 새 고객군 기획전의 쿠폰이 전원에게 발급되던 것. */
        @Test
        @DisplayName("🔴 모르는 고객군의 기획전 쿠폰은 발급하지 않는다 — 예전에는 ALL 로 읽어 전원에게 나갔다")
        void unknownAudienceIsNotEveryone() {
            offers("TIER_PLATINUM", "ALL", List.of());
            issuingWorks();

            CouponService.ClaimResult result = service.claimEventCoupon("autumn", USER_ID);

            assertThat(result.ok()).isFalse();
            assertThat(result.status()).isEqualTo("NOT_ELIGIBLE");
            verify(couponRepository, never()).save(any(Coupon.class));
        }

        /** 🔴 CP5 — 두 번 누름 · 두 탭: 늦은 쪽이 유니크에 걸리면 "이미 받음"(200). 예전에는 500. */
        @Test
        @DisplayName("🔴 동시 수령으로 늦은 쪽이 유니크에 걸리면 '이미 받음' — 예전에는 500")
        void concurrentClaimIsAlreadyOwned() {
            offers("ALL", "ALL", List.of());
            when(couponRepository.existsByNameAndUserId(anyString(), anyLong())).thenReturn(false);
            when(couponRepository.save(any(Coupon.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_coupon_name_user_id"));

            CouponService.ClaimResult result = service.claimEventCoupon("autumn", USER_ID);

            assertThat(result.status()).isEqualTo("ALREADY_OWNED");
            verify(couponIssuedProducer, never()).publish(any());
        }

        /** 🔴 CP5 — 발급 알림은 커밋 뒤에. 예전에는 커밋 전에 보내, 롤백된 쿠폰의 알림이 나갔다. */
        @Test
        @DisplayName("🔴 발급 알림은 커밋된 뒤에 나간다 — 롤백되면 나가지 않는다")
        void issuedNoticeWaitsForCommit() {
            offers("ALL", "ALL", List.of());
            issuingWorks();
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.claimEventCoupon("autumn", USER_ID);
                verify(couponIssuedProducer, never()).publish(any());

                org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();
                verify(couponIssuedProducer).publish(any(Coupon.class));
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}

