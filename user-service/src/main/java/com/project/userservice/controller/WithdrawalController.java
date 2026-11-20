package com.project.userservice.controller;

import com.project.common.exception.ClientMessage;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.response.MessageResponse;
import com.project.userservice.security.request.WithdrawalRequest;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.WithdrawalService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴.
 *
 * <p>세 동작을 HTTP 메서드로 나눴다 — 조회(GET), 신청(POST), 취소(DELETE). 취소가
 * DELETE 인 것은 <b>탈퇴 신청이라는 자원을 지우는 것</b>이기 때문이다(계정을 지우는 것이
 * 아니다). 계정을 지우는 쪽은 유예 기간이 지난 뒤 배치가 한다.
 *
 * <p>신원은 토큰에서만 온다. userId 를 본문이나 경로로 받지 않는다 — 받으면 남의 계정을
 * 탈퇴시킬 수 있다.
 *
 * <p>[JA] 会員退会。
 * 三つの動作を HTTP メソッドで分けた — 照会(GET)、申請(POST)、取消(DELETE)。取消が DELETE なのは
 * <b>退会申請というリソースを消す</b>からだ(アカウントを消すのではない)。アカウントを消すのは猶予期間が過ぎたあとバッチが行う。
 * 身元はトークンからだけ来る。userId を本文やパスで受け取らない — 受け取ると他人のアカウントを退会させられる。
 */
@RestController
@RequestMapping("/api/user/withdrawal")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @GetMapping
    ResponseEntity<?> status(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = idOf(userDetails);
        if (userId == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(withdrawalService.status(userId));
    }

    @PostMapping
    ResponseEntity<?> request(@Valid @RequestBody(required = false) WithdrawalRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            // 비밀번호를 비웠을 때 "최근 로그인" 을 확인하는 근거(docs/68 §16). HttpOnly 쿠키라 화면은 만지지 않는다.
            // [JA] パスワードを空にしたとき「最近のログイン」を確認する根拠(docs/68 §16)。HttpOnly Cookie なので画面は触らない。
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {   // = TokenType.REFRESH

        Long userId = idOf(userDetails);
        if (userId == null) {
            return unauthorized();
        }

        String password = request == null ? null : request.getPassword();
        String reason = request == null ? null : request.getReason();

        try {
            return ResponseEntity.ok(withdrawalService.request(userId, password, reason, refresh));
        } catch (com.project.userservice.security.CurrentPasswordGuard.TooManyAttemptsException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new MessageResponse(ClientMessage.of(e)));
        } catch (BadCredentialsException e) {
            // 비밀번호가 틀렸다. 되돌릴 수 없는 동작 앞의 본인 확인이므로 통과시키지 않는다.
            // [JA] パスワードが誤っている。取り消せない動作の前の本人確認なので通さない。
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse(ClientMessage.of(e)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    @DeleteMapping
    ResponseEntity<?> cancel(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = idOf(userDetails);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ResponseEntity.ok(withdrawalService.cancel(userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    private Long idOf(UserDetails userDetails) {
        return userDetails instanceof UserDetailsImpl impl ? impl.getId() : null;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse("로그인이 필요합니다."));
    }
}
