import { ChevronRightIcon } from '@heroicons/react/24/outline';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

// This component is used to render the sub-menu items when hovered
function HoveredSubMenuItem({ icon, text, active }) {
  return (
    <div
      className={`my-2 rounded-md p-2 ${
        active ? 'bg-gray-300' : ' hover:bg-indigo-50'
      }`}
    >
      <div className="flex items-center justify-center ">
        <span className="text-primary-500 h-6 w-6 ">{icon}</span>
        <span className="text-primary-500 ml-3 w-28 text-start">{text}</span>
        <div className="bg-primary-200 h-1" />
      </div>
    </div>
  );
}

/**
 * 관리자 사이드바의 한 줄 — 링크이거나, 하위 메뉴를 여닫는 그룹이다.
 *
 * <h3>🐛 하위 메뉴가 잘리거나 안 보였다</h3>
 *
 * <p>하위 메뉴의 높이를 <b>"항목 하나 = 40px" 로 짐작해</b> {@code height} 로 박았다. 실제
 * 항목은 48px(py-2 + my-1)이라 항목이 많을수록 더 잘렸다 — Content Management 다섯 개는
 * 마지막 하나가 통째로 잘렸다. 그리고 그룹의 제목이 {@code <Link to="">} 라 누르는 순간
 * 빈 주소로 이동했다. 이제 높이는 짐작하지 않는다 — CSS grid 의 {@code 0fr → 1fr} 로
 * 내용만큼 열린다(index.css .sub-menu). 그룹 제목은 링크가 아니다.
 */
export default function SidebarItem({
  icon,
  active = false,
  text,
  link,
  expanded = false,
  subMenu = null,
}) {
  const [expandSubMenu, setExpandSubMenu] = useState(false);

  useEffect(() => {
    if (!expanded) {
      setExpandSubMenu(false);
    }
  }, [expanded]);

  const open = Boolean(subMenu) && expanded && expandSubMenu;

  return (
    <>
      <li>
        <button
          type="button"
          className={`
         group relative my-1 flex w-full cursor-pointer
         items-center rounded-md px-3
         py-2 font-medium transition-colors
         ${
           active && !subMenu
             ? 'text-primary-500 bg-gradient-to-tr from-indigo-200 to-indigo-100'
             : 'text-gray-600 hover:bg-indigo-50'
         }
         ${!expanded && 'hidden sm:flex'}
     `}
          aria-expanded={subMenu ? open : undefined}
          onClick={() => setExpandSubMenu((curr) => expanded && !curr)}
        >
          <span className="h-6 w-6">{icon}</span>

          <span
            className={`overflow-hidden text-start transition-all ${
              expanded ? 'ml-3 w-44' : 'w-0'
            }`}
          >
            {/* 링크가 있을 때만 링크다. 그룹 제목을 <Link to=""> 로 두면 누를 때 이동한다. */}
            {link ? <Link to={link}>{text}</Link> : text}
          </span>
          {subMenu && (
            <div
              className={`absolute right-2 h-4 w-4${expanded ? '' : 'top-2'} transition-all ${expandSubMenu ? 'rotate-90' : 'rotate-0'}`}
            >
              <ChevronRightIcon />
            </div>
          )}

          {/*
            display item text or sub-menu items when hovered
          */}
          {!expanded && (
            <div
              className={`
            text-primary-500 invisible absolute left-full ml-6 -translate-x-3
            rounded-md bg-indigo-100 px-2
            py-1 text-sm opacity-20 transition-all
            group-hover:visible group-hover:translate-x-0 group-hover:opacity-100
        `}
            >
              {/*
                if hovered item has no sub-menu, display the text
                else display the sub-menu items
              */}
              {!subMenu
                ? text
                : subMenu.map((item, index) => (
                    <HoveredSubMenuItem
                      key={index}
                      text={item.text}
                      icon={item.icon}
                    />
                  ))}
            </div>
          )}
        </button>
      </li>
      {subMenu && (
        // 높이를 계산하지 않는다 — grid-template-rows 0fr/1fr 이 내용만큼 열고 닫는다.
        <li className="sub-menu" data-open={open}>
          <ul className="pl-6" hidden={!open}>
            {expanded &&
              subMenu.map((item, index) => (
                <SidebarItem key={index} {...item} expanded={expanded} />
              ))}
          </ul>
        </li>
      )}
    </>
  );
}
