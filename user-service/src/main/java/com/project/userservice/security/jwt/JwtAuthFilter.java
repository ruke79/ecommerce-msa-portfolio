package com.project.userservice.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.project.common.constants.TokenStatus;
import com.project.userservice.constants.TokenType;
import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.security.service.UserDetailsServiceImpl;

import io.jsonwebtoken.ExpiredJwtException;

import java.io.IOException;
import java.io.PrintWriter;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private UserDetailsServiceImpl userDetailsService;
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        log.info("AuthTokenFilter called for URI: {}", request.getRequestURI());

        String requestUri = request.getRequestURI();

        if (requestUri.matches("/user-service/registrationConfirm(?:\\/.*)?$")) {

            filterChain.doFilter(request, response);
            return;
        }

        if (requestUri.matches("/api/auth/public/signin(?:\\/.*)?$")) {

            filterChain.doFilter(request, response);
            return;
        }

        if (requestUri.matches("^\\/oauth2(?:\\/.*)?$")) {

            filterChain.doFilter(request, response);
            return;
        }

        if (requestUri.matches("/api/token(?:\\/.*)?$")) {

            filterChain.doFilter(request, response);
            return;
        }


        
        String accessToken = parseJwt(request);

        
        
        // 🐛 만료 검사가 주석 처리돼 있었고 getIdFromJwtToken 은 만료 예외를 삼켰다 — user-service 는 만료된 액세스 토큰을
        //    유효로 받았다. 리프레시 토큰도 액세스로 통했다(JwtUtils.TOKEN_TYPE_CLAIM).
        //
        // 유효한 액세스 토큰일 때만 인증을 세운다. 아니면 인증 없이 다음으로 넘긴다 — 보호된 경로는 SecurityConfig 의
        // JwtAuthEntryPoint 가 401 을 주고(화면은 401 이면 토큰을 갱신한다), 공개 경로(가입·비밀번호 찾기)는 오래된 쿠키가
        // 실려 와도 막히지 않는다.
        if (accessToken != null
                && jwtUtils.validateJwtToken(accessToken) == TokenStatus.VALID
                && jwtUtils.isAccessToken(accessToken)
                && ObjectUtils.isEmpty(redisTemplate.opsForValue().get(accessToken))) {
            try {
                String email = jwtUtils.getIdFromJwtToken(accessToken);
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException e) {
                // 탈퇴 등으로 사용자를 찾지 못했다 — 인증 없이 넘긴다.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String jwt = jwtUtils.getJwtFromHeader(request);
        return jwt;
    }
}
