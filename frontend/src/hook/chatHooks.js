import { useMutation, useQuery } from "@tanstack/react-query";
import { useRef, useEffect } from "react";
import { useDispatch } from "react-redux";
import { addRoomToList } from "../redux/ChatSlice";

import { createChatRoom, getChatRoomList, getRoomMessages, throwWithoutData } from "util/api";

export const useCreateChatRoom = (props) => {
    
    const dispatch = useDispatch();
    
    const {
      mutateAsync,
      data,
      isPending,
      isSuccess,
      error,
      reset,
    } = useMutation({mutationFn : createChatRoom,
      onSuccess: (data) => {
        if (data) {
          dispatch(addRoomToList(data));
        }
      },
      /**
       * 🐛 방 만들기가 실패하면 **홈으로 튕겼다.**
       *
       * 전역 QueryClient 가 `mutations: { throwOnError: true }` 다(util/api.js). 여기
       * `onError` 가 있어도 v5 는 그것과 별개로 예외를 다시 던지므로, 서버가 500 을 내면
       * 예외가 ErrorBoundary 까지 올라가 화면 전체가 대체되고 사용자는 홈으로 떨어졌다.
       * 이 mutation 만 끈다 — 방 하나 못 만든 것이 앱을 날릴 이유가 없다.
       *
       * 오류는 삼키지 않고 `error` 로 돌려준다. 호출부가 화면에 보여줄 수 있다.
       */
      throwOnError: false,
    });

    /**
     * 방을 만든다.
     *
     * <p>인자를 받는다. 예전에는 인자를 <b>무시하고</b> 훅에 넘긴 props 만 썼는데,
     * 호출부는 `handleCreateChatRoom(roomName)` 처럼 값을 넘기고 있었다 — 우연히 같은
     * 값이라 동작했을 뿐, 다른 값을 넘기면 조용히 무시된다.
     *
     * <p>`mutateAsync` 가 아니라 `mutate` 를 쓰되 Promise 를 돌려준다. 호출부가
     * "성공한 뒤에 모달을 닫는다"를 표현할 수 있어야 한다 — 예전에는 요청을 보내자마자
     * 닫아서 실패해도 아무 표시가 없었다.
     */
    const handleCreateChatRoom = (roomName) =>
        mutateAsync(roomName === undefined ? props : { roomName });

    return { handleCreateChatRoom, data, isPending, isSuccess, error, reset };
  };

  export const useFetchChatRooms = () => {
   
    
    const { data, isPending, refetch, isSuccess, error, isError } = useQuery({
      queryKey : ["rooms"],
      queryFn : async () => {
        const res = await getChatRoomList();                  
        return res;
        
      },      
      throwOnError: throwWithoutData,      
  });
    
    
    return {
      roomList: data,
      isPending: isPending,
      isFetch: isSuccess,
      error : error, isError : isError,
      updateChatRoomList: refetch,
    };
  };

  export const useFetchRoomMessages = (room, cursor) => {
    
    
    
    const { data, isPending, refetch, isSuccess } = useQuery({
      queryKey : [`room_messages/${room?.roomId}`, cursor ],
      queryFn : async () => {

        const res = await getRoomMessages(room, cursor);
        // 🐛 배열이 아닌 응답(게이트웨이의 HTML 오류 페이지 · 빈 본문)에 .reverse() 를 불러 TypeError 가 나면
        //    화면 전체가 오류 경계로 떨어졌다. 배열이 아니면 "메시지 없음" 으로 다룬다 — 방 목록 · 입력은 살아 있다.
        return Array.isArray(res) ? [...res].reverse() : [];
      },
    
      throwOnError: throwWithoutData,      
      staleTime : 1000,
      // roomId가 있을 때만 쿼리를 실행합니다.
      enabled: !!room?.roomId, 
  });
    
    return {
      roomMessages : data,
      isPending: isPending,
      isFetch: isSuccess,
      updateRoomMessages: refetch,
    };
  };
  
  // export const fetchMessages = (room, cursor) => {
    
  //   let promise = new Promise(async function (resolve, reject) {        
  //       const res = await getRoomMessages(room , cursor);              
  //       resolve(res);                         
  //     }
  //   );

  //   return promise;         
  // }


  export function usePrevState(state) {
    const ref = useRef(state);
    useEffect(() => {
      ref.current = state;
    }, [state]);
    return ref.current;
  }
  