import React, { createRef, useContext, useLayoutEffect, useEffect, useRef, useState, memo } from "react";
import styled from 'styled-components';

import { useDispatch, useSelector } from "react-redux";


import {
  selectedRoom, hideRoom

} from "../../../redux/ChatSelectors"
import { useStomp } from "util/stompProvider";
import { useFetchRoomMessages, usePrevState } from "hook/chatHooks";
import { showRoom  } from "../../../redux/ChatSlice";


import ChatHeader from "./ChatHeader";
import ChatFooter from "./ChatFooter";
import ChatContent from "./ChatContent";
import { useAuthContext } from "store/AuthContext";
import EmptyChatArea from "./EmptyArea";
import DotLoaderSpinner from "components/loader/Loading";
import { sameUserId } from "util/jwtUser";
import { useTranslation } from "i18n/I18nProvider";
import { chatMessageText, localizeChatName } from "util/chatNotice";


const Chat = styled.div`
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #b2c7d9;
`;

const ConnectionBanner = styled.div`
  width: 100%;
  padding: 6px 12px;
  text-align: center;
  font-size: 13px;
  color: #ffffff;
  background-color: ${(props) => (props.$isError ? "#c0392b" : "#7f8c8d")};
`;


let prevScrollHeight = 0;

export function ChatArea() {
  const { t } = useTranslation();

  const room = useSelector(selectedRoom);
  const isRoomHided = useSelector(hideRoom);

  const dispatch = useDispatch();

  
  const [isShowDownBtn, setIsShowDownBtn] = useState(false);
  const [sender, setSender] = useState(null);
  const [message, setMessage] = useState('');
  const [messages, setMessages] = useState([]);
  const [loaded, setLoaded] = useState(false);
  let prevMessages = usePrevState(messages);

  const [prevRoom, setPrevRoom] = useState(room);

  
  const cursor = useRef('-1');
  const messageRef = useRef(null);
 


  const { roomMessages, isPending, isFetch } = useFetchRoomMessages(room, cursor.current);

  const {user} = useAuthContext();
  const { isConnected, connectionError, subscribe, sendMessage } = useStomp();

  if (room !== prevRoom) {
    setPrevRoom(room);
    
    setIsShowDownBtn(false);
    setSender(null);
    setMessage('');
    setMessages([]);
    setLoaded(false);
    cursor.current = '-1';    

  }


  if (isFetch && loaded) {

    if (cursor.current === '-1') {
      setMessages(roomMessages);
    }
    else {      
      const merged = [...roomMessages, ...messages];
      setMessages(merged);
    }

    setLoaded(false);
  }


  useLayoutEffect(() => {

    // 리스너를 붙인 바로 그 요소를 붙잡아 둔다. 정리 함수가 도는 시점(방 전환·언마운트)에는
    // messageRef.current 가 이미 null 이거나 다른 요소라, 예전 코드는 removeEventListener 를
    // 엉뚱한 대상에 호출하고 catch 로 삼켰다 — 원래 요소에는 리스너가 그대로 남았다.
    // 이 effect 는 메시지가 올 때마다 다시 도니(deps 에 messages), 남은 리스너는 메시지 수만큼
    // 쌓이고 각각이 그 시점의 messages 배열을 클로저로 붙든다.
    const el = messageRef.current;
    if (!el) return undefined;

    // 스크롤 변롸에 따른 action
    const handleScroll = () => {

      const scrollTop = el.scrollTop;

      // 스크롤을 맨 위로 올리면, 서버에서 이전 채팅을 불러옵니다.
      if (messages.length > 0 && scrollTop === 0) {

        cursor.current = messages[0].id;
        setLoaded(true);

        prevScrollHeight = el.scrollHeight;
      }

    }

    el.addEventListener('scroll', handleScroll);

    changeScroll();

    return () => {
      el.removeEventListener('scroll', handleScroll);
    };

  // messageRef.current 는 deps 에 넣어도 React 가 변화를 감지하지 못한다(렌더 사이에 바뀌는 ref).
  // 요소는 이 컴포넌트가 사는 동안 하나뿐이므로 messages 만으로 충분하다.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages])

  


  // 스크롤을 가장 아래로 내립니다.
  const pageDown = () => {
    if (!messageRef.current) return;


    const scrollHeight = messageRef.current?.scrollHeight;
    messageRef.current.scrollTop = scrollHeight;
  };

  const changeScroll = () => {

    if (!messageRef.current || messages.length === 0) return;


    const numPrevChat = prevMessages.length;
    const numCurrChat = messages.length;
    const currScrollHeight = messageRef.current?.scrollHeight;
    
    if (numPrevChat !== numCurrChat) {

      if (numPrevChat === 0) {
        pageDown();
      } else {
        const prevLastChat = prevMessages[numPrevChat - 1];
        const currLastChat = messages[numCurrChat - 1];
        if (prevMessages[0].id !== messages[0].id) {
          messageRef.current.scrollTop = currScrollHeight - prevScrollHeight;
        }
        // 메시지 송수신 시 스크롤 변화
        else if (prevLastChat.id !== currLastChat.id) {
          // 채팅 수신 시, 채팅을 내가 보냈거나 스크롤이 밑에 있다면 스크롤을 가장 아래로 내립니다.
          // 예전엔 messageRef.scrollTop / messageRef.clientHeight 로 .current 가 빠져 있었다 —
          // ref 객체에는 그런 속성이 없어 undefined 산술 = NaN 이고, NaN 비교는 항상 false 라
          // "내가 보낸 메시지"가 아니면 스크롤이 바닥에 있어도 이 분기를 타지 못했다.
          if (
            sameUserId(currLastChat.senderId, user.userId) ||
            prevScrollHeight - messageRef.current.scrollTop <=
            messageRef.current.clientHeight + 100
          ) {
            pageDown();
          }
          // 스크롤이 위에 있다면, 채팅 왔다는 것을 알려주는 컴포넌트를 나타냅니다.
          else if (
            prevScrollHeight - messageRef.current?.scrollTop >
            messageRef.current?.clientHeight + 1000
          ) {
            setIsShowDownBtn(true);
            setSender(localizeChatName(t, currLastChat.nickname));
            setMessage(chatMessageText(t, currLastChat));
          }

        }
      }

      prevScrollHeight = currScrollHeight;
    }
  };


  // 구독
  useEffect(() => {
    let unsubscribe = () => { };

    
      // 신원은 CONNECT 때의 토큰이 정한다 — 헤더에 이메일을 싣지 않는다(서버도 읽지 않는다).
      const contentHeader =
        { roomId: room?.roomId };

      unsubscribe = subscribe(`/sub/chat/room/${room?.roomId}`, (message) => {
        const parsed = JSON.parse(message.body);
        //console.log("Received message:", parsed);
        setMessages((prev) => ([...prev, parsed]));


      }, contentHeader);

      setLoaded(true);
    

    return () => { unsubscribe(); }
  }, [isConnected, subscribe, room]);



  const onChatSumbmit = (msg) => {
    // 발신자·닉네임은 서버가 정한다(세션의 uid, 회원 행의 닉네임). 예전에는 이메일과 하드코딩된 닉네임 'yun' 을
    // 실어 보냈고, 서버는 그 둘을 덮어써 왔다 — 보낼 이유가 없다(docs/48 §6-⑥).
    sendMessage("/pub/chat/message", {
      type: 'TEXT_TALK',
      roomId: room?.roomId,
      content: msg
    });

  }

  const onHideRoom = () => {

    dispatch(showRoom(false));

  }

  // Flicking 
  //if (isPending) return ;

    return (
      
      isRoomHided ? (
      <Chat>
          {connectionError ? (
            <ConnectionBanner $isError>{connectionError}</ConnectionBanner>
          ) : !isConnected ? (
            <ConnectionBanner>{t("chat.connecting")}</ConnectionBanner>
          ) : null}
          <ChatHeader roomName={room.name} hideRoom={onHideRoom} />
          <ChatContent chattingList={messages}
            messageRef={messageRef} />
          <ChatFooter onChatSumbmit={onChatSumbmit} />
      </Chat> ) : <EmptyChatArea/> 
      
    ) 


}

export default memo(ChatArea);
