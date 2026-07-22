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

> 標準エンジンは `SpeechRecognizer.createOnDeviceSpeechRecognizer` を使う完全オフライン構成です。日本語のオンデバイスモデルが端末に入っていない場合、ネット経由 fallback はせず認識が動きません。その場合は **SenseVoice** エンジン（別途モデルを DL、完全オフライン）を選ぶか、Pixel 10 なら **ML Kit GenAI** エンジンを選んでください。

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
4. 画面下部の「終了」で完全終了。
5. 万一「音声エンジン使用不可」と表示された場合は、画面上部に出る赤い「アプリを再起動」ボタンをタップ。

## 公式アプリとの併用に関する注意

G2 の BLE 接続は同時に 1 つのアプリしか保持できません。Even Realities 公式 Hub や MentraOS が G2 に繋がっている状態だと、本アプリは G2 に接続できません（逆も同様）。

両方を入れて状況に応じて使い分けたい場合、切り替えのたびに手動で次の操作が必要です：

1. 使うのをやめる側のアプリを Android 設定 → アプリ → **強制停止**
2. Bluetooth を一度 **OFF → ON**
3. 使う側のアプリを起動

これで後から起動したアプリが BLE を握れる状態になります。
筆者の運用例：オフラインは本アプリ、オンラインや精度が必要な場面は公式 Hub、という使い分けです。

## 自分でビルドする場合

```bash
git clone <repo>
cd g2dev
# Android Studio で開く、または
./gradlew assembleDebug
```

`local.properties` には `sdk.dir` のみあれば足ります。
出来上がる APK は `app/build/outputs/apk/debug/app-debug.apk`。

SenseVoice エンジンを使うには sherpa-onnx の Android AAR が必要です。リポジトリには含めていないので、以下を実行して手動で配置してください：

```bash
mkdir -p app/libs
curl -L -o app/libs/sherpa-onnx-1.13.4.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar
```

## 音声認識エンジン

設定画面から切り替え：

| エンジン | ネット | 備考 |
|---|---|---|
| 標準（Pixel オフライン） | 不要 | 既定。Android `SpeechRecognizer` のオンデバイス経路（SODA） |
| ML Kit GenAI | 不要（初回のみモデル DL） | Pixel 10 限定。on-device Gemini ベース |
| SenseVoice | 不要（初回のみモデル DL） | sherpa-onnx + SenseVoice-Small int8。約 230MB のモデルを設定画面から DL |

いずれも完全オフラインで動作します。SenseVoice / ML Kit のモデルは初回のみ HTTPS でダウンロードします（設定画面のボタンから開始）。

## マイク音源

設定画面から切り替え：

| 選択 | 動作 |
|---|---|
| G2 グラス内蔵マイク | 既定。BLE 経由で G2 の DSP 処理済み音声を受け取る |
| スマホ本体マイク | Pixel 側のマイク（`VOICE_RECOGNITION` ソース）で拾う。G2 は表示専用として使う |

## 設計メモ

- 文字起こし以外の機能は持たない方針
- 既定は G2 内蔵マイクの LC3 音声を BLE 経由で受け取り、デコード後に STT へ流す（スマホマイクへの切替も可能）
- BLE 層は [Mentra Bluetooth SDK](https://github.com/Mentra-Community/MentraOS) を利用
- SenseVoice エンジンは [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) の Android AAR + Silero VAD + SenseVoice-Small int8

## ライセンス

MIT License — [LICENSE](LICENSE) を参照。

### 利用しているサードパーティ

- [Mentra Bluetooth SDK](https://github.com/Mentra-Community/MentraOS)（MIT License, © Mentra Community）
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）と [SenseVoice-Small モデル](https://github.com/FunAudioLLM/SenseVoice)
- Google ML Kit GenAI Speech Recognition
- AndroidX / Kotlin / OkHttp / Gson / Apache Commons Compress 他、各々のオープンソースライセンス
