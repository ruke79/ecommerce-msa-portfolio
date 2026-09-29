/**
 * 테스트의 시간대를 서울로 고정한다.
 *
 * <p>관리자 입력칸(datetime-local)은 현지 시각이고 서버는 UTC 다 — 그 변환(util/datetime)을 검증하려면 현지가 UTC 가 아니어야
 * 한다. CI 컨테이너는 UTC 라 변환을 빼먹어도 통과해 버린다(기획전 시작일이 하루 밀린 버그가 그렇게 숨었다). 테스트 파일 안에서
 * process.env.TZ 를 바꾸는 것은 먹지 않는다 — jest 가 테스트마다 process.env 사본을 주기 때문이다. 워커가 뜨기 전에 여기서 정한다.
 */
module.exports = async () => {
  process.env.TZ = "Asia/Seoul";
};
