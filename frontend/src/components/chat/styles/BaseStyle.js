import styled from 'styled-components';

export const SideBar = styled.aside`
  position: fixed;
  top: 0;
  left: 0;
  width: 100px;
  height: 100%;
  min-height: 100vh;
  background: #dfdfdf;
  padding-top: 20px;
  z-index: 2;
  & li {
    padding: 10px;
    text-align: center;
    font-size: 25px;
    color: #a6a7a8;
    cursor: pointer;
    &:hover {
      color: #888777;
    }
  }
`;
export const Main = styled.main`
  padding-left: 100px;
  width: 100%;
  min-height: 100vh;
`;
export const MainHeader = styled.section`  
  //position: fixed;
  // top: 0;
  //  left: 0;
  padding: 16px 16px 8px 16px;
  
  background-color: #fff;
  z-index: 1;
  & input {
    border: none;
    outline: none;
    border-radius: 10px;
    background-color: #f6f6f7;
    width: 100%;
    padding: 5px 10px;
    &:focus {
      &::placeholder {
        color: #f6f6f7;
      }
    }
  }
`;
export const TitleBlock = styled.section`
  position: relative;
  & h2 {
    font-size: 20px;
    font-weight: bold;
    margin-bottom: 10px;
  }

  /*
   * 방 만들기 버튼. 예전에는 아이콘 <i> 에 직접 onClick 이 달려 있어 키보드로 누를 수
   * 없었다(RoomListHeader 주석). <button> 으로 바뀌었으므로 브라우저 기본 버튼 스타일을
   * 지우고 원형 타깃을 준다 — 20px 아이콘 하나는 누르기에 너무 작다.
   */
  & button.add-room {
    position: absolute;
    top: 0;
    right: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: #4b4b4b;
    font-size: 16px;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;

    &:hover {
      background: #f0f0f2;
      color: #000;
    }
    &:focus-visible {
      outline: 2px solid #232f3e;
      outline-offset: 2px;
    }
  }
`;
export const MainContent = styled.section`
  flex: 1;
  min-height: 0;
  overflow: auto;

  & li {
    padding: 14px 20px;
    cursor: pointer;
    border-bottom: 1px solid #f1f1f3;
    &:focus-visible {
      outline: 2px solid #2563eb;
      outline-offset: -2px;
    }
    & p {
      color: #707070;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      min-height: 19px;
      font-size: 12px;
      & b {
        color: #000;
        font-weight: bold;
        font-size: 14px;
      }
    }
    &:hover {
      background-color: #eaeaeb;
    }
  }
`;

export const Notification = styled.span`
  position: absolute;
  display: inline-block;
  padding: 3px;
  color: #fff;
  background-color: #ff513d;
  border: none;
  border-radius: 20px;
  font-weight: bold;
  min-width: 25px;
  text-align: center;
`;
