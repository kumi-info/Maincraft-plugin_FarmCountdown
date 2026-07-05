# FarmCountdown

**バージョン: v1.7.0**

s2e-farm-pro の「ゲーム開始カウントダウン秒数」（既定 **10秒**）を、実行中のカウントダウンへのリフレクションで上書きする**実験的**プラグインです。LadderCountdown とは別の単機能プラグインとして分離しています。

---

## なぜ別プラグイン＆この方式なのか

s2e-farm-pro は **難読化された有料プラグイン**で、解析の結果：

- カウントダウン秒数は **設定ファイルにもコマンドにも存在しません**（`TimerSettings` に countdown 項目なし、内部の CountdownTimer も config を読まない）。
- 秒数は **コードにハードコード**され、**ゲーム開始ごとに作り直されます**。

そのため、ファイル書き換えでは変えられず、**「カウントを刻む BukkitRunnable の残り秒数 int を、開始ごとに上書きする」**しか手がありません。本プラグインはそれを行います。

> ⚠️ **注意**: 難読化された他社プラグインの内部を書き換えます。**必ず配信外でテスト**し、ライセンス/利用規約にご注意ください。動作はバージョン依存で、farm-pro の更新で効かなくなる可能性があります。

---

## 使い方（重要：まず field を特定する）

どの int フィールドが「画面に出る残り秒数」かはバージョンにより異なるため、最初に特定します。

1. jar を `plugins/` に置きサーバー再起動。`debug: true`（既定）のまま。
2. **配信外で**農場ゲームを開始。サーバーログ（コンソール）に次のような行が出ます：
   ```
   [debug] ticker XXXX (id) field[0]=.. field[1]=10
   [debug] ticker XXXX (id) field[0]=.. field[1]=9
   ```
   または `/farmcountdown status` をゲーム開始中に実行しても現在値が見えます。
3. **画面のカウントダウン（10,9,8…）と同じ値で減る field の index** を確認。
4. その index を `config.yml` の `remaining-field-index` に設定 → `/farmcountdown reload`。
5. 秒数を設定して有効化：`/farmcountdown 30`（30秒に。自動で ON）。
6. 問題なければ本番へ。

---

## コマンド

```
/farmcountdown <秒> | off | status | reload
```

| コマンド | 動作 |
|---|---|
| `/farmcountdown <秒>` | その秒数に設定して ON（1〜3600）。**実行中のカウントにも即反映** |
| `/farmcountdown off` | 上書きを無効化 |
| `/farmcountdown status` | 現在の設定と、検出中カウントダウンの int フィールド値を表示 |
| `/farmcountdown reload` | config 再読み込み |

- 権限: `farmcountdown.use`（op）。
- `remaining-field-index`（どの int フィールドを上書きするか）と `debug` は **config.yml** で設定（コマンドからは廃止＝オプションを減らしました）。

---

## 仕組み

- 毎 `poll-interval-ticks`（既定1）で、`source-plugin`（s2e-farm-pro）が所有するスケジューラタスクを走査し、内部の **BukkitRunnable で int を `min-int-fields`(既定2) 個以上持つもの**＝カウントダウン ticker を自動検出します（`ticker-class` を指定すればそのクラス名に限定）。
- 残り秒数の現在値を監視し、**「新規 ticker」「値が増えた＝クリア等でリセット」「目標秒数が変わった」とき**に `remaining-field-index` の int へ `seconds` を書き込みます。これにより：
  - クリアでタイマーがリセットされても、毎ラウンド設定秒数へ戻す。
  - **`/farmcountdown <秒>` をカウントダウン中に実行すると、動作中のカウントへ即反映**（次のゲームを待たない）。
  - 通常の減少中（目標と一致）は上書きしないので普通に減る。
- `enabled: false`（既定）の間は**何も書き換えず、debug のログ出力のみ**。安全に field を特定できます。

---

## config.yml（抜粋）

```yaml
source-plugin: s2e-farm-pro
enabled: false              # 特定後に true
seconds: 10                 # 上書きする秒数
remaining-field-index: 1    # 残り秒数の int フィールド index（debug で特定）
ticker-class: ""            # 空=自動検出
min-int-fields: 2
poll-interval-ticks: 1
debug: true
```

---

## インストール / 反映

1. `FarmCountdown_v1.0.0.jar` を `plugins/` に配置。
2. サーバー再起動（または `/reload confirm`）。

ビルドは [minecraft-build-env] のオフライン手順（JDK21 + paper-api、Maven不使用）。

---

## 更新履歴

| バージョン | 変更点 |
|---|---|
| v1.7.0 | コマンド名を `/farmcountdown` に戻した（`/fcd` を廃止）。使い方は `/farmcountdown <秒>`。 |
| v1.6.0 | 「設定値が2回表示される」不具合を修正。出所(manager)書換えで新ラウンドが既に正しい値で生成されるため、**新規/リセット時は実行中tickerを上書きしない**ようにした（戻し上書きによる二重表示を解消）。実行中の上書きは「カウント中の /fcd 変更」と「起動直後の最初の1ラウンド」のみ。 |
| v1.5.0 | **開始/リセット時の一瞬10を抑制**：ticker が参照する「マネージャ」の既定値int（int を1つだけ持つ参照先）を常時 seconds に保つことで、次ラウンドの ticker が最初から seconds で生成されるようにした。サーバー起動後の最初の1回のみ一瞬10が出る可能性あり（以降は出ない）。 |
| v1.4.0 | コマンドを `/fcd <秒\|off\|status\|reload>` に簡略化（`set`/`on`/`field`/`debug` のサブコマンドを廃止、秒数は数値を直接指定）。コマンド名を `fcd` 1つに統一（`farmcountdown` を廃止）。`field`/`debug` は config.yml で設定。 |
| v1.3.0 | 目標秒数の変更を毎tick検知し、**カウントダウン中でも即上書き**するように変更（`/fcd set` が動作中のカウントに即反映）。開始/リセット時の上書きと統合。 |
| v1.2.0 | ticker をクラス名でなく形状（int フィールド数 min〜max、既定ちょうど2個）で検出するように変更（厳密名指定だと難読化名と食い違い検知ゼロ＝カウント不能になる問題を解消）。アリーナ本体タイマー(int 4個)を除外。debug にクラス識別子を表示し `detected-tickers.txt` にフル名を記録。 |
| v1.1.0 | クリア等でタイマーがリセット（値が戻る）したら自動で再上書きするように変更（毎ラウンド設定秒数を維持）。一度きりの上書きから「リセット検知」方式へ。 |
| v1.0.0 | 初版。farm-pro のカウントダウン ticker を自動検出し、残り秒数 int を上書き。debug で field 特定、`/farmcountdown` で操作。 |

---

> 📌 **メンテナンス方針**: 機能を変更してバージョンを上げたときは、本 README の「バージョン」表記・コマンド表・更新履歴も合わせて更新すること。
