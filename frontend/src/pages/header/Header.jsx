import { Link } from "react-router-dom";
import { Bars3Icon } from "@heroicons/react/24/outline";
import BrandMark from "components/common/BrandMark";
import { useDispatch } from "react-redux";
import { openMenu } from "../../redux/MenuSlice";
import DeliveryTo from "./DeliveryTo";
import Search from "./Search";
import Account from './Account'
import HeaderMenu from "./HeaderMenu";
import Language from "./Language";
import { useEffect } from "react";
import NotificationBell from "components/notification/NotificationBell";
import { useAuthContext } from "../../store/AuthContext";
import useDocumentTitle from "hook/useDocumentTitle";




const Header = ({title, searchHandler}) => {
    const dispatch = useDispatch();
    const { isAuthenticated } = useAuthContext();

    // 모든 화면이 Header 를 통과하므로 제목도 여기서 세운다.
    useDocumentTitle(title);

    const openMenuHandler = () => {
        dispatch(openMenu());
    };

   
       

    return (
        <>
        
                    
        <header>
            {/*
              * 🐛 여기에 `<title>` 과 `<meta>` 가 있었다. React 18 은 이것들을 <head> 로
              * 올리지 않는다 — 그냥 <body> 안에 그려질 뿐이라(브라우저가 무시한다) 탭
              * 제목이 바뀌지 않았다. `public/index.html` 에도 <title> 이 없어서 탭에는
              * URL 이 그대로 보였다. (호이스팅은 React 19 부터다.)
              *
              * 제목은 위의 useDocumentTitle 이 document.title 로 직접 세운다.
              * viewport·description 은 index.html 에 있다 — 라우트마다 바뀔 값이 아니다.
              */}
            <div className="bg-amazon-blue_dark flex flex-col md:flex-row">
                <div className="flex flex-grow items-center p-3 md:space-x-5 md:px-4 text-white">
                    {/* Menu Icon Mobile */}
                    <div className="flex items-center justify-center">
                        <div
                            onClick={openMenuHandler}
                            className="md:hidden cursor-pointer mr-1"
                        >
                            <Bars3Icon className="h-8 md:h-7" />
                        </div>
                        {/* Logo */}
                        <Link to="/">
                            {/* 예전에는 Amazon 로고 png 였다(constants/brand.js 주석). */}
                            <BrandMark variant="light" size="md" className="pt-2" />
                        </Link>
                    </div>

                    <DeliveryTo />

                    {/* Search Desktop — 중괄호 없이 적혀 있어서 헤더에 "Search Desktop"
                        이라는 글자가 그대로 찍히고 있었다. */}
                    <div className="hidden md:flex flex-grow">
                        <Search searchHandler={searchHandler} />
                    </div>

                    <Language />
                    {/* 알림 종. 로그인 상태에서만 보인다 — 비로그인 사용자에게는 보여줄 알림이 없다. */}
                    <NotificationBell isAuthenticated={isAuthenticated} />
                    <Account />
                </div>

                {/* Search Mobile*/}
                <div className="md:hidden">
                    <Search />
                </div>
            </div>            
            <HeaderMenu handleOpenMenu={openMenuHandler} />
        </header>
    </>
    );

}

export default Header;