package com.project.userservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.security.util.VerificationResult;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

import java.util.List;
import java.util.Optional;

public interface UserService {

    void updateUserRole(Long userId, String roleName);

    List<User> getAllUsers();

    /** 관리자 사용자 목록 — 응답용 DTO. 엔티티를 그대로 내보내지 않는다(docs/70). */
    List<com.project.userservice.dto.UserProfileDto> getAllUserProfiles();

    UserProfileDto getUserProfileById(Long id);

    User findByUsername(String username);

    void updateAccountLockStatus(Long userId, boolean lock);

    List<Role> getAllRoles();

    void updateAccountExpiryStatus(Long userId, boolean expire);

    void updateAccountEnabledStatus(Long userId, boolean enabled);

    void updateCredentialsExpiryStatus(Long userId, boolean expire);

    void updatePassword(Long userId, String currPassword, String password);

    // Admin
    void updatePassword(Long userId, String password);

    void generatePasswordResetToken(String email);

    void resetPassword(String token, String newPassword);

    LoginResponse getLoginInfoByEmail(String email);

    User findById(Long userId);

    User findByEmail(String email);

    User getUser(final String verificationToken);

    // OAuth 2.0
    User registerOAuthUser(User user);

    User registerNewUserAccount(SignupRequest request);

    void registerConfirmed(User user) throws JsonProcessingException;

    void deleteUser(User user);

    void createVerificationTokenForUser(User user, String rawToken);


    /** 재발송용 토큰을 새로 만들고 메일에 실을 <b>원문</b>을 돌려준다. */
    String regenerateVerificationToken(User user);


    VerificationResult validateAndGetUser(String token);

    GoogleAuthenticatorKey generate2FASecret(Long userId);

    boolean validate2FACode(Long userId, int code);

    void enable2FA(Long userId);

    void disable2FA(Long userId);

    String updatePaymentMethod(String username, String paymentMethod);

    List<String> getUsersFromSessionRegistry();

    UserProfileDto findUserWithAddresses(User user);
    
    UserProfileDto findUserWithdefaultPaymentMethod(User user);

}
