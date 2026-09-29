import styled from 'styled-components';

import { useTranslation } from "i18n/I18nProvider";

const Chat = styled.div`
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 24px;
  color: #475569;
  font-size: 14px;
  text-align: center;
  background: #b2c7d9;

  @media (max-width: 767px) {
    display: none;
  }
`;

const EmptyChatArea = () => {
    const { t } = useTranslation();
    return <Chat>{t("chat.community.pick")}</Chat>;
}

export default EmptyChatArea;
