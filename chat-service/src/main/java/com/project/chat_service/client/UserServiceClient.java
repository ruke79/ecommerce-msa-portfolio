package com.project.chat_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.chat_service.common.config.FeignConfig;


/**
 * 마이크로서비스 간의 호출을 위한 feignclient
 */
@FeignClient(name="user-service", url="${feign.user-url}", configuration = FeignConfig.class)
public interface UserServiceClient {

   @GetMapping("api/auth/user/id")
    public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email, @RequestParam("Authorization") String token);
}
