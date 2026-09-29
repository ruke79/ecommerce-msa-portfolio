package com.project.userservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 기기 식별에 쓰는 순수 계산 — 토큰 만들기·해싱, 라벨 뽑기, IP 마스킹.
 *
 * <p>서비스에서 떼어 둔 이유: 이 셋은 요청도 DB 도 필요 없는 문자열 변환이고, 틀리면
 * <b>조용히</b> 틀린다(알림이 안 나가거나, 매번 나가거나, 남의 IP 가 노출되거나).
 * 독립적으로 표를 만들어 고정할 수 있게 한다.
 */
public final class DeviceIdentity {

    /** 기기 쿠키 이름. */
    public static final String COOKIE_NAME = "DEVICE";

    /**
     * 쿠키 수명 — 1년.
     *
     * <p>짧으면 그만큼 자주 만료돼 "새 기기" 알림이 나간다. 이 쿠키는 인증 수단이 아니라
     * <b>기억</b>이므로 오래 살아도 위험이 늘지 않는다. 반대로 영구(무한)로 두지 않는
     * 이유는 공용 PC 에 남긴 흔적이 영영 남지 않게 하기 위해서다.
     */
    public static final int COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceIdentity() {
    }

    /** 새 기기 토큰. 32바이트 난수 — 추측할 수 없어야 한다(그 이유는 KnownDevice 주석). */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 저장용 해시. 원문은 쿠키에만 있고 DB 에는 이 값만 들어간다. */
    public static String hash(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준이라 여기 올 수 없다. 올 수 있다면 기기 인식이 통째로
            // 불가능한 것이므로 감추지 않는다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    /** 요청에 실려 온 기기 쿠키. 없으면 null. */
    public static String tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * User-Agent 에서 "Chrome · Windows" 같은 표시용 문자열을 만든다.
     *
     * <p><b>원문을 그대로 저장하지 않는 이유.</b> User-Agent 는 지문 가치가 있는 값이고
     * (확장·버전까지 담긴다), 사용자에게 필요한 것은 "어느 브라우저, 어느 운영체제였나"
     * 뿐이다. 필요한 만큼만 남긴다.
     *
     * <p>순서가 중요하다 — Edge 의 UA 에는 "Chrome" 이, Chrome 의 UA 에는 "Safari" 가
     * 들어 있다. 좁은 것부터 본다.
     */
    public static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase();

        String browser;
        if (ua.contains("edg/") || ua.contains("edge")) {
            browser = "Edge";
        } else if (ua.contains("opr/") || ua.contains("opera")) {
            browser = "Opera";
        } else if (ua.contains("samsungbrowser")) {
            browser = "Samsung Internet";
        } else if (ua.contains("firefox")) {
            browser = "Firefox";
        } else if (ua.contains("chrome") || ua.contains("crios")) {
            browser = "Chrome";
        } else if (ua.contains("safari")) {
            browser = "Safari";
        } else {
            browser = "알 수 없는 브라우저";
        }

        String os;
        if (ua.contains("android")) {
            os = "Android";
        } else if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) {
            os = "iOS";
        } else if (ua.contains("windows")) {
            os = "Windows";
        } else if (ua.contains("mac os") || ua.contains("macintosh")) {
            os = "macOS";
        } else if (ua.contains("linux")) {
            os = "Linux";
        } else {
            os = null;
        }

        String label = os == null ? browser : browser + " · " + os;
        return label.length() > 120 ? label.substring(0, 120) : label;
    }

    /**
     * 표시용 IP.
     *
     * <p><b>마지막 옥텟을 가린다.</b> 전체를 보여 주면 사용자 자신에게도, 그 화면을 어깨
     * 너머로 보는 사람에게도 필요 이상의 정보가 된다. "203.0.113.xxx" 만으로도 "집에서
     * 들어온 것 같다 / 모르는 곳이다" 는 판단이 된다.
     *
     * <p>⚠️ 이 값은 <b>표시 전용</b>이다. {@code X-Forwarded-For} 는 클라이언트가 위조할
     * 수 있으므로(게이트웨이의 {@code CustomUserKeyResolver} 가 같은 이유로 무시한다)
     * 어떤 판정에도 쓰지 않는다. 새 기기인지는 쿠키가 정한다.
     */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String trimmed = ip.trim();

        // IPv6 — 앞 두 그룹만 남긴다. 그 이상은 특정 회선을 가리킬 수 있다.
        if (trimmed.contains(":")) {
            String[] groups = trimmed.split(":");
            if (groups.length >= 2) {
                return groups[0] + ":" + groups[1] + ":xxxx";
            }
            return "xxxx";
        }

        String[] octets = trimmed.split("\\.");
        if (octets.length == 4) {
            return octets[0] + "." + octets[1] + "." + octets[2] + ".xxx";
        }
        // 형식을 모르겠으면 통째로 가린다 — 반쯤 아는 값을 흘리는 것보다 낫다.
        return "xxx";
    }

    /**
     * 요청의 클라이언트 IP(원문).
     *
     * <p>게이트웨이를 거치면 {@code getRemoteAddr()} 은 게이트웨이의 주소다. 그래서
     * {@code X-Forwarded-For} 의 <b>첫 항목</b>(클라이언트에 가장 가까운 값)을 쓴다.
     *
     * <p>🔒 그 값은 <b>클라이언트가 위조할 수 있다.</b> 그래도 쓰는 이유는 이 값이 오직
     * 알림 문구에만 들어가고 어떤 판정에도 쓰이지 않기 때문이다 — 위조해 봤자 자기
     * 알림의 IP 표시를 바꾸는 것뿐이다. 레이트리밋처럼 <b>판정에 쓰는 곳에서는 절대
     * 이렇게 하면 안 된다</b>(게이트웨이가 그래서 이 헤더를 버린다).
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
