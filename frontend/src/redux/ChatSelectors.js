

export const selectedUser = (state) => state.chat.selectedUser;
export const selectedRoom = (state) => state.chat.selectedRoom;
export const hideRoom = (state) => state.chat.showRoom;
export const message = (state) => state.chat.message;
export const rooms = (state) => state.chat.rooms;
export const users = (state) => state.chat.users;
// `conversation` 셀렉터를 지웠다. chat 슬라이스에 `conversation` 이라는 필드는 없다
// (있는 것은 `roomConversation` 뿐이다). 이 셀렉터를 쓰면 언제나 undefined 가 나오는데,
// 이름이 그럴듯해서 "대화 내용을 가져오는 셀렉터"로 잘못 쓰이기 딱 좋았다.
export const roomConversation = (state) => state.chat.roomConversation;

