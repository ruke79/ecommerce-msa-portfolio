import React from 'react';
import styled from 'styled-components';
import { useTranslation } from 'i18n/I18nProvider';
const Wrapper = styled.header`
  width: 100%;
  background-color: #a9bdce;
  height: 50px;
  
  display: flex;
   align-items: center;  
  border-bottom: 1px solid #dddddd35;
  
  
  
  & span {
    display: inline-block;
    font-weight: bold;
    font-size: 20px;
    margin-left: 10px;
    margin-top: 10px;
  }
  & button {
    font-size: 20px;
    padding: 10px 10px 10px 30px;
    background-color: #a9bdce;
    outline: none;
    cursor: pointer;
    &:hover {
      color: #dcdcdc;
    }
  }
`;

const ChatHeader = props => {
    const { t } = useTranslation();
    const { roomName, hideRoom } = props;
  const onBackBtnClick = (event) => {
    event.preventDefault();
    hideRoom();
  };
  return  (
    <Wrapper>
      <button type="button" onClick={onBackBtnClick} aria-label={t("chat.community.back")}>
        <span aria-hidden="true">←</span>
      </button>
      <span>{roomName}</span>
    </Wrapper>
  );

}

export default ChatHeader;