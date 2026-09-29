import { useState } from "react";
import { avatarUrl, initial } from "util/userDisplay";

/**
 * 프로필 사진. 없으면 이니셜 원형을 그린다.
 *
 * <p>🐛 예전에는 `<img src={data.image}>` 를 그냥 그렸다. `image` 는 JWT 에서 온 사용자
 * 객체에 <b>존재하지 않는 필드</b>라 언제나 undefined 였고, React 는 undefined 인 src 를
 * 속성에서 빼 버린다 — 결과는 <b>깨진 이미지 아이콘</b>이다. 사용자에게는 "사진을 아직
 * 안 올림"이 아니라 "이 사이트 고장남"으로 보인다.
 *
 * <p>값이 없는 상태는 정상이다. 정상인 상태를 오류처럼 그리지 않는다. 주소가 있어도 받아지지 않으면(지운 파일·만료된 링크)
 * 같은 이니셜로 바꾼다 — 그것도 깨진 아이콘이 된다.
 *
 * @param alt 사진의 대체 문구. 옆에 이름이 따로 쓰여 있으면 비워 둔다(스크린 리더가 이름을 두 번 읽지 않게).
 */
const Avatar = ({ user, size = 100, className = "", alt = "" }) => {
    const src = avatarUrl(user);
    const px = `${size}px`;
    const [failed, setFailed] = useState(null);

    if (src && failed !== src) {
        return (
            <img
                src={src}
                alt={alt}
                onError={() => setFailed(src)}
                width={size}
                height={size}
                className={`rounded-full object-cover ${className}`}
                style={{ width: px, height: px }}
            />
        );
    }

    return (
        <span
            aria-hidden="true"
            className={`rounded-full bg-amazon-blue_light text-white flex items-center justify-center font-bold select-none ${className}`}
            style={{ width: px, height: px, fontSize: `${Math.round(size * 0.4)}px` }}
        >
            {initial(user)}
        </span>
    );
};

export default Avatar;
