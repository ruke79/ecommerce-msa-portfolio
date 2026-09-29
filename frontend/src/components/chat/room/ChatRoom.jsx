import { useDispatch} from 'react-redux';
import { selectRoom, showRoom } from '../../../redux/ChatSlice';
import { useState } from 'react';
import { useTranslation } from 'i18n/I18nProvider';




const ChatRoom = (props) => {

    const { t } = useTranslation();
    const dispatch = useDispatch();
    const { room,  } = props;  
    
    const open = () => {
        dispatch(selectRoom(room));
        dispatch(showRoom(true));
    }

    // 예전에는 더블클릭으로만 열렸다 — 알려 주는 곳도 없고 키보드 · 터치로는 열 수 없었다.
    const onKeyDown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            open();
        }
    }

    return (
        <li role="button" tabIndex={0} onClick={open} onKeyDown={onKeyDown}>
          <p>
            <b>{room.name}</b>
          </p>
          <p>{t("chat.room.online")}</p>
        </li>
      );
}

export default ChatRoom;