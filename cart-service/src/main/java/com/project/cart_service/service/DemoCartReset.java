package com.project.cart_service.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.project.cart_service.client.UserServiceClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정의 장바구니를 매일 비운다(docs/100 — 기본 19:05 UTC = 04:05 KST, user-service 의 초기화 5분 뒤).
 *
 * <p>계정 목록은 user-service 에 묻는다(cart 는 누가 데모인지 모른다 — 스키마를 바꾸지 않는다). 지우기는 멱등이라 파드마다 돌아도
 * 괜찮다. 데모가 꺼져 있으면({@code DEMO_ACCOUNTS_ENABLED}) 묻지도 않는다. 실패는 다음 날 다시 — 사용자 요청을 막지 않는다.
 *
 * <p>[JA] 公開デモアカウントのカートを毎日空にする(既定 19:05 UTC)。一覧は user-service に聞く — cart は誰がデモか知らない。
 *      削除は冪等なので Pod ごとに回ってよい。無効なら聞きもしない。失敗は翌日に再度。
 */
@Slf4j
@Component
public class DemoCartReset {

    private final boolean enabled;
    private final UserServiceClient users;
    private final CartService carts;

    @Autowired
    public DemoCartReset(@Value("${demo.accounts.enabled:false}") boolean enabled, UserServiceClient users, CartService carts) {
        this.enabled = enabled;
        this.users = users;
        this.carts = carts;
    }

    /** @return 비운 장바구니 수(실패면 -1) [JA] 空にしたカート数(失敗なら -1) */
    @Scheduled(cron = "${demo.accounts.reset.cart-cron:0 5 19 * * *}", zone = "UTC")
    public int run() {
        if (!enabled) {
            return 0;
        }
        List<Long> ids;
        try {
            Map<String, List<Long>> res = users.demoAccounts();
            ids = res == null || res.get("userIds") == null ? List.of() : res.get("userIds");
        } catch (RuntimeException e) {
            log.warn("데모 장바구니 초기화 — 계정 목록을 받지 못해 건너뛴다(내일 다시): {}", e.getClass().getSimpleName());
            return -1;
        }
        int done = 0;
        for (Long id : ids) {
            try {
                carts.resetDemoCart(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("데모 장바구니 초기화 실패: userId={} {}", id, e.getClass().getSimpleName());
            }
        }
        log.info("데모 장바구니 초기화: 계정={} 비움={}", ids.size(), done);
        return done;
    }
}
