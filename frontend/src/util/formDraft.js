/**
 * 폼 임시저장 — localStorage 초안.
 *
 * 상품 등록 폼은 길다(이름·설명·사이즈·상세·문답…). 새로고침·탭 닫힘 한 번에 전부
 * 날아가던 것을 초안으로 지킨다.
 *
 * ⚠️ **이미지는 저장하지 않는다.** data URL 은 장당 수백 KB~수 MB 라 localStorage
 * 쿼터(보통 5MB)를 즉시 넘긴다 — 쿼터 초과는 예외가 나며, 잡지 않으면 타이핑마다
 * 터진다. 텍스트 필드만 지키고, 이미지는 복구 후 다시 고르게 한다(파일 선택은
 * 어차피 브라우저 보안상 프로그램으로 복원할 수 없다).
 */

export function saveDraft(key, value) {
    try {
        const { images, color, ...rest } = value ?? {};
        // color.image(base64)도 같은 이유로 뺀다 — 색상값만 남긴다.
        const slim = { ...rest, color: color ? { color: color.color, image: "" } : color };
        localStorage.setItem(key, JSON.stringify(slim));
    } catch (e) {
        // 쿼터 초과·시크릿 모드 등 — 초안은 편의 기능이라 조용히 포기한다.
    }
}

export function loadDraft(key) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        return null;
    }
}

export function clearDraft(key) {
    try {
        localStorage.removeItem(key);
    } catch (e) {
        // 지울 수 없으면 다음 저장이 덮는다.
    }
}
