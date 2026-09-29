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
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = idOf(userDetails);
        if (userId == null) {
            return unauthorized();
        }

        String password = request == null ? null : request.getPassword();
        String reason = request == null ? null : request.getReason();

        try {
            return ResponseEntity.ok(withdrawalService.request(userId, password, reason));
        } catch (BadCredentialsException e) {
            // 비밀번호가 틀렸다. 되돌릴 수 없는 동작 앞의 본인 확인이므로 통과시키지 않는다.
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
