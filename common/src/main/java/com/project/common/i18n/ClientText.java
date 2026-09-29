package com.project.common.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.context.request.RequestContextHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * 손님에게 가는 서버 문구를 요청 언어로 (docs/60 §4).
 *
 * <h3>왜 "한국어 원문이 키" 인가</h3>
 *
 * <p>서버 문구 약 250개가 예외·응답 생성 자리에 한국어로 박혀 있다({@code throw new OrderDomainException("장바구니가 비어
 * 있습니다…")}). 그것을 전부 메시지 키로 바꾸면 로그·테스트·운영 문서가 모두 키가 된다 — 한국어 운영자가 로그를 못 읽는다.
 * 그래서 코드는 그대로 두고, <b>응답으로 나가는 순간</b> 원문을 카탈로그에서 찾아 바꾼다(gettext 와 같은 방식). 한국어 요청은
 * 아무것도 하지 않는다.
 *
 * <h3>카탈로그</h3>
 *
 * <p>{@code classpath*:i18n/client-text.ja.txt} — 모듈마다 하나(common·각 서비스). 두 줄이 한 쌍이다:
 *
 * <pre>
 * 장바구니가 비어 있습니다. 상품을 담은 뒤 다시 시도해 주세요.
 * = カートが空です。商品を追加してからもう一度お試しください。
 *
 * 재고가 부족해 주문하지 못했습니다: {}. 장바구니에서 수량을 조정해 주세요.
 * = 在庫が不足しているため注文できませんでした：{}。カートで数量を調整してください。
 * </pre>
 *
 * <p>{@code {}} 는 문구 안의 바뀌는 부분이다(원문에서 문자열을 이어 붙인 자리). 번역에서도 같은 순서로 {@code {}}, 순서를 바꿔야
 * 하면 {@code {0}}·{@code {1}}. 바뀌는 부분도 다시 카탈로그로 찾는다 — "티셔츠(M) 품절, 바지(L) 남은 수량 1개" 처럼 조각이
 * 쉼표로 이어진 것은 조각마다. 카탈로그에 없는 문구는 원문 그대로 나간다(빠진 것은 ClientTextCoverageTest 가 잡는다).
 *
 * <p>번역이 {@code = -} 이면 <b>운영자용</b>이라 번역하지 않는다는 표시다 — 기동 시 설정 검증, 직렬화 실패처럼 손님 응답으로
 * 나가지 않는 문구. 검사는 "적혀 있음" 으로 친다(빠뜨린 것과 일부러 두는 것을 구분한다).
 */
@Slf4j
public final class ClientText {

    public static final String CATALOG = "classpath*:i18n/client-text.ja.txt";
    private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7a3]");
    private static final Pattern SLOT = Pattern.compile("\\{(\\d*)\\}");
    private static final int MAX_DEPTH = 3;

    record Rule(Pattern source, String target, int literalLength) {
    }

    /** 한 언어의 카탈로그 — 정확히 같은 문구와, 바뀌는 부분이 있는 문구. */
    static final class Catalog {
        final Map<String, String> exact = new HashMap<>();
        final List<Rule> patterns = new ArrayList<>();
    }

    private static volatile Catalog ja;

    private ClientText() {
    }

    /** 지금 요청의 언어로. 요청 밖(Kafka 리스너·스케줄러)이면 그대로 — 그 문구는 사람에게 가는 응답이 아니다. */
    public static String localize(String message) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return message;
        }
        return localize(message, LocaleContextHolder.getLocale());
    }

    public static String localize(String message, Locale locale) {
        if (message == null || locale == null || !"ja".equals(locale.getLanguage()) || !HANGUL.matcher(message).find()) {
            return message;
        }
        return translate(catalog(), message, 0);
    }

    static String translate(Catalog c, String text, int depth) {
        String exact = c.exact.get(text.strip());
        if (exact != null) {
            return exact;
        }
        if (depth >= MAX_DEPTH) {
            return text;
        }
        // 쉼표로 이은 조각들 — 조각마다 찾는다. 패턴보다 먼저: "{}({}) 품절" 같은 패턴은 목록 전체("A(M) 남은 수량 1개, B(L) 품절")에도
        // 억지로 맞아 버린다. 문장 전체(depth 0)는 쉼표가 문장 안의 쉼표일 수 있어 패턴을 먼저 본다.
        if (depth > 0 && text.contains(", ")) {
            String joined = translateList(c, text, depth);
            if (joined != null) {
                return joined;
            }
        }
        for (Rule rule : c.patterns) {
            Matcher m = rule.source().matcher(text.strip());
            if (m.matches()) {
                List<String> parts = new ArrayList<>();
                for (int i = 1; i <= m.groupCount(); i++) {
                    parts.add(translate(c, m.group(i), depth + 1));
                }
                return fill(rule.target(), parts);
            }
        }
        if (depth == 0 && text.contains(", ")) {
            String joined = translateList(c, text, depth);
            if (joined != null) {
                return joined;
            }
        }
        return text;
    }

    /**
     * 쉼표로 이은 조각을 조각마다 — 하나도 바뀌지 않으면 null.
     *
     * <p>🐛 상품 이름에 ", " 가 들어 있으면("셔츠, 블루(M) 품절") 이름 한가운데서 잘려 두 조각 다 모양에 맞지 않았고, 그 조각들은
     * 원문 그대로 나갔다. 이제 <b>맞는 모양이 나올 때까지 이웃 조각을 이어 붙인다</b> — i 번째에서 시작해 i..j 를 ", " 로 다시 이은
     * 것이 번역되는 가장 짧은 j 를 고른다. 어디까지 이어도 맞지 않으면 그 조각만 원문으로 두고 다음으로 간다.
     */
    private static String translateList(Catalog c, String text, int depth) {
        String[] pieces = text.split(", ");
        List<String> out = new ArrayList<>();
        boolean changed = false;
        int i = 0;
        while (i < pieces.length) {
            int matched = -1;
            String translated = null;
            StringBuilder joined = new StringBuilder();
            for (int j = i; j < pieces.length; j++) {
                if (j > i) {
                    joined.append(", ");
                }
                joined.append(pieces[j]);
                String t = translate(c, joined.toString(), depth + 1);
                if (!t.equals(joined.toString())) {
                    matched = j;
                    translated = t;
                    break;
                }
            }
            if (matched < 0) {
                out.add(pieces[i]);
                i++;
            } else {
                out.add(translated);
                changed = true;
                i = matched + 1;
            }
        }
        return changed ? String.join("、", out) : null;
    }

    private static String fill(String target, List<String> parts) {
        Matcher m = SLOT.matcher(target);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (m.find()) {
            int index = m.group(1).isEmpty() ? next++ : Integer.parseInt(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(index < parts.size() ? parts.get(index) : ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Catalog catalog() {
        Catalog c = ja;
        if (c == null) {
            synchronized (ClientText.class) {
                if (ja == null) {
                    ja = load(CATALOG);
                }
                c = ja;
            }
        }
        return c;
    }

    static Catalog load(String location) {
        Catalog c = new Catalog();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                try (InputStream in = r.getInputStream()) {
                    parseInto(c, new String(in.readAllBytes(), StandardCharsets.UTF_8), r.getDescription());
                }
            }
        } catch (IOException e) {
            log.warn("서버 문구 번역 카탈로그를 읽지 못했다 — 원문 그대로 나간다 ({})", e.getClass().getSimpleName());
        }
        c.patterns.sort(Comparator.comparingInt(Rule::literalLength).reversed());
        return c;
    }

    /** 두 줄이 한 쌍 — 원문, 그리고 "= 번역". '#' 로 시작하는 줄과 빈 줄은 건너뛴다. */
    static void parseInto(Catalog c, String text, String origin) {
        String source = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("=")) {
                if (source == null) {
                    log.warn("번역만 있고 원문이 없는 줄: {} ({})", line, origin);
                    continue;
                }
                String target = line.substring(1).strip();
                if (!"-".equals(target)) {   // "-" = 운영자용 문구(설정 검증·직렬화 실패) — 손님에게 가지 않아 번역하지 않는다
                    add(c, source, target);
                }
                source = null;
            } else {
                source = line;
            }
        }
    }

    static void add(Catalog c, String source, String target) {
        if (!source.contains("{}")) {
            c.exact.put(source, target);
            return;
        }
        StringBuilder regex = new StringBuilder();
        int literal = 0;
        String[] pieces = source.split("\\{\\}", -1);
        for (int i = 0; i < pieces.length; i++) {
            regex.append(Pattern.quote(pieces[i]));
            literal += pieces[i].length();
            if (i < pieces.length - 1) {
                regex.append("(.+?)");
            }
        }
        c.patterns.add(new Rule(Pattern.compile(regex.toString(), Pattern.DOTALL), target, literal));
    }

    /** 테스트용 — 카탈로그를 다시 읽게 한다. */
    static void reset() {
        ja = null;
    }
}
