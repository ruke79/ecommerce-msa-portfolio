/**
 * 업로드 전 클라이언트 리사이즈.
 *
 * 왜 필요한가: 폰 사진 원본은 장당 5~12MB 다. 상품 등록은 이미지 3장 + 스타일 이미지를
 * **한 요청**으로 보내므로 원본 그대로면 수십 MB 가 되고, 두 군데서 터진다:
 *
 *   · Cloudflare 무료 플랜의 업로드 본문 100MB 제한 — 엣지에서 잘려 **서버 로그에
 *     아무것도 남지 않는다** (docs/33 §11). 원인을 찾을 수 없는 실패가 된다
 *   · 상품 상세가 그 원본을 그대로 내려준다 — 페이지 하나에 수십 MB
 *
 * 긴 변 1920px, JPEG 품질 0.85 면 화면용으로 충분하고 장당 수백 KB 로 줄어든다.
 *
 * 반환은 data URL — 기존 파이프라인(FileReader.readAsDataURL → 미리보기 → 제출 시
 * dataURItoBlob)이 전부 data URL 을 전제하므로 형태를 유지한다.
 */

export const MAX_DIMENSION = 1920;
export const JPEG_QUALITY = 0.85;

/**
 * 목표 크기 계산 — 리사이즈의 유일한 산수라 따로 떼어 테스트한다.
 * 긴 변이 한도 이하면 원본 크기 그대로(확대는 절대 하지 않는다 — 화질만 상한다).
 */
export function targetDimensions(width, height, max = MAX_DIMENSION) {
    const longSide = Math.max(width, height);
    if (longSide <= max) {
        return { width, height, resized: false };
    }
    const scale = max / longSide;
    return {
        width: Math.round(width * scale),
        height: Math.round(height * scale),
        resized: true,
    };
}

/**
 * File → 리사이즈된 data URL.
 *
 * 실패(디코드 불가·canvas 미지원)하면 **원본 data URL 로 폴백**한다 — 리사이즈는
 * 최적화이지 게이트가 아니다. 여기서 실패를 던지면 정상적인 이미지 업로드까지 막는다.
 */
export function fileToResizedDataUrl(file, max = MAX_DIMENSION, quality = JPEG_QUALITY) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () => reject(reader.error);
        reader.onload = () => {
            const original = reader.result;
            const img = new Image();
            img.onerror = () => resolve(original);   // 디코드 실패 → 원본 폴백
            img.onload = () => {
                try {
                    const { width, height, resized } = targetDimensions(img.width, img.height, max);
                    if (!resized) {
                        resolve(original);
                        return;
                    }
                    const canvas = document.createElement("canvas");
                    canvas.width = width;
                    canvas.height = height;
                    canvas.getContext("2d").drawImage(img, 0, 0, width, height);
                    // PNG 의 투명도는 JPEG 로 가면 사라진다 — 투명 가능성이 있는 포맷은
                    // PNG 로 유지한다. 사진(대부분 JPEG)만 품질 손실 압축을 받는다.
                    const type = file.type === "image/png" ? "image/png" : "image/jpeg";
                    resolve(canvas.toDataURL(type, quality));
                } catch (e) {
                    resolve(original);               // canvas 실패 → 원본 폴백
                }
            };
            img.src = original;
        };
        reader.readAsDataURL(file);
    });
}
