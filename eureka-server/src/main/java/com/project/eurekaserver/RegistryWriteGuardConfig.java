package com.project.eurekaserver;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.project.common.security.InternalCallerFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link RegistryWriteGuard} 를 Eureka 의 Jersey 앞에 건다. enforce 인데 내부 CA 를 읽지 못하면 <b>기동을 멈춘다</b> — 그대로 뜨면
 * 모든 서비스의 하트비트가 403 이 되어 90초 뒤 등록부가 비고, 서비스 간 호출이 전부 끊긴다.
 *
 * <p>[JA] RegistryWriteGuard を Eureka の Jersey の前に掛ける。enforce なのに内部 CA を読めなければ起動を止める —
 * そのまま起動するとすべてのハートビートが 403 になり、登録が空になる。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RegistryWriteGuardConfig {

    @Bean
    public FilterRegistrationBean<RegistryWriteGuard> registryWriteGuard(
            @Value("${eureka-guard.write-mode:${EUREKA_WRITE_AUTH_MODE:off}}") String mode,
            @Value("${eureka-guard.ca-cert:${LOCAL_CACERT_PATH:}}") String caPath) {
        return build(mode, caPath);
    }

    /**
     * common 의 {@link InternalCallerFilter}(서비스 간 <b>내부 API</b> 판정)는 Eureka 에서 끈다 — Eureka 에는 내부 API 가 없고, 이 서버의
     * 판정은 {@link RegistryWriteGuard} 가 한다. 같은 이름의 빈이 있으면 common 의 자동 구성이 물러난다.
     *
     * <p>🐛 켜 둔 채 클라이언트 인증서를 받기 시작하면(client-auth=want), 허용 주체(INTERNAL_AUTH_ALLOWED_SUBJECTS) 밖의 CN — config-server —
     * 이 하트비트를 보낼 때마다(30초) {@code [mTLS rule] 허용 주체가 아니다} 경고가 남는다(그 경고는 중복을 거르지 않는다).
     *
     * <p>[JA] common の InternalCallerFilter は Eureka では無効にする — 内部 API がなく、判定は RegistryWriteGuard が行う。有効のままだと
     * 許可主体外の config-server のハートビートごとに警告が残る。
     */
    @Bean(name = "internalCallerFilter")
    public FilterRegistrationBean<InternalCallerFilter> internalCallerFilter() {
        var bean = new FilterRegistrationBean<>(new InternalCallerFilter(InternalCallerFilter.Mode.OFF, null,
                java.util.Set.of(), java.util.List.of()));
        bean.setEnabled(false);
        return bean;
    }

    static FilterRegistrationBean<RegistryWriteGuard> build(String mode, String caPath) {
        RegistryWriteGuard.Mode m = RegistryWriteGuard.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        var ca = m == RegistryWriteGuard.Mode.OFF ? null : InternalCallerFilter.loadCa(caPath);
        if (m == RegistryWriteGuard.Mode.ENFORCE && ca == null) {
            throw new IllegalStateException("eureka-guard.write-mode=enforce 인데 내부 CA 를 읽지 못했다: " + caPath);
        }
        log.info("Eureka 등록 쓰기 판정: mode={} (CA {})", m, ca == null ? "없음" : "읽음");
        var bean = new FilterRegistrationBean<>(new RegistryWriteGuard(m, ca));
        bean.addUrlPatterns("/eureka/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
