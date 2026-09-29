package com.project.coupon_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.coupon_service.config.FeignConfig;






/**
 * 마이크로서비스 간의 호출을 위한 feignclient
 */
@FeignClient(name="user-service", url="${feign.user-url}", configuration = FeignConfig.class)
public interface UserServiceClient {

   /**
    * 이메일 → 사용자. 관리자가 이메일로 쿠폰을 발급할 때만 쓴다 — 요청자의 신원은 언제나 {@code User-Id} 다.
    *
    * <p>🔒 예전에는 {@code @RequestParam("Authorization")} 으로 <b>액세스 토큰을 쿼리 파라미터</b>에 실었다 — URL 은 액세스
    * 로그·프록시·예외 메시지에 남는다(order 의 같은 버그, docs/47 §5-4). 받는 쪽은 읽지도 않았고, 헤더는
    * {@code FeignConfig} 의 인터셉터가 이미 넘긴다.
    */
   @GetMapping("api/auth/user/id")
   public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email);

   /**
    * 이 사용자가 지금 속한 고객군.
    *
    * <p>판정은 user-service 가 한다 — "신규가 며칠인가" 는 그쪽의 정책이고 가입일도
    * 그쪽만 갖고 있다. 가입일을 받아 여기서 계산하면 기준을 바꿀 때 두 곳을 고쳐야 하고,
    * 한 곳을 빠뜨리면 혜택이 조용히 어긋난다.
    *
    * <p>없는 사용자는 빈 목록이다 — 404 로 구분해 주면 그 자체가 가입 여부 신호가 된다.
    */
   @GetMapping("api/auth/user/segments")
   public ResponseEntity<java.util.Map<String, java.util.List<String>>> findUserSegments(
           @RequestParam("userId") Long userId);
}
