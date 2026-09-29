# 46. 多重ログイン判定 — 名前が Redis だった HashMap

**日本語** | [한국어](46-multiple-login-detection.ko.md)

自分一人で使っているアカウントでログインしたところ、こんな案内が表示された:

> User attempted to log in multiply, logging out from previous logged in state

他の場所でログインしたことはない。たった今パスワードを再設定して、初めてログインしたところである。

---

## 1. 何がその判定をしているのか

`AuthLoginFilter.successfulAuthentication` はログインに成功した後、こう問い合わせる。

```java
String previousSessionId = sessionManager.getSession(userDetails.getEmail());
if (previousSessionId != null) {
    // 「すでにログインしている」→ 以前のセッションにログアウトイベントを送り、警告文を付ける
}
```

その記憶を持っているのが `RedisSessionManager` である。

## 2. ところが Redis ではなかった

```java
// 修正前
public class RedisSessionManager {
    private static RedisSessionManager instatnce;
    private Map<String, String> sessions;

    private RedisSessionManager() { sessions = new HashMap<>(); }

    public static synchronized RedisSessionManager getInstance() { ... }

    public synchronized void addSession(String userId, String sessionId) {
        log.info("addSession : {}", sessionId);   // ← セッション id を平文で残す
        sessions.put(userId, sessionId);
    }
}
```

**名前だけ Redis で、実際には JVM 内の `HashMap`** だった。`JwtUtils` が
`getInstance()` で静的シングルトンを取得して使っていた。セッション id を `INFO` で出力する行も併せて
削除した — `AuthLoginFilter` の `Login session Id : {}` も同じ値を残していた。

その結果、二つのことが**同時に**誤っていた。user-service は `k8s/40-user-service.yaml` で
`replicas: 2` として起動する。

### 偽陽性 — 一人で使っているのに警告が出る

エントリを消す場所は**明示的なログアウトただ一つ**だった
（`AuthLogoutFilter:123`、ヘッダー `MultiLogin: false` の経路）。そのため、次の場合に記録が
そのまま残る。

| 状況 | 記録は消えるか |
|---|---|
| ログアウトボタン | ✅ 消える |
| ブラウザをそのまま閉じる | ❌ 残る |
| リフレッシュトークン期限切れ | ❌ 残る |
| パスワード再設定後の再ログイン | ❌ 残る |
| Pod 再起動 | マップが丸ごと消える（別の意味での誤動作） |

報告された症状はまさに 4 行目である。残っていた古い記録のせいで、**初めてログインするのに
「すでにログインしている」**になった。

### 偽陰性 — 本来防ぎたかったものを半分しか防げない

マップは Pod ごとに別々である。2 回目のログインが**別の Pod にルーティングされると、その Pod のマップには
何もないため**、判定自体が行われない。レプリカが 2 つならおおよそ半分がそのまま通過する。

つまりこの機能は、**存在しない多重ログインは通知し、存在する多重ログインは半分見逃して**いた。

## 3. 修正方法

`RedisSessionManager` を名前どおり Redis バックエンドの `@Component` に変えた。

```java
private static final String KEY_PREFIX = "login-session:";

public void addSession(String userId, String sessionId) {
    redisTemplate.opsForValue().set(key(userId), sessionId, sessionTtlMs, TimeUnit.MILLISECONDS);
}
```

変わった点は三つ:

1. **レプリカが同じ記憶を見る。** 偽陰性がなくなる。
2. **TTL がある。** `${spring.app.jwtRefreshExpirationMs}` に合わせた — リフレッシュトークンが
   死ねばそのセッションも死んだのだから、記録も一緒に期限切れになるべきである。それより長いと使えないセッションを
   生きているとみなし、短いと生きているセッションを見逃す。
   **削除するコードが漏れても、古い記録が永遠に残ることはない** — 偽陽性に対する構造的な防衛線である。
3. **パスワード変更時に削除する。** `UserServiceImpl.applyNewPassword` が
   `sessionManager.removeSession(user.getEmail())` を呼び出す。パスワードを変えれば既存の
   セッションはどのみち無効である（`RefreshTokenController` が `passwordChangedAt` より前の `iat` を
   拒否する）。記録だけが残って次のログインで警告を出すのを防ぐ。

`JwtUtils` は `getInstance()` の代わりにコンストラクタ注入で受け取る。`AuthLoginFilter` は
`SecurityConfig` から Bean を受け取る（7 番目のパラメータ）。

### この記録は権限ではない

セッションが実際に有効かどうかを決めるのは、**リフレッシュトークン（Redis）と `User.passwordChangedAt`**
である。`login-session:` エントリは「多重ログインのように見えるか」を判断する**根拠にすぎず**、
エントリが残っていてもトークンがなければ、そのセッションはすでに死んでいる。この区別が曖昧になると、
TTL を延ばしたくなる誘惑が生まれる。

## 4. 残っていること

- 文言が英語である（`User attempted to log in multiply, …`）。i18n の対象（docs/45）。
- 以前のセッションを強制ログアウトさせる動作そのものが正しいポリシーかどうかは別の判断である。現在は
  「最後のログインが勝つ」であり、その判定がようやく**正確に**動作するようになった。

## 検証

```
:user-service:test  tests=215 failures=0
```

- `PasswordChangeTest.clearsSessionRecord` — パスワード変更がセッション記録を消すかどうかを固定。
- Pod を増やしても同じ記憶を見るかどうかは、Redis を共有しているため構造的に成り立つ。
