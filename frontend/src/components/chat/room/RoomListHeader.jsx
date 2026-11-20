import React, { useState } from 'react';
import { MainHeader, TitleBlock } from '../styles/BaseStyle';
import CreateRoomModal from './CreateRoomModal';
import { useTranslation } from 'i18n/I18nProvider';
import { FaPlus } from 'react-icons/fa';

/**
 * 채팅방 목록 헤더 — 제목 · 방 만들기 · 검색.
 *
 * <p>손본 것:
 *
 * <ul>
 *   <li>방 만들기가 <b>`<i>` 태그에 onClick</b> 이었다. 버튼이 아니라 키보드로 누를 수
 *       없고 스크린리더에도 잡히지 않는다. `<button>` 으로 바꾸고 aria-label 을 달았다.</li>
 *   <li>아이콘이 {@code fa-user-plus}(사람 추가)였다. 여기서 만드는 것은 사람이 아니라
 *       <b>방</b>이다 — 아이콘과 title("Add Room")이 서로 다른 말을 하고 있었다.</li>
 *   <li>제목 "Room" 과 placeholder "Search Name" 이 영어인데 모달은 한국어였다.</li>
 *   <li>쓰이지 않는 `createRoom` prop 과, .js 파일에 들어와 있던 TS 타입 import
 *       (`ChangeEvent`) 를 걷어냈다 — 후자는 런타임에 존재하지 않는 이름이다.</li>
 * </ul>
 *
 * <p>[JA] チャットルーム一覧のヘッダー — タイトル · 部屋作成 · 検索。
 * 手を入れたもの:
 * ・部屋作成が `<i>` タグの onClick だった。ボタンではないのでキーボードで押せず、スクリーンリーダーにも拾われない。
 * `<button>` に変えて aria-label を付けた。
 * ・アイコンが fa-user-plus(人の追加)だった。ここで作るのは人ではなく部屋である — アイコンと title("Add Room")が別のことを言っていた。
 * ・タイトル "Room" と placeholder "Search Name" が英語なのにモーダルは韓国語だった。
 * ・使われない `createRoom` prop と、.js ファイルに入り込んでいた TS 型の import(`ChangeEvent`)を取り除いた — 後者は実行時に存在しない名前である。
 */
const RoomListHeader = ({ changeSearch }) => {
  const { t } = useTranslation();
  const [isOpenCreateRoom, setIsOpenCreateRoom] = useState(false);

  const createRoomModal = isOpenCreateRoom ? (
    <CreateRoomModal
      onClose={() => setIsOpenCreateRoom(false)}
      overlayClose={false}
    />
  ) : null;

  const onSearchChange = (event) => {
    changeSearch(event.target.value);
  };

  return (
    <React.Fragment>
      {createRoomModal}
      <MainHeader>
        <TitleBlock>
          <h2>{t("chat.rooms.title")}</h2>
          <button
            type="button"
            className="add-room"
            title={t("chat.room.create")}
            aria-label={t("chat.room.create")}
            onClick={() => setIsOpenCreateRoom(true)}
          >
            <FaPlus aria-hidden="true" />
          </button>
        </TitleBlock>
        <input
          placeholder={t("chat.rooms.search")}
          aria-label={t("chat.rooms.search")}
          onChange={onSearchChange}
        />
      </MainHeader>
    </React.Fragment>
  );
};

export default RoomListHeader;
