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
