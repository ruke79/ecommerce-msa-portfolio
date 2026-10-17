package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 데모 카탈로그의 자리 표시 이미지 주소 검사(generate-catalog-data.py {@code --placeholder-image}, ops/44 PLACEHOLDER_IMAGE).
 *
 * <p>이 주소는 모든 SKU 이미지 · 색상 견본이 되고, 장바구니 · 주문 줄에 그대로 복사된다({@code image varchar(255)}). 같은
 * 출처 경로나 http(s) 만, 255자 이하, 배열 리터럴을 깨는 글자 없이 — DB 에 닿기 전에 거절한다. 실제 적재는 catalog 의
 * {@code DemoCatalogSeedIntegrationTest}(컨테이너)가 본다.
 *
 * <p>[JA] デモ用プレースホルダー画像アドレスの検査。DB に触れる前に拒否する。
 */
@DisplayName("generate-catalog-data.py — --placeholder-image 주소 검사")
class CatalogPlaceholderImageArgTest {

    private record Run(int code, String out) {}

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static boolean python() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** DSN 은 닿지 않는 주소 — 검사를 통과하면 그 다음(연결 · psycopg2)에서 다른 오류로 멈춘다. */
    private static Run gen(String placeholder) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-I", "ops/tools/generate-catalog-data.py", "--products", "1",
                "--copy", "host=127.0.0.1 port=1 dbname=x user=x connect_timeout=1", "--placeholder-image", placeholder)
                .directory(repoRoot().toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    @ParameterizedTest
    @ValueSource(strings = { "javascript:alert(1)", "/a,b.png", "/x\"}.png" })
    @DisplayName("🔴 /경로 · http(s) 가 아니거나 배열 리터럴을 깨는 글자가 있으면 DB 에 닿기 전에 거절")
    void rejectsBadAddresses(String bad) throws Exception {
        assumeTrue(python(), "python3 가 없다");
        Run r = gen(bad);
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("--placeholder-image 는");
    }

    @Test
    @DisplayName("🔴 255자를 넘으면 거절(장바구니 · 주문 줄 image 가 varchar(255)) — 데모 기본값 /logo512.png 은 통과")
    void lengthLimitAndDefaultPasses() throws Exception {
        assumeTrue(python(), "python3 가 없다");
        Run tooLong = gen("/" + "a".repeat(255));
        assertThat(tooLong.code()).isNotZero();
        assertThat(tooLong.out()).contains("255자 이하");

        Run ok = gen("/logo512.png");
        assertThat(ok.out()).as("검사는 통과하고 그 다음(연결)에서 멈춘다").doesNotContain("--placeholder-image 는");
    }
}
