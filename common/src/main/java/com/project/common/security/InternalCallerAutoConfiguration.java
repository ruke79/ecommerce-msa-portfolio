package com.project.common.security;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import lombok.extern.slf4j.Slf4j;

/**
 * 서비스 간 mTLS 판정 필터를 모든 서블릿 서비스에 건다(docs/57 §16). 게이트웨이(WebFlux)는 대상이 아니다 — 판정은 받는 쪽이 한다.
 *
 * <ul>
 *   <li>{@code internal-auth.mode} — off(기본)·observe·enforce</li>
 *   <li>{@code internal-auth.ca-cert} — 내부 CA 인증서(PEM). 기본은 {@code LOCAL_CACERT_PATH}(k8s 에서는 /app/resources/ca.crt)</li>
 *   <li>{@code internal-auth.allowed-subjects} — 쉼표로 구분한 CN 목록. 비우면 내부 CA 가 서명한 인증서 전부</li>
 *   <li>{@code internal-auth.watch-paths} — observe 에서 경고할 내부 경로 조각</li>
 *   <li>{@code internal-auth.path-rules} — {@code 경로앞부분=CN|CN;경로앞부분=CN} — 그 경로를 부를 수 있는 서비스</li>
 *   <li>{@code internal-auth.rules-mode} — 허용 주체·경로 규칙을 observe(남기기만)·enforce(막기) 중 무엇으로. 기본 enforce</li>
 * </ul>
 *
 * <p>enforce 인데 CA 를 읽지 못하면 <b>기동을 멈춘다</b> — 그대로 뜨면 모든 내부 호출이 조용히 403 이 된다.
 *
 * <p>[JA] サービス間 mTLS 判定フィルターをすべてのサーブレットサービスに適用する(docs/57 §16)。ゲートウェイ(WebFlux)は
 * 対象外である — 判定は受け側が行う。
 *
 * <ul>
 *   <li>{@code internal-auth.mode} — off(デフォルト)・observe・enforce</li>
 *   <li>{@code internal-auth.ca-cert} — 内部 CA 証明書(PEM)。デフォルトは {@code LOCAL_CACERT_PATH}
 *       (k8s では /app/resources/ca.crt)</li>
 *   <li>{@code internal-auth.allowed-subjects} — カンマ区切りの CN リスト。空なら内部 CA が署名した証明書すべて</li>
 *   <li>{@code internal-auth.watch-paths} — observe で警告する内部パスの断片</li>
 *   <li>{@code internal-auth.path-rules} — {@code パス接頭辞=CN|CN;パス接頭辞=CN} — そのパスを呼び出せるサービス</li>
 *   <li>{@code internal-auth.rules-mode} — 許可主体・パスルールを observe(記録のみ)・enforce(遮断)のどちらで
 *       扱うか。デフォルトは enforce</li>
 * </ul>
 *
 * <p>enforce なのに CA を読めなければ<b>起動を止める</b> — そのまま起動すると、すべての内部呼び出しが静かに 403 になる。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalCallerAutoConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "internalCallerFilter")
    public FilterRegistrationBean<InternalCallerFilter> internalCallerFilter(
            @Value("${internal-auth.mode:off}") String mode,
            @Value("${internal-auth.ca-cert:${LOCAL_CACERT_PATH:}}") String caPath,
            @Value("${internal-auth.allowed-subjects:}") String allowed,
            @Value("${internal-auth.watch-paths:/internal/,/api/product/rating,/api/auth/user/}") String watch,
            @Value("${internal-auth.path-rules:}") String pathRules,
            @Value("${internal-auth.rules-mode:enforce}") String rulesMode) {
        return build(mode, caPath, allowed, watch, pathRules, rulesMode);
    }

    FilterRegistrationBean<InternalCallerFilter> build(String mode, String caPath, String allowed, String watch,
            String pathRules, String rulesMode) {

        InternalCallerFilter.Mode m = parse(mode);
        InternalCallerFilter.Mode rm = parse(rulesMode);
        List<InternalCallerFilter.PathRule> rules = parseRules(pathRules);
        var ca = m == InternalCallerFilter.Mode.OFF ? null : InternalCallerFilter.loadCa(caPath);
        if (m == InternalCallerFilter.Mode.ENFORCE && ca == null) {
            throw new IllegalStateException("internal-auth.mode=enforce 인데 내부 CA 인증서를 읽지 못했다(internal-auth.ca-cert / "
                    + "LOCAL_CACERT_PATH). 이대로 뜨면 모든 내부 호출이 403 이 된다 — docs/57 §16");
        }
        if (m == InternalCallerFilter.Mode.OBSERVE && ca == null) {
            log.warn("internal-auth.mode=observe 인데 내부 CA 인증서가 없다 — 모든 헤더 없는 요청이 '인증서 없음' 으로 보인다");
        }
        if (m != InternalCallerFilter.Mode.OFF) {
            log.info("서비스 간 mTLS 판정: mode={} ca={}", m, ca == null ? "없음" : InternalCallerFilter.commonName(ca));
            if (!split(allowed).isEmpty() || !rules.isEmpty()) {
                log.info("서비스 간 mTLS 규칙: rules-mode={} 허용 주체 {}개 경로 규칙 {}개", rm, split(allowed).size(), rules.size());
            }
        }

        FilterRegistrationBean<InternalCallerFilter> bean = new FilterRegistrationBean<>(
                new InternalCallerFilter(m, ca, split(allowed), List.copyOf(split(watch)), rules, rm));
        // 인가 판정(인터셉터·컨트롤러·스프링 시큐리티)보다 먼저 — 그것들이 읽는 헤더를 이 필터가 바로잡는다.
        // [JA] 認可判定(インターセプター・コントローラー・Spring Security)より先に — それらが読むヘッダーを
        //      このフィルターが正す。
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.setName("internalCallerFilter");
        return bean;
    }

    static InternalCallerFilter.Mode parse(String mode) {
        try {
            return InternalCallerFilter.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalStateException("internal-auth.mode 는 off·observe·enforce 중 하나여야 한다: " + mode);
        }
    }

    /** {@code /api/order/internal/purchase-stats=user-service;/api/internal/stock=order-service|admin} — 사람이 적는 값이라 틀리면 기동을 멈춘다. */
    // [JA] {@code /api/order/internal/purchase-stats=user-service;/api/internal/stock=order-service|admin}
    //      — 人が書く値なので、誤っていれば起動を止める。
    static List<InternalCallerFilter.PathRule> parseRules(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<InternalCallerFilter.PathRule> out = new java.util.ArrayList<>();
        for (String part : raw.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0 || !p.startsWith("/")) {
                throw new IllegalStateException("internal-auth.path-rules 형식: /경로앞부분=CN|CN;… — 이 조각을 읽지 못했다: " + p);
            }
            Set<String> callers = Arrays.stream(p.substring(eq + 1).split("\\|")).map(String::trim).filter(x -> !x.isEmpty())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (callers.isEmpty()) {
                throw new IllegalStateException("internal-auth.path-rules — 부를 수 있는 서비스가 비었다: " + p);
            }
            out.add(new InternalCallerFilter.PathRule(p.substring(0, eq).trim(), Set.copyOf(callers)));
        }
        return List.copyOf(out);
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
