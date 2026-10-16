package com.project.notification_service.controller;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.dto.BroadcastDtos.BroadcastAdminView;
import com.project.notification_service.dto.BroadcastDtos.BroadcastRequest;
import com.project.notification_service.service.BroadcastService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전체 알림 관리 — 관리자가 직접 보내는 안내(점검 예고 등).
 *
 * <p><b>🔒 경로가 곧 인가 규칙이다.</b> {@code /api/notifications/admin} 접두사 아래는
 * {@link com.project.notification_service.config.AdminOnlyInterceptor} 가 읽기까지 전부
 * 관리자로 제한한다. <b>새 관리 엔드포인트는 반드시 이 접두사 아래에 둘 것</b> — 다른 곳에
 * 두면 로그인한 일반 사용자가 사이트 전체에 대고 아무 문구나 띄울 수 있다.
 *
 * <p>공지사항({@code NOTICE_PUBLISHED})은 여기서 만들지 않는다. 그쪽은 catalog-service 의
 * 공지 글을 공개할 때 카프카로 들어온다({@code BroadcastEventListener}) — 글이 있고 알림은
 * 그 글을 가리킨다. 여기서 만드는 것은 글 없이 그 자리에서 쓰는 안내라
 * {@code ANNOUNCEMENT} 다.
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications/admin/broadcasts")
@RequiredArgsConstructor
public class BroadcastAdminController {

    private final BroadcastService broadcastService;

    /** 예약분·만료분까지 전부. 관리자는 안 보이는 것도 봐야 고칠 수 있다. */
    @GetMapping
    public ResponseEntity<List<BroadcastAdminView>> list(
            @RequestParam(defaultValue = "50") int limit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return ResponseEntity.ok(broadcastService.all(limit).stream()
                .map(b -> BroadcastAdminView.of(b, now))
                .toList());
    }

    @PostMapping
    public ResponseEntity<BroadcastAdminView> create(@Valid @RequestBody BroadcastRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 카프카로 들어오는 공지와 달리 여기에는 "원래 사건"이 없다 — 키를 비워 두면 저장 버튼을 두 번 누르면 같은 안내가 두 줄
        // 생긴다. 키 = 내용(제목 · 본문 · 링크)의 해시 + 분 단위 시각(adminEventId).
        String eventId = adminEventId(request.title(), request.content(), request.url(), now);

        return broadcastService.create(
                        NotificationType.ANNOUNCEMENT,
                        request.title(),
                        request.content(),
                        request.url(),
                        eventId,
                        request.startsAt(),
                        request.endsAt())
                .map(saved -> {
                    log.info("전체 알림 발송: id={}", saved.getBroadcastId());
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(BroadcastAdminView.of(saved, now));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    /**
     * 관리자 안내의 멱등 키 — 같은 내용을 같은 분 안에 두 번 보내면 같은 키(두 번째는 409).
     *
     * <p>🐛 예전: {@code "admin:" + now} — 주석은 "밀리초 단위로 섞어 연타를 막는다" 였지만 {@code LocalDateTime.now()} 는 나노초까지라
     * 연타가 매번 다른 키가 되어 같은 안내가 두 줄 생겼다. 시각만 자르면 같은 분의 서로 다른 안내가 합쳐지므로 내용을 함께 넣는다.
     * 같은 내용을 일부러 다시 보내려면 1분 뒤에(드물고, 그 편이 실수보다 낫다).
     */
    // [JA] 管理者案内の冪等キー — 同じ内容を同じ分に二度送ると同じキー。以前は now() のナノ秒で連打が毎回別キーになっていた。
    static String adminEventId(String title, String content, String url, LocalDateTime now) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {title, content, url}) {
                sha.update(String.valueOf(part).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            String digest = java.util.HexFormat.of().formatHex(sha.digest()).substring(0, 16);
            return "admin:" + digest + ":" + now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 모든 JVM 에 있다
        }
    }

    /**
     * 완전히 내린다.
     *
     * <p>사용자의 {@code DELETE /broadcasts/{id}} 와 <b>다른 동작</b>이다. 그쪽은 "내
     * 목록에서만 치운다"이고, 이쪽은 모두에게서 사라진다. 읽음 행도 함께 정리한다.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        if (!broadcastService.remove(id)) {
            return ResponseEntity.notFound().build();
        }
        log.warn("전체 알림 삭제: id={}", id);
        return ResponseEntity.noContent().build();
    }
}
