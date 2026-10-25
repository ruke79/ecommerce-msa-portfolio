import { searchKey } from './searchKey';

describe('searchKey — 화면 안 검색의 비교 키', () => {
  it('🔴 전각 영숫자 · 반각 카나 · 전각 공백은 같은 키 — 일본어 IME 가 내는 모양', () => {
    expect(searchKey('ＶＩＰ　ﾙｰﾑ')).toBe(searchKey('vip ルーム'));
    expect(searchKey('ｼｬﾂ')).toBe('シャツ');
    expect(searchKey('Ｒｏｏｍ１')).toBe('room1');
  });

  it('공백은 지우고(예전 규칙) · 비어 있으면 빈 문자열', () => {
    expect(searchKey('신상 문의')).toBe('신상문의');
    expect(searchKey(undefined)).toBe('');
    expect(searchKey(null)).toBe('');
  });
});
