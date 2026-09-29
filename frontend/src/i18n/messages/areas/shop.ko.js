/**
 * 한국어 문구 — 영역 "shop" (docs/60 §2). 키 앞머리는 영역의 화면 이름으로 한다.
 *
 * <p>영역마다 파일을 나눈 이유: 한 파일(ko.js)에 모두 모으면 여러 사람이 동시에 고칠 때 늘 충돌한다. 합치는 곳은 ../ko.js 이고,
 * 같은 키가 두 파일에 있으면 i18n.test.jsx 가 실패한다.
 */
const shop = {
    // ── 상품 상세 — 비슷한 상품
    "product.similar.title": "비슷한 상품",

    // ── 상품 상세 — 리뷰 목록
    "product.review.title": "고객 리뷰 ({{count}})",
    "product.review.average": "평균 별점",
    "product.review.none": "아직 리뷰가 없습니다.",
    "product.review.login": "로그인하고 리뷰 작성하기",
    "product.review.card.size": "사이즈: {{size}}",
    "product.review.card.fit": "착용감: {{fit}}",
    "product.review.like": "좋아요",
    "product.review.like.undo": "좋아요 취소",
    "product.review.like.login": "로그인이 필요합니다",
    "product.review.like.count": "좋아요 {{count}}",

    // ── 상품 상세 — 리뷰 걸러 보기·정렬(TableHeader). 값은 코드라 번역하지 않고 이름표만 번역한다.
    "product.review.filter.all": "전체",
    "product.review.filter.stars": "별 {{count}}개",
    "product.review.filter.all_styles": "모든 스타일",
    "product.review.order.recommended": "추천순",
    "product.review.order.newest": "최신순",
    "product.review.order.oldest": "오래된순",

    // ── 상품 상세 — 리뷰 쓰기. label·select 의 끝은 Select/TableSelect 의 text 값(size·style·fit·rating·order)이다.
    "product.review.label.size": "사이즈",
    "product.review.label.style": "스타일",
    "product.review.label.fit": "착용감",
    "product.review.label.rating": "별점",
    "product.review.label.order": "정렬",
    "product.review.select.size": "사이즈 선택",
    "product.review.select.style": "스타일 선택",
    "product.review.select.fit": "착용감은 어땠나요?",
    "product.review.select.rating": "별점 선택",
    "product.review.select.order": "정렬 선택",
    "product.review.fit.small": "작아요",
    "product.review.fit.true_to_size": "정사이즈",
    "product.review.fit.large": "커요",
    "product.review.images.add": "사진 추가",
    "product.review.images.max": "이미지는 최대 {{max}}장까지 올릴 수 있습니다.",
    "product.review.images.unsupported": "{{name}} 은(는) 지원하지 않는 형식입니다. JPEG, PNG, WEBP 만 올릴 수 있습니다.",
    "product.review.images.too_large": "{{name}} 의 용량이 너무 큽니다. 최대 {{max}}까지 올릴 수 있습니다.",
    "product.review.placeholder": "리뷰를 작성해 주세요...",
    "product.review.submit": "리뷰 등록",
    "product.review.submitting": "등록 중…",
    "product.review.delete": "리뷰 삭제",
    "product.review.update": "리뷰 수정",
    "product.review.delete_confirm": "내 리뷰를 삭제할까요? 삭제한 리뷰는 되돌릴 수 없습니다.",
    "product.review.error.submit_failed": "리뷰를 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    "product.review.error.delete_header": "리뷰를 삭제할 수 없습니다",
    "product.review.error.delete_failed": "리뷰를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    "product.review.select.empty": "고를 수 있는 항목이 없습니다.",
    "product.review.added.header": "리뷰를 등록했습니다",
    "product.review.added.msg": "소중한 리뷰 감사합니다.",
    "product.review.deleted.header": "리뷰를 삭제했습니다",
    "product.review.deleted.msg": "리뷰가 삭제되었습니다.",
    "product.review.error.header": "리뷰를 등록할 수 없습니다",
    "product.review.error.size": "사이즈를 선택해 주세요.",
    "product.review.error.style": "스타일을 선택해 주세요.",
    "product.review.error.fit": "착용감을 선택해 주세요.",
    "product.review.error.rating": "별점을 선택해 주세요.",
    "product.review.error.review": "리뷰 내용을 입력해 주세요.",

    // ── 상품 목록 — 왼쪽 필터. 성별 값(Men 등)은 서버로 가는 질의 값이라 번역하지 않고 이름표만 번역한다.
    "browse.filter.clear_all": "전체 해제 ({{count}})",
    "browse.filter.category": "카테고리",
    "browse.filter.sizes": "사이즈",
    "browse.filter.colors": "색상",
    "browse.filter.brands": "브랜드",
    "browse.filter.styles": "스타일",
    "browse.filter.materials": "소재",
    "browse.filter.gender": "성별",
    "browse.filter.gender.men": "남성",
    "browse.filter.gender.women": "여성",
    "browse.filter.gender.unisex": "남녀공용",

    // ── 상품 목록 — 결과
    "browse.list.empty": "조건에 맞는 상품이 없습니다.",
    "browse.list.load_more_failed": "상품을 더 불러오지 못했습니다.",
    "browse.list.retry": "다시 시도",
    "browse.list.end": "상품 {{count}}개를 모두 보셨습니다.",

    // ── 장바구니 화면
    "cart.page.title": "장바구니",
    "cart.page.save_failed": "장바구니를 저장하지 못했습니다. 다시 시도해 주세요.",
    "cart.page.update_failed": "장바구니를 바꾸지 못했습니다. 다시 시도해 주세요.",
    "cart.page.remove": "장바구니에서 빼기",

    // ── 기획전
    "event.title": "기획전",
    "event.loading": "불러오는 중…",
    "event.load_failed": "기획전을 불러오지 못했습니다.",
    "event.list.empty": "진행 중인 기획전이 없습니다.",
    "event.audience.new_user": "신규 가입자 전용",
    "event.audience.existing_user": "기존 회원 전용",
    "event.detail.starts_at": "{{from}} 시작",
    "event.detail.not_found": "종료되었거나 찾을 수 없는 기획전입니다.",
    "event.detail.coupon": "{{discount}}% 할인 쿠폰",
    "event.detail.claim": "쿠폰 받기",
    "event.detail.claiming": "받는 중…",
    "event.detail.claimed": "받았습니다",
    "event.detail.claimed_toast": "쿠폰을 받았습니다.",
    "event.detail.login": "로그인하면 쿠폰을 받을 수 있습니다.",
    "event.detail.empty": "이 기획전에 담긴 상품이 없습니다.",
};

export default shop;
