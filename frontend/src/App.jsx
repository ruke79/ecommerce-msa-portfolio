import { Provider } from "react-redux";
import { Suspense, lazy } from "react";
import { PersistGate } from "redux-persist/integration/react";
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import RootPage from "./pages/Root";
import HomePage, { loader as homeLoader } from './pages/home/Home';
import RegisterPage from './pages/user/RegisterPage';
import SignInPage from './pages/user/SignInPage';
import ForgotPage from './pages/user/ForgotPage';
import VerificationFailedPage from './pages/user/VerificationFailedPage';
import ResetPasswordPage from './pages/user/ResetPasswordPage';
import SupportPage from "./pages/support/SupportPage";
import SupportDesk from "./pages/support/SupportDesk";
import { ContextProvider } from "./store/AuthContext";
import { persistor, store } from "./redux/store";
import SingleProduct, { loader as productLoader } from "./pages/Product";
import Browse, { loader as browseLoader } from "pages/browse";
import Cart from "pages/cart";
import Checkout from "pages/checkout";
import OrderPage, { loader as loaderOrder } from "pages/order";
import Address, { loader as loaderAddress } from "pages/profile/address";
import Orders, { loader as loaderOrders } from "pages/profile/orders";
import Security, { loader as loaderSecurity } from "pages/profile/security";
import Profile, { loader as loaderProfile } from "pages/profile/profile";
import Payment, { loader as loaderPayment } from "pages/profile/payment"
import ProtectedRoute from "components/ProtectedRoute";
import { ErrorBoundary } from "react-error-boundary";
import ErrorFallback from "components/common/ErrorFallback";
import { Outlet } from 'react-router-dom';
import ErrorPage from "pages/Error";
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from 'util/api';
import Maintenance from 'components/error/Maintenance';
import { PayPalScriptProvider } from "@paypal/react-paypal-js";
import TokenRefresher from "util/TokenRefresher";
import toast, { Toaster } from 'react-hot-toast';
import ChatRoomList from "components/chat/list/ChatRoomList";
import { ChatPage } from "pages/chat/ChatPage";
import Notification from "util/Notification";
import CartSync from "components/cart/CartSync";
import I18nProvider, { useTranslation } from "i18n/I18nProvider";

// 코드 스플리팅을 적용할 컴포넌트들을 lazy loading으로 변경합니다.
const DashboardLayout = lazy(() => import('./components/admin/DashboardLayout'));
const Categories = lazy(() => import('./pages/admin/Category'));
const AdminProduct = lazy(() => import('./pages/admin/Index'));
const Products = lazy(() => import('pages/admin/ProductList'));
const AdminBanners = lazy(() => import('./pages/admin/Banners'));
const AdminNotices = lazy(() => import('./pages/admin/Notices'));
const AdminRefunds = lazy(() => import('./pages/admin/Refunds'));
const AdminDeliveries = lazy(() => import('./pages/admin/Deliveries'));
const AdminStock = lazy(() => import('./pages/admin/Stock'));
const AdminBroadcasts = lazy(() => import('./pages/admin/Broadcasts'));
const AdminEvents = lazy(() => import('./pages/admin/Events'));
const AdminHomeSections = lazy(() => import('./pages/admin/HomeSections'));
const AdminProductImport = lazy(() => import('./pages/admin/ProductImport'));
const NoticeList = lazy(() => import('./pages/notice/NoticeList'));
const NoticeDetail = lazy(() => import('./pages/notice/NoticeDetail'));
const EventList = lazy(() => import('./pages/event/EventList'));
const EventDetail = lazy(() => import('./pages/event/EventDetail'));

const initialOptions = {
  "client-id": process.env.REACT_APP_PAYPAL_CLIENT_ID,
  currency: "USD",
  intent: "capture",
  components: 'buttons'
};

/** 지연 로딩 화면이 뜨기 전의 자리 — 문구는 지금 언어로 그린다. */
const RouteFallback = ({ admin = false }) => {
  const { t } = useTranslation();
  return <div>{t(admin ? "app.loading_admin" : "app.loading")}</div>;
};

const ErrorBoundaryLayout = () => (
  <ErrorBoundary FallbackComponent={ErrorFallback}>
    {/* 언어(I18nProvider)는 App 맨 바깥으로 옮겼다 — 이 오류 경계의 대체 화면(ErrorFallback)과
        라우터 오류 화면(ErrorPage)도 번역 대상이라, 둘보다 바깥에 있어야 한다. */}
    <ContextProvider>
      <TokenRefresher />
      {/* 로그인·로그아웃 때 장바구니를 서버와 맞춘다 — 서버가 장바구니의 기준이다(docs/40 0-b). */}
      <CartSync />
      {/* 실시간 알림(SSE). 주석 처리돼 있어서 다른 기기에서 로그인해 세션이 밀려나도
          이 탭은 계속 로그인 상태로 남아 있었다. 재연결이 영구히 멈추던 버그를 고친
          뒤 다시 켠다(util/Notification.js). */}
      <Notification />
      <Outlet />
    </ContextProvider>
  </ErrorBoundary>
);

/**
 * 🐛 `createBrowserRouter` 를 컴포넌트 **안에서** 부르고 있었다.
 *
 * <p>이 함수는 라우터 인스턴스를 만든다 — 히스토리 구독, 로더 상태, 스크롤 복원 위치를
 * 들고 있는 **살아 있는 객체**다. 렌더마다 새로 만들면 그 상태가 통째로 버려지고 새
 * 라우터가 처음부터 시작한다. `AppRouter` 가 리렌더되는 순간 진행 중이던 로더가 끊기고
 * 스크롤 복원이 초기화된다.
 *
 * <p>지금은 `AppRouter` 에 prop 도 state 도 없어서 실제로 리렌더될 일이 드물었다 —
 * 즉 <b>증상 없이 잠복해 있었다.</b> 위에 Provider 가 하나만 늘어도 터진다.
 *
 * <p>모듈 최상위로 올린다. 라우트 정의는 상수이므로 한 번만 만들면 된다.
 */
const router = createBrowserRouter([
    {
      element: <ErrorBoundaryLayout />,
      errorElement: <ErrorPage />,
      children: [
        {
          path: '/',
          element: <RootPage />,
          children: [
            {
              index: true,
              element: <HomePage />,
              loader: homeLoader,
            },
            { path: 'signin', element: <SignInPage /> },
            { path: 'register', element: <RegisterPage /> },
            // 로그인 화면의 "Forgot your password?" 가 여기로 온다. 라우트가 없어서
            // 그 링크는 라우터 오류 화면으로 갔고 — 비밀번호를 잊은 사용자는 계정을
            // 되찾을 방법이 아예 없었다. ForgotPage 는 만들어져 있었는데 아무도
            // 연결하지 않았다.
            { path: 'forgot', element: <ForgotPage /> },
            // 고객센터 채팅(docs/58) — 고객 ↔ 고객지원팀. 친구 채팅(/chat)과 따로다.
            { path: 'support', element: <ProtectedRoute><SupportPage /></ProtectedRoute> },
            // 상담 데스크 — 상담원(ROLE_SUPPORT)·관리자. 서버도 같은 규칙을 강제한다.
            { path: 'support/desk', element: <ProtectedRoute supportOnly><SupportDesk /></ProtectedRoute> },
            // user-service 의 RegistrationController 가 인증 실패 시 여기로 리다이렉트한다
            // (`/verification-failed?reason=expired|invalid`). 라우트가 없어서 그 리다이렉트는
            // **라우터 오류 화면**으로 떨어졌고 — 링크를 늦게 누른 사용자는 무엇이 잘못됐는지도,
            // 무엇을 하면 되는지도 알 수 없었다. /forgot 과 똑같은 종류의 구멍이다.
            { path: 'verification-failed', element: <VerificationFailedPage /> },
            // 재설정 메일의 링크가 여기로 온다. 라우트가 없어서 그 링크는 라우터 오류
            // 화면으로 떨어졌고 — 비밀번호 찾기는 처음부터 끝까지 동작한 적이 없다.
            { path: 'reset-password', element: <ResetPasswordPage /> },
            // 커뮤니티 채팅(여러 사람의 단체 대화방) — 보존한다. 고객 상담은 /support(1:1)다.
            // 예전에는 헤더 없는 전체 화면이라 안쪽의 fixed 요소들이 헤더 메뉴와 겹쳤다 — 이제 사이트 레이아웃 안의 카드다.
            {
              path: 'chat',
              element: <ProtectedRoute><ChatPage /></ProtectedRoute>,
              children: [
                { index: true, element: <ChatRoomList /> },
                { path: 'chatrooms', element: <ChatRoomList /> },
              ]
            },
          ]
        },
        { path: 'error_server', element: <Maintenance /> },
        {
          path: '/profile',
          element: <ProtectedRoute><Profile /></ProtectedRoute>,
          loader: loaderProfile,
        },
        {
          path: 'profile/address',
          element: <ProtectedRoute><Address /></ProtectedRoute>,
          loader: loaderAddress,
        },
        {
          path: 'profile/orders',
          element: <ProtectedRoute><Orders /></ProtectedRoute>,
          loader: loaderOrders
        },
        {
          path: 'profile/payment',
          element: <ProtectedRoute><Payment /></ProtectedRoute>,
          loader: loaderPayment
        },
        {
          path: 'profile/security',
          element: <ProtectedRoute><Security /></ProtectedRoute>,
          loader: loaderSecurity
        },
        {
          path: '/product/:slug',
          element: <SingleProduct />,
          loader: productLoader,
        },
        {
          path: '/browse',
          element: <Browse />,
          loader: browseLoader,
        },
        // 공지사항. 로그인 없이 열린다 — 점검·정책 안내는 비회원도 봐야 한다.
        {
          path: '/notice',
          element: <Suspense fallback={<RouteFallback />}><NoticeList /></Suspense>,
        },
        {
          path: '/notice/:id',
          element: <Suspense fallback={<RouteFallback />}><NoticeDetail /></Suspense>,
        },
        // 기획전도 로그인 없이 열린다 — 세일을 보려고 가입해야 하면 아무도 안 본다.
        // 진행 중인 것만 서버가 내려준다(초안·예약분은 404).
        {
          path: '/event',
          element: <Suspense fallback={<RouteFallback />}><EventList /></Suspense>,
        },
        {
          path: '/event/:slug',
          element: <Suspense fallback={<RouteFallback />}><EventDetail /></Suspense>,
        },
        {
          path: '/cart',
          element: <ProtectedRoute><Cart /></ProtectedRoute>,
        },
        {
          path: '/checkout',
          element: <ProtectedRoute><Checkout /></ProtectedRoute>,
        },
        {
          path: '/order/:id',
          element: <ProtectedRoute><OrderPage /></ProtectedRoute>,
          loader: loaderOrder,
        },
        // 관리자 페이지. 지연 로딩(Suspense)과 **권한 가드**를 함께 건다.
        // 예전에는 가드가 없어서, 로그인하지 않은 사람도 /admin/product 를 주소창에 직접
        // 치면 관리자 화면이 그대로 그려졌다. 부모에 한 번 걸면 자식 라우트까지 함께 막힌다.
        {
          path: '/admin',
          element: (
            <ProtectedRoute adminOnly>
              <Suspense fallback={<RouteFallback admin />}><DashboardLayout /></Suspense>
            </ProtectedRoute>
          ),
          children: [
            { path: 'category', element: <Suspense fallback={<RouteFallback />}><Categories /></Suspense> },
            { path: 'product', element: <Suspense fallback={<RouteFallback />}><AdminProduct /></Suspense>},
            { path: 'banner', element: <Suspense fallback={<RouteFallback />}><AdminBanners /></Suspense> },
            { path: 'notice', element: <Suspense fallback={<RouteFallback />}><AdminNotices /></Suspense> },
            { path: 'broadcast', element: <Suspense fallback={<RouteFallback />}><AdminBroadcasts /></Suspense> },
            { path: 'event', element: <Suspense fallback={<RouteFallback />}><AdminEvents /></Suspense> },
            { path: 'refund', element: <Suspense fallback={<RouteFallback />}><AdminRefunds /></Suspense> },
            { path: 'delivery', element: <Suspense fallback={<RouteFallback />}><AdminDeliveries /></Suspense> },
            { path: 'stock', element: <Suspense fallback={<RouteFallback />}><AdminStock /></Suspense> },
            { path: 'home', element: <Suspense fallback={<RouteFallback />}><AdminHomeSections /></Suspense> },
            { path: 'import', element: <Suspense fallback={<RouteFallback />}><AdminProductImport /></Suspense> },
            { path: 'allproducts',
              element: <Suspense fallback={<RouteFallback />}><Products /></Suspense>,
              // loader는 lazy loading과 함께 사용될 수 없습니다.
              // 필요하다면 컴포넌트 내부에서 데이터를 페칭하는 방식으로 변경해야 합니다.
              // loader:loaderProducts
            }
          ]
        }
      ]
    }
]);

const AppRouter = () => <RouterProvider router={router} />;

function App() {
  // 언어는 인증보다, 그리고 오류 화면보다 바깥이다 — 로그인하지 않은 사람도 일본어로 볼 수 있어야 하고,
  // 로그인 화면·오류 화면 자체가 번역 대상이다.
  return (
    <I18nProvider>
      <div>
        <Toaster />
      </div>
      <QueryClientProvider client={queryClient}>
        <Provider store={store}>
          <PersistGate loading={null} persistor={persistor}>
            <PayPalScriptProvider options={initialOptions}>
              <AppRouter />
            </PayPalScriptProvider>
          </PersistGate>
        </Provider>
      </QueryClientProvider>
    </I18nProvider>
  );
}

export default App;