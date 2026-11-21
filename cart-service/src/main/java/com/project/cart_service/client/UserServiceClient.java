package com.project.cart_service.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import com.project.cart_service.config.FeignConfig;

/**
 * user-service 호출 — 공개 데모 계정 id 목록만(매일 초기화, docs/100). 내부 경로 규칙: {@code /api/auth/user/demo-accounts=cart-service|…}.
 * [JA] user-service 呼び出し — 公開デモアカウントの id 一覧だけ(毎日の初期化)。
 */
@FeignClient(name = "user-service", url = "${feign.user-url}", configuration = FeignConfig.class)
public interface UserServiceClient {

    /** @return {@code {"userIds": [...]}} — 데모가 꺼져 있으면 빈 목록. [JA] デモが無効なら空。 */
    @GetMapping("api/auth/user/demo-accounts")
    Map<String, List<Long>> demoAccounts();
}
