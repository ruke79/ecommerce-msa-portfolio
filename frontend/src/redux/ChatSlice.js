import { createSlice } from "@reduxjs/toolkit";

const initialState  = {
    users: [],
    contacts: [],    
    roomConversation: [],
    rooms: [],    
    showRoom : false,
    selectedUser: null,
    selectedRoom: null,        
    loadUser: false,
    search: "",
    error: "",
    message: "",    
    loading: false,
    image : {file: null, url: "",}
  };

  export const chatSlice = createSlice({
    name : "chat",
    initialState : initialState,
    reducers: {
      addRoomToList : (state, action) => {
        state.rooms.push(action.payload)             
      },
      selectRoom : (state, action) => {
        state.selectedRoom = action.payload;
      },
      setRooms : (state, action) => {
        state.rooms = action.payload;
      },
      setRoomConversation : (state, action) => {
        state.roomConversation = action.payload;
      },
      fetchRoomConversation : (state, action) => {
        state.roomConversation = [...action.payload, ...state.roomConversation];
      },
      setImage : (state, action) => {
        state.image = action.payload;
      },
      showRoom : (state, action) => {
        state.showRoom = action.payload;
      }
    }
  });

  export const { addRoomToList, setRooms, setRoomConversation, fetchRoomConversation, selectRoom, setImage, showRoom } = chatSlice.actions;

  export default chatSlice.reducer;

  // 여기 있던 손으로 쓴 `chatReducer` 스위치문(약 40줄)을 지웠다. 이 파일의 기본
  // 내보내기는 위의 `chatSlice.reducer` 이고 스토어도 그것을 쓴다 — 아래 함수는 어디에도
  // 연결되지 않은 채, 슬라이스와 **다른 상태 전이 규칙**을 담고 있었다. 채팅 동작을 고치러
  // 온 사람이 둘 중 어느 쪽이 진짜인지 알 수 없었고, 실제로 쓰이지 않는 쪽을 고쳐도
  // 아무 일도 일어나지 않는다. 관련 액션 타입 상수 import 도 함께 정리했다.
  // [JA] ここにあった手書きの `chatReducer` の switch 文(約 40 行)を削除した。このファイルの default
  //      export は上の `chatSlice.reducer` で、ストアもそれを使う — 下の関数はどこにも
  //      つながっていないまま、スライスと **異なる状態遷移ルール** を持っていた。チャットの動作を直しに
  //      来た人はどちらが本物かわからず、実際には使われていない方を直しても
  //      何も起きない。関連するアクションタイプ定数の import も一緒に整理した。
