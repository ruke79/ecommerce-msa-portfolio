import { BRAND_NAME } from "constants/brand";

/**
 * 사이트 로고(워드마크).
 *
 * <p><b>예전에는 `assets/images/amazon-logo.png` · `amazon-dark.png` 를 직접 import 해서
 * 썼다</b> — 헤더, 푸터, 로그인·가입·비밀번호 찾기 화면 다섯 곳. 남의 로고를 계정 생성
 * 화면에 거는 것이라 공개 배포 상태에서는 그대로 둘 수 없었다.
 *
 * <p>비트맵 대신 텍스트로 그린다. 이유가 몇 가지 있다:
 *
 * <ul>
 *   <li>이미지 파일이 없어도 되니 <b>지금 당장 교체가 끝난다</b>. 디자인이 나오면
 *       이 컴포넌트 하나만 바꾸면 되고, 부르는 쪽 다섯 곳은 손대지 않는다.</li>
 *   <li>어느 크기에서도 선명하다. 예전 png 는 헤더에서 `w-20`, 푸터에서 `w-20 h-20`
 *       으로 <b>가로세로 비율이 달라진 채</b> 쓰이고 있었다.</li>
 *   <li>배경색에 맞춰 색만 바꾸면 된다 — 밝은 로고/어두운 로고 두 파일을 관리하지
 *       않아도 된다.</li>
 * </ul>
 *
 * @param variant "light" — 어두운 배경 위(헤더·푸터) / "dark" — 밝은 배경 위(로그인 화면)
 */
const BrandMark = ({ variant = "light", className = "", size = "md" }) => {
    const color = variant === "dark" ? "text-amazon-blue_dark" : "text-white";
    const accent = variant === "dark" ? "text-amazon-orange" : "text-amazon-orange";

    const sizes = {
        sm: "text-lg",
        md: "text-2xl",
        lg: "text-3xl",
    };

    return (
        <span
            className={`inline-flex items-baseline font-bold tracking-tight select-none ${sizes[size] ?? sizes.md} ${color} ${className}`}
            // 워드마크는 글자지만 의미상 로고 하나다. 스크린리더가 글자를 쪼개 읽지 않도록.
            role="img"
            aria-label={BRAND_NAME}
        >
            <span aria-hidden="true">ysy</span>
            <span aria-hidden="true" className={accent}>project</span>
        </span>
    );
};

export default BrandMark;
