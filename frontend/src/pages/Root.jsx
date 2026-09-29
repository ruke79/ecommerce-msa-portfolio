import { Outlet } from 'react-router-dom';
import Header from './header/Header';
import Footer from './Footer';
import MenuSideBar from './header/MenuSidebar';
import NavigationProgress from 'components/loader/NavigationProgress';
import { useAuthContext, STATUS } from 'store/AuthContext';

function RootPage() {

    const { status } = useAuthContext();   

   // lazy loading until loading user information when refreshing a page
   if (status === STATUS.PENDING) {
        return null;
    }

    return (
        <>
      {/*
        * 라우터가 다음 화면을 준비하는 동안 위쪽에 얇은 막대를 그린다(docs/43 D5).
        *
        * 여기 있던 `{navigation.state === 'loading' && <p>Loading...</p>}` 은 주석
        * 처리돼 있었다. 그래서 `/browse` 에서 필터를 눌러도 응답이 올 때까지 아무
        * 반응이 없었다 — 데이터 라우터는 로더가 끝나야 URL 까지 바꾸므로, 클릭이
        * 먹었는지조차 알 수 없어 사용자는 한 번 더 누른다.
        *
        * 문구 대신 막대인 이유는 NavigationProgress 주석에 적었다: 덮지 않고, 레이아웃을
        * 밀지 않고, 짧은 전환에서는 아예 나타나지 않는다.
        */}
      <NavigationProgress />
      <Header />      
      <main>
        <Outlet />
      </main>
      <Footer />
      <MenuSideBar />
    </>
  );
}

export default RootPage;


