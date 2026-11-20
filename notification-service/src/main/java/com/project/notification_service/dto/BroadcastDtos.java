package com.project.notification_service.dto;

import java.time.LocalDateTime;

import com.project.notification_service.domain.BroadcastNotification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 화면이 쓰는 전체 알림 DTO. */
// [JA] 管理者画面が使う全体通知の DTO。
public final class BroadcastDtos {

    private BroadcastDtos() {
    }

    /**
     * 관리자가 직접 보내는 안내.
     *
     * <p>길이 제한은 DB 컬럼(V4)과 같은 값이다. 여기서 막지 않으면 서버 500 으로 끝나고
     * 관리자는 무엇이 문제인지 모른 채 다시 시도한다.
     *
     * <p>{@code startsAt} 이 비면 즉시 시작, {@code endsAt} 이 비면 만료 없음이다.
     * 점검 예고처럼 지나면 의미가 없는 안내는 끝 시각을 넣어 두는 편이 낫다 — 사용자가
     * 하나하나 지우게 하지 않는다.
     *
     * <p>[JA] 管理者が直接送る案内。
     * 長さ制限は DB の列(V4)と同じ値である。ここで止めないとサーバーの 500 で終わり、
     * 管理者は何が問題か分からないまま再試行する。
     * {@code startsAt} が空なら即時開始、{@code endsAt} が空なら期限なしである。
     * メンテナンス予告のように過ぎれば意味のない案内は終了時刻を入れておく方がよい — ユーザーに一つずつ消させない。
     */
    public record BroadcastRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 1000) String content,
            @Size(max = 512) String url,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
    }

    /**
     * 관리자 목록 한 줄.
     *
     * <p>{@code sourceEventId} 는 내보내지 않는다 — 중복 방지용 내부 키이고 화면에서
     * 쓸 일이 없다.
     *
     * <p>[JA] 管理者一覧の一行。
     * {@code sourceEventId} は出さない — 重複防止用の内部キーで、画面で使うことがない。
     */
    public record BroadcastAdminView(
            Long id,
            String type,
            String title,
            String content,
            String url,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            LocalDateTime createdAt,
            boolean active) {

        public static BroadcastAdminView of(BroadcastNotification b, LocalDateTime now) {
            return new BroadcastAdminView(
                    b.getBroadcastId(),
                    b.getType().name(),
                    b.getTitle(),
                    b.getContent(),
                    b.getUrl(),
                    b.getStartsAt(),
                    b.getEndsAt(),
                    b.getCreatedAt(),
                    // 관리자는 "지금 실제로 보이는가"를 알아야 한다. 시작·종료 시각 두 개를
                    // 보고 스스로 계산하게 하면 예약분과 만료분을 헷갈린다.
                    // [JA] 管理者は「今実際に見えているか」を知る必要がある。開始・終了時刻の二つを
                    //      見て自分で計算させると予約分と期限切れ分を取り違える。
                    b.isActiveAt(now));
        }
    }
}
