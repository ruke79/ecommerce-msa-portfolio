import { Outlet } from "@mui/icons-material";
import ChatRoomList from "./ChatRoomList"
import ChatUserinfo from "./ChatUserinfo"
import styled from "styled-components";

const Wrapper = styled.div`

flex: 1;
display: flex;
flex-direction: column;    
`;



const List = () => {
  return (
    <Wrapper>
      <ChatUserinfo/>
      {/* <ChatRoomList/> */}
      <Outlet/>
    </Wrapper>
  )
}

export default List