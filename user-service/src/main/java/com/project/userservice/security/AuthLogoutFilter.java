package com.project.userservice.security;

import java.io.IOException;
import java.io.PrintWriter;

import javax.security.sasl.AuthenticationException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.filter.GenericFilterBean;

import com.project.common.constants.TokenStatus;
import com.project.userservice.constants.TokenType;
import com.project.userservice.model.User;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;

import ch.qos.logback.core.subst.Token;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AuthLogoutFilter extends GenericFilterBean {
    
    
    private final JwtUtils jwtUtils;
    
    private final RefreshTokenService refreshTokenService;    
    
    
    @Autowired
    public AuthLogoutFilter(JwtUtils jwtUtils, RefreshTokenService refreshTokenService) {
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;        
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // TODO Auto-generated method stub

        doFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
        
    }

     private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {

     
        //path and method verify
        String requestUri = request.getRequestURI();
        if (!requestUri.matches("/api/auth/logout$")) {

            filterChain.doFilter(request, response);
            return;
        }
        
        String requestMethod = request.getMethod();
        if (!requestMethod.equals("POST")) {

            filterChain.doFilter(request, response);
            return;
        }
        
       
        String refreshToken = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(TokenType.REFRESH.getType())) {
                    refreshToken = cookie.getValue();
                }
            }
        }

        // Refresh Token이 없는 경우 BadCredentialsException을 던짐
        if (refreshToken == null) {
            log.warn("Refresh cookie not found for logout request");
            throw new BadCredentialsException("Refresh cookie not found");
        }

        try {
            // Refresh Token 만료 여부 검사
            if (jwtUtils.isJwtTokenExpired(refreshToken)) {
                log.warn("Refresh token expired");
                throw new BadCredentialsException("Refresh token expired");
            }
            
            // Refresh Token 유효성 검사 (TokenStatus 로직을 사용하는 경우)
            if (jwtUtils.validateJwtToken(refreshToken) != TokenStatus.VALID || !jwtUtils.isRefreshTokenType(refreshToken)) {
                log.warn("Refresh token invalid");
                throw new BadCredentialsException("Refresh token invalid");
            }

            String email = jwtUtils.getIdFromJwtToken(refreshToken);
            String sessionId = jwtUtils.getSessionIdFromJwtToken(refreshToken);
            
            // 🔴 로그아웃 = 그 로그인의 <b>묶음 전체</b>를 끊는다(docs/68). 예전에는 지금 낸 토큰의 키 하나만 지워서, 갱신 전의 옛
            //    토큰(각자 다른 키)이 로그아웃 뒤에도 살아 있었다 — "탈취가 의심되면 로그아웃" 이 통하지 않았다.
            //    이미 회전된 옛 토큰으로 로그아웃해도 뜻은 같다("이 로그인을 끝내라") — 묶음을 끊고 전처럼 거절 응답을 준다.
            //    [JA] ログアウト = そのログインのファミリー全体を切る。以前は提示したトークンのキー一つだけを消し、更新前の古いトークンが
            //         ログアウト後も生きていた。
            boolean stored = refreshTokenService.findByUserId(sessionId, email).isPresent();
            refreshTokenService.revoke(refreshToken);
            if (!stored) {
                // 이메일은 남기지 않는다(docs/51).
                log.warn("로그아웃 — 저장소에 없는 리프레시 토큰(이미 회전됐거나 만료) — 그 묶음을 끊었다");
                throw new BadCredentialsException("Refresh token not found");
            }

            // 🗑️ 예전에는 여기서 액세스 토큰 <b>원문</b>을 Redis 키로 적었다("블랙리스트"). 그 키를 읽는 곳이 없었다 — 게이트웨이는
            //    서명만 본다. 막는 효과 없이 유효한 토큰만 저장소에 쌓았다(docs/68 §10). 긴 연결은 §9 의 해제 표시가 막는다.

            if ("false".equals(request.getHeader("MultiLogin"))) {
                jwtUtils.removeSession(email);
                log.info("정상 로그아웃");
            } else {
                log.info("중복 로그인 시도에 의한 로그아웃");
            }
            
            // Refresh 토큰 쿠키 만료
            Cookie cookie = new Cookie(TokenType.REFRESH.getType(), null);
            cookie.setMaxAge(0);
            cookie.setPath("/");
            response.addCookie(cookie);

            // 성공 응답 전송
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Logout Success\"}");

        } catch (ExpiredJwtException e) {
            log.warn("Refresh token expired: {}", e.getMessage());
            throw new BadCredentialsException("Refresh token expired", e);
        } catch (AuthenticationException e) {
            throw e; // Spring Security가 처리하도록 예외를 다시 던짐
        } catch (Exception e) {
            log.error("Error during logout process", e);
            throw new ServletException("Internal server error during logout", e);
        }
    }

}
