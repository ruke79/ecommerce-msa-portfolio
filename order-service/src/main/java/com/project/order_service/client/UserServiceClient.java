package com.project.order_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.order_service.config.FeignConfig;


/**
 * 마이크로서비스 간의 호출을 위한 feignclient
 *
 * <h3>🔒 토큰이 URL 에 들어가고 있었다</h3>
 *
 * <p>네 메서드 모두 {@code @RequestParam("Authorization") String token} 이었다. 그러면
 * 액세스 토큰이 <b>쿼리 문자열</b>에 실린다:
 *
 * <pre>
 * GET /api/auth/user/id?email=…&amp;Authorization=Bearer%20eyJhbGciOiJIUzI1NiJ9…
 * </pre>
 *
 * <p>URL 은 어디에나 남는다 — 액세스 로그, 프록시, 예외 메시지, Referer. 실제로
 * 형제 클라이언트({@link CartServiceClient})에서 그 결과를 봤다: Feign 예외 메시지에
 * <b>액세스 토큰 전문</b>이 그대로 찍혔고 그게 운영 로그에 남았다.
 *
 * <p>게다가 <b>아무 쓸모도 없었다.</b> {@link FeignConfig} 의 인터셉터가 들어온 요청의
 * {@code Authorization} <b>헤더</b>를 이어 붙이므로 인증은 그것으로 이미 된다. 받는 쪽인
 * {@code /api/auth/user/id} 는 이 파라미터를 읽지도 않는다.
 *
 * <p>그래서 파라미터를 지운다. 여기서 {@code @RequestHeader("Authorization")} 으로 다시
 * 선언하면 인터셉터와 겹쳐 같은 헤더가 두 번 간다.
 */
@Component
@FeignClient(name="user-service", url="${feign.user-url}", configuration = FeignConfig.class)
public interface UserServiceClient {

   /**
    * 이메일로 사용자를 찾는다.
    *
    * <p>⚠️ 게이트웨이를 거치지 않는 <b>서비스 간 직접 호출</b>이라 {@code User-Role} 헤더가
    * 붙지 않는다. 받는 쪽은 그것을 "내부 호출" 의 근거로 삼는다
    * ({@code AuthController.findUserByEmail}) — 그러니 이 호출에 {@code User-Role} 을
    * 실어 보내면 <b>관리자가 아니라는 이유로 403</b> 이 된다. 인터셉터가 그 헤더를
    * 전달하지 않는 것은 의도된 것이다.
    */
   @GetMapping("api/auth/user/id")
   public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email);

   /**
    * id 여럿 → 이메일({@code {"<id>": "<email>"}}). 관리자 주문 목록의 표시용이다 — 고객 사본을 없애면서 옮겨 왔다
    * (docs/48 §6-①). user-service 쪽은 내부 전용(신원 헤더가 있으면 관리자만).
    */
   @GetMapping("api/auth/user/emails")
   java.util.Map<String, String> findEmailsByIds(@RequestParam("ids") java.util.List<Long> ids);

   @GetMapping("api/auth/user/name")
   public ResponseEntity<?> findUserByName(@RequestParam("userName") String userName);

   @GetMapping("/api/user/profile/address")
   ResponseEntity<?> getUserInfoWithAddresses();

   @GetMapping("/api/user/profile/select/{addressId}")
    ResponseEntity<?> selectShippingAddresses(@PathVariable("addressId") String addressId);
}
