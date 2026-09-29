import { useEffect } from "react";

/** 탭 제목의 꼬리표. 제목이 없을 때는 이것만 쓴다. */
export const SITE_NAME = "ysyproject";

/**
 * 탭 제목을 세운다.
 *
 * <p>🐛 예전에는 `Header.jsx` 가 `<header>` 안에 `<title>{title}</title>` 을 그렸다.
 * React 18 은 `<title>` 을 `<head>` 로 올리지 않는다 — `<body>` 안의 그 태그를 브라우저는
 * 무시한다(호이스팅은 React 19 부터). 게다가 `public/index.html` 에도 `<title>` 이 없어서
 * <b>탭에 URL 이 그대로 보였다.</b>
 *
 * <p>`react-helmet-async` 가 이미 의존성에 있지만, 그것을 쓰려면 앱 전체를
 * `HelmetProvider` 로 감싸야 한다. 제목 한 줄을 위해 그럴 필요는 없다.
 *
 * <p>정리(cleanup)에서 이전 제목으로 되돌린다. 되돌리지 않으면 제목을 세우지 않는 화면으로
 * 이동했을 때 <b>이전 화면의 제목이 남는다.</b>
 */
export function useDocumentTitle(title) {
  useEffect(() => {
    const previous = document.title;
    document.title = title ? `${title} | ${SITE_NAME}` : SITE_NAME;

    return () => {
      document.title = previous;
    };
  }, [title]);
}

export default useDocumentTitle;
