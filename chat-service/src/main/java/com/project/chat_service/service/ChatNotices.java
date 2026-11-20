package com.project.chat_service.service;

import java.util.List;

/**
 * 채팅의 <b>시스템 문구</b> — 서버가 만들어 메시지 본문·닉네임으로 저장하는 글 (docs/60 §8).
 *
 * <h3>왜 문구 그대로 두고 화면이 번역하나</h3>
 *
 * <p>이 글들은 Kafka(Avro {@code ChatMessage})로 흘러 Mongo 에 저장된다. 코드와 인자를 싣는 필드를 더하려면 Avro 스키마를
 * 두 릴리스에 걸쳐 바꿔야 하고(docs/55), 그래도 <b>이미 저장된</b> 메시지는 문구뿐이다. 문구는 서버가 만든 정해진 모양이라
 * 화면이 알아볼 수 있다 — 화면({@code frontend/src/util/chatNotice.js})이 모양을 알아보고 화면 언어로 다시 그린다. 저장된 옛
 * 메시지도 함께 번역된다.
 *
 * <p>⚠️ 여기 문구를 바꾸면 화면이 알아보지 못해 원문 그대로 나간다. {@link #FORMATS} 의 모양은 화면 파일에도 그대로 있어야
 * 한다 — ChatNoticesContractTest 가 대조한다. 바꿔야 하면 <b>새 모양을 더하고</b>(옛 메시지를 위해 옛 모양은 남긴다) 화면에도
 * 더한다.
 *
 * <p>[JA] チャットの<b>システム文言</b> — サーバが作ってメッセージ本文・ニックネームとして保存する文(docs/60 §8)。
 * <p><b>なぜ文言をそのまま置き画面が翻訳するのか</b>: これらの文は Kafka(Avro {@code ChatMessage})を流れて Mongo に保存される。コードと引数を載せるフィールドを足すには Avro スキーマを二つのリリースにまたがって変えなければならず(docs/55)、それでも<b>すでに保存された</b>メッセージは文言だけである。文言はサーバが作る決まった形なので画面が認識できる — 画面({@code frontend/src/util/chatNotice.js})が形を認識し画面の言語で描き直す。保存済みの古いメッセージも一緒に翻訳される。
 * <p>⚠️ ここの文言を変えると画面が認識できず原文のまま出る。{@link #FORMATS} の形は画面ファイルにもそのままなければならない — ChatNoticesContractTest が照合する。変える必要があるなら<b>新しい形を追加し</b>(古いメッセージのために古い形は残す)画面にも追加する。
 */
public final class ChatNotices {

    private ChatNotices() {
    }

    /** 상담 안내의 발신자 이름. */
    // [JA] 相談案内の送信者名。
    public static final String SUPPORT_TEAM = "고객지원팀";

    /** 탈퇴한 회원의 표시 이름. */
    // [JA] 退会した会員の表示名。
    public static final String WITHDRAWN_NICKNAME = "탈퇴한 사용자";

    /** 회원 정보를 찾지 못했을 때의 표시 이름. */
    // [JA] 会員情報が見つからなかったときの表示名。
    public static final String UNKNOWN_NICKNAME = "회원";

    /** 가입 이벤트가 오기 전의 임시 이름 — "회원 " + id(36진수). */
    // [JA] 加入イベントが来る前の仮の名前 — "회원 " + id(36 進数)。
    public static final String TEMP_NICKNAME_PREFIX = "회원 ";

    public static String entered(String name) {
        return name + " entered.";
    }

    public static String left(String name) {
        return name + " leaved.";
    }

    public static String agentJoined(String name) {
        return "상담원 " + name + " 님이 연결되었습니다.";
    }

    public static final String CLOSED_BY_CUSTOMER = "고객님이 상담을 종료했습니다.";
    public static final String CLOSED_BY_AGENT = "상담원이 상담을 종료했습니다.";
    /** 상담원이 상담을 반납했다 — 다른 상담원이 이어받는다(docs/58 §11). */
    // [JA] 相談員が相談を返却した — 別の相談員が引き継ぐ(docs/58 §11)。
    public static final String RELEASED = "상담원이 바뀝니다. 잠시만 기다려 주세요.";

    public static String tempNickname(long userId) {
        return TEMP_NICKNAME_PREFIX + Long.toUnsignedString(userId, 36);
    }

    /**
     * 화면이 알아봐야 하는 모양 — {@code {name}} 이 바뀌는 자리. 화면 파일(util/chatNotice.js)에 같은 글자로 있어야 한다.
     * ("leaved" 는 틀린 영어지만 저장된 메시지가 이 모양이라 그대로 둔다 — 화면이 번역하므로 손님에게는 보이지 않는다.)
     *
     * <p>[JA] 画面が認識すべき形 — {@code {name}} が置き換わる位置。画面ファイル(util/chatNotice.js)に同じ文字で存在しなければならない。
     * ("leaved" は誤った英語だが保存済みメッセージがこの形なのでそのままにする — 画面が翻訳するので客には見えない。)
     */
    public static final List<String> FORMATS = List.of(
            entered("{name}"), left("{name}"), agentJoined("{name}"),
            CLOSED_BY_CUSTOMER, CLOSED_BY_AGENT, RELEASED,
            SUPPORT_TEAM, WITHDRAWN_NICKNAME, UNKNOWN_NICKNAME, TEMP_NICKNAME_PREFIX + "{id}");
}
