/**
 * 탭 간 로그인 동기화 — 토큰을 보내지 않고, 자기 메시지는 받지 않고, BroadcastChannel 이 없으면 storage 이벤트로.
 */
// 여러 탭을 흉내 내는 BroadcastChannel — 같은 이름의 다른 인스턴스에 전달한다(같은 탭의 다른 인스턴스 포함, 실제와 같다).
class FakeChannel {
  static all = [];
  constructor(name) {
    this.name = name;
    this.onmessage = null;
    this.closed = false;
    this.posted = [];
    FakeChannel.all.push(this);
  }
  postMessage(data) {
    this.posted.push(data);
    FakeChannel.all
      .filter((c) => c !== this && c.name === this.name && !c.closed && c.onmessage)
      .forEach((c) => c.onmessage({ data: JSON.parse(JSON.stringify(data)) }));
  }
  close() {
    this.closed = true;
  }
}

const load = () => {
  jest.resetModules();
  return require("./authSync");
};

describe("authSync — BroadcastChannel", () => {
  beforeEach(() => {
    FakeChannel.all = [];
    window.BroadcastChannel = FakeChannel;
  });
  afterEach(() => {
    delete window.BroadcastChannel;
  });

  it("🔴 보내는 메시지에는 종류 · 사용자 id · 출처 · 시각만 — 토큰이 들어갈 자리가 없다", () => {
    const sync = load();
    sync.publishAuth(sync.SIGNED_IN, "1234567890123456789");
    const sent = FakeChannel.all[0].posted[0];
    expect(Object.keys(sent).sort()).toEqual(["at", "from", "type", "uid"]);
    expect(sent.uid).toBe("1234567890123456789");
    expect(JSON.stringify(sent)).not.toMatch(/eyJ/);   // JWT 모양이 없다
  });

  it("🔴 같은 탭이 보낸 것은 받지 않는다(같은 탭의 다른 채널 인스턴스에도 전달되므로 출처로 거른다)", () => {
    const sync = load();
    const got = jest.fn();
    sync.subscribeAuth(got);
    sync.publishAuth(sync.SIGNED_OUT);
    expect(got).not.toHaveBeenCalled();
  });

  it("다른 탭의 알림은 종류 · id 만 넘긴다", () => {
    const sync = load();
    const got = jest.fn();
    sync.subscribeAuth(got);
    const other = new FakeChannel(sync.AUTH_SYNC_CHANNEL);
    other.postMessage({ type: "signed-in", uid: 42, from: "other-tab", at: 1, token: "eyJx" });
    expect(got).toHaveBeenCalledWith({ type: "signed-in", uid: "42" });
  });

  it("모르는 종류 · 깨진 메시지는 무시한다", () => {
    const sync = load();
    const got = jest.fn();
    sync.subscribeAuth(got);
    const other = new FakeChannel(sync.AUTH_SYNC_CHANNEL);
    other.postMessage({ type: "steal-token", from: "other-tab" });
    other.postMessage("signed-out");
    sync.publishAuth("whatever");
    expect(got).not.toHaveBeenCalled();
  });

  it("구독을 해제하면 채널을 닫는다", () => {
    const sync = load();
    const off = sync.subscribeAuth(jest.fn());
    off();
    expect(FakeChannel.all[0].closed).toBe(true);
  });
});

describe("authSync — BroadcastChannel 이 없을 때(storage 이벤트)", () => {
  beforeEach(() => {
    delete window.BroadcastChannel;
    window.localStorage.clear();
  });

  it("🔴 값을 쓰자마자 지운다 — 저장소에 아무것도 남지 않는다", () => {
    const sync = load();
    const setItem = jest.spyOn(Storage.prototype, "setItem");
    sync.publishAuth(sync.SIGNED_OUT);
    expect(setItem).toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    setItem.mockRestore();
  });

  it("다른 탭이 쓴 값을 받는다 · 자기 것과 깨진 값은 무시한다", () => {
    const sync = load();
    const got = jest.fn();
    sync.subscribeAuth(got);
    const fire = (newValue) =>
      window.dispatchEvent(new StorageEvent("storage", { key: "ecommerce-auth-sync", newValue }));
    fire(JSON.stringify({ type: "signed-out", uid: null, from: "other-tab", at: 1 }));
    fire("{broken");
    fire(null);
    expect(got).toHaveBeenCalledTimes(1);
    expect(got).toHaveBeenCalledWith({ type: "signed-out", uid: null });
  });
});
