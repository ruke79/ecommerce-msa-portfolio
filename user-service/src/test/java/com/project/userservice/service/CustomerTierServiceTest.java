package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.common.constants.event.CustomerTier;
import com.project.common.dto.order.PurchaseStatDto;
import com.project.userservice.client.OrderServiceClient;
import com.project.userservice.config.TierProperties;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 등급 배치.
 *
 * <p>여기서 지키는 것 중 가장 중요한 하나 — <b>절반만 받은 목록으로 강등하지 않는다.</b>
 * order-service 는 최근 구매가 있는 고객만 돌려주므로, 응답에 없다는 것은 두 가지 뜻이
 * 될 수 있다: "창에서 빠졌다"(강등해야 함) 또는 "아직 못 받았다"(강등하면 안 됨).
 * 이 둘을 섞으면 통신 장애 하나로 전 고객 등급이 날아간다.
 */
@DisplayName("등급 배치")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerTierServiceTest {

    @Mock
    private OrderServiceClient orderServiceClient;

    @Mock
    private UserRepository userRepository;

    private TierProperties properties;
    private CustomerTierService service;

    @BeforeEach
    void setUp() {
        properties = new TierProperties();
        properties.setMonths(3);
        properties.setBatchSize(2);
        properties.setSilverAmount(200_000L);
        properties.setGoldAmount(500_000L);
        properties.setVipAmount(1_500_000L);

        service = new CustomerTierService(orderServiceClient, userRepository, properties);

        when(userRepository.updateTier(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(1);
        when(userRepository.demoteStale(any(), any())).thenReturn(0);
    }

    private static PurchaseStatDto stat(long customerId, long lifetime, long recentAmount) {
        return new PurchaseStatDto(customerId, lifetime, lifetime, recentAmount);
    }

    private void respond(List<PurchaseStatDto>... pages) {
        List<Map<String, List<PurchaseStatDto>>> bodies = new ArrayList<>();
        for (List<PurchaseStatDto> page : pages) {
            bodies.add(Map.of("stats", page));
        }
        var stub = when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt()));
        for (Map<String, List<PurchaseStatDto>> body : bodies) {
            stub = stub.thenReturn(body);
        }
    }

    @Test
    @DisplayName("금액에 맞는 등급을 찍는다")
    void assignsTierByAmount() {
        respond(List.of(stat(1L, 3, 600_000L)), List.of());

        service.recalculateAll();

        verify(userRepository).updateTier(eq(1L), eq(CustomerTier.GOLD), eq(3L), eq(600_000L), any());
    }

    @Test
    @DisplayName("끝까지 돌면 남은 사람을 일반으로 내린다")
    void demotesAfterAFullRun() {
        respond(List.of(stat(1L, 1, 10_000L)), List.of());

        CustomerTierService.Result result = service.recalculateAll();

        assertThat(result.completed()).isTrue();
        verify(userRepository).demoteStale(any(), any());
    }

    /**
     * 🔴 배치의 가장 위험한 실패. 통신이 끊긴 것을 "구매가 없다" 로 읽으면 못 받은
     * 구간의 고객이 전부 일반 등급으로 떨어진다. 이미 발급된 상위 등급 쿠폰은 회수할 수
     * 없고, 다음날 다시 올려 줘도 그 사이의 불만은 되돌릴 수 없다.
     */
    @Test
    @DisplayName("중간에 실패하면 강등하지 않는다 — 통신 장애로 전 고객 등급이 날아가면 안 된다")
    void neverDemotesOnPartialData() {
        when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt()))
                .thenReturn(Map.of("stats", List.of(stat(1L, 1, 900_000L), stat(2L, 1, 900_000L))))
                .thenThrow(new RuntimeException("connection reset"));

        CustomerTierService.Result result = service.recalculateAll();

        assertThat(result.completed()).isFalse();
        assertThat(result.demoted()).isZero();
        verify(userRepository, never()).demoteStale(any(), any());
    }

    @Test
    @DisplayName("첫 호출부터 실패해도 강등하지 않는다")
    void neverDemotesWhenNothingArrived() {
        when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("down"));

        assertThat(service.recalculateAll().completed()).isFalse();
        verify(userRepository, never()).demoteStale(any(), any());
    }

    @Test
    @DisplayName("응답이 null 이어도 강등하지 않는다")
    void nullBodyIsAFailureNotAnEmptyResult() {
        when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt())).thenReturn(null);

        assertThat(service.recalculateAll().completed()).isFalse();
        verify(userRepository, never()).demoteStale(any(), any());
    }

    /**
     * 🔴 회차 시작 시각을 마지막에 읽으면, 배치가 도는 동안 갱신한 행이 전부 그 시각보다
     * 과거가 되어 <b>방금 승급시킨 사람까지 도로 강등된다.</b>
     */
    @Test
    @DisplayName("강등 기준 시각은 회차 시작 시각이다 — 방금 올린 사람을 다시 내리면 안 된다")
    void demotionCutoffIsTheRunStart() {
        respond(List.of(stat(1L, 1, 900_000L)), List.of());

        service.recalculateAll();

        ArgumentCaptor<LocalDateTime> stamped = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).updateTier(anyLong(), any(), anyLong(), anyLong(), stamped.capture());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).demoteStale(cutoff.capture(), any());

        // 갱신에 찍힌 시각이 강등 기준보다 과거면, 그 사람이 강등 대상에 걸린다.
        assertThat(stamped.getValue()).isAfterOrEqualTo(cutoff.getValue());
    }

    @Test
    @DisplayName("커서가 전진한다 — 같은 페이지를 다시 받지 않는다")
    void theCursorAdvances() {
        when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt()))
                .thenReturn(Map.of("stats", List.of(stat(10L, 1, 1_000L), stat(20L, 1, 1_000L))))
                .thenReturn(Map.of("stats", List.of(stat(30L, 1, 1_000L))))
                .thenReturn(Map.of("stats", List.of()));

        service.recalculateAll();

        ArgumentCaptor<Long> cursors = ArgumentCaptor.forClass(Long.class);
        verify(orderServiceClient, org.mockito.Mockito.times(3))
                .purchaseStats(anyInt(), cursors.capture(), anyInt());

        assertThat(cursors.getAllValues()).containsExactly(Long.MIN_VALUE, 20L, 30L);
    }

    /**
     * 🔴 <b>덜 찬 페이지를 끝으로 읽으면 안 된다.</b> order-service 는 응답 하나의 크기에
     * 자체 상한(500)이 있어서, 그보다 큰 size 를 요청하면 말없이 잘라서 돌려준다. 그것을
     * "끝" 으로 읽으면 아직 보지도 않은 나머지 고객이 전부 강등된다 — 두 서비스의 숫자
     * 약속이 어긋나는 순간 터지는, 아무도 눈치채지 못할 사고다.
     *
     * <p>여기서는 요청 크기(2)보다 작은 페이지(1건)를 먼저 주고, 그 뒤에 아직 남은
     * 고객이 있는 상황을 만든다. 지름길을 쓰면 두 번째 고객을 영영 보지 못한다.
     */
    @Test
    @DisplayName("덜 찬 페이지를 끝으로 읽지 않는다 — 서버가 말없이 잘라 준 것일 수 있다")
    void aShortPageIsNotTheEnd() {
        when(orderServiceClient.purchaseStats(anyInt(), anyLong(), anyInt()))
                .thenReturn(Map.of("stats", List.of(stat(1L, 1, 1_000L))))
                .thenReturn(Map.of("stats", List.of(stat(2L, 1, 900_000L))))
                .thenReturn(Map.of("stats", List.of()));

        CustomerTierService.Result result = service.recalculateAll();

        assertThat(result.completed()).isTrue();
        verify(userRepository).updateTier(eq(2L), eq(CustomerTier.GOLD), anyLong(), anyLong(), any());
    }

    /** 끝은 빈 페이지 하나로만 판정한다. */
    @Test
    @DisplayName("빈 페이지를 받아야 끝이다")
    void anEmptyPageEndsTheRun() {
        respond(List.of());

        CustomerTierService.Result result = service.recalculateAll();

        assertThat(result.completed()).isTrue();
        verify(orderServiceClient, org.mockito.Mockito.times(1)).purchaseStats(anyInt(), anyLong(), anyInt());
    }

    /**
     * order-service 에는 주문이 있는데 user-service 에 계정이 없을 수 있다(탈퇴 후 익명화,
     * 테스트 데이터). 한 건 때문에 배치 전체를 멈출 이유가 없다.
     */
    @Test
    @DisplayName("없는 계정은 건너뛰고 계속 간다")
    void unknownAccountsDoNotStopTheRun() {
        when(userRepository.updateTier(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        respond(List.of(stat(1L, 1, 1_000L)), List.of());

        assertThat(service.recalculateAll().completed()).isTrue();
    }

    @Test
    @DisplayName("customerId 가 없는 행은 무시한다")
    void rowsWithoutACustomerAreIgnored() {
        respond(List.of(new PurchaseStatDto(null, 1, 1, 1_000L)), List.of());

        service.recalculateAll();

        verify(userRepository, never()).updateTier(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("설정한 기간을 그대로 물어본다 — 정책은 설정이다")
    void asksForTheConfiguredWindow() {
        properties.setMonths(6);
        respond(List.of());

        service.recalculateAll();

        verify(orderServiceClient).purchaseStats(eq(6), anyLong(), anyInt());
    }
}
