package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B-82: 바깥(Cloudflare 터널)이 게이트웨이로 보내는 경로 = 게이트웨이가 라우팅하는 경로.
 *
 * <p>운영 터널(k8s-overlays/prod/cloudflared.yaml)은 정규식에 맞는 경로만 게이트웨이로 보내고 나머지는 traefik → 프론트 nginx 로
 * 보낸다. nginx 는 모르는 경로에 {@code index.html} 200 을 준다(SPA 의 try_files). 그래서 게이트웨이에 라우트를 더하고 터널을
 * 잊으면 <b>오류가 아니라 HTML 200</b> 이 돌아오고, 게이트웨이까지 오지 않아 서버 로그에도 없다.
 *
 * <p>🐛 실제로 그랬다: 주문 진행 타임라인(docs/76)의 {@code /saga-projection/...} 이 터널 정규식에 없었다. B-49 는 프론트 경로와
 * 게이트웨이를 대조하지만 그 목록도 {@code *-service} 뿐이라 이 경로를 보지 않았다.
 *
 * <p>[JA] 外(Cloudflare トンネル)がゲートウェイへ送るパス = ゲートウェイがルーティングするパス。トンネルを忘れるとエラーではなく
 * HTML 200 が返り、サーバーログにも残らない。saga-projection が実際に抜けていた。
 */
@DisplayName("B-82: 터널이 게이트웨이로 보내는 경로 = 게이트웨이 라우트의 첫 경로 조각")
class EdgeRoutingGuardTest {

    private static Path root() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /** 게이트웨이 Path= 술어들의 첫 경로 조각(/user-service, /saga-projection …). */
    static TreeSet<String> gatewayPrefixes() throws IOException {
        String yml = Files.readString(root().resolve("gateway-server/src/main/resources/application-local.yml"))
                .replaceAll("\\\\\\s*\\n\\s*", " ");
        TreeSet<String> out = new TreeSet<>();
        Matcher route = Pattern.compile("^\\s*-\\s*Path=(.+)$", Pattern.MULTILINE).matcher(yml);
        while (route.find()) {
            for (String raw : route.group(1).split(",")) {
                Matcher first = Pattern.compile("^/([A-Za-z0-9_-]+)").matcher(raw.trim());
                if (first.find()) {
                    out.add("/" + first.group(1));
                }
            }
        }
        return out;
    }

    /** 터널에서 게이트웨이로 가는 규칙들의 path 정규식. */
    static List<Pattern> tunnelToGateway(String cloudflared) {
        List<Pattern> out = new ArrayList<>();
        Matcher rule = Pattern.compile("-\\s*path:\\s*(\\S+)\\s*\\n\\s*service:\\s*https?://gateway-server[^\\s]*").matcher(cloudflared);
        while (rule.find()) {
            out.add(Pattern.compile(rule.group(1)));
        }
        return out;
    }

    @Test
    @DisplayName("🔴 게이트웨이 라우트의 모든 첫 조각이 운영 터널에서 게이트웨이로 간다 — 아니면 프론트 nginx 의 index.html 200")
    void tunnelForwardsEveryGatewayPrefix() throws IOException {
        TreeSet<String> prefixes = gatewayPrefixes();
        assertThat(prefixes).as("게이트웨이 술어를 읽었다").contains("/user-service", "/saga-projection");

        List<Pattern> toGateway = tunnelToGateway(Files.readString(root().resolve("k8s-overlays/prod/cloudflared.yaml")));
        assertThat(toGateway).as("터널에 게이트웨이로 가는 규칙이 있다").isNotEmpty();

        List<String> missing = prefixes.stream()
                .filter(p -> toGateway.stream().noneMatch(r -> r.matcher(p + "/x").find()))
                .toList();
        assertThat(missing).as("터널이 게이트웨이로 보내지 않는 게이트웨이 접두사 — cloudflared.yaml 의 path 정규식에 더한다").isEmpty();
    }

    @Test
    @DisplayName("터널 정규식은 접두사 경계를 지킨다 — /user-servicex · 프론트 경로는 게이트웨이로 가지 않는다")
    void tunnelRegexKeepsBoundaries() throws IOException {
        List<Pattern> toGateway = tunnelToGateway(Files.readString(root().resolve("k8s-overlays/prod/cloudflared.yaml")));
        for (String notApi : List.of("/user-servicex/a", "/products/1", "/", "/static/js/main.js")) {
            assertThat(toGateway.stream().anyMatch(r -> r.matcher(notApi).find())).as(notApi).isFalse();
        }
    }
}
