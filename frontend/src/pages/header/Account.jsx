import { useRef } from "react";
import {
    ChevronDownIcon,
    ShoppingCartIcon,
    UserIcon,
} from "@heroicons/react/24/outline";
import { ChevronRightIcon } from "@heroicons/react/24/solid";
import { Link, useNavigate, redirect, useLocation } from "react-router-dom";
import { useAuthContext, STATUS } from "../../store/AuthContext";
import toast from "react-hot-toast";
import { useSelector } from "react-redux";
import tokenUtil from "util/tokenUtil";
import { postRequest } from "util/api";
import { useLogout, useReRenderer } from "hook/hooks";
import { hasSupportRole } from "components/ProtectedRoute";
import { useTranslation } from "i18n/I18nProvider";




const Account = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const cart = useSelector((state) => state.cart.cartItems);

    const { user, token, isAuthenticated } = useAuthContext();
    const { t } = useTranslation();
    const { logout, isPending } = useLogout();

    const isLoadingRef = useRef(false);
    // 로그인 정보에는 이메일만 있다(util/jwtUser). 헤더 줄에는 앞부분만 — 전체는 팝업과 title 에.
    const email = user?.email ?? "";
    const displayName = email.includes("@") ? email.slice(0, email.indexOf("@")) : email;
    const reRender = useReRenderer();

    
    
    return (
        <div className="flex items-center max-md:ml-auto md:space-x-6 space-x-2">
            {/*
              * account Icon in Mobile
              *
              * 🐛 예전에는 인증 상태를 보지 않고 **언제나** "Sign in" 이었다. 로그인한
              * 사용자에게도 로그인하라고 하고, 누르면 로그인 화면으로 보냈다.
              * 데스크톱 블록(바로 아래)은 처음부터 상태를 보고 있었는데 이쪽만 빠져 있었다.
              */}
            <div className=" md:hidden">
                <Link
                    className="flex items-center"
                    to={isAuthenticated ? "/profile" : "/signin"}
                >
                    <p className="text-sm">
                        {isAuthenticated ? t("account.profile") : t("account.signin")}
                    </p>
                    <ChevronRightIcon className="h-3 " />
                    <UserIcon className="h-6" />
                </Link>
            </div>

            {/*
              * 계정 메뉴(데스크톱, 마우스를 올리면 팝업).
              *
              * 🐛 예전에는 문구가 전부 영어였고("Hello", "Account & Lists", "Profile", "Sign Out", "Your List"…) 목록의
              *    항목은 링크가 아닌 글자였다. 그리고 이메일과 버튼이 **한 줄**(flex justify-between)에 있어서, 이메일이
              *    길면 버튼이 팝업 밖으로 밀렸다 — 이메일에는 줄바꿈할 곳이 없다. 이제 이메일은 제 줄에서 말줄임(전체는
              *    title 로), 버튼은 그 아래 줄에서 필요하면 줄바꿈한다. 헤더 줄의 인사도 이메일 앞부분만, 폭을 넘으면
              *    말줄임한다.
              */}
            <div className="hidden md:inline link relative show-account p-1" data-testid="account-menu">
                <p className="text-xs text-slate-300 max-w-[11rem] truncate" title={isAuthenticated ? email : undefined}>
                    {isAuthenticated ? t("account.hello", { name: displayName }) : t("account.hello_guest")}
                </p>
                <p className="flex font-bold text-sm whitespace-nowrap">
                    {t("account.title")}
                    <ChevronDownIcon className="h-4 self-end ml-1" />
                </p>

                {/* popOver Account */}
                <div className="z-20 show-account-popup absolute w-96 -right-14 h-auto bg-white rounded-sm border shadow-md mt-1 text-left">
                    <div className="absolute h-3 w-3 bg-white rotate-45 -mt-1 right-[3.85rem] "></div>
                    {isAuthenticated ? (
                        <div className="p-3 border-b">
                            <p className="text-xs text-slate-500">{t("account.signed_in_as")}</p>
                            <Link
                                to="/profile"
                                className="block min-w-0 truncate text-base font-semibold text-amazon-blue_light hover:underline"
                                title={email}
                                data-testid="account-email"
                            >
                                {email}
                            </Link>
                            <div className="mt-2 flex flex-wrap gap-2" data-testid="account-actions">
                                {/* 상담원·관리자에게만 — 상담 데스크(docs/58). 서버도 같은 규칙을 강제한다. */}
                                {hasSupportRole(user) && (
                                    <Link to="/support/desk" className="button-orange px-3 py-[0.3rem] text-sm text-gray-900 whitespace-nowrap">
                                        {t("support.desk.title")}
                                    </Link>
                                )}
                                <Link to="/profile" className="button-orange px-4 py-[0.3rem] text-sm text-gray-900 whitespace-nowrap">
                                    {t("account.profile")}
                                </Link>
                                <button
                                    onClick={async () => {
                                            if (isLoadingRef.current) {
                                                return;
                                            }
                                            isLoadingRef.current = true;
                                            reRender();
                                            await logout();
                                            isLoadingRef.current = false;
                                            reRender();
                                        }
                                    }
                                    disabled={isLoadingRef.current}
                                    className="button-orange px-4 py-[0.3rem] text-sm text-gray-900 whitespace-nowrap"
                                >
                                    {isLoadingRef.current ? t("account.signing_out") : t("account.signout")}
                                </button>
                            </div>
                        </div>
                    ) :
                        (
                            <div className="flex flex-col items-center p-3 m-3 border-b pb-2">
                                <button
                                    onClick={
                                        () => navigate('/signin')
                                    }
                                    className="button-orange px-16 py-[0.3rem] text-sm text-gray-900"
                                >
                                    {t("account.signin")}
                                </button>
                                <p className="text-xs text-gray-900 mt-2">
                                    {t("account.new_customer")}{" "}
                                    <Link
                                        to="/register"
                                        className="text-[#05a] hover:text-amazon-orange hover:underline"
                                    >
                                        {t("account.register")}
                                    </Link>
                                </p>
                            </div>
                        )}

                    {/* 예전의 "Your List"(Create a list·Registry…)는 없는 기능이었다. 있는 화면으로만 잇는다. */}
                    <div className="grid grid-cols-2 gap-4 m-3">
                        <div className="flex flex-col min-w-0">
                            <h4 className="font-bold text-base text-black mb-2">{t("account.menu.shopping")}</h4>
                            <ul className="text-gray-900 text-xs space-y-1">
                                <li><Link className="hover:underline" to="/profile/orders">{t("profile.orders.title")}</Link></li>
                                <li><Link className="hover:underline" to="/cart">{t("account.cart")}</Link></li>
                                <li><Link className="hover:underline" to="/event">{t("account.menu.events")}</Link></li>
                                <li><Link className="hover:underline" to="/notice">{t("account.menu.notices")}</Link></li>
                            </ul>
                        </div>

                        <div className="flex flex-col min-w-0 border-l pl-4">
                            <h4 className="font-bold text-base text-black mb-2">{t("account.menu.account")}</h4>
                            <ul className="text-gray-900 text-xs space-y-1">
                                <li><Link className="hover:underline" to="/profile">{t("profile.title")}</Link></li>
                                <li><Link className="hover:underline" to="/profile/address">{t("profile.addresses.title")}</Link></li>
                                <li><Link className="hover:underline" to="/profile/payment">{t("profile.payment.title")}</Link></li>
                                <li><Link className="hover:underline" to="/profile/security">{t("profile.security.title")}</Link></li>
                                <li><Link className="hover:underline" to="/support">{t("support.title")}</Link></li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>

            <Link to="/profile/orders" className="link hidden md:inline">
                <p className="text-xs text-slate-300 whitespace-nowrap">{t("account.orders_top")}</p>
                <p className="font-bold text-sm whitespace-nowrap">{t("account.orders_bottom")}</p>
            </Link>

            {/*
              * 🐛 `token ? navigate("/cart") : {}` 였다. 로그인하지 않은 상태에서 장바구니를
              * 누르면 **아무 일도 일어나지 않는다** — 화면도 안 바뀌고 안내도 없어서
              * 사용자는 버튼이 고장난 줄 안다. 로그인 화면으로 보내고, 로그인하면 원래
              * 가려던 장바구니로 돌아온다(SignInPage 의 redirectTo).
              */}
            <div
                onClick={() =>
                    token
                        ? navigate("/cart")
                        : navigate("/signin", { state: { from: { pathname: "/cart" } } })
                }
                className="relative link flex items-center cursor-pointer"
            >
                <span className="flex items-center justify-center absolute top-0 right-[0.44rem] md:right-8 bg-amazon-orange text-amazon-blue_dark font-semibold h-5 w-5 rounded-full">
                    {cart?.length}
                </span>
                <ShoppingCartIcon className="h-10" />
                <p className="hidden md:inline font-bold mt-2 text-sm">{t("account.cart")}</p>
            </div>
        </div>




    );



}

export default Account;


