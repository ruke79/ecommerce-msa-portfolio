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
    // [JA] 管理者のユーザー一覧 — 応答用 DTO。エンティティをそのまま出さない(docs/70)。
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

    /**
     * 가입 + 확인 메일 의도(아웃박스)를 <b>한 트랜잭션</b>에(/public/register, docs/93 ③ 2단계). 메일은 RegistrationMailRelay 가 보낸다.
     * [JA] 登録 + 確認メールの意図(アウトボックス)を一つのトランザクションで。
     */
    User registerNewUserAccountWithConfirmationMail(SignupRequest request, java.util.Locale locale);

    /** 확인 메일 재발송 — 아웃박스에 적는다(보내기 · 재시도는 릴레이). [JA] 確認メールの再送 — アウトボックスに書く。 */
    void requestVerificationMail(User user, java.util.Locale locale);

    void registerConfirmed(User user) throws JsonProcessingException;

    void deleteUser(User user);

    void createVerificationTokenForUser(User user, String rawToken);


    /** 재발송용 토큰을 새로 만들고 메일에 실을 <b>원문</b>을 돌려준다. */
    // [JA] 再送用のトークンを新しく作り、メールに載せる<b>原文</b>を返す。
    String regenerateVerificationToken(User user);


    VerificationResult validateAndGetUser(String token);

    GoogleAuthenticatorKey generate2FASecret(Long userId);

    boolean validate2FACode(Long userId, int code);

    void enable2FA(Long userId);

    void disable2FA(Long userId);

    /** 로그인 중의 2FA 변경 결과(docs/68 §14). */
    // [JA] ログイン中の 2FA 変更結果(docs/68 §14)。
    /*
    /*
     * NO_PHONE · PHONE_CHANGED · UNAVAILABLE 은 SMS 수단에서만 나온다(docs/24 §10) — 인증된 번호가 없다 · 켤 때의 번호와 다르다 ·
     * notification 에 닿지 못했다(그때는 통과시키지 않는다).
     *
     * <p>[JA] NO_PHONE · PHONE_CHANGED · UNAVAILABLE は SMS 手段でだけ出る(docs/24 §10) — 認証済みの番号がない · 有効化時の番号と違う · notification に届かなかった(そのときは通さない)。
     */
    enum TwoFactorChange { OK, INVALID_CODE, TOO_MANY_ATTEMPTS, ALREADY_ENABLED, NOT_ENABLED, NO_PHONE, PHONE_CHANGED, UNAVAILABLE }

    /** 설정 시작 — 이미 켜져 있으면 비밀을 바꾸지 않는다(끄고 다시 켠다). */
    // [JA] 設定開始 — 既に有効なら秘密を変えない(無効にしてから再度有効にする)。
    TwoFactorChange start2FASetup(Long userId);

    /** 설정 확인 — 새 앱의 코드가 맞으면 켠다. */
    // [JA] 設定確認 — 新しいアプリのコードが合えば有効にする。
    TwoFactorChange confirm2FASetup(Long userId, int code);

    /** 끄기 — 지금 코드가 맞아야 한다. */
    // [JA] 無効化 — 現在のコードが合わなければならない。
    TwoFactorChange disable2FA(Long userId, int code);

    /**
     * 로그인 2단계의 코드 확인 — 사용자별 실패 한도를 관리 경로와 같이 센다(docs/68 §15).
     * OK · INVALID_CODE · TOO_MANY_ATTEMPTS · NOT_ENABLED.
     *
     * <p>[JA] ログイン二段階目のコード確認 — ユーザー別の失敗上限を管理経路と同じく数える(docs/68 §15)。OK · INVALID_CODE · TOO_MANY_ATTEMPTS · NOT_ENABLED。
     */
    TwoFactorChange verifyLoginTwoFactor(Long userId, int code);

    String updatePaymentMethod(String username, String paymentMethod);

    List<String> getUsersFromSessionRegistry();

    UserProfileDto findUserWithAddresses(User user);
    
    UserProfileDto findUserWithdefaultPaymentMethod(User user);

}
