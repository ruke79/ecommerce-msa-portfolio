import { useState } from 'react';
import styled from 'styled-components';
import Modal from '../Modal';
import { useCreateChatRoom, useFetchChatRooms } from 'hook/chatHooks';
import { useTranslation } from 'i18n/I18nProvider';
import { FaTimes } from 'react-icons/fa';

const MAX_LEN = 20;

/**
 * 🐛 <b>확인·취소 버튼이 모달 밖 화면 맨 아래에 붙어 있었다.</b>
 *
 * 예전 `FooterWrapper` 는 `position: fixed; bottom: 0; left: 0; width: 100%` 였다.
 * 그런데 `Modal` 의 컨테이너도 `position: fixed` 로 화면 한가운데 떠 있고, Footer 는 그
 * 안의 <b>형제</b>였다. fixed 는 뷰포트를 기준으로 하므로 버튼 줄만 떨어져 나와 화면
 * 밑변에 가로로 꽉 차게 그려졌다 — 360px 짜리 대화상자와 아무 상관없는 위치에.
 *
 * 대화상자 하나로 합친다. 높이도 고정(450px)이 아니라 내용에 맞춘다.
 */
const Dialog = styled.div`
  width: 360px;
  max-width: calc(100vw - 32px);
  border-radius: 12px;
  overflow: hidden;
  color: #000;
  background: #fff;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
  display: flex;
  flex-direction: column;
`;

const Header = styled.div`
  position: relative;
  padding: 18px 20px 14px;
  border-bottom: 1px solid #ececec;

  & h4 {
    font-size: 17px;
    font-weight: 600;
  }
  & p {
    margin-top: 4px;
    font-size: 12px;
    color: #767676;
  }
`;

const CancelIcon = styled.button`
  position: absolute;
  top: 14px;
  right: 14px;
  font-size: 15px;
  line-height: 1;
  color: #767676;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;

  &:hover {
    color: #000;
  }
`;

const Body = styled.div`
  padding: 20px;

  & label {
    display: block;
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 8px;
  }

  & .field {
    display: flex;
    align-items: center;
    border: 1px solid #dcdcdc;
    border-radius: 8px;
    padding: 0 10px;
    transition: border-color 0.15s;

    &:focus-within {
      border-color: #111;
    }
  }

  & input {
    flex: 1;
    padding: 10px 0;
    outline: none;
    border: none;
    font-size: 14px;
    background: transparent;
  }

  & .count {
    font-size: 12px;
    color: #969696;
    padding-left: 8px;
  }

  & .error {
    margin-top: 10px;
    font-size: 12px;
    color: #d32f2f;
  }
`;

const Footer = styled.div`
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid #ececec;
  background: #fafafa;

  & button {
    border: 1px solid #dcdcdc;
    border-radius: 8px;
    background: #fff;
    padding: 9px 20px;
    font-size: 14px;
    cursor: pointer;

    &.confirm {
      background: #fee500;
      border-color: #f2d900;
      font-weight: 600;

      &:hover {
        background: #fada0a;
      }
    }
    &:disabled {
      color: #969696;
      background: #e2e2e2;
      border-color: #e2e2e2;
      cursor: not-allowed;
    }
    &.cancel:hover {
      background: #f5f5f5;
    }
  }
`;

const CreateRoomModal = props => {
  const { overlayClose, onClose } = props;
  const { t } = useTranslation();

  const [roomName, setRoomName] = useState('');
  const { handleCreateChatRoom, isPending, error } = useCreateChatRoom({ roomName });
  const { updateChatRoomList } = useFetchChatRooms();

  const trimmed = roomName.trim();
  const canSubmit = trimmed.length > 0 && !isPending;

  const onNameChange = (event) => {
    const value = event.target.value;
    if (value.length <= MAX_LEN) {
      setRoomName(value);
    }
  };

  /**
   * 🐛 예전 `onConfirmed` 는 요청을 보내자마자 <b>곧바로</b> 목록을 새로고침하고 모달을
   * 닫았다. 그래서
   *
   *   · 방이 아직 만들어지기 전에 목록을 다시 받아 새 방이 보이지 않았고,
   *   · 서버가 실패해도(500) 화면에는 아무 표시가 없었다. 사용자에게는 "모달이 닫히고
   *     방은 없다"로만 보였다.
   *
   * 성공한 뒤에 닫는다. 실패하면 열어 둔 채로 이유를 보여준다.
   */
  const onConfirmed = async () => {
    if (!canSubmit) return;

    try {
      await handleCreateChatRoom(trimmed);
      await updateChatRoomList();
      onClose();
    } catch (e) {
      // error 로 화면에 표시된다. 모달은 닫지 않는다.
    }
  };

  const onSubmit = (event) => {
    event.preventDefault();
    onConfirmed();
  };

  return (
    <Modal overlayClose={overlayClose} onClose={onClose}>
      <Dialog>
        <Header>
          <h4>{t("chat.room.create")}</h4>
          <p>{t("chat.room.name_limit", { max: MAX_LEN })}</p>
          <CancelIcon type="button" aria-label={t("chat.room.close")} onClick={onClose}>
            <FaTimes aria-hidden="true" />
          </CancelIcon>
        </Header>

        <Body>
          <form onSubmit={onSubmit}>
            <label htmlFor="chat-room-name">{t("chat.room.name")}</label>
            <div className="field">
              <input
                id="chat-room-name"
                value={roomName}
                maxLength={MAX_LEN}
                autoFocus={true}
                placeholder={t("chat.room.name_placeholder")}
                disabled={isPending}
                onChange={onNameChange}
              />
              <span className="count">{`${roomName.length}/${MAX_LEN}`}</span>
            </div>

            {error && (
              <p className="error">
                {error?.response?.data?.message ?? t("chat.room.create_failed")}
              </p>
            )}
          </form>
        </Body>

        <Footer>
          <button type="button" className="cancel" onClick={onClose} disabled={isPending}>
            {t("common.cancel")}
          </button>
          {/*
            * 예전에는 `isCanSubmit={true}` 로 **항상** 활성화돼 있었다. 이름을 비운 채
            * 확인을 누르면 이름이 빈 방이 만들어졌다.
            */}
          <button type="button" className="confirm" onClick={onConfirmed} disabled={!canSubmit}>
            {isPending ? t("chat.room.creating") : t("chat.room.confirm")}
          </button>
        </Footer>
      </Dialog>
    </Modal>
  );
};

export default CreateRoomModal;
