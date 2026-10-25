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

    /** 서명 키 링(docs/68 §17) — user-service · 게이트웨이와 같은 값. 비어 있으면 JWT_SECRET_KEY 하나(kid "legacy"). */
    // [JA] 署名鍵リング — user-service · ゲートウェイと同じ値。空なら JWT_SECRET_KEY 一つ。
    @Value("${spring.app.jwtSigningKeys:}")
    private String jwtSigningKeys = "";

    @Value("${spring.app.jwtActiveKid:}")
    private String jwtActiveKid = "";

    @Value("${spring.app.jwtAcceptLegacy:true}")
    private boolean jwtAcceptLegacy = true;

    private volatile String ringSource;
    private volatile com.project.common.security.JwtKeyRing ring;

    com.project.common.security.JwtKeyRing ring() {
        String source = secretKey + "\u0000" + jwtSigningKeys + "\u0000" + jwtActiveKid + "\u0000" + jwtAcceptLegacy;
        if (!source.equals(ringSource)) {
            ring = com.project.common.security.JwtKeyRing.of(secretKey, jwtSigningKeys, jwtActiveKid, jwtAcceptLegacy);
            ringSource = source;
        }
        return ring;
    }

    /** 기동 때 한 번 — 키 설정이 틀렸으면 뜨지 않는다. */
    @jakarta.annotation.PostConstruct
    void checkKeyRing() {
        ring();
    }


    

    /** 이미 검증된 토큰의 발급 시각(epoch 초). {@code iat} 가 없으면 0 — 로그인 해제 표시가 있으면 거절된다(docs/68 §9). */
    public long getIssuedAtEpochSec(String token) {
        Date iat = JWT.decode(token).getIssuedAt();
        return iat == null ? 0 : iat.getTime() / 1000;
    }

    /** 이미 검증된 토큰의 만료 시각(epoch ms) — 채팅 연결은 이때 닫힌다(docs/68 §9). */
    public long getExpiresAtEpochMs(String token) {
        return JWT.decode(token).getExpiresAt().getTime();
    }

    public boolean verifyToken(String token) {
        try {
            // 토큰 머리의 kid 로 키를 고른다 — 없으면 옛 토큰(legacy), 모르는 kid 는 거절(docs/68 §17).
            byte[] key = ring().verificationKey(JWT.decode(token).getKeyId()).orElse(null);
            if (key == null) {
                log.info("알 수 없는 서명 키 — 거절");
                return false;
            }
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(key)).build();
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
     * <p>검증한 뒤에만 읽는다 — 검증하지 않은 {@code JWT.decode} 결과를 신뢰용 식별자로 쓰지 않기 위해서다.
     * (예전의 subject(email) 판정 {@code resolveVerifiedSubject} · 검증 없이 subject 를 꺼내던 {@code getUid} 는
     * 쓰는 곳이 없어 지웠다 — 채팅의 신원은 uid 다, docs/48 §6-⑥.)
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
