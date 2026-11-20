package com.project.notification_service.dto;

import java.time.LocalDateTime;

import com.project.notification_service.domain.BroadcastNotification;
import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.SmsStatus;

/**
 * 화면에 내려줄 알림.
 *
 * <p>엔티티를 그대로 내보내지 않는다. {@code smsDetail}(공급자 오류 메시지)이나 시도 횟수는
 * 내부 운영 정보라 사용자에게 보일 이유가 없다.
 *
 * <p>[JA] 画面に返す通知。
 * エンティティをそのまま出さない。{@code smsDetail}(プロバイダーのエラーメッセージ)や試行回数は
 * 内部の運用情報で、ユーザーに見せる理由がない。
 */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String content,
        String url,
        boolean read,
        String smsStatus,
        LocalDateTime createdAt,
        String scope) {

    /** 개인 알림. */
    // [JA] 個人通知。
    public static final String SCOPE_PERSONAL = "PERSONAL";

    /** 전체 알림(공지·관리자 안내). */
    // [JA] 全体通知(お知らせ・管理者案内)。
    public static final String SCOPE_BROADCAST = "BROADCAST";

    /**
     * 제목·본문을 지금 요청의 언어로 — 개인 알림은 서버가 만든 문구가 한국어로 저장돼 있다(NotificationTexts). 저장된 글은
     * 그대로 두고 읽을 때 고른다 — 이미 쌓인 알림도 화면 언어를 따른다(docs/60 §9). 전체 알림(공지)은 관리자가 쓴 글이라 쓰지 않는다.
     *
     * <p>[JA] タイトル・本文を今のリクエストの言語で — 個人通知はサーバーが作った文言が韓国語で保存されている(NotificationTexts)。保存された文は
     * そのままにして読むときに選ぶ — 既に溜まった通知も画面の言語に従う(docs/60 §9)。全体通知(お知らせ)は管理者が書いた文なので使わない。
     */
    public NotificationResponse localized() {
        return new NotificationResponse(id, type,
                com.project.common.i18n.ClientText.localize(title),
                com.project.common.i18n.ClientText.localize(content),
                url, read, smsStatus, createdAt, scope);
    }

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getNotificationId(),
                n.getType().name(),
                n.getTitle(),
                n.getContent(),
                n.getUrl(),
                n.isRead(),
                n.getSmsStatus().name(),
                n.getCreatedAt(),
                SCOPE_PERSONAL);
    }

    /**
     * 전체 알림.
     *
     * <p><b>{@code scope} 가 왜 필요한가.</b> 목록은 개인 알림과 전체 알림을 <b>합쳐서</b>
     * 돌려준다. 그런데 읽음·삭제 엔드포인트가 서로 다르다 — 전체 알림은 공유 행이라
     * 개인 알림처럼 지울 수 없고, 개인별 읽음 표시만 따로 남긴다.
     *
     * <p>id 만으로는 어느 쪽인지 알 수 없다(각 테이블이 자기 TSID 를 쓴다). 화면이
     * {@code /{id}/read} 를 부를지 {@code /broadcasts/{id}/read} 를 부를지 정하려면 이
     * 값이 있어야 한다. 없으면 잘못된 엔드포인트로 가서 404 가 나거나 — 더 나쁘게 —
     * 우연히 같은 id 의 남의 개인 알림을 건드린다.
     *
     * <p>SMS 상태는 없다. 전체 알림은 문자로 나가지 않는다(MailKind·SmsTemplates 주석).
     *
     * <p>[JA] 全体通知。
     * <b>{@code scope} がなぜ必要か。</b> 一覧は個人通知と全体通知を<b>合わせて</b>返す。ところが既読・削除のエンドポイントが互いに違う —
     * 全体通知は共有行なので個人通知のように消せず、個人別の既読の印だけを別に残す。
     * id だけではどちらか分からない(各テーブルが自分の TSID を使う)。画面が {@code /{id}/read} を呼ぶか
     * {@code /broadcasts/{id}/read} を呼ぶかを決めるにはこの値が必要である。なければ誤ったエンドポイントに行って 404 になるか —
     * さらに悪いことに — 偶然同じ id の他人の個人通知に触れる。
     * SMS 状態はない。全体通知は SMS で出ない(MailKind・SmsTemplates のコメント)。
     */
    public static NotificationResponse from(BroadcastNotification b, boolean read) {
        return new NotificationResponse(
                b.getBroadcastId(),
                b.getType().name(),
                b.getTitle(),
                b.getContent(),
                b.getUrl(),
                read,
                // 전체 알림은 SMS 대상이 아니다(MailKind·SmsTemplates 주석).
                // [JA] 全体通知は SMS の対象ではない(MailKind・SmsTemplates のコメント)。
                SmsStatus.NOT_APPLICABLE.name(),
                b.getStartsAt(),
                SCOPE_BROADCAST);
    }
}
