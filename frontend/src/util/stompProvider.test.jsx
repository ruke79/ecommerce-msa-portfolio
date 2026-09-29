import { render } from "@testing-library/react";

import { StompProvider } from "util/stompProvider";
import { AUTH_REFRESH_EVENT } from "util/authEvents";

const mockClients = [];
jest.mock("@stomp/stompjs", () => ({
  Client: jest.fn().mockImplementation((config) => {
    const client = { config, activate: jest.fn(), deactivate: jest.fn(), subscribe: jest.fn(), publish: jest.fn() };
    mockClients.push(client);
    return client;
  }),
}));
jest.mock("sockjs-client", () => jest.fn());

/**
 * 채팅 연결 — 서버가 토큰 만료 · 로그인 해제로 닫았을 때(user-service docs/68 §9).
 *
 * <p>같은 토큰으로 다시 붙어 봐야 거절된다. 자동 재연결을 멈추고 갱신을 부탁해야 한다.
 */
describe("StompProvider — 서버가 4401 로 닫으면", () => {
  const headers = { Authorization: "tok" };

  beforeEach(() => {
    mockClients.length = 0;
  });

  it("🔴 자동 재연결을 멈추고 토큰 갱신을 부탁한다", () => {
    const onRefresh = jest.fn();
    window.addEventListener(AUTH_REFRESH_EVENT, onRefresh);
    render(<StompProvider brokerURL="http://x/chat" connectHeaders={headers}><div /></StompProvider>);

    mockClients[0].config.onWebSocketClose({ code: 4401 });

    expect(mockClients[0].deactivate).toHaveBeenCalled();
    expect(onRefresh).toHaveBeenCalledTimes(1);
    window.removeEventListener(AUTH_REFRESH_EVENT, onRefresh);
  });

  it("그밖의 끊김(네트워크 · 서버 재시작)은 그대로 자동 재연결에 맡긴다", () => {
    const onRefresh = jest.fn();
    window.addEventListener(AUTH_REFRESH_EVENT, onRefresh);
    render(<StompProvider brokerURL="http://x/chat" connectHeaders={headers}><div /></StompProvider>);

    mockClients[0].config.onWebSocketClose({ code: 1006 });

    expect(mockClients[0].deactivate).not.toHaveBeenCalled();
    expect(onRefresh).not.toHaveBeenCalled();
    window.removeEventListener(AUTH_REFRESH_EVENT, onRefresh);
  });

  it("토큰이 바뀌면 새 토큰으로 다시 붙는다", () => {
    const { rerender } = render(
      <StompProvider brokerURL="http://x/chat" connectHeaders={headers}><div /></StompProvider>);

    rerender(<StompProvider brokerURL="http://x/chat" connectHeaders={{ Authorization: "new" }}><div /></StompProvider>);

    expect(mockClients).toHaveLength(2);
    expect(mockClients[1].config.connectHeaders).toEqual({ Authorization: "new" });
    expect(mockClients[1].activate).toHaveBeenCalled();
  });
});
