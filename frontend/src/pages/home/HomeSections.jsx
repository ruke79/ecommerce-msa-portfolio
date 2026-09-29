import GridCategory from "./GridCategory";
import { browseLinkOf } from "./browseLink";
import HomeProductSwiper from "components/home/HomeProductSwiper";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 서버가 내려 준 홈 섹션을 배치한다.
 *
 * <p>예전에는 이 배치가 코드에 박혀 있었다 — {@code CategoryProducts.jsx} 안에
 * {@code <GridCategory category="fashion" .../>} 다섯 줄, {@code Home.jsx} 안에
 * {@code <HomeProductSwiper category="books" .../>} 다섯 줄. 카테고리 이름을 바꾸거나
 * 기획전을 하나 올리려면 프론트를 고쳐 다시 배포해야 했고, 이름이 어긋나면
 * (필터가 0건을 돌려주므로) <b>아무 오류 없이 그 줄만 사라졌다.</b>
 *
 * <p>이제 무엇을 어떤 순서로 보여줄지는 {@code content.home_section} 테이블이 정하고,
 * 여기는 레이아웃만 안다: GRID 는 캐러셀 위로 겹쳐 올라가는 상단 격자, SWIPER 는 그 아래
 * 가로 스크롤 줄.
 */
const HomeSections = ({ sections, error }) => {
    const { t } = useTranslation();
    const list = Array.isArray(sections) ? sections : [];
    const grids = list.filter((s) => s.layout === "GRID");
    const swipers = list.filter((s) => s.layout === "SWIPER");

    // 실패와 "아직 아무것도 안 만듦"을 구분해서 알린다. 예전에는 둘 다 똑같이
    // 빈 화면이었고(loader 가 null 을 돌려주고 `products &&` 로 통째로 감췄다),
    // 운영자는 어느 쪽인지 알 방법이 없었다.
    if (list.length === 0) {
        return (
            <div className="relative z-10 p-8 text-center text-slate-500">
                {error
                    ? t("home.sections.error")
                    : t("home.sections.empty")}
            </div>
        );
    }

    return (
        <>
            {/*
              * 🐛 상품 격자가 캐러셀을 덮고 있었다.
              *
              * 예전 클래스는 `-mt-16 sm:-mt-32 md:-mt-48 lg:-mt-80` — **고정 픽셀** 음수
              * 마진으로 격자를 끌어올려 배너 위에 겹쳤다(lg 에서 -20rem = -320px).
              * 의도는 아마존식 "배너 하단에 격자가 걸치는" 레이아웃이다.
              *
              * 문제는 캐러셀 높이가 **유동**이라는 점이다. 배너는 폭 100% 로 그려지므로
              * 높이 = 뷰포트 폭 ÷ 종횡비다. 고정 마진 vs 가변 높이라, 브레이크포인트와
              * 실제 폭이 어긋나는 구간에서 격자가 **배너 한가운데까지** 올라왔다.
              *
              * 겹치는 양을 배너 높이에 **비례**시킨다. 캐러셀은 5:2 이므로
              * 높이 = 폭 × 0.4 다. 그 20%(= 폭 × 0.08)만큼만 겹친다 — 폭이 얼마든
              * 배너 아래쪽 1/5 에 걸치고, 위로 올라가 배너를 덮는 일이 없다.
              *
              * `100vw` 가 아니라 `min(100vw, 1536px)` 인 이유: 부모가
              * `max-w-screen-2xl`(1536px) 로 묶여 있다. 넓은 화면에서 vw 로 계산하면
              * 실제 배너 폭보다 큰 값이 나와 겹침이 의도보다 깊어진다.
              */}
            {/*
              * 🐛 마지막 줄에 빈 칸이 남았다.
              *
              * `md:grid-cols-4` 는 **열 개수를 고정**한다. 섹션 개수는 데이터가 정하는데
              * (시드는 GRID 5개), 5 를 4 로 나누면 마지막 줄에 카드 하나 + **빈 칸 셋**이다.
              * `grid-flow-row-dense` 는 뒤에 오는 작은 항목을 앞의 구멍에 끼워 넣는 것이라
              * 여기서는 아무 일도 하지 않았다 — 모든 카드가 1칸이면 메울 구멍이 없다.
              *
              * flex 줄바꿈으로 바꾸면 **열 개수를 내용이 정한다.** `flex-1 basis-[220px]` 는
              * "220px 정도가 적당하지만 남는 폭은 나눠 가져라" 라는 뜻이라, 한 줄에 몇 개가
              * 들어가든 그 줄은 **항상 꽉 찬다.** 섹션이 몇 개든 빈 칸이 생기지 않는다.
              */}
            {grids.length > 0 && (
                <div
                    style={{ marginTop: "calc(min(100vw, 1536px) * -0.08)" }}
                    className="relative flex flex-wrap gap-4 z-10 p-4 bg-gradient-to-t from-gray-100 to-transparent"
                >
                    {grids.map((section) => (
                        // min-w-0: flex 항목의 기본 최소 폭은 `auto`(내용 크기)라, 긴 제목
                        // 하나가 basis 를 무시하고 카드를 늘려 줄바꿈을 망가뜨린다.
                        <div key={section.id} className="flex min-w-0 flex-1 basis-[220px]">
                            <GridCategory
                                title={section.title}
                                products={section.products ?? []}
                                moreHref={browseLinkOf(section)}
                            />
                        </div>
                    ))}
                </div>
            )}

            {swipers.length > 0 && (
                <div className="z-10 relative">
                    {swipers.map((section) => (
                        <HomeProductSwiper
                            key={section.id}
                            title={section.title}
                            products={section.products ?? []}
                            moreHref={browseLinkOf(section)}
                        />
                    ))}
                </div>
            )}
        </>
    );
};

export default HomeSections;
