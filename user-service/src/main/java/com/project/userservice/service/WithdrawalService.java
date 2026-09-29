package com.project.userservice.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.dto.WithdrawalStatusDto;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.ReviewLikeRepository;
import com.project.userservice.repository.jpa.ShippingAddressRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.repository.jpa.WishiItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 탈퇴 — 신청, 취소, 최종 처리.
 *
 * <p><b>왜 즉시 지우지 않는가.</b> 탈퇴는 되돌릴 수 없다. 홧김에 눌렀을 수도, 계정을 빼앗긴
 * 사람 대신 공격자가 눌렀을 수도, 결제 분쟁이 진행 중일 수도 있다. 유예 기간은 그 모든
 * 경우에 되돌릴 시간을 준다.
 *
 * <p><b>왜 유예 기간 동안 계정을 잠그지 않는가.</b> 잠그면 취소할 방법이 사라진다 — 취소
 * 링크를 메일로 보내는 별도 경로를 만들지 않는 한, 로그인할 수 있어야 마음을 바꿀 수 있다.
 * 대신 상태를 API 로 노출해 화면이 "N일 뒤 삭제됩니다 / 취소하기"를 띄운다.
 *
 * <p><b>최종 처리는 행 삭제가 아니라 익명화다.</b> 이 저장소의 주문은 order-service 에 있고
 * {@code customerId} 로 회원을 가리킨다 — 서비스가 달라 외래키가 없으므로 users 행을 지워도
 * DB 는 막지 않지만, 과거 주문이 누구 것인지 영원히 알 수 없게 된다(환불·CS·거래기록 보존).
 * 그래서 <b>식별정보는 지우고 행은 묘비로 남긴다.</b> 사용자 입장에서는 계정이 사라지고,
 * 로그인할 수 없으며, 같은 이메일로 다시 가입할 수 있다.
 *
 * <p>지우는 것: 배송지, 위시리스트, 좋아요, 가입확인·비밀번호재설정 토큰.
 * 남기는 것: 리뷰 본문(작성자는 이미 익명이다 — 지우면 상품 평점이 왜곡된다).
 *
 * <p>다른 서비스의 복제본(chat_member)은 {@code user-deleted} 로 같은 익명화를 한다 — 원본만 지우면 탈퇴의 약속이
 * 절반만 지켜진다(docs/48 §6-⑤).
 *
 * <p>⚠️ 로그에는 userId 만 남긴다. 이메일과 탈퇴 사유는 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final ShippingAddressRepository shippingAddressRepository;
    private final WishiItemRepository wishItemRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final PasswordEncoder passwordEncoder;
    /** 복제본(chat_member)도 식별정보를 지우게 한다 — 같은 트랜잭션의 아웃박스(docs/48 §6-⑤). */
    private final com.project.userservice.message.UserOutboxHelper userOutboxHelper;

    /** 신청부터 최종 처리까지 기다리는 기간. */
    @Value("${app.withdrawal.grace:P7D}")
    private Duration grace;

    @Transactional(readOnly = true)
    public WithdrawalStatusDto status(Long userId) {
        return statusOf(load(userId));
    }

    /**
     * 탈퇴를 신청한다.
     *
     * <p>이미 신청한 상태면 <b>아무것도 바꾸지 않고</b> 현재 상태를 돌려준다. 다시 눌렀다고
     * 시계를 되감으면, 버튼을 두 번 누른 사람의 삭제일이 계속 밀린다.
     */
    @Transactional
    public WithdrawalStatusDto request(Long userId, String rawPassword, String reason) {
        User user = load(userId);

        if (user.isWithdrawalPending()) {
            return statusOf(user);
        }

        requirePassword(user, rawPassword);

        user.setWithdrawalRequestedAt(LocalDateTime.now());
        user.setWithdrawalReason(reason == null || reason.isBlank() ? null : reason.trim());
        userRepository.save(user);

        log.info("회원 탈퇴 신청: userId={}, 처리 예정={}", userId, scheduledAt(user));
        return statusOf(user);
    }

    /** 탈퇴를 취소한다. 신청한 적이 없으면 아무 일도 일어나지 않는다. */
    @Transactional
    public WithdrawalStatusDto cancel(Long userId) {
        User user = load(userId);

        if (user.isWithdrawalPending()) {
            user.setWithdrawalRequestedAt(null);
            user.setWithdrawalReason(null);
            userRepository.save(user);
            log.info("회원 탈퇴 취소: userId={}", userId);
        }

        return WithdrawalStatusDto.none();
    }

    /**
     * 유예 기간이 지난 계정을 최종 처리한다.
     *
     * <p><b>익명화를 먼저 하고 부속 데이터를 지운다.</b> 순서가 중요하다 — 아래 삭제 쿼리들은
     * JPQL 벌크 연산이라 영속성 컨텍스트를 비우므로, 그 뒤에 엔티티를 만지면 준영속 상태에서
     * 병합하게 된다. 같은 트랜잭션 안이라 둘 중 하나만 반영되는 일은 없다.
     *
     * @return 실제로 처리했으면 true. 그 사이 취소했거나 이미 처리됐으면 false.
     */
    @Transactional
    public boolean finalizeWithdrawal(Long userId) {
        User user = userRepository.findById(userId).orElse(null);

        if (user == null || !user.isWithdrawalPending()) {
            // 유예 기간 중에 마음을 바꿨다. 건드리면 안 된다.
            return false;
        }

        anonymize(user);
        userRepository.saveAndFlush(user);
        // 원본만 지우면 복제본에는 이메일·이름·사진이 남는다 — 탈퇴의 약속이 절반만 지켜진다.
        userOutboxHelper.recordUserDeleted(userId);

        reviewLikeRepository.deleteByUserId(userId);
        wishItemRepository.deleteByUserId(userId);
        shippingAddressRepository.deleteByUserId(userId);
        verificationTokenRepository.deleteByUserId(userId);
        passwordResetTokenRepository.deleteByUserId(userId);

        // 되돌릴 수 없으므로 info 가 아니라 warn 으로 남긴다.
        log.warn("회원 탈퇴 최종 처리: userId={}", userId);
        return true;
    }

    /**
     * 식별정보를 지운다.
     *
     * <p>username·email 을 비우지 않고 <b>고유한 대체값</b>으로 바꾸는 이유: 둘 다 유니크
     * 제약이 걸려 있어 여러 명이 탈퇴하면 두 번째부터 제약에 걸린다. 그리고 이메일을 비워
     * 두면 같은 주소로 재가입할 때 "이미 존재한다"가 뜨지 않도록 하려는 목적도 함께 달성된다.
     */
    private void anonymize(User user) {
        // username 은 @Size(max = 20) 이다. TSID 를 36진수로 줄이면 최대 13자라 "del_" 을
        // 붙여도 17자로 들어간다. 10진수 그대로 쓰면 19자 + 접두사로 넘친다.
        String tag = "del_" + Long.toUnsignedString(user.getUserId(), 36);

        user.setUsername(tag);
        user.setEmail(tag + "@deleted.invalid");
        user.setName(null);
        user.setImage(null);
        user.setDefaultPaymentMethod(null);
        user.setTwoFactorSecret(null);
        user.setTwoFactorEnabled(false);
        user.setEmailVerified(false);
        user.setEnabled(false);

        // 빈 비밀번호를 두면 인코더 구현에 따라 예외가 나거나 빈 문자열이 매치될 수 있다.
        // 아무도 모르는 값을 넣어 로그인 경로를 확실히 닫는다.
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));

        user.setWithdrawnAt(LocalDateTime.now());
    }

    /**
     * 본인 확인.
     *
     * <p>소셜 로그인 계정에는 확인할 비밀번호가 없다. 그런 계정에까지 비밀번호를 요구하면
     * <b>탈퇴할 방법 자체가 사라진다.</b>
     */
    private void requirePassword(User user, String rawPassword) {
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()) {
            return;
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, stored)) {
            throw new BadCredentialsException("비밀번호가 일치하지 않습니다.");
        }
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private LocalDateTime scheduledAt(User user) {
        return user.getWithdrawalRequestedAt() == null ? null
                : user.getWithdrawalRequestedAt().plus(grace);
    }

    private WithdrawalStatusDto statusOf(User user) {
        if (!user.isWithdrawalPending()) {
            return WithdrawalStatusDto.none();
        }

        LocalDateTime scheduled = scheduledAt(user);
        long seconds = Duration.between(LocalDateTime.now(), scheduled).getSeconds();
        // 올림한다. 23시간 남았는데 "0일 남음"이라고 쓰면 이미 지난 것처럼 읽힌다.
        long daysLeft = seconds <= 0 ? 0L : (seconds + 86_399L) / 86_400L;

        return new WithdrawalStatusDto(true, user.getWithdrawalRequestedAt(), scheduled, daysLeft);
    }
}
