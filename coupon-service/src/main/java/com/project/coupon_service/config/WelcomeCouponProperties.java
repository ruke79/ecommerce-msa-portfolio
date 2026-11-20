package com.project.coupon_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * 가입 축하 쿠폰 정책.
 *
 * <p><b>예전에는 전부 자바 코드에 박혀 있었다</b> — {@code UserMessageListener} 안에
 * 딱 한 줄이었다:
 *
 * <pre>
 *   new CouponRequest("30% discount coupon", now, now.plusDays(31), 30, userId)
 * </pre>
 *
 * 이름·할인율·유효기간이 모두 리터럴이라, 할인율을 10% 로 바꾸려면 코드를 고치고 서비스를
 * 다시 빌드·배포해야 했다. 판촉 정책은 코드가 아니라 설정이다.
 *
 * <p>여기까지가 이번 범위다. "관리자 화면에서 가입 쿠폰 정책을 편집한다"까지 가려면
 * 정책 테이블과 화면이 필요하다 — 그건 설정으로 충분한 지금 단계에서는 과하다.
 *
 * <p>[JA] 会員登録お祝いクーポンのポリシー。<b>以前はすべて Java コードに埋め込まれていた</b> — {@code UserMessageListener} の中のたった一行だった。名前・割引率・有効期間がすべてリテラルで、割引率を 10% に変えるにはコードを直してサービスを再ビルド・デプロイする必要があった。販促ポリシーはコードではなく設定だ。
 * <p>今回の範囲はここまで。「管理画面で登録クーポンのポリシーを編集する」まで行くにはポリシーテーブルと画面が必要で、設定で十分な今の段階では過剰だ。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "coupon.welcome")
public class WelcomeCouponProperties {

    /**
     * 가입 시 쿠폰을 발급할지.
     *
     * <p>끌 수 있어야 하는 이유: 발급이 실패하면 {@code UserMessageListener} 가 예외를
     * 던지고 그 레코드는 DLT 로 간다. 쿠폰 정책을 잠시 내리고 싶을 때 코드를 고치는 대신
     * 설정으로 끈다.
     *
     * <p>[JA] 登録時にクーポンを発行するか。
     * <p>無効化できるべき理由: 発行が失敗すると {@code UserMessageListener} が例外を投げ、そのレコードは DLT へ行く。クーポンポリシーを一時的に下げたいとき、コードを直す代わりに設定で無効にする。
     */
    private boolean enabled = true;

    /**
     * 쿠폰 이름. 사용자가 화면에서 보는 문자열이자 <b>적용할 때 서버가 찾는 키</b>다
     * ({@code uk_coupon_name_user_id}). 바꾸면 이후 가입자부터 새 이름으로 발급되고,
     * 이미 발급된 쿠폰은 예전 이름 그대로 남아 계속 동작한다.
     *
     * <p>[JA] クーポン名。ユーザーが画面で見る文字列であり、<b>適用時にサーバーが探すキー</b>でもある({@code uk_coupon_name_user_id})。変えると以降の登録者から新しい名前で発行され、既に発行されたクーポンは以前の名前のまま残って動き続ける。
     */
    private String name = "웰컴 10% 할인 쿠폰";

    /** 할인율(%). */
    // [JA] 割引率(%)。
    private int discount = 10;

    /** 발급일로부터 며칠간 유효한가. */
    // [JA] 発行日から何日間有効か。
    private int validDays = 31;
}
