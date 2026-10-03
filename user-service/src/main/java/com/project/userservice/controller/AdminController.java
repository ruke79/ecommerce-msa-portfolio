package com.project.userservice.controller;

import com.project.common.exception.ClientMessage;
 

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.project.common.constants.StatusMessages;
import com.project.common.response.MessageResponse;

import com.project.userservice.dto.CouponDto;

import com.project.userservice.dto.UserProfileDto;

import com.project.userservice.model.User;


import com.project.userservice.service.UserService;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;


import java.util.List;


@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminController {

    private final UserService userService;

    
    @GetMapping("/getusers")
    public ResponseEntity<List<com.project.userservice.dto.UserProfileDto>> getAllUsers() {
        // 🔒 엔티티(User)를 그대로 내보내지 않는다(docs/70). 예전에는 모든 사용자의 2FA 비밀(TOTP)이 응답에 실렸다 —
        //    관리자 화면 · 관리자 토큰 하나가 새면 전원의 2단계 인증이 무력화된다. 응답용 DTO 로만.
        //    [JA] エンティティをそのまま返さない。以前は全ユーザーの 2FA 秘密が応答に載っていた。
        return new ResponseEntity<>(userService.getAllUserProfiles(), HttpStatus.OK);
    }

    @PutMapping("/update-role")
    public ResponseEntity<String> updateUserRole(@RequestParam String userId,
            @RequestParam String roleName) {
        userService.updateUserRole(Long.parseLong(userId), roleName);
        return ResponseEntity.ok(StatusMessages.USER_ROLE_UPDATED);
    }

    @GetMapping("/user/{id}")
    public ResponseEntity<UserProfileDto> getUser(@PathVariable String id) {
        return new ResponseEntity<>(userService.getUserProfileById(Long.parseLong(id)),
                HttpStatus.OK);
    }

    @PutMapping("/update-lock-status")
    public ResponseEntity<String> updateAccountLockStatus(@RequestParam String userId,
            @RequestParam boolean lock) {
        userService.updateAccountLockStatus(Long.parseLong(userId), lock);
        return ResponseEntity.ok(StatusMessages.ACCOUNT_LOCK_STATUS_UPDATED);
    }

    /** 역할 목록 — 이름과 id 만(id 는 문자열, 브라우저의 숫자 정밀도). */
    record RoleView(String roleId, String roleName) {
    }

    /**
     * 🐛 예전: {@code Role} 엔티티를 그대로 돌려줬다. {@code Role.users} 가 {@code @JsonManagedReference} 라 Jackson 이
     * <b>모든 사용자</b>(이메일 · 등급 · 잠금 · 탈퇴 사유, 주소 · 전화번호까지 지연 로딩으로)를 직렬화했다 — 관리자 화면이라도
     * 내보낼 이유가 없는 값이고, 리뷰의 지연 프록시에 닿으면 500 이었다. 응답은 DTO 로만(TotpSecretExposureTest 가 Role 도 본다).
     */
    // [JA] 以前は Role エンティティをそのまま返し、Role.users 経由で全ユーザー(メール · 住所 · 電話番号まで)を直列化していた。
    @GetMapping("/roles")
    List<RoleView> getAllRoles() {
        return userService.getAllRoles().stream()
                .map(r -> new RoleView(String.valueOf(r.getRoleId()), r.getRoleName().name()))
                .toList();
    }

    @PutMapping("/update-expiry-status")
    ResponseEntity<String> updateAccountExpiryStatus(@RequestParam String userId,
            @RequestParam boolean expire) {
        userService.updateAccountExpiryStatus(Long.parseLong(userId), expire);
        return ResponseEntity.ok(StatusMessages.ACCOUNT_EXPIRY_STATUS_UPDATED);
    }

    @PutMapping("/update-enabled-status")
    ResponseEntity<String> updateAccountEnabledStatus(@RequestParam String userId,
            @RequestParam boolean enabled) {
        userService.updateAccountEnabledStatus(Long.parseLong(userId), enabled);
        return ResponseEntity.ok(StatusMessages.ACCOUNT_ENABLE_STATUS_UPDATED);
    }

    @PutMapping("/update-credentials-expiry-status")
    ResponseEntity<String> updateCredentialsExpiryStatus(@RequestParam String userId,
            @RequestParam boolean expire) {
        userService.updateCredentialsExpiryStatus(Long.parseLong(userId), expire);
        return ResponseEntity.ok(StatusMessages.CREDENTIALS_EXPIRY_STATUS_UPDATED);
    }

    @PutMapping("/update-password")
    ResponseEntity<String> updatePassword(@RequestParam String userId,
            @RequestParam String password) {
        try {
            userService.updatePassword(Long.parseLong(userId), password);
            return ResponseEntity.ok(StatusMessages.PASSWORD_UPDATED);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ClientMessage.of(e));
        }
    }
    

    // DB에 이미지 저장할 때
    // @PostMapping("/product")
    // public ResponseEntity<?> addProduct(
    // @RequestPart("product") ProductRequest request,
    // @RequestPart("images") MultipartFile[] images,
    // @RequestPart("colorImage") MultipartFile colorImage) throws IOException {

    // Product product = productRepository.findByName(request.getName());

    // if (product != null)
    // return null;

    // ProductSku skuProject = productService.addProduct(request, images,
    // colorImage);

    // return new ResponseEntity<>(skuProject, HttpStatus.OK);

    // }

      
}
