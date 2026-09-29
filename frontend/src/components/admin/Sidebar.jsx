import {
    ArrowRightIcon,
    ArrowLeftIcon,
    HomeIcon,
    CogIcon,
    UserIcon,
    EllipsisVerticalIcon,
  } from '@heroicons/react/24/outline';
  import { useState } from 'react';
  import SidebarItem from './SidebarItem';
  import { useTranslation } from 'i18n/I18nProvider';
  
  // This sidebar component is for both mobile and desktop
  function Sidebar({ children, expanded, setExpanded }) {
    return (
      <div className="relative">
        {/* 
          This div is used to create the background overlay when the sidebar is expanded
          It is only visible on mobile screens
        */}
        <div
          className={`fixed inset-0 -z-10 block bg-gray-400  ${expanded ? 'block sm:hidden' : 'hidden'}`}
        />
        <aside
          className={`box-border h-screen transition-all ${expanded ? 'w-5/6 sm:w-64' : 'w-0 sm:w-20'}`}
        >
          <nav className="flex h-full flex-col border-r bg-white shadow-sm">
            <div className="flex items-center justify-between p-4 pb-2">
              <img
                src="https://img.logoipsum.com/243.svg"
                className={`overflow-hidden transition-all ${
                  expanded ? 'w-32' : 'w-0'
                }`}
                alt=""
              />
              <div className={`${expanded ? '' : 'hidden sm:block'}`}>
                <button
                  onClick={() => setExpanded((curr)  => !curr)}
                  className="rounded-lg bg-gray-50 p-1.5 hover:bg-gray-100"
                >
                  {expanded ? (
                    <ArrowRightIcon className="h-6 w-6" />
                  ) : (
                    <ArrowLeftIcon className="h-6 w-6" />
                  )}
                </button>
              </div>
            </div>
            {/* 하위 메뉴를 여러 개 열면 화면보다 길어진다 — 잘리지 않고 스크롤한다. */}
            <ul className="flex-1 overflow-y-auto px-3">{children}</ul>
            <div className="flex border-t p-3">
              <img
                src="https://ui-avatars.com/api/?background=c7d2fe&color=3730a3&bold=true&name=Mark+Ruffalo"
                alt=""
                className="h-10 w-10 rounded-md"
              />
              <div
                className={`
                flex items-center justify-between
                overflow-hidden transition-all ${expanded ? 'ml-3 w-52' : 'w-0'}
            `}
              >                
                <EllipsisVerticalIcon className="h-6 w-6" />
              </div>
            </div>
          </nav>
        </aside>
      </div>
    );
  }
  
  export default function MakeSidebar() {
    const [expanded, setExpanded] = useState(true);
    const { t } = useTranslation();
    // 메뉴 이름은 렌더할 때 사전에서 꺼낸다 — 언어를 바꾸면 바로 다시 그려진다.
    const navBarItems = [
      {
        icon: <HomeIcon />,
        text: t('admin.sidebar.home'),
        link: '/admin',
        active: true,
      },
      {
        icon: <UserIcon />,
        subMenu: [
          {
            icon: <UserIcon />,
            text: t('admin.sidebar.category'),
            link: '/admin/category'
          },
          // {
          //   icon: <CogIcon />,
          //   text: 'Coupon',
          //   link: '/admin/coupon'
          // },

          {
            icon: <CogIcon />,
            text: t('admin.sidebar.product'),
            link: '/admin/product'
          },

          {
            icon: <CogIcon />,
            text: t('admin.sidebar.all_products'),
            link: '/admin/allproducts'
          },

          {
            icon: <CogIcon />,
            text: t('admin.sidebar.bulk_import'),
            link: '/admin/import'
          },


        ],
        text: t('admin.sidebar.product_management'),
      },
      {
        icon: <CogIcon />,
        subMenu: [
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.home_sections'),
            link: '/admin/home'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.banner'),
            link: '/admin/banner'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.notice'),
            link: '/admin/notice'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.broadcast'),
            link: '/admin/broadcast'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.event'),
            link: '/admin/event'
          },
        ],
        text: t('admin.sidebar.content_management'),
      },
      {
        icon: <CogIcon />,
        text: t('admin.sidebar.orders'),
        // 🐛 `submenu` 였다 — SidebarItem 은 `subMenu` 를 받는다. 환불 메뉴가 어디에도 없었다.
        subMenu: [
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.refunds'),
            link: '/admin/refund'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.deliveries'),
            link: '/admin/delivery'
          },
          {
            icon: <CogIcon />,
            text: t('admin.sidebar.stock'),
            link: '/admin/stock'
          },
        ],
      },
      {
        icon: <CogIcon />,
        text: t('admin.sidebar.settings'),
      },
    ];
  
    // Desktop Sidebar
    return (
      <Sidebar expanded={expanded} setExpanded={setExpanded}>
        {navBarItems.map((item, index) => (
          <SidebarItem key={index} expanded={expanded} {...item} />
        ))}
      </Sidebar>
    );
  }