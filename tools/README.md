# FrostOrtho 設定の作り方(Claude向けメモ)

このリポジトリは、Conductor のキーマップを FrostOrtho(ZMK, seeeduino_xiao_ble ×2, 右=central)へ移植したもの。
**キー配置・ガイド表示は `tools/` のスクリプトから自動生成する。生成物を手で直さないこと。**

## どこに何があるか
| 目的 | ファイル |
|---|---|
| 元データ(Conductor の書き出し) | `tools/conductor-keymap.json` |
| FrostOrtho 用の変更(キーの追加・置き換え・エンコーダー役割) | `tools/layout.py` ← キーを変えるときはまずここ |
| キーマップ生成 → `config/FrostOrtho.keymap` | `tools/mk_keymap.py` |
| ガイド生成(Mac版 html と Android アプリ内 html を同時に出力) | `tools/guide_gen.py` + `tools/guide_tpl.html`(機能一覧 FEATURES もここ) |
| トラボ設定(速度・AML・スクロール縦横固定・精密モード) | `config/boards/shields/FrostOrtho/FrostOrtho_R.overlay` |
| 右手の設定(LED・DeXの接続先 CONFIG_FROST_DEX_PROFILES など) | `config/boards/shields/FrostOrtho/FrostOrtho_R.conf` |
| 自作機能(C) | `src/`:status_led(LED)、scroll_snap(縦横固定)、behavior_drag_lock、frost_features(DeX自動切替・電池/状態通知)、frost_ble_guide(Android版へBLE通知) |
| Android(DeX)版ガイドアプリ | `android/`(GitHub Actions `Build Android Guide` で APK を作る)。F13(キー番号39)の長押し/2回タップで音声入力(VoiceInput.java、文字入れは FrostInputService=ユーザー補助) |

## 主な決めごと(美穂さんと合意済み)
- 物理配置: 上3段は1対1、最下段左6キー=L30〜L35、最下段右=Bksp/Enter/空き(Iの下=大文字モード)/F13/スクショ
- レイヤー番号は Conductor と同じ(0基本 1記号 2数字 3移動 4マウス(AML) 5スクロール 6設定 7ショートカット 8DeX 9DeX移動 10DeXマウス 12精密 13ジェスチャー)
- マウスレイヤー: Y左クリック U スクロール I右クリック O ドラッグ固定、左手 ZXCV=取消/切取/コピー/貼付、B=Enter A=全選択 D=削除 F=検索 E/R=戻る/進む Q=Esc(DeXでは自動でスマホ用)
- DeX(スマホ)は接続先 BT 3 と BT 4。選ぶと DeX モード自動オン
- ガイドのパケット: 0xFF レイヤー / 0xF1 キー押下 / 0xB1 電池 / 0xC1 状態(大文字・ドラッグ・接続先・接続中)

## 変更の手順(Claude がやる)
1. `tools/layout.py`(または overlay / conf / src)を変更
2. `python3 tools/mk_keymap.py` と `python3 tools/guide_gen.py` を実行
3. ZMK をローカルでビルドして確認(右: `-DSHIELD="FrostOrtho_R raw_hid_adapter" -S studio-rpc-usb-uart`、左: `FrostOrtho_L`、`-DZMK_EXTRA_MODULES=<repo>`)
4. 美穂さんには「右手用 uf2 / 変更ファイルの zip / ガイド html」を渡し、GUI だけの手順で案内する(ターミナルは使わない)
5. 機能を増やしたら `guide_tpl.html` の FEATURES(機能一覧)も必ず更新する
