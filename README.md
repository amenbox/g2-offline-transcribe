# G2 オフライン文字起こし

Even Realities G2 用の「文字起こし専用」Android アプリ。

公式 Conversate と違い：

- **オフラインで動く**（標準エンジンは Pixel 内蔵の日本語オンデバイス音声認識）
- **Hub を経由しない**（独立アプリ、起動即接続）
- **機能は文字起こしだけ**（AI 連携・写真・メニュー等は無し）

主に聴覚障害者のオフライン環境会話補助用途を想定。

> **個人の趣味プロジェクトです。** 本業エンジニアではない作者が AI 支援（Claude Code）で書いた、いわゆる「バイブコーディング」の産物です。バグ・予期しない挙動・端末によっては動かない等が十分あり得ます。無保証・自己責任で使ってください。

## 動作環境

- Android 12（API 31）以上
- G2 グラス本体
- 日本語のオンデバイス音声認識モデルが入った端末（Pixel 系で確認済み）

> 標準エンジンは `SpeechRecognizer.createOnDeviceSpeechRecognizer` を使う完全オフライン構成です。日本語のオンデバイスモデルが端末に入っていない場合、ネット経由 fallback はせず認識が動きません。その場合は YYAPIs を使ってください。

## インストール

ビルド済みの APK を [Releases](../../releases) からダウンロードしてください。

スマホ単体で入れる場合：

1. スマホのブラウザで Releases ページを開いて `app-debug.apk` をダウンロード
2. Android の設定でブラウザ（またはファイルマネージャ）に「不明なアプリのインストール」を許可
3. ダウンロードした APK をタップしてインストール

PC 経由なら `adb install app-debug.apk` でも入ります。

> debug 署名でビルドされた APK です。Google Play 公開や正式配布用ではありません。個人用途で使う範囲ならこのまま動きます。

## 使い方

1. アプリを起動。権限（Bluetooth / マイク / 通知）を許可。
2. 自動で G2 を探して接続し、字幕表示が始まります。
3. G2 のテンプルをシングルタップで現在のセッションをクリア。
4. アプリ右上の「終了」で完全終了。

## 公式アプリとの併用に関する注意

G2 の BLE 接続は同時に 1 つのアプリしか保持できません。Even Realities 公式 Hub や MentraOS が G2 に繋がっている状態だと、本アプリは G2 に接続できません（逆も同様）。

両方を入れて状況に応じて使い分けたい場合、切り替えのたびに手動で次の操作が必要です：

1. 使うのをやめる側のアプリを Android 設定 → アプリ → **強制停止**
2. Bluetooth を一度 **OFF → ON**
3. 使う側のアプリを起動

これで後から起動したアプリが BLE を握れる状態になります。
筆者の運用例：オフラインや手早い字幕が要るときは本アプリ、AI 連携などを使いたいときは公式 Hub、という使い分けです。

## 自分でビルドする場合

```bash
git clone <repo>
cd g2dev
# Android Studio で開く、または
./gradlew assembleDebug
```

`local.properties` には `sdk.dir` のみあれば足ります（API キーは不要、後述）。
出来上がる APK は `app/build/outputs/apk/debug/app-debug.apk`。

## 音声認識エンジン

設定画面から切り替え：

| エンジン | ネット | 精度 | 備考 |
|---|---|---|---|
| 標準（Pixel オフライン） | 不要 | 端末依存 | 既定。完全オフライン |
| YYAPIs | 必要 | 高い | API キーが必要（下記） |

### YYAPIs を使う場合

YYAPIs は外部のクラウド音声認識サービスです。利用するには各自で API キーを取得して設定画面の「YYAPIs API キー」欄に貼り付けてください。

API キーはアプリの SharedPreferences に保存されます。リポジトリ／APK にはキーは含まれません。

## 設計メモ

- 文字起こし以外の機能は持たない方針
- G2 内蔵マイクの LC3 音声を BLE 経由で受け取り、デコード後に STT へ流す
- BLE 層は [Mentra Bluetooth SDK](https://github.com/Mentra-Community/MentraOS) を利用

## ライセンス

MIT License — [LICENSE](LICENSE) を参照。

### 利用しているサードパーティ

- [Mentra Bluetooth SDK](https://github.com/Mentra-Community/MentraOS)（MIT License, © Mentra Community）
- AndroidX / Kotlin / gRPC / OkHttp / Gson 他、各々のオープンソースライセンス
