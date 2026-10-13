package com.project.common.featureflag;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;

/**
 * 플래그 정의 파일 — flagd 형식(https://flagd.dev/schema/v0/flags.json)의 <b>부분집합</b>(docs/74).
 *
 * <pre>
 * { "flags": { "catalog.search-engine": {
 *     "state": "ENABLED",
 *     "variants": { "es": "es", "postgres": "postgres" },
 *     "defaultVariant": "postgres",
 *     "targeting": { "fractional": [ ["es", 10], ["postgres", 90] ] },
 *     "metadata": { "owner": "catalog", "expires": "2027-03-31" } } } }
 * </pre>
 *
 * <p>받는 것은 {@code state} · {@code variants} · {@code defaultVariant} · {@code metadata} 와, targeting 은
 * {@code fractional}(비율 배정) <b>하나뿐</b>이다. 그 밖의 targeting 은 <b>읽을 때 거부한다</b> — 모르는 규칙을
 * 조용히 무시하면 "10% 에만" 이라고 적은 것이 100% 로 돈다. 형식을 flagd 에 맞춘 것은 나중에 flagd 로 옮길 때
 * 파일을 그대로 쓰기 위해서다. 다만 비율 배정의 해시는 flagd 와 같다고 보장하지 않는다 — 옮기면 누가 어느 쪽에
 * 들어가는지는 한 번 바뀐다.
 *
 * <p>[JA] フラグ定義ファイル — flagd 形式の<b>サブセット</b>(docs/74)。受け付けるのは state ・ variants ・
 * defaultVariant ・ metadata と、targeting は {@code fractional}(比率割り当て)<b>だけ</b>である。それ以外の
 * targeting は<b>読み込み時に拒否する</b> — 知らない規則を黙って無視すると「10% だけ」と書いたものが 100% で動く。
 * 形式を flagd に合わせたのは、将来 flagd へ移るときにファイルをそのまま使うためである。ただし比率割り当ての
 * ハッシュが flagd と同じであることは保証しない — 移行すると誰がどちらに入るかは一度変わる。
 */
public final class FlagDefinitions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final FlagDefinitions EMPTY = new FlagDefinitions(Map.of());

    private final Map<String, Flag> flags;

    private FlagDefinitions(Map<String, Flag> flags) {
        this.flags = flags;
    }

    public Flag get(String key) {
        return flags.get(key);
    }

    public Map<String, Flag> all() {
        return flags;
    }

    /** 파일 전체를 읽는다. 하나라도 틀리면 <b>전체를 거부</b>한다 — 호출자는 직전 정의를 유지한다. */
    public static FlagDefinitions parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 이 아니다: " + e.getOriginalMessage());
        }
        if (root == null || !root.path("flags").isObject()) {
            throw new IllegalArgumentException("최상위에 \"flags\" 객체가 없다");
        }
        Map<String, Flag> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.get("flags").properties()) {
            out.put(e.getKey(), Flag.parse(e.getKey(), e.getValue()));
        }
        return new FlagDefinitions(Collections.unmodifiableMap(out));
    }

    /** 플래그 하나. 값은 문자열 · 불리언 · 숫자만 — 객체 값은 1단계에서 쓰지 않는다. */
    public record Flag(String key, boolean enabled, Map<String, Object> variants, String defaultVariant,
            List<Split> fractional, String owner, LocalDate expires) {

        /** 이 대상의 변형(variant)과 그렇게 정해진 이유. 대상 키가 없으면 비율 배정을 하지 않고 기본값이다. */
        public Resolution resolve(String targetingKey) {
            if (fractional.isEmpty()) {
                return new Resolution(defaultVariant, "STATIC");
            }
            if (targetingKey == null || targetingKey.isEmpty()) {
                return new Resolution(defaultVariant, "DEFAULT");
            }
            int total = fractional.stream().mapToInt(Split::weight).sum();
            if (total <= 0) {
                return new Resolution(defaultVariant, "DEFAULT");
            }
            long bucket = bucket(key, targetingKey, total);
            long acc = 0;
            for (Split s : fractional) {
                acc += s.weight();
                if (bucket < acc) {
                    return new Resolution(s.variant(), "SPLIT");
                }
            }
            return new Resolution(defaultVariant, "DEFAULT"); // 도달하지 않는다(bucket < total)
        }

        static Flag parse(String key, JsonNode n) {
            String where = "flags." + key;
            String state = n.path("state").asText("");
            if (!state.equals("ENABLED") && !state.equals("DISABLED")) {
                throw new IllegalArgumentException(where + ".state 는 ENABLED 또는 DISABLED 여야 한다: '" + state + "'");
            }
            JsonNode vs = n.path("variants");
            if (!vs.isObject() || vs.isEmpty()) {
                throw new IllegalArgumentException(where + ".variants 가 비었다");
            }
            Map<String, Object> variants = new LinkedHashMap<>();
            vs.properties().forEach(v -> variants.put(v.getKey(), scalar(where + ".variants." + v.getKey(), v.getValue())));
            String def = n.path("defaultVariant").asText(null);
            if (def == null || !variants.containsKey(def)) {
                throw new IllegalArgumentException(where + ".defaultVariant '" + def + "' 가 variants 에 없다");
            }
            List<Split> fractional = new ArrayList<>();
            JsonNode t = n.get("targeting");
            if (t != null && !(t.isObject() && t.isEmpty())) {
                if (!t.isObject() || t.size() != 1 || !t.has("fractional")) {
                    throw new IllegalArgumentException(where + ".targeting 은 {\"fractional\": [...]} 만 지원한다 — "
                            + "다른 규칙을 조용히 무시하면 의도와 다른 비율로 돈다");
                }
                for (JsonNode pair : t.get("fractional")) {
                    if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).canConvertToInt()) {
                        throw new IllegalArgumentException(where + ".targeting.fractional 의 각 항목은 [\"변형\", 정수비중] 이다");
                    }
                    String variant = pair.get(0).asText();
                    int weight = pair.get(1).asInt();
                    if (!variants.containsKey(variant)) {
                        throw new IllegalArgumentException(where + ".targeting.fractional 의 '" + variant + "' 가 variants 에 없다");
                    }
                    if (weight < 0) {
                        throw new IllegalArgumentException(where + ".targeting.fractional 의 비중은 0 이상이다");
                    }
                    fractional.add(new Split(variant, weight));
                }
                if (fractional.isEmpty()) {
                    throw new IllegalArgumentException(where + ".targeting.fractional 이 비었다");
                }
            }
            JsonNode md = n.path("metadata");
            String owner = md.path("owner").asText(null);
            LocalDate expires = null;
            if (md.hasNonNull("expires")) {
                try {
                    expires = LocalDate.parse(md.get("expires").asText());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(where + ".metadata.expires 는 YYYY-MM-DD 다: " + md.get("expires").asText());
                }
            }
            return new Flag(key, state.equals("ENABLED"), Collections.unmodifiableMap(variants), def,
                    List.copyOf(fractional), owner, expires);
        }

        private static Object scalar(String where, JsonNode v) {
            if (v.isTextual()) return v.asText();
            if (v.isBoolean()) return v.asBoolean();
            if (v.isIntegralNumber()) return v.asLong();
            if (v.isNumber()) return v.asDouble();
            throw new IllegalArgumentException(where + " 는 문자열 · 불리언 · 숫자만 된다");
        }
    }

    public record Split(String variant, int weight) { }

    public record Resolution(String variant, String reason) { }

    /**
     * 0 ≤ bucket &lt; total. 같은 (플래그, 대상) 은 언제나 같은 칸이다 — 비율을 10 → 20 으로 올려도 처음 10 에
     * 들었던 대상은 그대로 남는다(앞쪽 변형의 비중을 늘리는 한). 플래그 키를 섞는 이유: 플래그마다 같은 대상이
     * 늘 같은 쪽에 몰리지 않게.
     *
     * <p>[JA] 同じ(フラグ, 対象)は常に同じ枠になる — 比率を 10 → 20 に上げても、最初の 10 に入っていた対象は
     * そのまま残る(前方の変形の比重を増やす限り)。フラグキーを混ぜるのは、フラグごとに同じ対象が常に同じ側へ
     * 偏らないようにするため。
     */
    static long bucket(String flagKey, String targetingKey, int total) {
        int h = Hashing.murmur3_32_fixed().hashString(flagKey + targetingKey, StandardCharsets.UTF_8).asInt();
        return (Integer.toUnsignedLong(h) * total) >>> 32;
    }
}
