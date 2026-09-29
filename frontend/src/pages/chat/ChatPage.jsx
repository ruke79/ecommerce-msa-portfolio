import React, { useMemo } from "react";

import { Link, Outlet } from "react-router-dom";
import { useAuthContext } from "store/AuthContext";
import { StompProvider } from "../../util/stompProvider";
import { API_BASE } from "../../util/apiBase";

import ChatArea from "components/chat/chatting/ChatArea";

import styled from "styled-components";
import ChatUserinfo from "components/chat/list/ChatUserinfo";
import { useTranslation } from "i18n/I18nProvider";
import { useSelector } from "react-redux";
import { hideRoom } from "../../redux/ChatSelectors";


/*
 * ⚠️ 겹침(docs/58 §11): 예전에는 이 화면이 사이트 헤더 없이 뷰포트 전체(100vw × 100vh)를 덮었고, 안쪽의
 * 입력줄(position: fixed, 폭 50%) · 아래로 버튼(fixed, z-index 200) · 오른쪽 메뉴의 화면 덮개(fixed inset-0)가
 * 서로 · 헤더 메뉴와 겹쳤다. 이제 헤더 아래의 카드 한 장이고, 안쪽은 전부 흐름(flex) 배치다.
 * isolation: isolate — 카드 안의 z-index 가 밖(헤더의 계정 · 언어 메뉴)으로 새지 않는다.
 */
const Wrapper = styled.div`
  position: relative;
  isolation: isolate;
  display: flex;
  height: calc(100vh - 16.5rem);
  min-height: 32rem;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  background-color: #f5f6fa;
`;

const ListWrapper = styled.div`
  display: flex;
  flex: 0 0 20rem;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid #e5e7eb;
  background: #fff;

  @media (max-width: 767px) {
    flex-basis: 100%;
    display: ${(p) => (p.$hidden ? "none" : "flex")};
  }
`;

 export const ChatPage =() =>  {
    // no dispatch because of websoket
         
    const { t } = useTranslation();
    const { token } = useAuthContext();
    // 좁은 화면에서는 방을 열면 목록을 접는다(대화 머리의 ← 로 돌아온다). 선택자 이름이 hideRoom 이지만 값은 "방이 열려 있다" 다.
    const roomOpen = useSelector(hideRoom);
    // 매 렌더링마다 새 객체/문자열을 만들면 StompProvider의 재연결 effect가 매번 재실행되어
    // 소켓이 불필요하게 끊겼다 다시 연결되므로 token이 바뀔 때만 갱신되도록 메모이즈한다.
    const brokerURL = useMemo(
      () => API_BASE + '/chat-service/chat',
      []
    );
    const connectHeaders = useMemo(() => ({ Authorization: token }), [token]);


    return (
      
      
      <StompProvider brokerURL={brokerURL} connectHeaders={connectHeaders}>
        <section className="mx-auto w-full max-w-7xl px-4 py-6">
          <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h1 className="text-2xl font-bold text-gray-900">{t("chat.community.title")}</h1>
              <p className="mt-1 text-sm text-gray-500">{t("chat.community.intro")}</p>
            </div>
            <Link to="/support" className="rounded-lg bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700">
              {t("chat.community.to_support")}
            </Link>
          </div>
          <Wrapper>
            <ListWrapper $hidden={roomOpen}>
              <ChatUserinfo/>
              <Outlet/>
            </ListWrapper>
            <ChatArea />
          </Wrapper>
        </section>
      </StompProvider>
      
    );

};

