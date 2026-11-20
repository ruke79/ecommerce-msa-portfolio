package com.project.userservice.security.response;

public interface OAuth2Response {

    //제공자 (Ex. naver, google, ...)
    // [JA] プロバイダー(例: naver, google, ...)
    String getProvider();
    //제공자에서 발급해주는 아이디(번호)
    // [JA] プロバイダーが発行する ID(番号)
    String getProviderId();
    //이메일
    // [JA] メールアドレス
    String getEmail();
    //사용자 실명 (설정한 이름)
    // [JA] ユーザーの実名(設定した名前)
    String getName();
}
