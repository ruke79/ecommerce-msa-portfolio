/**
 * 홈 섹션의 "더 보기"가 갈 곳.
 *
 * <p>🐛 예전에는 "더 보기"·"지금 쇼핑하기"가 <b>링크가 아닌 글자</b>였다 — 손가락 모양
 * 커서와 밑줄까지 있어 눌리는 것처럼 보였지만, 누르면 아무 일도 없었다. 섹션이 어느
 * 카테고리인지 서버가 알려 주지 않았기 때문이다.
 *
 * <p>탐색 화면(`/browse`)은 카테고리를 <b>이름이 아니라 id</b> 로 거른다
 * (`?category=<id>&subCategory=<id>`). 서버가 섹션마다 그 id 를 문자열로 실어 준다
 * (`HomeSectionView.categoryId`·`subcategoryId`, TSID 라 숫자로 받으면 끝자리가 바뀐다).
 *
 * <p>id 가 없으면(카테고리가 지워졌거나 조회 실패) 전체 목록으로 보낸다 — 죽은 링크보다
 * 넓은 목록이 낫다.
 */
export function browseLinkOf(section) {
    const params = new URLSearchParams();
    if (section?.categoryId) {
        params.set("category", String(section.categoryId));
        if (section.subcategoryId) {
            params.set("subCategory", String(section.subcategoryId));
        }
    }
    const query = params.toString();
    return query ? `/browse?${query}` : "/browse";
}
