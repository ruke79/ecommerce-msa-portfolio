import { useLoaderData } from 'react-router-dom';

import CarouselContainer from "components/home/CarouselContainer";
import HomeSections from "./HomeSections";
import MicroServices from './MicroServices';
import { getHome, getBanners } from 'util/api';

/**
 * 첫 화면.
 *
 * <p>구성은 전부 서버에서 온다({@code GET /api/content/home}). 예전에는 여기서
 * 캐시 워밍용 엔드포인트({@code /api/product/frontpage})를 불러 전 카테고리 상품을
 * 한 배열로 받은 뒤, 화면 컴포넌트들이 카테고리 이름으로 다시 걸러 냈다.
 */
const HomePage = () => {
    const { home, banners, error } = useLoaderData() ?? {};

    return (
        <main className="max-w-screen-2xl mx-auto bg-gray-100">
            <MicroServices />
            <CarouselContainer banners={banners} />
            <HomeSections sections={home?.sections} error={error} />
        </main>
    );
};

export default HomePage;

/**
 * 홈 데이터 로더.
 *
 * <p><b>절대 throw 하지 않는다.</b> 라우터 로더에서 예외가 나가면 페이지 전체가
 * 에러 화면으로 바뀐다 — 카탈로그 한 곳이 흔들렸다고 캐러셀과 헤더까지 사라질 이유는
 * 없다. 실패는 플래그로 내려보내고 아래쪽 섹션 영역만 안내 문구로 대체한다.
 *
 * <p>예전 코드는 {@code console.error} 후 null 을 돌려줬고, 화면은 {@code products &&}
 * 로 통째로 감췄다. 그래서 "상품이 안 보인다"가 장애인지 데이터가 없는 건지
 * 화면만 봐서는 알 수 없었다.
 *
 * <p>🐛 <b>배너를 여기서 함께 받는다.</b> 예전에는 {@code CarouselContainer} 가 마운트된
 * 뒤 {@code useEffect} 로 받아 왔다. 상품 섹션은 loader 가 가져오므로 첫 페인트에 이미
 * 있는데 배너만 나중에 도착해, <b>번들 폴백 이미지 → 원격 이미지로 통째 교체</b>되면서
 * 높이가 무너졌다 커지고 그 아래 전체가 튀었다. 같이 받으면 교체 자체가 없다.
 *
 * <p>{@code Promise.allSettled} 인 이유: 둘 중 하나가 실패해도 나머지는 살린다.
 * {@code Promise.all} 이면 배너 API 가 죽었을 때 <b>상품까지 함께 사라진다.</b>
 */
export async function loader() {
    const [homeResult, bannerResult] = await Promise.allSettled([
        getHome(),
        getBanners(),
    ]);

    if (homeResult.status === "rejected") {
        console.error("홈 구성을 불러오지 못했습니다.",
            homeResult.reason?.response?.data?.message ?? homeResult.reason);
    }
    if (bannerResult.status === "rejected") {
        // 배너 실패는 화면에 알리지 않는다 — 폴백 이미지가 그 자리를 채우므로
        // 사용자가 알아차릴 것이 없다.
        console.error("배너를 불러오지 못했습니다.",
            bannerResult.reason?.response?.data?.message ?? bannerResult.reason);
    }

    return {
        home: homeResult.status === "fulfilled" ? homeResult.value : null,
        // 실패하면 null — CarouselContainer 가 번들 폴백으로 그린다.
        banners: bannerResult.status === "fulfilled" ? bannerResult.value : null,
        error: homeResult.status === "rejected",
    };
}
