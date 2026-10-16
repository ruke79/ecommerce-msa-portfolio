package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 게이트웨이가 백엔드로 넘길 때 <b>자기 인증서(CN=gateway-server)를 낸다</b> — 받는 쪽은 신원 헤더를 이 인증서로 온 요청에서만
 * 믿는다(InternalCallerFilter, docs/57 §16). 고리 하나가 빠지면 identity enforce 에서 모든 로그인 요청의 신원이 버려진다.
 *
 * <p>고리: 게이트웨이 HttpClient 의 key-store(application-local.yml) ← 매니페스트의 GATEWAY_CLIENT_KEY_STORE · /app/identity 마운트
 * (k8s/31) ← Secret tls-id-gateway-server 발급(ops/38 · ops/39 · k8s/96).
 *
 * <p>[JA] ゲートウェイがバックエンドへ渡すとき自分の証明書を提示する — 受け側は身元ヘッダーをこの証明書で来たリクエストでのみ信じる。
 * 鎖が一つ抜けると identity enforce で全ログインリクエストの身元が捨てられる。
 */
@DisplayName("게이트웨이 클라이언트 인증서 — 신원 헤더를 믿는 근거")
class GatewayClientCertificateGuardTest {

    private static String read(String file) throws IOException {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
        return Files.readString(root.resolve(file));
    }

    @Test
    @DisplayName("🔴 게이트웨이 HttpClient 가 key-store 로 클라이언트 인증서를 낸다(Netty 는 JVM keystore 를 읽지 않는다)")
    void gatewayHttpClientPresentsCertificate() throws IOException {
        String yml = read("gateway-server/src/main/resources/application-local.yml");
        assertThat(yml).contains("key-store: ${GATEWAY_CLIENT_KEY_STORE:}", "key-store-type: PKCS12");
    }

    @Test
    @DisplayName("🔴 매니페스트가 신원 Secret 을 마운트하고 그 keystore 를 가리킨다")
    void manifestMountsGatewayIdentity() throws IOException {
        String k8s = read("k8s/31-gateway-server.yaml");
        assertThat(k8s).contains("name: GATEWAY_CLIENT_KEY_STORE", "value: \"file:/app/identity/keystore.p12\"",
                "mountPath: /app/identity", "secretName: tls-id-gateway-server");
    }

    @Test
    @DisplayName("🔴 신원 Secret 을 발급하는 곳 셋 모두 게이트웨이를 안다 — ops/38 · ops/39 · k8s/96")
    void gatewayIdentityIsIssued() throws IOException {
        assertThat(read("ops/38-issue-service-identities.sh")).containsPattern("SERVICES=\"\\$\\{SERVICES:-gateway-server ");
        assertThat(read("ops/39-cert-manager.sh")).contains("SERVICES=\"gateway-server ");
        assertThat(read("k8s/96-service-identities.yaml")).contains("secretName: tls-id-gateway-server", "commonName: gateway-server");
    }
}
