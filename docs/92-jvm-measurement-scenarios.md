# 92. JVM 測定シナリオ — 性能書二冊をこのシステムに

**日本語** | [한국어](92-jvm-measurement-scenarios.ko.md)

> 📝 **状態（2026-11-17）— 準備、測定前。** 性能書二冊の章別測定シナリオを、このシステム（バックエンド 13 個 · k3s · JDK 17 JRE イメージ）のサービス · ツール · コマンド · 判定に移した。負荷なしですぐ見る事実表 `ops/60-jvm-facts.sh`（§2）を足し、残りは既存の `ops/53` · `ops/58` · Prometheus で測る（§3 · §4）。**結果はまだ一つもない** — §5 の表は空である。
>
> ⚠️ **正直な告知**: この文書を書くにあたって測定はしていない。元になった二冊の「測定シナリオ」文書（別リポジトリ — 名前と番号だけで指す）も測定結果ではなく**計画**である。§2 の「予想」はマニフェストの上限と JDK の規則で計算した値で、1 コア · 768Mi → Serial · 384MB と 1Gi → 512MB だけが docs/91 §4-3 で同じ条件で確認されている。コードについての断定は grep で確かめたものだけを書き、見つけられなかったものは「確認できず」と書いた。判定は絶対値ではなく**方向 · 比率**で行う（docs/19 §0 · docs/83 §1 — 実習クラスターの数値はコアを分け合った値である）。

## 0. 要約

**なぜ**: docs/91 §4-3 が GC 測定の配線（天井表の GC 列 · 警報 · ダッシュボード）とロードマップ ①〜④ を立てた。そのロードマップは「GC を替えてみる」一本だが、二冊のシナリオにはその手前（JVM が何を選んだか）と横（JIT · スレッド · コネクション · ヒープ外メモリー · Kafka のリバランス）がまだある。この文書はそのシナリオを一つずつこのシステムに当て、**何をどのコマンドで測り、何で判定するか**を決める。docs/91 と重なる配線 · 指標の説明は繰り返さず、指すだけにする。

| 層 | 何を | ツール | この文書の行 | 状態 |
|---|---|---|---|---|
| ① 負荷なしですぐ | JVM がコンテナー上限を見て選んだもの — GC · ヒープ · スレッド · ForkJoin 並列度 · コードキャッシュ | `ops/60-jvm-facts.sh`（新）· Prometheus 一回 | F-1 〜 F-6（§2） | 6 — すべてすぐ可能 |
| ② 負荷中 | 天井の階段の遅延 · CPU か GC か · CPU の使い道 · 割り当て · 待ち · メモリー · リバランス | `ops/53 ceiling`（GC 列）· `ops/58` · `vmstat` · PromQL | L-1 〜 L-12（§3） | 12 — すべてすぐ可能（二つはツールの動作確認が要る） |
| ③ 比較実験 | 変数を一つだけ替えて同じ負荷で — GC · ヒープ · CPU 上限 · 暖機 · レプリカ · プール · 起動 | `ops/53`（`replicas` · `pool` · `jvm` · `STAGES`）· 新ツール | X-1 〜 X-14（§4） | 14 — すぐ可能 8 · 新ツールが必要 6 |
| 該当なし | このシステムに対象がない、またはすでに反映済み | — | N-1 〜 N-12（§4-4） | 書籍シナリオ 29 個 |

- 書籍の表記: **JP** = Scott Oaks *Java Performance* 第 2 版の測定シナリオ（S2-1 … S11-3、30 個）、**OC** = Evans · Gough *Optimizing Cloud Native Java* 第 2 版の測定シナリオ（1-1 … 15-3 · A-1 … B-3、48 個）。78 個すべてが下のどれか一行（または該当なし）に入っている — 索引は §4-5。
- 順序: ① → ② → ③。① が想定と違えば（例: 思っていたのと違う GC）② · ③ の解釈がすべて変わる。

---

## 1. 実習クラスターの準備

測定は本番ではなく実習クラスター（k3s — サーバー一台 · エージェント二台以上）で行う。始める前にノードがすべて Ready で、データ Pod · アプリ Pod が Running か、一時停止から起こした VM なら時計が合っているか（`timedatectl`）を見る。終わったら測定用の設定を必ず戻す: `./ops/53-load-k6.sh limits default` · `pool default` · `replicas default` · `jvm default`。

---

## 2. ① 負荷なし — 事実表（`ops/60-jvm-facts.sh`）

```bash
./ops/60-jvm-facts.sh                                  # バックエンド 13 個 — 結果 ~/.ecommerce-msa/jvm-facts/<UTC 時刻>/jvm-facts.md · raw/<サービス>.txt
./ops/60-jvm-facts.sh catalog-service gateway-server   # 選んだサービスだけ
ALLOW_PROD=1 ./ops/60-jvm-facts.sh catalog-service     # 本番 — 空いている時間にサービス一つずつ（下記）
```

**何をするか**: サービスごとに最も長く動いている Running Pod 一つの中で、そのコンテナーの上限 · イメージ · 環境変数のまま**二つ目の JVM** を `-XX:+PrintFlagsFinal -XshowSettings:system -version` で起動する。JRE イメージなので `jcmd` がなく、動いている JVM にはアタッチできないからである — 入力が同じならエルゴノミクスも同じ。エントリーポイント（`ops/Dockerfile.runtime`）と同じ `-XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE}` と `JAVA_OPTS` を渡すが、ファイルに書く · アタッチするオプション（`-Xlog` · JFR · エージェント · ダンプのパス）は外す（動いている JVM の gc.log を上書きしないように）。

**表の列 · 読み方**

| 列 | 意味 | 見るところ |
|---|---|---|
| 上限 CPU / メモリー | マニフェストの `limits` | 下の予想と同じか |
| 見えた CPU · 見えたメモリー · cgroup | `-XshowSettings:system` の Effective CPU Count · Memory Limit · Provider | 上限がそのまま JVM に見えているか（cgroup v2） |
| GC | `Use*GC` のうち true のもの | **見えた CPU < 2 なら Serial** — 停止中は一つのスレッドが全部片付ける |
| 最大ヒープ · 初期ヒープ | MaxHeapSize · InitialHeapSize | メモリー上限 × `MaxRAMPercentage` |
| GC スレッド 並列/並行 · JIT スレッド · コードキャッシュ | ParallelGCThreads/ConcGCThreads · CICompilerCount · ReservedCodeCacheSize | 1 コアではコンパイルスレッドがアプリと同じコアを分け合う（L-8） |
| 圧縮 oop | UseCompressedOops | ヒープが小さいので常に true（N-3） |
| ForkJoin 並列度 | 見えた CPU − 1（最小 1）— JDK の規則で計算 | 1 なら `parallelStream` · `CompletableFuture` の既定実行器は並列ではない（F-3） |
| JDK | 版 | `ops/Dockerfile.runtime` の固定版（B-98）と同じか |

**予想**（マニフェストから計算 — 測定前）:

| サービス | 上限 | GC | 最大ヒープ | 初期ヒープ | JIT スレッド | ForkJoin |
|---|---|---|---|---|---|---|
| catalog-service | 1000m / 1024Mi | Serial | 512M | ~16M（1/64） | 2 | 1 |
| 残り 12 個（gateway · user · cart · order · pay · coupon · chat · notification · delivery · saga-projection · eureka · config） | 1000m / 768Mi | Serial | 384M | ~12M（1/64） | 2 | 1 |

- 384M · 512M · Serial は docs/91 §4-3 が同じ条件で確認した値である。初期ヒープは `InitialRAMPercentage` の既定（1.5625%）で計算した値、JIT スレッド 2 は階層型コンパイルの下限と見た値なので表で確かめる。Serial なら GC スレッドの列は意味を持たない。
- 本番は `ops/28 local`（→ `external` → `prod` → ベース）なので同じ上限である（`external` は CPU の**要求**だけを 100m に下げる）。`k8s-overlays/oci`（2 OCPU 用 — 800m · 512Mi · `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC`）を使えば最大ヒープは ~307M になる。
- 上限のない Pod なら JVM はノード（VM）の vCPU を見る — すると G1 になる。表の「見えた CPU」が 1 でなければ、まずその Pod の上限を見る。

**本番で動かすとき**: 二つ目の JVM は `-version` だけで終わるが（ヒープは予約だけ、実際に使うメモリーは数十 MB）、768Mi 上限の Pod の中で一瞬いっしょに動く。`ALLOW_PROD=1` のときだけ、空いている時間にサービス一つずつ。出力に秘密はない（`JAVA_TOOL_OPTIONS` はストアのパスだけ — B-83）。

**テスト**: `JvmFactsScriptTest`（5 — 1 コアなら Serial で `-Xlog` のファイルオプションを外す · 4 コアなら G1 · ForkJoin 3 · 本番は明示の許可 · Pod なし · 失敗しても他のサービスは続ける · サービス名の検査）。テスト環境の実際の JVM でプローブを動かす。

### 2-1. ① の行

| 行 | 書籍シナリオ | 何を · どうやって | 判定 | 状態 |
|---|---|---|---|---|
| F-1 | JP S5-1 · S3-1、OC 4-1 · 8-1 · 9-2 | 上限 → GC · ヒープ · 見えた CPU/メモリー — `ops/60` | 上の予想と同じなら終わり。違えばそのサービスの上限 · `JAVA_OPTS` · `JVM_MAX_RAM_PERCENTAGE` を見る | すぐ可能 |
| F-2 | JP S3-1（コンパイラースレッドの表）、OC 6-3（大きさ） | JIT スレッド · コードキャッシュの大きさ — `ops/60` の列 | 記録だけ（L-8 の基準） | すぐ可能 |
| F-3 | JP S9-2、OC 13-2 | ForkJoin 並列度 — `ops/60`。コード: `parallelStream` · `.parallel()` · `CompletableFuture.supplyAsync/runAsync` · `commonPool` の使用 0 件（grep、テスト外）。非同期はすべて自前の実行器（`SearchFilterFacets` · `AsyncMailConfig` · `StompFanoutConfig` など） | 並列度 1 · 使用箇所 0 なら「共通プールはこのシステムのボトルネックではない」。ライブラリー内の使用は確認できず | すぐ可能 |
| F-4 | OC B-2 | 実習 ↔ 本番が同じ決定をするか — 二つのクラスターで `ops/60`（本番は `ALLOW_PROD=1`） | GC · ヒープ · 見えた CPU が同じなら実習の**比率**を本番に持ち込める。アーキテクチャー（実習 amd64 · 本番 arm64）は違う — JIT のコードが違うので絶対値は持ち込まない | すぐ可能 |
| F-5 | OC 3-1 · B-1 | 有効にしているスイッチの点検 — `raw/<サービス>.txt` で出所が `{command line}` の行 | 理由を言えないスイッチがないこと（今明示しているのは `MaxRAMPercentage` · `ErrorFile` · `-CreateCoredumpOnCrash`、oci は `UseSerialGC`） | すぐ可能 |
| F-6 | JP S5-1（指標で突き合わせる）— docs/91 §4-3 ロードマップ ① | 今の GC の種類 · ヒープ · 平常時の GC 負担 — `count by (service, gc) (jvm_gc_pause_seconds_count)` · `max by (service) (jvm_memory_max_bytes{area="heap"})` · `max by (service) (jvm_gc_overhead)` | `gc` が `Copy` · `MarkSweepCompact` なら Serial — F-1 と同じであること。本番の値は docs/91 §4-3 に書く — 数日分の平均 · 最大は `PROM=http://localhost:9090 ./ops/61-gc-baseline.sh`（読むだけ、既定 7 日） | すぐ可能 |

---

## 3. ② 負荷中 — `ops/53 ceiling` · `ops/58` · 指標

共通の準備（docs/83 §3 · docs/91 §4）: `./ops/53-load-k6.sh smoke` → `limits load` → 測定 → `limits default`。サーバー側の列は `PROM` があって初めて出る（§1）。階段 · 判定 · GC 列は docs/91 §4 · §4-3 のとおり。

```bash
./ops/53-load-k6.sh limits load
PROM=$PROM ./ops/53-load-k6.sh ceiling browse                    # 結果 load-results/ceiling-browse-<時刻>/ceiling.md
PROM=$PROM CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order
PROM=$PROM WHEN=rps:gateway-server:900 ./ops/58-cpu-profile.sh    # 別の窓 — その負荷に届いたら catalog · ゲートウェイのサンプル
./ops/53-load-k6.sh limits default
```

| 行 | 書籍シナリオ | 何を · どうやって（このシステム） | 判定 | 状態 |
|---|---|---|---|---|
| L-1 | OC 1-1、JP S2-3 | **肘 · 平均と裾** — `ceiling browse` の階段表（リクエスト/秒 · p99 · 5xx）とサービス別の GC 停止の最大 | p99 が折れる階段 = 天井（docs/91 §4）。折れた階段の p99 とその階段の GC 停止の最大が同じ桁なら、裾は GC の分（Serial の停止はそのまま遅延に足される） | すぐ可能 |
| L-2 | OC 7-3 · 10-3（窓に注意）— docs/91 ロードマップ ② | **CPU か GC かホストか** — ceiling.md の CPU · GC 負担 · GC 後ヒープの列 + 同じ階段でノード内の `vmstat 5` | CPU ≥ 0.8 かつ GC 負担 < 0.05 → リクエストあたりの仕事（L-3）。GC 負担 ≥ `CEILING_GC_OVERHEAD`（0.10）→ GC（X-2 · X-4）。GC 後ヒープ ≥ 90% → ヒープ不足（X-4）。`vmstat` の `st`（steal）が大きければホストのコアが足りない — その階段の結果はサーバーの天井ではない。`sy` が高ければ GC ではない（GC はユーザー空間）。GC 負担は 5 分窓なので、境目の階段は `CEILING_HOLD=5m` | すぐ可能 |
| L-3 | JP S3-3、OC 12-1 | **CPU をどこで使うか** — `ops/58`（async-profiler `itimer` — セーフポイントの偏りが少ない側）。まとまり · flat · inclusive | docs/83 の最後のプロファイル（catalog: JSON 17.6% · Tomcat 17.5% · Spring 16.2% · TLS 13.1%）と同じ段階で比べる — まとまりの順位が変われば、それが結果。ツール同士の比較（JP S3-3）はしない: 同じツールで前後だけを見る | すぐ可能 |
| L-4 | OC 1-2、JP S10-3（割り当て側） | **割り当てはどこで** — ceiling の Pod あたり割り当て MB/s 列 + `PROM=$PROM EVENT=alloc ./ops/58-cpu-profile.sh catalog-service gateway-server` | 割り当て MB/s が高いサービスの alloc サンプル上位が JSON · キャッシュ値の展開なら、docs/83 の CPU プロファイルと同じ場所。⚠️ `EVENT` は async-profiler のイベント名をそのまま渡す（検査は英小文字だけ）— `alloc` で動かしたことはなく、まとまりの表は CPU 基準で作ったものなので、**まず一度動かして表が出るか確認する** | すぐ可能（確認が要る） |
| L-5 | JP S3-2 | **ロック · モニター待ち** — JFR の代わりに `WHEN=now EVENT=lock ./ops/58-cpu-profile.sh <サービス>`（`jcmd` がなく、実行中に JFR を開始できない） | 上位の待ちが Hikari プール · ログの appender · 自前コードの `synchronized`（テスト外 15 行、grep）のどれか。⚠️ L-4 と同じく `lock` イベントで動かしたことはない | すぐ可能（確認が要る） |
| L-6 | JP S10-1 | **ワーカー数 = 同時にブロックしたリクエスト数** — `tomcat_threads_busy_threads` ÷ `tomcat_threads_config_max_threads`（既定 200、oci 50）· `hikaricp_connections_pending` · `http_server_requests_active_seconds_gcount`（docs/83 §4） | プール（Pod あたり 5、catalog はデータソース二つ）が先に埋まりスレッドが余っていれば、詰まりは DB コネクション（→ X-8）。両方埋まればスレッドの飽和。ゲートウェイ（WebFlux）には Tomcat の指標がない | すぐ可能 |
| L-7 | JP S10-2 | **サービス間 HTTP 接続の再利用** — Feign（JDK `HttpURLConnection`）の待機接続 64（`HttpKeepAlive`）· 応答を最後まで読む（`FeignResponseBody`）。`ceiling order` 中の `WHEN=now ./ops/58-cpu-profile.sh cart-service order-service` の TLS のまとまり | docs/83 2026-10-18: 直す前は cart の CPU の 23% が新しい TLS ハンドシェイク、直した後は 50 回の呼び出しで接続 1。TLS のまとまりがまた大きくなれば接続が捨てられている | すぐ可能 |
| L-8 | OC 6-3、JP S4-1（一部） | **JIT · コードキャッシュ** — `jvm_memory_used_bytes{area="nonheap", id=~"CodeHeap.*"}` · docs/84 §4 の C2 CPU 比率のループ（`kill -3` ダンプの `C2 CompilerThread0` の cpu/elapsed） | コードキャッシュの使用が F-2 の大きさに届かないこと。C2 の比率は正常で 0.2 〜 0.5%— 数十 % ならコンパイルの停滞（2026-10-20 user-service の 10 時間、docs/83 §2.8）。1 コアの Pod ではコンパイルがリクエストと同じコアを使う | すぐ可能 |
| L-9 | JP S8-1 · S8-2、OC 8-2（観察） | **ヒープ外メモリー · 上限までの余裕** — `kubectl -n ecommerce top pods` のメモリー vs `sum by (pod) (jvm_memory_committed_bytes)` · `jvm_buffer_memory_used_bytes{id="direct"}` | （コンテナーのメモリー − JVM の committed）がスレッドスタック · メタスペース以外のネイティブの分。768Mi 上限で残りが減っていくサービスが X-4 の対象。NMT は `jcmd` がなく使えない。ゲートウェイ（Netty）のダイレクトメモリーが JDK のバッファー指標に全部載るかは確認できず | すぐ可能 |
| L-10 | JP S7-1 | **リークの傾向** — 長時間実行（`mixed` 8 時間、docs/83）での `jvm_memory_usage_after_gc{area="heap",pool="long-lived"}` の傾き · 警報 `JvmHeapAfterGcHigh` | GC 後ヒープが時間とともに上がり続けるならリークの疑い。ヒストグラム二枚の差（JP S7-1 の方法）は `jmap` · `jcmd` がなくできない — 疑わしければ X-4 のように新ツール | すぐ可能 |
| L-11 | OC 14-1 | **リバランス = 分散 STW** — `order` の固定負荷（`STAGES`）中にコンシューマーのサービス一つを `kubectl -n ecommerce rollout restart deploy/notification-service`、`max by (service, topic) (kafka_consumer_fetch_manager_records_lag_max)` | 今の割り当て戦略は `RebalanceSettings` の第 1 段階（`kafka.rebalance.protocol` の既定 `eager` — yml に設定なし、`[cooperative-sticky, range]` を知っているがグループは eager）: 再起動の間そのグループの全パーティションの遅延がそろって上がれば eager のとおり。第 2 段階（cooperative）との比較は設定を変える必要があり新ツール（§6） | すぐ可能 |
| L-12 | OC 4-2 | **割り当てのバースト · 早期昇格** — `spike`（平常 → 20 秒で最高）の間の `rate(jvm_gc_memory_promoted_bytes_total[1m])` · `jvm_gc_pause_seconds_count{action="end of major GC"}` | 急増の直後に昇格バイトとフル GC の数がいっしょに跳ねれば早期昇格 — 若い世代は 384M ヒープの一部なので Serial ではそのまま停止。⚠️ 昇格の指標は Micrometer の `JvmGcMetrics` が出すと理解しているが、このリポジトリのルール · ダッシュボードは読んでいない — 出ているかをまず確認 | すぐ可能（確認が要る） |

---

## 4. ③ 比較実験 — 変数は一つだけ

### 4-1. 共通の規則

- **負荷**: 天井（§5 の L-1）の 80% で固定 — `RATE=<r> STAGES="1m:<r>,10m:<r>,30s:0" ./ops/53-load-k6.sh browse`（RATE は最も高い段階と同じに — docs/83 §3）。暖機は `smoke` と 1 分の段階。
- **繰り返し**: 基準 · 対象を**交互に** 5 回以上（JP S2-1 · OC 2-1）。片方をまとめて測ると、ホストの状態が変わったとき片方だけが損をする。
- **判定**: 前もって決める — p99 · Pod の CPU あたりのスループット（リクエスト/秒 ÷ 使ったコア）· GC 負担 · GC 停止の最大。Welch の t 検定で p < 0.1 かつ差が 10% 以上のとき「違う」。そうでなければ「結論なし」（同じ、ではない）。計算は標準ライブラリーの python 数行（JP S2-1 のもの）で足りるがリポジトリにない → 新ツール（§6）。
- **測定用の例外**: 設定を変えるのは `ops/53` のモード（`limits` · `pool` · `replicas` · `jvm`）だけ — 実習クラスターの判定 · 戻し · デプロイ前の点検（`ops/28 check_load_overrides`）が付いている。**`JAVA_OPTS` · `JVM_MAX_RAM_PERCENTAGE` · CPU 上限を手で `kubectl set env` しない** — `ops/53 jvm`（2026-11-17）でだけ変える: 元の値（JAVA_OPTS · ヒープ比率 · CPU 上限）を Deployment の注釈 `ecommerce.io/jvm-measure` に書いて一回のロールアウトで変え、`jvm default` がその値で戻す。もう一度 `load` すると元の値の上に新しく（重ねない）。残っていれば `ops/28` がデプロイ前に知らせる（設定は `ops/28` だけで、測定用の例外は `ops/53` のモードだけで）。変えた後に JVM が本当にそう選んだかは `ops/60-jvm-facts.sh <サービス>`。

### 4-2. すぐ可能なもの

| 行 | 書籍シナリオ | 対象 | 替える変数 | コマンド | 判定（方向 · 比率） |
|---|---|---|---|---|---|
| X-6 | OC 9-1 · 6-1、JP S4-1 | catalog · gateway | Pod が暖まっているか（新しい Pod vs 古い Pod） | 固定負荷中に `kubectl -n ecommerce rollout restart deploy/catalog-service` → Pod 別の `histogram_quantile(0.99, sum by (pod, le) (rate(http_server_requests_seconds_bucket{service="catalog-service"}[1m])))` · ゲートウェイの 5xx | 新しい Pod の p99 ÷ 暖まった Pod の p99 を 1 · 3 · 5 分で。1.2 を下回るまでの時間が「暖機時間」。readiness は health しか見ない（ゲートウェイの `RateLimiterWarmup` 以外に暖機なし）— その間の 5xx が大きければ readiness 前の暖機を判断する根拠 |
| X-7 | JP S9-1（アムダール） | catalog · gateway | レプリカ N | `./ops/53-load-k6.sh replicas load <N> catalog-service gateway-server` → `ceiling browse` → `replicas default` | 倍数 k = N/N₀ のときの天井の比 S。直列の割合 F ≈ (k/S − 1)/(k − 1)。docs/83では catalog 3 → 4 で +13% — ホストのコアを分け合っただけだった。エージェントが二つの今は L-2 の `st` も書く |
| X-8 | JP S10-1 の延長 | catalog | DB プール 5 → 10 | `./ops/53-load-k6.sh pool load 10` → `ceiling browse` → `pool default`（DB 接続の予算はスクリプトが先に数える） | `hikaricp_connections_pending` が 0 になり天井が上がればプールがボトルネック。CPU が先に埋まれば（2026-10-18 のように）プールではない |
| X-10 | OC B-3 | catalog | データの形 · 流入の形 | `SKEW=0.5` · `LONG_NAMES=1` で `ops/44` を入れ直す（docs/83 §2.5）/ `spike` | 同じ商品数の既定の形の回と比べる。先に折れる場所（件数カウント · フィルターメニュー · 深いページ · 応答の大きさ）が変わるか |
| X-2 | JP S5-2、OC 5-1 — docs/91 ロードマップ ③ | gateway · catalog | 1 コアでの GC: Serial（今）· Parallel · G1 | `./ops/53-load-k6.sh jvm load gateway-server catalog-service gc=parallel`（次の回は `gc=g1`、基準は `gc=serial`）→ §4-1 の固定負荷 → 終わったら `jvm default` | 書籍: 1 コア · CPU を使い切る仕事は Serial が最速で、G1 はバックグラウンドスレッドが計算を奪う。応答型（CPU 50%）では G1 の p99 が低かった。ここでは 80% 負荷なのでスループットは Serial ≥ 他、p99 は未定 — それを測る。ヒープは固定（同じ `MaxRAMPercentage`） |
| X-3 | JP S6-3 · S6-2、OC 5-2 | gateway · catalog | CPU 上限 1 → 2 コア（G1 になる）、そして 2 コアで ZGC · Shenandoah | `jvm load gateway-server catalog-service cpu=2`（JVM が自分で G1）· `cpu=2 gc=z` · `cpu=2 gc=shenandoah`、比較対象の「1 コア二つ」は `replicas load 2`。上限だけを変えるので要求の予算はそのまま — ノードの実際の CPU は `kubectl top nodes`。G1 の巨大オブジェクトは `gclog=1` のログで | 問いは「1 コアの Pod 二つ vs 2 コアの Pod 一つ」— 同じ総コアで天井 · p99 · 停止の最大。G1 なら `-Xlog:gc+humongous` で巨大オブジェクト（リージョン 1MB なら 512KB 以上）· IHOP の並行モード失敗もいっしょに見る |
| X-4 | OC 8-2、JP S7-2 | catalog（1Gi）· 768Mi のサービス一つ | ヒープ比率 50 · 60 · 70% | `jvm load catalog-service heap=60` · `heap=70`（65 以上ならスクリプトが警告）— メモリ上限はそのまま | GC 負担 · GC 後ヒープが下がり `OOMKilled`（`kubectl get pods` の lastState）が 0 なら上げる根拠。イメージのコメントは「65% 以上なら 768Mi のサービスはコンテナー上限に先に届く」— 測定はない、これで確かめる。データは 100 万（2026-10-19 に 25% で catalog がヒープ不足を繰り返した条件、docs/83 §5）。ヒープの OOM はそのスレッドだけを終わらせ JVM は生きる（JP S7-2）— 今は `ExitOnOutOfMemoryError` がなく、liveness が殺した |
| X-5 | JP S5-3、OC 10-3 · 5-3 | gateway（GC 負担が跳ねた Pod、docs/83） | GC ログを有効に | `jvm load gateway-server gclog=1` — `-Xlog:gc*,safepoint:file=/var/log/jvm/gc.log:…`（一つのファイル、3 個 × 10MB でローテーション）→ `kubectl -n ecommerce cp <Pod>:/var/log/jvm/gc.log ./gc.log`（emptyDir — Pod を消す前に） | 指標（5 分窓 · 最大）とログの停止イベントを同じ時刻に並べ、窓が隠した偏り（毎分 1 件 vs 最後の 0.1 秒に 60 件）があるか。セーフポイントへの到達時間が GC 停止より長ければ GC ではなく止まるのが遅いスレッド |

### 4-3. 新ツールが必要なもの

| 行 | 書籍シナリオ | 対象 | 替える変数 | 今ないもの | 判定（方向 · 比率） |
|---|---|---|---|---|---|
| X-1 | JP S2-1、OC 2-1 | すべての比較 | — （判定の方法） | 回のまとまりの比較 — `ops/53 report` が複数回の p99 · スループットを集めて Welch の p を出すもの | §4-1 の規則 |
| X-9 | OC 6-2 · 8-3 | すべてのサービス | 起動: CDS/AppCDS · ベースイメージ | イメージを替えてビルド（`ops/Dockerfile.runtime` の一行 — B-98） | 基準線は今も読める: `application_ready_time_seconds`（サービス別）。startupProbe の予算 5 分 · HPA の遅れ（docs/83 spike）と比べて起動が縮む比率 |
| X-11 | OC 2-3 | browse · order | 応答の種類別の遅延 | k6 の要約をステータスコード別に — 今はエンドポイント（`name`）別だけなので、隔壁の速い 503 が全体の p99 を押し下げる | 2xx だけの p99 と全体の p99 を並べる。天井の判定は 5xx ≤ 0.1% で切るので、天井の階段では差が小さいはず |
| X-12 | JP S10-3 | user-service · coupon | `ObjectMapper` を呼び出しごとに作る vs 一つ | ログイン · トークン更新の k6 シナリオ（ログインは IP あたり毎秒 1 — `CLIENT_IPS` で分ける） | コードの事実（grep）: `AuthLoginFilter`（ログイン成功 · 失敗の応答ごと）· `RefreshTokenController` · `JwtAuthEntryPoint`（401 ごと）· `RedisOAuth2AuthorizationRequestRepository` · `CouponService`（メールアドレスで発行）が呼び出しごとに `new ObjectMapper()`。変えるかはこの測定の後 — `ops/58` でその経路の Jackson 初期化の分が見えたら |
| X-13 | JP S11-1 | catalog | JDBC バッチ（`hibernate.jdbc.batch_size` — 設定なし、grep） | アプリ経路の一括書き込みの測定（商品一括登録 `ProductImportService`）— `ops/44` は生成器で入れるのでアプリ経路ではない | 同じ件数でバッチの有無 · コミットの境界を分けて時間の比。書籍: 利得の大半は呼び出し回数を減らしたこと、コミット回数の差は雑音 |
| X-14 | OC 13-1 · 3-2、JP S6-3（世代別 ZGC）— docs/91 ロードマップ ④ | 全体（仮想スレッドは order の経路 — catalog · order · cart） | JDK 21 · 仮想スレッド | jdk21-build ブランチのイメージを手で（`ops/19`、CI はデフォルトブランチだけ発行）— 2026-11-19 そのブランチで仮想スレッドを有効にした（`VIRTUAL_THREADS_ENABLED`、pinning 四か所を修正、そのブランチの docs/75 §5）。比較は三本: このブランチのイメージ（JDK 17）/ JDK 21 `jvm load … vt=0` / `vt=1 pinning=1` | A ↔ B は JDK の版、B ↔ C は仮想スレッド。見るもの: 天井 · p99 · CPU あたりの処理量 · Hikari の待ち（Tomcat の 200 の上限がなくなると増えうる）· GC 後ヒープ · ログの `monitors:`（pinning）。世代別 ZGC（2 コア以上）、`ops/58` の動的アタッチの警告（JEP 451） |

### 4-4. 該当なし — 理由

| 行 | 書籍シナリオ | 理由 |
|---|---|---|
| N-1 | JP S2-2 · S4-2 · S4-3、OC A-1 · A-2 · A-3 | マイクロベンチマーク · JIT の内部（デッドコード · 脱最適化 · インライン化 · ループカウンター）。このリポジトリに JMH はなく、判断がその単位から出てこない。サービスでは暖機（X-6）と JIT の結果（L-8）だけを見る |
| N-2 | JP S6-1 | `GCTimeRatio` · 停止目標 — Serial はこの目標を使わない。X-2 に Parallel を入れるとき `raw/` の値だけを書く |
| N-3 | JP S7-3 | 圧縮 oop の 32GB 境界 — 最大ヒープは 384 〜 512M |
| N-4 | JP S8-3 | `Inflater.end()` の書き忘れ — コードに `Inflater` · `Deflater` · GZIP ストリームの直接の使用なし（grep、テスト外） |
| N-5 | JP S9-3 | `ThreadPoolExecutor` のキューの規則 — コードで済む構造的な事実（docs/19 §0）。例: メール実行器（`AsyncMailConfig`）は core 2 · max 5 · キュー 100 なので、三つ目のスレッドはキュー 100 が埋まってから生まれ、その後は `CallerRunsPolicy`。フィルターメニューの実行器は固定サイズ · キュー 64 · `AbortPolicy` |
| N-6 | JP S11-2 · S11-3 | fetch size · L2 キャッシュ — `setFetchSize` · `defaultRowFetchSize` の設定なし（PgJDBC の既定）、ストリーミング読み取り（`Stream<…>` を返すリポジトリ）なし、Hibernate の二次キャッシュは使っていない（キャッシュは Redis · Pod のメモリー — docs/83） |
| N-7 | OC 7-1 · 7-2（JP S9-1 の偽共有の半分） | キャッシュライン · 偽共有 — ハードウェアの微視的な実験。リクエスト経路にスレッド同士で激しく更新する変数がない（`AtomicLong` などはカウンター · フラグが数か所） |
| N-8 | OC 4-3 · 15-1 · 15-2 · 15-3 | JDK 25 の機能（コンパクトヘッダー · AOT キャッシュ · FFM · ScopedValue）— イメージは JDK 17 |
| N-9 | OC 2-2 · 10-1 · 10-2 · 11-2 | すでに反映した測り方: 到着率（開いたモデル）+ 捨てた反復 1% の判定（docs/83 §2 · docs/91 §4）、ヒストグラムのバケットで SLO（百分位を平均しない — docs/91 §2）、テールサンプリング · `TRACING_SAMPLING_PROBABILITY=1.0`（B-72 · B-102）、ゲートウェイのタイマーの path タグ無効 · ヒストグラム無効（docs/91 §2-7） |
| N-10 | OC 3-3 | ディストリビューション間の差 — JRE イメージは版 + ダイジェスト一つに固定（B-98） |
| N-11 | OC 11-1 · 12-2 · 12-3 | Java エージェントなし（Micrometer Tracing + OTLP — 計装のコストは `ops/58` の「観測」のまとまりで見る）、JFR の常時記録なし、ヒープダンプ · ヒストグラムは `jcmd` · `jmap` がなく、ヒープに個人情報があるので本番では禁止（イメージはコアダンプも無効にしている） |
| N-12 | OC 13-3 · 14-2 · 14-3 | リクエスト経路に CAS の再試行ループなし、id は TSID を Pod 内で作る（DB の往復なし — 10 モジュール）、下流の障害の伝播はゲームデイの手順書（game-day ブランチ）の範囲 |

### 4-5. 索引 — 書籍シナリオ → この文書の行

| JP 章 | シナリオ → 行 |
|---|---|
| 2 性能テスト | S2-1 → X-1 · S2-2 → N-1 · S2-3 → L-1 |
| 3 ツール | S3-1 → F-1 · F-2 · S3-2 → L-5 · S3-3 → L-3 |
| 4 JIT | S4-1 → X-6（· L-8）· S4-2 → N-1 · S4-3 → N-1 |
| 5 GC 入門 | S5-1 → F-1 · F-6 · S5-2 → X-2 · S5-3 → X-5 |
| 6 GC アルゴリズム | S6-1 → N-2 · S6-2 → X-3 · S6-3 → X-3（· X-14） |
| 7 ヒープ | S7-1 → L-10 · S7-2 → X-4 · S7-3 → N-3 |
| 8 ネイティブメモリー | S8-1 → L-9 · S8-2 → L-9 · S8-3 → N-4 |
| 9 スレッド | S9-1 → X-7（偽共有は N-7）· S9-2 → F-3 · S9-3 → N-5 |
| 10 サーバー | S10-1 → L-6（· X-8）· S10-2 → L-7 · S10-3 → X-12（· L-4） |
| 11 DB | S11-1 → X-13 · S11-2 → N-6 · S11-3 → N-6 |

| OC 章 | シナリオ → 行 |
|---|---|
| 1 · 2 定義 · 方法論 | 1-1 → L-1 · 1-2 → L-4 · 2-1 → X-1 · 2-2 → N-9 · 2-3 → X-11 |
| 3 · 4 JVM · GC | 3-1 → F-5 · 3-2 → X-14 · 3-3 → N-10 · 4-1 → F-1 · 4-2 → L-12 · 4-3 → N-8 |
| 5 · 6 高度な GC · コード実行 | 5-1 → X-2 · 5-2 → X-3 · 5-3 → X-5 · 6-1 → X-6 · 6-2 → X-9 · 6-3 → L-8（· F-2） |
| 7 · 8 ハードウェア · クラウドスタック | 7-1 → N-7 · 7-2 → N-7 · 7-3 → L-2 · 8-1 → F-1 · 8-2 → X-4（· L-9）· 8-3 → X-9 |
| 9 · 10 デプロイ · 観測 | 9-1 → X-6 · 9-2 → F-1 · 10-1 → N-9 · 10-2 → N-9 · 10-3 → X-5（· L-2） |
| 11 · 12 計装 · プロファイル | 11-1 → N-11 · 11-2 → N-9 · 12-1 → L-3 · 12-2 → N-11 · 12-3 → N-11 |
| 13 · 14 並行性 · 分散 | 13-1 → X-14 · 13-2 → F-3 · 13-3 → N-12 · 14-1 → L-11 · 14-2 → N-12 · 14-3 → N-12 |
| 15 · 付録 | 15-1 〜 15-3 → N-8 · A-1 〜 A-3 → N-1 · B-1 → F-5 · B-2 → F-4 · B-3 → X-10 |

数: JP 30 = ① 3 · ② 8 · ③ 10 · 該当なし 9、OC 48 = ① 7 · ② 7 · ③ 14 · 該当なし 20（括弧内はいっしょに見る行 — 数えない）。

---

## 5. 結果の記録（測ったら埋める）

環境欄にはクラスター（実習クラスター · VM の大きさ）· ノード · k6 の場所（ホスト / 負荷 VM）· イメージのコミット · データ量を書く。元のフォルダーは `load-results/…` · `~/.ecommerce-msa/{jvm-facts,profiles}/…`。

### 5-1. ① 事実表（`ops/60`）

| 日付 · コミット · クラスター | サービス | GC | 最大ヒープ | 見えた CPU | JIT スレッド | コードキャッシュ | ForkJoin | 予想どおりか |
|---|---|---|---|---|---|---|---|---|
| — | catalog-service | — | — | — | — | — | — | （予想 Serial · 512M · 1 · 2 · 1） |
| — | gateway-server | — | — | — | — | — | — | （予想 Serial · 384M · 1 · 2 · 1） |
| — | 残り 11 個 | — | — | — | — | — | — | （予想 上と同じ） |
| — | 本番（F-4） | — | — | — | — | — | — | |

本番の平常時の GC（F-6 — GC 負担 · 停止 · 割り当て · GC 後ヒープ、数日分）は `ops/61-gc-baseline.sh` の表を docs/91 §4-3「本番の基準値」に — ここの GC 列と同じ種類かを見る。

### 5-2. ② 負荷中

| 行 | 日付 · コミット · 環境 | シナリオ · 階段 | 結果 | 判定 | 元 |
|---|---|---|---|---|---|
| L-1 | — | ceiling browse | — | — | — |
| L-2 | — | 〃 | — | — | — |
| L-3 | — | ops/58 | — | — | — |
| L-4 | — | ops/58 `EVENT=alloc` | — | — | — |
| L-5 | — | ops/58 `EVENT=lock` | — | — | — |
| L-6 | — | ceiling browse · order | — | — | — |
| L-7 | — | ceiling order · ops/58 | — | — | — |
| L-8 | — | mixed 8 時間 | — | — | — |
| L-9 | — | 〃 | — | — | — |
| L-10 | — | 〃 | — | — | — |
| L-11 | — | order 固定 + 再起動 | — | — | — |
| L-12 | — | spike | — | — | — |

天井そのもの（サービス別の Pod あたりリクエスト/秒）は docs/91 §4-1 に書く — ここには同じ回の JVM 側の解釈だけを書く。

### 5-3. ③ 比較実験

| 行 | 日付 · コミット · 環境 | 基準 → 対象 | 回数（基準/対象） | p99 | CPU あたりスループット | GC 負担 · 停止の最大 | p（Welch） | 判定 |
|---|---|---|---|---|---|---|---|---|
| X-6 | — | 暖まった → 新しい Pod | — | — | — | — | — | — |
| X-7 | — | N₀ → N | — | — | — | — | — | — |
| X-8 | — | プール 5 → 10 | — | — | — | — | — | — |
| X-10 | — | 既定 → SKEW/LONG_NAMES | — | — | — | — | — | — |
| X-2 | — | Serial → Parallel · G1 | — | — | — | — | — | — |
| X-3 | — | 1 コア二つ → 2 コア一つ（G1 · Z · Shenandoah） | — | — | — | — | — | — |
| X-4 | — | ヒープ 50 → 60 · 70% | — | — | — | — | — | — |
| X-5 | — | GC ログ（指標の窓 vs イベント） | — | — | — | — | — | — |
| X-9 · X-11 〜 X-14 | — | （新ツールの後） | — | — | — | — | — | — |

---

## 6. 残り

1. **実習クラスターを起こす**（§1）— agent1 の `K3S_URL` の確認から。観測スタック（`PROM`）がどこにあるか、k6 をどこで動かすかを決める。なければ ② のサーバー側の列が空になる。
2. **① → ② の実行** — `ops/60` で §5-1、`ceiling browse` · `ceiling order` · `ops/58` で §5-2 と docs/91 §4-1。`EVENT=alloc` · `lock` は、まず一度表が出るかから（L-4 · L-5）。
3. **新ツール**（③ の 6 行が待っている）:
   1. ~~`ops/53` の JVM 測定モード~~ → ✅ 2026-11-17 `ops/53 jvm load|default|status`（本番拒否 · 元の値は注釈 · 一回のロールアウト · `ops/28` の警告 · テスト `LoadK6ScriptTest` +3 · `DeployLoadOverrideScriptTest` +1、変異の確認 5 種）。X-2 〜 X-5 は §4-2 へ移った。L-11 の cooperative 比較（`KAFKA_REBALANCE_PROTOCOL`）はこのモードの外なので残る。
   2. 回のまとまりの比較（Welch）— X-1。
   3. k6: ステータスコード別の遅延（X-11）、ログイン · トークン更新のシナリオ（X-12）。
   4. 一括書き込みの測定（X-13）、イメージの比較（X-9 · X-14）。
4. **判断項目**（測定の後）: `ExitOnOutOfMemoryError` を置くか（X-4）、呼び出しごとに作る `ObjectMapper`（X-12）、readiness 前の暖機（X-6）、1 コア二つ vs 2 コア一つ（X-3）。
5. 本番の GC の種類 · 平常時の GC 負担の記録（F-6）は docs/91 §4-3 に。
