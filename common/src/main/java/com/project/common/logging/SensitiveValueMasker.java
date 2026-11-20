package com.project.common.logging;

import com.fasterxml.jackson.core.JsonStreamContext;

import net.logstash.logback.mask.ValueMasker;

/**
 * JSON 로그용 마스킹 — logstash 인코더의 {@code MaskingJsonGeneratorDecorator} 에 등록된다.
 *
 * <p>메시지·MDC·구조화 인자·스택트레이스까지 <b>모든 문자열 값</b>이 직렬화되기 직전에 여기를
 * 지난다. 규칙은 {@link SensitiveDataMasker} 하나다 — 콘솔과 JSON 이 다르게 가리면 한쪽으로 샌다.
 *
 * <p>[JA] JSON ログ用のマスキング — logstash エンコーダーの {@code MaskingJsonGeneratorDecorator} に登録される。
 * メッセージ · MDC · 構造化引数 · スタックトレースまで<b>すべての文字列値</b>がシリアライズ直前にここを通る。規則は {@link SensitiveDataMasker} 一つ — コンソールと JSON で隠し方が違えば片方から漏れる。
 */
public class SensitiveValueMasker implements ValueMasker {

    /**
     * @return 가려야 하면 바꿀 값, 그대로 두면 {@code null}
     *
     * <p>[JA] 戻り値: 隠すべきなら置き換える値、そのままにするなら {@code null}。
     */
    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof CharSequence text)) {
            return null;
        }
        String original = text.toString();
        if (!SensitiveDataMasker.containsSensitiveData(original)) {
            return null;
        }
        return SensitiveDataMasker.mask(original);
    }
}
