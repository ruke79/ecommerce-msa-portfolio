package com.project.common.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 응답 본문의 <b>안내 문구 자리</b>만 요청 언어로 바꾼다 (docs/60 §4) — {@link ClientText}.
 *
 * <p>바꾸는 자리: 본문이 문자열이면 그 문자열, 맵이면 {@code message·error·detail·reason} 값, 알려진 응답 봉투
 * ({@link #ENVELOPES})면 같은 이름의 문자열 필드와 {@code errors} 문자열 목록. <b>그 아래로는 내려가지 않는다</b> — 채팅 메시지·상품 설명처럼 사람이 쓴 글이
 * 들어 있는 필드를 건드리지 않기 위해서다. 한국어 요청이면 아무것도 하지 않는다.
 *
 * <p>[JA] レスポンスボディの<b>案内文言の箇所</b>だけをリクエスト言語に置き換える (docs/60 §4) — {@link ClientText}。
 *
 * <p>置き換える箇所: ボディが文字列ならその文字列、マップなら {@code message·error·detail·reason} の値、既知のレスポンスエンベロープ
 * ({@link #ENVELOPES})なら同名の文字列フィールドと {@code errors} 文字列リスト。<b>それより下には降りない</b> — チャットメッセージ・商品説明のように
 * 人が書いた文章が入っているフィールドに触れないためである。韓国語のリクエストなら何もしない。
 */
@RestControllerAdvice
public class ClientTextResponseAdvice implements ResponseBodyAdvice<Object> {

    static final Set<String> MESSAGE_FIELDS = Set.of("message", "error", "detail", "reason");

    /**
     * 안내 문구를 싣는 응답 봉투 — 이 타입만 속성을 바꾼다. 이름으로 고른 이유: {@code message} 라는 필드는 채팅 메시지·알림
     * 본문처럼 <b>사람이 쓰거나 저장된 글</b>에도 있다. 그것을 번역 카탈로그에 대면 우연히 맞는 문장이 바뀔 수 있다.
     *
     * <p>[JA] 案内文言を載せるレスポンスエンベロープ — この型だけプロパティを置き換える。名前で選んだ理由: {@code message} というフィールドは
     * チャットメッセージ・通知本文のように<b>人が書いた、または保存された文章</b>にもある。それを翻訳カタログに照合すると、
     * 偶然一致した文が置き換わってしまうおそれがある。
     */
    static final Set<String> ENVELOPES = Set.of(
            "MessageResponse", "ApiError", "GenericResponse", "ApiResponse", "ErrorMessage", "CouponResponse", "LoginResponse");

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !"ja".equals(LocaleContextHolder.getLocale().getLanguage())) {
            return body;
        }
        try {
            return localizeBody(body);
        } catch (RuntimeException e) {
            return body;   // 번역 때문에 응답이 실패하면 안 된다 — 원문 그대로 ／ [JA] 翻訳のせいでレスポンスが失敗してはならない — 原文のまま
        }
    }

    static Object localizeBody(Object body) {
        if (body instanceof String s) {
            return ClientText.localize(s);
        }
        if (body instanceof Map<?, ?> map) {
            boolean touches = map.keySet().stream().anyMatch(k -> MESSAGE_FIELDS.contains(String.valueOf(k)) || "errors".equals(k));
            if (!touches) {
                return body;
            }
            Map<Object, Object> copy = new LinkedHashMap<>(map);   // Map.of 는 바꿀 수 없다 ／ [JA] Map.of は変更できない
            copy.replaceAll((k, v) -> MESSAGE_FIELDS.contains(String.valueOf(k)) && v instanceof String s ? ClientText.localize(s)
                    : "errors".equals(k) ? localizeList(v) : v);
            return copy;
        }
        if (!ENVELOPES.contains(body.getClass().getSimpleName())) {
            return body;
        }
        for (Class<?> type = body.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                String name = field.getName();
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !(MESSAGE_FIELDS.contains(name) || "errors".equals(name))) {
                    continue;
                }
                try {
                    field.setAccessible(true);   // final 인 필드(chat ApiResponse)도 — 레코드가 아닌 보통 클래스라 된다
                    // [JA] final なフィールド(chat ApiResponse)も — レコードではなく通常のクラスなので可能である
                    Object value = field.get(body);
                    if (value instanceof String s) {
                        field.set(body, ClientText.localize(s));
                    } else if ("errors".equals(name) && value instanceof List<?>) {
                        field.set(body, localizeList(value));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // 못 바꾸면 원문 그대로
                    // [JA] 置き換えられなければ原文のまま
                }
            }
        }
        return body;
    }

    private static Object localizeList(Object value) {
        if (!(value instanceof List<?> list)) {
            return value;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add(o instanceof String s ? ClientText.localize(s) : o);
        }
        return out;
    }
}
