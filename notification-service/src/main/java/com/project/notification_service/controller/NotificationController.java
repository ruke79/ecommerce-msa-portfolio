package com.project.notification_service.controller;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.dto.NotificationResponse;
import com.project.notification_service.dto.PreferenceResponse;
import com.project.notification_service.dto.VerifyCodeRequest;
import com.project.notification_service.dto.VerifyPhoneRequest;
import com.project.notification_service.service.BroadcastService;
import com.project.notification_service.service.NotificationService;
import com.project.notification_service.service.VerificationService;
import com.project.notification_service.service.VerificationService.IssueResult;
import com.project.notification_service.service.VerificationService.VerifyResult;
import com.project.notification_service.sms.PhoneNumbers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 알림 조회·설정 및 SMS 인증 API.
 *
 * <p><b>사용자 식별.</b> 게이트웨이가 JWT 를 검증한 뒤 {@code User-Id} 헤더로 넘겨준다.
 * 이 헤더를 <b>클라이언트가 직접 보낼 수 있으면 안 된다</b> — 남의 알림을 그대로 읽게 된다.
 * 게이트웨이가 외부 요청의 이 헤더를 제거하고 자기가 채워 넣어야 하며, 서비스는 클러스터 밖에
 * 노출되지 않아야 한다. 여기서는 헤더가 없으면 401 로 끊는다.
 *
 * <p>[JA] 通知の照会・設定および SMS 認証 API。
 * <b>ユーザーの識別。</b> ゲートウェイが JWT を検証した後 {@code User-Id} ヘッダーで渡す。
 * このヘッダーを<b>クライアントが直接送れてはならない</b> — 他人の通知をそのまま読めてしまう。
 * ゲートウェイが外部リクエストのこのヘッダーを除去して自分で埋め、サービスはクラスター外に公開されてはならない。
 * ここではヘッダーがなければ 401 で切る。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final VerificationService verificationService;
    private final BroadcastService broadcastService;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail,
            @RequestParam(defaultValue = "20") int size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 알림 메일을 보내려면 주소가 필요한데 이 서비스는 사용자 테이블이 없다. 게이트웨이가
        // JWT 에서 꺼내 붙인 헤더를 여기서 기록해 둔다(NotificationService#rememberEmail 주석).
        // ⚠️ 배지(unread-count)에는 넣지 않는다 — 화면마다 불리는 경로라 조회가 하나 더 붙는다.
        // [JA] 通知メールを送るにはアドレスが必要だが、このサービスにはユーザーテーブルがない。ゲートウェイが
        //      JWT から取り出して付けたヘッダーをここで記録しておく(NotificationService#rememberEmail のコメント)。
        //      ⚠️ バッジ(unread-count)には入れない — 画面ごとに呼ばれる経路なので照会が一つ増える。
        notificationService.rememberEmail(userId, userEmail);

        /*
         * 개인 알림과 **전체 알림(공지·관리자 안내)** 을 합쳐서 돌려준다.
         *
         * 전체 알림은 사용자별 행을 만들지 않는다(BroadcastNotification 주석) — 공지 하나에
         * 사용자 수만큼 insert 하지 않기 위해서다. 그래서 저장은 따로, 화면에 보일 때는
         * 하나로 합친다. 사용자에게는 "알림" 하나이고 어디에 저장됐는지는 관심 밖이다.
         *
         * 합친 뒤 시간 역순으로 정렬하고 size 개만 남긴다. 각 응답의 scope 로 어느 쪽인지
         * 구분되므로, 화면은 읽음·삭제에 맞는 엔드포인트를 고를 수 있다.
         *
         * <p>[JA] 個人通知と**全体通知(お知らせ・管理者案内)**を合わせて返す。
         * 全体通知はユーザー別の行を作らない(BroadcastNotification のコメント) — お知らせ一つにユーザー数分の insert をしないためである。
         * だから保存は別々で、画面に出すときに一つに合わせる。ユーザーにとっては「通知」一つで、どこに保存されたかは関心の外である。
         * 合わせた後に時刻の降順で並べ size 件だけ残す。各レスポンスの scope でどちらか区別できるので、
         * 画面は既読・削除に合うエンドポイントを選べる。
         */
        Stream<NotificationResponse> personal = notificationService.recent(userId, size)
                .stream()
                .map(NotificationResponse::from)
                .map(NotificationResponse::localized);   // 서버가 만든 한국어 문구 → 요청 언어(docs/60 §9) ／ [JA] サーバーが作った韓国語文言 → リクエストの言語(docs/60 §9)

        Stream<NotificationResponse> broadcast = broadcastService.visibleFor(userId, size)
                .stream()
                .map(v -> NotificationResponse.from(v.broadcast(), v.read()));

        List<NotificationResponse> merged = Stream.concat(personal, broadcast)
                .sorted(Comparator.comparing(NotificationResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, size))
                .toList();

        return ResponseEntity.ok(merged);
    }

    /** 배지 숫자. 화면 진입마다 불리므로 가볍게 유지한다. */
    // [JA] バッジの数字。画面に入るたびに呼ばれるので軽く保つ。
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 배지는 사용자가 보는 "안 읽은 알림" 전체다 — 저장 위치로 나뉘면 안 된다.
        // [JA] バッジはユーザーが見る「未読通知」全体である — 保存場所で分かれてはならない。
        long count = notificationService.unreadCount(userId) + broadcastService.unreadCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 남의 알림이면 404 다 — 403 을 주면 "그 id 는 존재한다"를 알려주는 셈이다.
        // [JA] 他人の通知なら 404 である — 403 を返すと「その id は存在する」と教えることになる。
        return notificationService.markRead(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead(
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 배지를 0 으로 만드는 버튼이다. 개인 알림만 읽음 처리하면 전체 알림이 남아
        // 배지가 0 이 되지 않는다 — 사용자는 "모두 읽음"을 계속 누르게 된다.
        // [JA] バッジを 0 にするボタンである。個人通知だけを既読にすると全体通知が残って
        //      バッジが 0 にならない — ユーザーは「すべて既読」を押し続けることになる。
        int updated = notificationService.markAllRead(userId) + broadcastService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return notificationService.delete(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── 전체 알림(공지·관리자 안내) ─────────────────────────────────────────
    //
    // 개인 알림과 **엔드포인트가 다르다.** 전체 알림은 모두가 공유하는 행이라 개인 알림처럼
    // 지울 수 없고, 개인별로는 읽음·치움 표시만 남긴다. 목록 응답의 scope 가 화면에게
    // 어느 쪽을 부를지 알려 준다(NotificationResponse 주석).
    //
    // 경로를 나눈 이유: id 만으로는 구분할 수 없다. 두 테이블이 각자 TSID 를 쓰므로,
    // 한 경로로 받으면 우연히 같은 id 의 남의 개인 알림을 건드릴 수 있다.
    // [JA] ── 全体通知(お知らせ・管理者案内) ──
    //      
    //      個人通知とは**エンドポイントが違う。** 全体通知は皆が共有する行なので個人通知のように消せず、
    //      個人別には既読・片付けの印だけを残す。一覧レスポンスの scope が画面にどちらを呼ぶかを教える(NotificationResponse のコメント)。
    //      
    //      パスを分けた理由: id だけでは区別できない。二つのテーブルがそれぞれ TSID を使うので、
    //      一つのパスで受けると偶然同じ id の他人の個人通知に触れうる。

    @PatchMapping("/broadcasts/{id}/read")
    public ResponseEntity<Void> markBroadcastRead(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return broadcastService.markRead(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 내 목록에서만 치운다 — 공지 자체는 남는다(한 사람의 삭제가 남의 화면을 바꾸면 안 된다). */
    // [JA] 自分の一覧からだけ片付ける — お知らせ自体は残る(一人の削除が他人の画面を変えてはならない)。
    @DeleteMapping("/broadcasts/{id}")
    public ResponseEntity<Void> dismissBroadcast(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return broadcastService.dismiss(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── 알림 설정 ────────────────────────────────────────────────────────────
    // [JA] ── 通知設定 ──

    @GetMapping("/preferences")
    public ResponseEntity<PreferenceResponse> preferences(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(
                PreferenceResponse.from(notificationService.preferenceOf(userId, userEmail)));
    }

    @PatchMapping("/preferences")
    public ResponseEntity<?> updatePreferences(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail,
            @RequestBody Map<String, Boolean> body) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 바꾸기 · 저장은 서비스의 한 트랜잭션에서(🐛 예전에는 여기서 세터를 불러 저장되지 않았다 — NotificationService.updatePreferences).
        // 2단계 인증(twoFactorEnabled)은 여기서 받지 않는다 — 400, 다른 키도 저장하지 않는다(docs/24 §10).
        // [JA] 変更 · 保存はサービスの一つのトランザクションで(🐛 以前はここでセッターを呼んで保存されなかった — NotificationService.updatePreferences)。
        //      二段階認証(twoFactorEnabled)はここでは受けない — 400、他のキーも保存しない(docs/24 §10)。
        NotificationService.PreferenceUpdate result = notificationService.updatePreferences(userId, userEmail, body);
        if (result instanceof NotificationService.PreferenceUpdate.Rejected r) {
            return ResponseEntity.badRequest().body(Map.of("message", r.reason()));
        }
        return ResponseEntity.ok(PreferenceResponse.from(
                ((NotificationService.PreferenceUpdate.Updated) result).preference()));
    }

    // ── SMS 인증 (번호 등록 · 2단계 인증) ──────────────────────────────────────
    // [JA] ── SMS 認証(番号登録 · 二段階認証) ──

    /**
     * 인증번호를 요청한다.
     *
     * <p>속도 제한에 걸리면 429 와 함께 재시도 가능 시각을 준다. 이 제한은 사용자 편의가 아니라
     * <b>남의 번호로 문자 폭탄을 보내는 것을 막기 위한 것</b>이다 — 요금은 우리가 낸다.
     *
     * <p>[JA] 認証番号を要求する。
     * レート制限に掛かると 429 と共に再試行可能な時刻を返す。この制限はユーザーの便宜ではなく
     * <b>他人の番号に SMS 爆弾を送るのを防ぐためのもの</b>である — 料金は我々が払う。
     */
    @PostMapping("/phone/verification")
    public ResponseEntity<Map<String, Object>> requestCode(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @Valid @RequestBody VerifyPhoneRequest request) {

        IssueResult result = verificationService.issue(
                request.phone(), PhoneNumbers.regionFrom(request.region()), userId);

        if (result instanceof IssueResult.Sent sent) {
            return ResponseEntity.ok(Map.of(
                    "phone", sent.maskedPhone(),
                    "expiresInMinutes", sent.ttlMinutes()));
        }
        if (result instanceof IssueResult.TooMany tooMany) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(tooMany.retryAfterSeconds()))
                    .body(Map.of("message", tooMany.reason(),
                                 "retryAfterSeconds", tooMany.retryAfterSeconds()));
        }
        IssueResult.Rejected rejected = (IssueResult.Rejected) result;
        return ResponseEntity.badRequest().body(Map.of("message", rejected.reason()));
    }

    /** 인증번호를 확인하고, 로그인 사용자면 번호를 등록한다. */
    // [JA] 認証番号を確認し、ログインユーザーなら番号を登録する。
    @PostMapping("/phone/verification/confirm")
    public ResponseEntity<Map<String, Object>> confirmCode(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @Valid @RequestBody VerifyCodeRequest request) {

        VerifyResult result = verificationService.verify(
                request.phone(), PhoneNumbers.regionFrom(request.region()), request.code(), userId);

        if (result instanceof VerifyResult.Verified verified) {
            if (userId != null) {
                NotificationPreference preference =
                        verificationService.confirmPhoneForUser(userId, verified.e164());
                return ResponseEntity.ok(Map.of(
                        "verified", true,
                        "preference", PreferenceResponse.from(preference)));
            }
            // 로그인 전 2FA 흐름 — 번호 등록 없이 통과 사실만 알린다.
            // [JA] ログイン前の 2FA フロー — 番号登録なしで通過した事実だけを知らせる。
            return ResponseEntity.ok(Map.of("verified", true));
        }

        VerifyResult.Rejected rejected = (VerifyResult.Rejected) result;
        return ResponseEntity.badRequest().body(Map.of(
                "verified", false,
                "message", rejected.reason(),
                "attemptsLeft", rejected.attemptsLeft()));
    }
}
