import React from 'react';
import styled from 'styled-components';
import { SeparationBoundary } from './Info';

const ChatWrapper = styled.div`
   
  position: relative;
  display: inline-block;
  padding: 7px 8px;
  border-radius: 4px;
  margin-bottom: 7px;
  box-shadow: 0px 1px 2px 0px #8fabc7;
  max-width: 70%;
  word-wrap: break-word;
  white-space: pre-wrap;
`;

// 시각(.time)은 말풍선 끝에 붙인다(right/left: calc(100% + 6px)). 예전에는 -70px 고정이라 "오후 10:46" 처럼
// 70px 보다 긴 시각이 말풍선을 덮었다.
const RightBlock = styled.div`
  flex: 1;
  text-align: right;  
  margin-top: 10px;
  margin-left: 10px;
  margin-right: 10px;

  & ${ChatWrapper} {
    background-color: #ffec42;
    text-align: left;
    & span {
      position: absolute;
      display: inline-block;
      &.time {
        right: calc(100% + 6px);
        bottom: 0;
        white-space: nowrap;
        font-size: 11px;
        color: #475569;
      }
      &.not-read {
        color: #ffec42;
        min-width: 30px;
        text-align: right;
        bottom: 16px;
        left: -35px;
      }
    }
  }
`;
const LeftBlock = styled.div`  
  flex:1;
  position: relative;  
  margin-top: 10px;
  margin-left: 10px;
  margin-right: 10px;
  padding-left: 50px;
  & ${ChatWrapper} {
    background-color: #fff;       
    & span {
      position: absolute;
      &.time {
        left: calc(100% + 6px);
        bottom: 0;
        white-space: nowrap;
        font-size: 11px;
        color: #475569;
      }
      &.not-read {
        color: #ffec42;
        min-width: 30px;
        text-align: left;
        bottom: 16px;
        right: -35px;
      }
    }
  }
  & img {
    position: absolute;
    top: 3px;
    left: 0;
    height: 45px;
    width: 45px;
    border-radius: 20px;
    float: left;
    cursor: pointer;
  }
`;
const NameBlock = styled.div`
  margin-bottom: 5px;
`;

export const Chat = ({ msg, localeTime, notRead }) => {
    return (
      <ChatWrapper>
        {msg}
        <span className="time">{localeTime}</span>
        <span className="not-read">{notRead > 0 ? notRead : ''}</span>
      </ChatWrapper>
    );
  };


  export const MyChat = props => {
    const { content } = props;
    return (
      <React.Fragment>
        {content ? <SeparationBoundary content={content} /> : null}
        <RightBlock>
          <div>
            <Chat {...props} />
          </div>
        </RightBlock>
      </React.Fragment>
    );
  };

  export const OthersChat = props => {
    return (
      <LeftBlock>
        <div>
          <Chat {...props} />
        </div>
      </LeftBlock>
    );
  };

  export const OthersChatWithThumbnail = props => {
    const { content, onImgClick } = props;
    return (
      <React.Fragment>
        {content ? <SeparationBoundary content={content} /> : null}
        <LeftBlock>
          {<NameBlock></NameBlock> }
          <div>
            <Chat {...props} />
          </div>
        </LeftBlock>
      </React.Fragment>
    );
  };
  