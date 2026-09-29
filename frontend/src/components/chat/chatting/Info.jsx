import React from 'react';
import styled from 'styled-components';

const BorderBlock = styled.div`
  position: relative;
  text-align: center;
  width: 100%;
  padding: 13px 0;
  & span {
    position: relative;
    display: inline-block;
    background-color: #b2c7d9;
    padding: 0 10px;
  }
  &:before {
    content: '';
    display: block;
    position: absolute;
    left: 2%;
    top: 50%;
    width: 96%;
    height: 1px;
    background-color: #727b83;
  }
`;

const DownBtnWrapper = styled.div`
  position: absolute;
  bottom: 96px;
  right: 24px;
  z-index: 5;
  & i {
    width: 40px;
    height: 40px;
    padding: 10px;
    border-radius: 20px;
    text-align: center;
    background: #6e6e6e;
    color: #fff;
    font-size: 20px;
    &:hover {
      cursor: pointer;
      border: 1px solid #000;
    }
  }
`;

// 날짜를 표시하는 등 채팅방의 경계를 나타냅니다.
export const SeparationBoundary = ({content }) => {
    return (
      <BorderBlock>
        <span>{content}</span>
      </BorderBlock>
    );
  };

export const DownBtn = props => {
    const { onDownClick } = props;
    return (
      <DownBtnWrapper onClick={onDownClick}>
        <i className="fas fa-angle-down" />
      </DownBtnWrapper>
    );
  };