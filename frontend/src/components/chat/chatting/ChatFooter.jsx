import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { useTranslation } from 'i18n/I18nProvider';


const Wrapper = styled.footer`

  flex-shrink: 0;
  width: 100%;
  min-height: 25px;
  max-height: 200px;     
   background-color:rgb(150, 142, 142);  
   
    padding: 10px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-top: 1px solid #dddddd35;
    gap: 20px;
    margin-top: auto;
  
   .icons {
     padding: 10px;
      display: flex;
      gap: 20px;
    }

      img {
      width: 40px;
      height: 40px;
      cursor: pointer;
    }

    input {      
      flex: 1;
      background-color: rgba(17, 25, 40, 0.5);
      border: none;
      outline: none;
      color: white;
      padding: 20px;
      border-radius: 10px;
      font-size: 16px;

      &.disabled {
        cursor: not-allowed;
      }
    }

    &.emoji {
      position: relative;

      &.picker {
        position: absolute;
        bottom: 50px;
        left: 0;
      }
    }

    .sendButton {      
      background-color: #5183fe;
      color: white;
      padding: 10px 20px;
      border: none;
      border-radius: 5px;
      cursor: pointer;

        &.disabled {
        background-color: #5182feb4;
        cursor: not-allowed;
      }    
    }

`;



const ChatFooter = ({ onChatSumbmit }) => {
    const { t } = useTranslation();
    const [message, setMessage] = useState('');

    const [img, setImg] = useState({
      file: null,
      url: "",
    });

    // URL.createObjectURL 은 브라우저가 파일을 붙들게 만든다 — revoke 하기 전까지는 GC 대상이
    // 아니다. 파일을 바꾸거나 푸터가 사라질 때 이전 URL 을 놓아준다.
    // (첨부 UI 는 지금 주석 처리돼 있어 이 경로는 잠들어 있다. 되살릴 때 누수가 같이
    //  살아나지 않도록 미리 막아 둔다.)
    useEffect(() => {
      const url = img.url;
      return () => {
        if (url) URL.revokeObjectURL(url);
      };
    }, [img.url]);

    // 채팅 내용이 공백이라면, 채팅을 보낼 수 없도록 설정하였습니다.
  const isCanSubmit = !!message.replace(/ |\n/g, '');
  const btnClassName = isCanSubmit ? 'canSubmit' : 'cannotSubmit';
  const onMessageChange = (event) => {
    event.preventDefault();
    const value = event.target.value;
    setMessage(value);
  };
  const requestSubmit = () => {
    if (isCanSubmit) {
      onChatSumbmit(message);
      setMessage('');
    }
  };

  const onSubmit = (event) => {
    event.preventDefault();
    requestSubmit();
  };
  const onEnterPress = (event) => {
    // shift + enter 이면 줄바꿈이 되고, enter키만 누르면 채팅 전송이 됩니다.
    if (!event.shiftKey && event.key === 'Enter') {
      event.preventDefault();
      requestSubmit();
    }
  };

  const handleImg = (e) => {
    if (e.target.files[0]) {
      setImg({
        file: e.target.files[0],
        url: URL.createObjectURL(e.target.files[0]),
      });
    }
  };

  return (
    <Wrapper>
         {/* <div className={"icons"}>
          <label htmlFor="file">
            <img src="./img.png" alt="" />
          </label>
          <input
            type="file"
            id="file"
            style={{ display: "none" }}
            onChange={handleImg}
          />          
        </div> */}
        <input className={"input"}
          type="text"          
          value={message}
          onChange={onMessageChange}
          onKeyDown={onEnterPress}          
        />
         <button  type="button"
          className={"sendButton"}
          onClick={onSubmit}          
        >
          {t("chat.send")}
        </button>
      
    </Wrapper>
  );
};

export default ChatFooter;