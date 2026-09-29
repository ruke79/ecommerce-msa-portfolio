package com.project.chat_service.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;

import com.project.chat_service.common.exception.handler.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class TokenHandler {
    
    
    @Value("${spring.app.jwtSecret}")
    private String secretKey;


    

    /** 이미 검증된 토큰의 발급 시각(epoch 초). {@code iat} 가 없으면 0 — 로그인 해제 표시가 있으면 거절된다(docs/68 §9). */
    public long getIssuedAtEpochSec(String token) {
        Date iat = JWT.decode(token).getIssuedAt();
        return iat == null ? 0 : iat.getTime() / 1000;
    }

    /** 이미 검증된 토큰의 만료 시각(epoch ms) — 채팅 연결은 이때 닫힌다(docs/68 §9). */
    public long getExpiresAtEpochMs(String token) {
        return JWT.decode(token).getExpiresAt().getTime();
    }

    public String getUid(String token) {
        DecodedJWT decodedJWT = JWT.decode(token);
        return decodedJWT.getSubject();
    }

    public boolean verifyToken(String token) {
        try {
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(secretKey.getBytes())).build();
            DecodedJWT decodedJWT = verifier.verify(token);
            // 🔒 액세스 토큰만 — 리프레시 토큰(같은 키로 서명, 14일)으로 STOMP·API 에 붙지 못하게(user-service JwtUtils.TOKEN_TYPE_CLAIM).
            if (!"access".equals(decodedJWT.getClaim("tokenType").asString())) {
                log.info("액세스 토큰이 아니다 — 거절");
                return false;
            }
            return decodedJWT.getExpiresAt().after(new Date());
        } catch (Exception e) {
            log.info("토큰 검증 실패");
            return false;
        }
    }

    /**
     * "Bearer {token}" 헤더에서 토큰을 추출하고 서명/만료를 검증한 뒤 subject(email)를 반환한다.
     * 검증되지 않은 getUid()의 결과를 신뢰용 식별자로 사용하지 않기 위한 유일한 진입점.
     */
    public String resolveVerifiedSubject(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null || !verifyToken(token)) {
            throw ErrorCode.unauthorizedTokenException();
        }
        return getUid(token);
    }

    public String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }

    /**
     * 검증된 토큰의 {@code uid} 클레임(user-service 의 userId).
     *
     * <p>chat-service 의 {@code chat_member.member_id} 는 user-service 의 userId 를 그대로
     * 쓴다(ChatMember 주석). 회원 행이 없어 즉석에서 만들어야 할 때 그 값이 필요하다.
     *
     * <p>{@link #resolveVerifiedSubject} 와 같은 검증 경로를 지난다 — 검증하지 않은
     * {@code JWT.decode} 결과를 신뢰용 식별자로 쓰지 않기 위해서다.
     *
     * @return uid 클레임. 없거나 숫자가 아니면 null.
     */
    public Long resolveVerifiedUserId(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null || !verifyToken(token)) {
            throw ErrorCode.unauthorizedTokenException();
        }
        return getUserId(token);
    }

    /**
     * 이미 검증된 <b>원시 토큰</b>의 {@code uid} 클레임.
     *
     * <p>STOMP CONNECT 는 {@code Authorization} 네이티브 헤더에 토큰을 그대로 싣는다
     * ("Bearer " 접두사가 없다). 그래서 {@link #resolveVerifiedUserId} 를 쓸 수 없다 —
     * 그쪽은 HTTP 헤더 모양을 기대한다. 호출 전에 {@link #verifyToken} 을 통과시킬 것.
     *
     * @return uid 클레임. 없거나 숫자가 아니면 null.
     */
    public Long getUserId(String token) {
        try {
            return JWT.decode(token).getClaim("uid").asLong();
        } catch (RuntimeException e) {
            log.warn("토큰에 uid 클레임이 없거나 숫자가 아니다");
            return null;
        }
    }

    /**
     * 이미 검증된 원시 토큰의 {@code role} 클레임(예: {@code ROLE_SUPPORT}). 상담 데스크 권한(docs/58)에 쓴다.
     * 호출 전에 {@link #verifyToken} 을 통과시킬 것.
     *
     * @return 없으면 null
     */
    public String getRole(String token) {
        try {
            return JWT.decode(token).getClaim("role").asString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Bearer {token}" 헤더를 검증하고 {@code role} 클레임을 돌려준다. 검증에 실패하면 401. */
    public String resolveVerifiedRole(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null || !verifyToken(token)) {
            throw ErrorCode.unauthorizedTokenException();
        }
        return getRole(token);
    }
}
