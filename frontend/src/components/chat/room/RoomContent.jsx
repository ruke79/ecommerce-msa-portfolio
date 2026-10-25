import styled from 'styled-components';
import { MainContent } from '../styles/BaseStyle';
import { useDispatch, useSelector } from 'react-redux';
import { selectedRoom } from '../../../redux/ChatSelectors';
import { selectRoom } from '../../../redux/ChatSlice';
import ChatRoom from './ChatRoom';
import { searchKey } from '../../../util/searchKey';


const RoomContent = ({search, rooms}) => {

    /**
     * 🐛 예전에는 검색어를 그대로 `new RegExp(...)` 에 넣었다. 사용자가 `(` · `[` · `*`
     * 같은 정규식 메타문자를 한 글자만 쳐도 <b>RegExp 생성자가 예외를 던져 채팅 목록
     * 전체가 죽었다.</b> 검색은 "포함하는가"면 충분하다 — 정규식이 필요 없다.
     *
     * <p>공백을 지우고 비교하는 규칙은 그대로 둔다("신상 문의"를 "신상문의"로 찾기 위한 것). 전각 · 반각 카나는 NFKC 로 맞춘다(searchKey).
     */
    const normalize = searchKey;

    const needle = normalize(search);

    // `rooms` 가 없을 수 있다(첫 로드·요청 실패). 예전에는 [...rooms] 에서 그대로 터졌다.
    const temp = Array.isArray(rooms) ? [...rooms] : [];
    const roomList = temp.sort((a, b) => {
      return String(a?.name ?? '').localeCompare(String(b?.name ?? ''));
    });

    const searchedRooms = needle === ''
      ? roomList
      : roomList.filter(room => normalize(room?.name).includes(needle));

    const roomSelected = useSelector(selectedRoom);    
    const dispatch = useDispatch();
  
      
    
  

    return (      
        <MainContent>          
          <ul> {
            searchedRooms.map(room => {
              return (
                <ChatRoom room={room}               
                key = {room.roomId}                  
                />                
              )
              
          })
        }
        </ul>        
        </MainContent>      
    )   
        
}

export default RoomContent;