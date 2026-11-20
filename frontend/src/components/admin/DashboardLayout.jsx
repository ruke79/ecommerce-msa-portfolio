import Sidebar from "./Sidebar";
import { Outlet } from 'react-router-dom';

/**
 * 관리자 화면의 골격.
 *
 * <p>예전에는 사이드바 자리에 `//<Sidebar/>` 라고 적혀 있었다. JSX 안에서 `//` 는 주석이
 * 아니라 <b>그냥 글자</b>다. 그래서 사이드바는 렌더링되지 않고, 대신 화면 왼쪽 위에 `//`
 * 두 글자가 그대로 찍혔다. JSX 에서 주석은 중괄호로 감싼 블록 주석이어야 한다.
 *
 * <p>[JA] 管理画面の骨格。
 * 以前はサイドバーの位置に `//<Sidebar/>` と書かれていた。JSX の中で `//` はコメントではなく<b>ただの文字</b>である。
 * そのためサイドバーはレンダリングされず、代わりに画面左上に `//` の二文字がそのまま表示された。JSX のコメントは波括弧で包んだブロックコメントでなければならない。
 */
const DashboardLayout = () => {

    return (
      <div className="flex min-h-screen">
          <Sidebar/>
          <div className="w-full md:pl-8 "><Outlet/></div>
        </div>
    );
}

export default DashboardLayout;