import { useAuthContext } from "store/AuthContext"

import styled from "styled-components";

const Wrapper = styled.div`

  padding: 12px 16px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #4b5563;
  

  .user {
    display: flex;
    align-items: center;
    gap: 20px;

    img {
      width: 50px;
      height: 50px;
      border-radius: 50%;
      object-fit: cover;
    }
  }

  .icons {
    display: flex;
    gap: 20px;

    img {
      width: 20px;
      height: 20px;
      cursor: pointer;
    }
  }
`;


const ChatUserinfo = () => {

  const { user } = useAuthContext();
  // 이메일이 없는 토큰(소셜 로그인 등)이면 빈 칸 대신 아무것도 그리지 않는다 — 예전에는 목록 위에 큰 빈 줄이 남았다.
  if (!user?.email) return null;

  return (
    <Wrapper>
      <div className={"user"}>        
        <span>{user.email}</span>
        </div>
      </Wrapper>
  )
}

export default ChatUserinfo