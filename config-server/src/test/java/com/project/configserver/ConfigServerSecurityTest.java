package com.project.configserver;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * config-server 의 HTTP 인증 — 비밀번호가 없으면 닫히고, 있으면 Basic 으로만 열린다. 프로브 · 지표는 늘 열려 있다.
 *
 * <p>🐛 예전: 인증이 없어 설정(GET /{application}/{profile}) · 복호화(POST /decrypt)를 누구나 불렀다.
 *
 * <p>[JA] config-server の HTTP 認証 — パスワードがなければ閉じ、あれば Basic でだけ開く。プローブ・メトリクスは常に開く。
 */
@DisplayName("config-server — HTTP 인증")
class ConfigServerSecurityTest {

    private static final String PROPS = "management.endpoint.health.probes.enabled=true";

    @Nested
    @SpringBootTest(properties = {PROPS, "config-server.password="})
    @AutoConfigureMockMvc
    @DisplayName("비밀번호가 없을 때")
    class Closed {
        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("🔴 설정 · 복호화 · 그 밖의 액추에이터는 거절, 프로브는 연다")
        void everythingButProbesIsRefused() throws Exception {
            mvc.perform(get("/order-service/default")).andExpect(status().isUnauthorized());
            mvc.perform(post("/decrypt").content("x")).andExpect(status().isUnauthorized());
            mvc.perform(post("/encrypt").content("x")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
            mvc.perform(get("/order-service/default").with(httpBasic(ConfigServerSecurity.USER, "guess")))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            // 전체 health 는 이 테스트 환경에서 DOWN(503)일 수 있다(Eureka · 디스크 등) — 인증이 막지 않는 것만 본다.
            mvc.perform(get("/actuator/health")).andExpect(result -> org.assertj.core.api.Assertions
                    .assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
            mvc.perform(get("/actuator/prometheus")).andExpect(result -> org.assertj.core.api.Assertions
                    .assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
        }
    }

    @Nested
    @SpringBootTest(properties = {PROPS, "config-server.password=s3cret-for-test"})
    @AutoConfigureMockMvc
    @DisplayName("비밀번호가 있을 때")
    class WithPassword {
        @Autowired
        MockMvc mvc;

        @Test
        @DisplayName("🔴 Basic 으로만 통과한다 — 없거나 틀리면 401")
        void onlyBasicPasses() throws Exception {
            mvc.perform(post("/decrypt").content("x")).andExpect(status().isUnauthorized());
            mvc.perform(post("/decrypt").content("x").with(httpBasic(ConfigServerSecurity.USER, "wrong")))
                    .andExpect(status().isUnauthorized());
            // 인증은 통과한다 — 키가 없어 복호화 자체는 실패하지만(4xx/5xx) 401 · 403 은 아니다.
            mvc.perform(post("/decrypt").content("x").with(httpBasic(ConfigServerSecurity.USER, "s3cret-for-test")))
                    .andExpect(result -> {
                        int code = result.getResponse().getStatus();
                        org.assertj.core.api.Assertions.assertThat(code).isNotIn(401, 403);
                    });
            mvc.perform(get("/actuator/env").with(httpBasic(ConfigServerSecurity.USER, "s3cret-for-test")))
                    .andExpect(status().isNotFound());   // 노출 목록 밖(health · info · prometheus 만)
        }
    }
}
