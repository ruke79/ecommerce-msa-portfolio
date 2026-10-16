package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ops/37 · 서비스 간 mTLS 점검 — 신원 헤더(docs/57 §16)를 판정한다.
 *
 * <p>가짜 kubectl 이 ConfigMap · 로그 · 대조 파드의 출력을 돌려준다. 확인하는 것: identity enforce 인데 "인증서 없이
 * {@code User-Role: ROLE_ADMIN}" 이 403 이 아니면 실패, 게이트웨이가 아닌 곳에서 온 신원 헤더({@code [mTLS identity]})가 로그에
 * 있으면 enforce 로 올리라고 하지 않는다.
 *
 * <p>[JA] ops/37 · サービス間 mTLS の点検 — 身元ヘッダーを判定する。偽の kubectl が ConfigMap・ログ・対照 Pod の出力を返す。
 */
@DisplayName("ops/37 · mTLS 점검 — 신원 헤더")
class CheckMtlsScriptTest {

    @TempDir
    Path tmp;

    private Path bin;

    private record Run(int code, String out) {}

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean shellAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "command -v grep && command -v sed").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                    && FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @BeforeEach
    void cluster() throws Exception {
        assumeTrue(shellAvailable(), "bash · grep · sed · POSIX 가 없다");
        bin = Files.createDirectories(tmp.resolve("bin"));
        // 값은 환경 변수로 — 시나리오마다 다르다.
        String kubectl = String.join("\n",
                "#!/bin/sh",
                "case \"$*\" in",
                "  *INTERNAL_AUTH_IDENTITY_MODE*) printf '%s' \"$FAKE_IDENTITY_MODE\" ;;",
                "  *INTERNAL_AUTH_RULES_MODE*) printf enforce ;;",
                "  *INTERNAL_AUTH_PATH_RULES*) printf '/api/order/internal/purchase-stats=user-service' ;;",
                "  *INTERNAL_AUTH_MODE*) printf enforce ;;",
                "  *'get secret'*) exit 0 ;;",
                "  *'rollout status'*) exit 0 ;;",
                "  *'notReadyAddresses'*) printf '' ;;",
                "  *'get endpoints'*) printf 10.42.0.5 ;;",
                "  *'get networkpolicy'*) exit 0 ;;",
                "  *'get deploy/'*) exit 0 ;;",
                "  *'logs deploy/order-service'*) echo '서비스 간 mTLS 판정: mode=ENFORCE'; printf '%b' \"$FAKE_LOGS\" ;;",
                "  *'logs deploy/'*) printf '%b' \"$FAKE_LOGS\" ;;",
                "  *' run '*) printf 'TRIES=0\\nNOCERT=403 0 \\nFORGED=%s 0 \\nWITHCERT=200 0 \\n' \"$FAKE_FORGED\" ;;",
                "esac",
                "");
        Path k = bin.resolve("kubectl");
        Files.writeString(k, kubectl, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(k, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(String identityMode, String forged, String logs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", repoRoot().resolve("ops/37-check-mtls.sh").toString());
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.get("PATH"));
        env.put("FAKE_IDENTITY_MODE", identityMode);
        env.put("FAKE_FORGED", forged);
        env.put("FAKE_LOGS", logs);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    @Test
    @DisplayName("🔴 identity enforce — 인증서 없이 관리자 헤더가 403 이면 통과, 200 이면 실패")
    void enforceExpectsForgedIdentityRejected() throws Exception {
        Run good = run("enforce", "403", "");
        assertThat(good.code()).as(good.out()).isZero();
        assertThat(good.out()).contains("신원 헤더: enforce", "게이트웨이를 거치지 않은 신원 헤더는 버린다(403)");

        Run bad = run("enforce", "200", "");
        assertThat(bad.code()).as(bad.out()).isEqualTo(1);
        assertThat(bad.out()).contains("identity enforce 인데 200");
    }

    @Test
    @DisplayName("🔴 observe — 게이트웨이가 아닌 곳의 신원 헤더가 로그에 있으면 enforce 로 올리라고 하지 않는다")
    void observeCountsUntrustedIdentity() throws Exception {
        Run clean = run("observe", "200", "");
        assertThat(clean.code()).as(clean.out()).isZero();
        assertThat(clean.out()).contains("INTERNAL_AUTH_IDENTITY_MODE: \"enforce\" 로 올린다");

        Run dirty = run("observe", "200",
                "WARN [mTLS identity] 내부 인증서 없이 신원 헤더 — caller=(인증서 없음) GET /api/cart (observe)\\n");
        assertThat(dirty.code()).as(dirty.out()).isEqualTo(1);
        assertThat(dirty.out()).contains("①-3 신원 헤더").doesNotContain("INTERNAL_AUTH_IDENTITY_MODE: \"enforce\" 로 올린다");
    }
}
