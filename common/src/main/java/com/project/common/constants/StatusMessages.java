package com.project.common.constants;


/**
 * 여러 서비스가 응답에 싣는 공통 문구 — <b>손님 화면에 그대로 뜬다.</b>
 *
 * <p>🐛 예전에는 전부 영어 진단 문구였다("Review delete failed", "User not found", "Serach failed"…). 한국어 화면에도
 * 일본어 화면에도 영어가 떴고, 서버 문구 번역(ClientText)은 한국어 원문만 찾으므로 이 문구들은 번역되지도 않았다. 이제
 * 한국어로 적고 번역은 common 카탈로그(i18n/client-text.ja.txt)에 둔다 — 이 파일의 한국어는 ClientTextCoverageTest 가
 * 전부 번역이 있는지 본다.
 */
public final class StatusMessages {

    
    public static final String ACCOUNT_LOCK_STATUS_UPDATED = "계정 잠금 상태를 변경했습니다.";
    public static final String ACCOUNT_EXPIRY_STATUS_UPDATED = "계정 만료 상태를 변경했습니다.";
    public static final String ACCOUNT_ENABLE_STATUS_UPDATED = "계정 사용 상태를 변경했습니다.";
    public static final String CREDENTIALS_EXPIRY_STATUS_UPDATED = "비밀번호 만료 상태를 변경했습니다.";

    public static final String USER_FULLNAME_EXISTS = "같은 이름이 이미 있습니다.";
    public static final String USER_ID_EXISTS = "같은 아이디가 이미 있습니다.";

    /**
     * 가입 중복 — <b>화면에 그대로 뜨는 문구</b>다.
     *
     * <p>예전에는 위의 {@code USER_ID_EXISTS}("The same user Id exists")를 200 과 함께 보냈다.
     * 200 이라 프론트는 성공으로 처리했고 이 문구는 아무도 보지 못했다. 이제 409 로 나가
     * 실제로 사용자에게 보이므로, 영어 진단 문구가 아니라 <b>다음에 뭘 해야 하는지</b>를 적는다.
     *
     * <p>미인증 계정과 인증된 계정을 <b>구분하지 않는다</b> — 구분해서 답하면 그것이 계정
     * 상태를 알려주는 도구가 된다. 대신 두 경우 모두에 도움이 되는 안내를 함께 준다.
     */
    public static final String EMAIL_ALREADY_REGISTERED =
            "이미 가입된 이메일입니다. 로그인해 주세요. 가입 확인 메일을 받지 못했다면 확인 메일 재발송을 이용해 주세요.";

    public static final String USERNAME_ALREADY_TAKEN =
            "이미 사용 중인 이름입니다. 다른 이름을 입력해 주세요.";

    public static final String USER_REGISTRATION_SUCCESS = "회원가입이 완료되었습니다.";

    public static final String USERNAME_IN_USE = "이미 사용 중인 이름입니다.";
    public static final String EMAIL_IN_USE = "이미 사용 중인 이메일입니다.";

    public static final String PASSWORD_UPDATED = "비밀번호를 변경했습니다.";
        
    public static final String CATEGORY_IS_EXISTED = "이미 있는 카테고리입니다.";
    public static final String SUBCATEGORY_IS_EXISTED = "이미 있는 하위 카테고리입니다.";
    public static final String COUPON_IS_EXISTED = "이미 있는 쿠폰입니다.";
    
    public static final String PRODUCT_IS_EMPTY = "상품을 찾을 수 없습니다.";
    /** 장바구니에 담을 수 없다 — 관리자가 품절로 내렸거나, 고른 스타일·사이즈가 없다. */
    public static final String PRODUCT_NOT_AVAILABLE = "품절되었거나 판매하지 않는 상품입니다.";

    public static final String REVIEW_REGISTRATION_FAILED = "리뷰를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    public static final String REVIEW_DELETE_SUCCESS = "리뷰를 삭제했습니다.";
    

    public static final String SERACH_FAILED = "검색하지 못했습니다. 잠시 후 다시 시도해 주세요.";    
    public static final String GET_USERINFO_FAILED = "회원 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    public static final String ADD_WISHLIST_FAILED = "위시리스트에 담지 못했습니다. 잠시 후 다시 시도해 주세요.";
    public static final String GET_COUPONS_FAILED = "쿠폰 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    public static final String REVIEW_DELETE_FAILED = "리뷰를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    public static final String PAYMENT_TRANSACTION_FAILED = "결제를 처리하지 못했습니다";

    public static final String USER_NOT_FOUND = "회원 정보를 찾을 수 없습니다.";
    public static final String USER_ROLE_UPDATED = "회원 권한을 변경했습니다.";
    public static final String CATEGORY_NOT_FOUND = "카테고리를 찾을 수 없습니다.";
    public static final String SUBCATEGORY_NOT_FOUND = "하위 카테고리를 찾을 수 없습니다.";
    public static final String COLOR_ATTRIBUTE_NOT_FOUND = "색상 정보를 찾을 수 없습니다.";
    public static final String ORDER_NOT_FOUND = "주문을 찾을 수 없습니다.";
    public static final String COUPON_NOT_FOUND = "쿠폰을 찾을 수 없습니다.";

    public static final String REVIEW_LOAD_FAILED = "리뷰를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    /** 지울 내 리뷰가 없다 — 예전에는 성공(200)으로 답해 화면이 "삭제되었습니다" 를 띄웠다. */
    public static final String REVIEW_NOT_FOUND = "삭제할 리뷰가 없습니다.";
    public static final String LOGIN_REQUIRED = "로그인이 필요합니다.";
    /** 받은(배송 완료·구매 확정) 상품만 리뷰를 쓸 수 있다(docs/57 §13). */
    public static final String REVIEW_PURCHASE_REQUIRED = "받으신 상품만 리뷰를 작성할 수 있습니다.";
    /** 구매 여부를 묻는 order-service 에 닿지 못했다 — 모르면 받지 않는다. */
    public static final String REVIEW_PURCHASE_CHECK_UNAVAILABLE = "구매 내역을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
