package com.project.userservice.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.security.InternalCallGuard;
import com.project.userservice.service.DemoAccountService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정 id 목록 — <b>cart-service 만</b>(경로 규칙 {@code /api/auth/user/demo-accounts=cart-service}, 매일 장바구니 초기화,
 * docs/100). 게이트웨이를 거친 요청은 관리자라도 거절한다. 꺼져 있으면 빈 목록.
 *
 * <p>[JA] 公開デモアカウントの id 一覧 — cart-service だけ(毎日のカート初期化)。ゲートウェイ経由は管理者でも拒否。無効なら空。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DemoAccountInternalController {

    private final DemoAccountService demo;

    @GetMapping("/api/auth/user/demo-accounts")
    public ResponseEntity<Map<String, List<Long>>> demoAccounts(
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)
                || InternalCallGuard.fromGateway(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 데모 계정 목록 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(Map.of("userIds", demo.allDemoAccountIds()));
    }
}
