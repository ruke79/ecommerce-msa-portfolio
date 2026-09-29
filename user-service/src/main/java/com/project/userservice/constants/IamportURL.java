package com.project.userservice.constants;

public enum IamportURL {
    GET_TOKEN_URL("https://api.iamport.kr/users/getToken"),
    CANCEL_URL("https://api.iamport.kr/payments/cancel");
    private String url;

    public String getURL() {
        return url;
    }

    IamportURL(String url) {
        this.url = url;
    }
}
