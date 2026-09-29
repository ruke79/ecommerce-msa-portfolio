import React,{ useEffect, useState } from "react";

import RoomListHeader from "../room/RoomListHeader";
import RoomContent from  "../room/RoomContent";
import { useFetchChatRooms } from "hook/chatHooks";
import { useDispatch, useSelector } from "react-redux";
import { setRooms } from "../../../redux/ChatSlice";
import { rooms } from "../../../redux/ChatSelectors";
import DotLoaderSpinner from "components/loader/Loading";
import { useTranslation } from "i18n/I18nProvider";



const ChatRoomList = () => {
  const { t } = useTranslation();
  const dispatch = useDispatch();  
  const [input, setInput] = useState("");
  const currRooms = useSelector(rooms);
  

  
  const { roomList, isPending, isFetch  } =  useFetchChatRooms();   
        
  useEffect(() => {
    // 🐛 의존성 배열이 `[dispatch, , isPending]` 이었다 — 가운데가 **빈 칸**이다(undefined).
    //    타이핑 실수로 보이지만 결과가 있다: 정작 동기화 대상인 `roomList` 가 빠져 있어
    //    목록이 새로 도착해도 redux 에 반영되지 않았다. 방을 만든 뒤 목록을 refetch 해도
    //    화면이 그대로인 이유가 이것이다.
    if (isFetch) {
      dispatch(setRooms(roomList));
    }
  }, [dispatch, isFetch, roomList]);

  // 헤더는 항상 그린다. 예전에는 로딩 중 아무것도 반환하지 않아(`return;`) 목록 영역이
  // 통째로 사라졌다가 나타났다 — 방 만들기 버튼까지 함께 사라졌다.
  return (
    <React.Fragment>
      <RoomListHeader changeSearch={setInput} />
      {isPending
        ? <p style={{ padding: '16px', color: '#767676', fontSize: '13px' }}>
            {t("chat.rooms.loading")}
          </p>
        : <RoomContent search={input} rooms={currRooms} />}
    </React.Fragment>
  );
};

export default ChatRoomList;
