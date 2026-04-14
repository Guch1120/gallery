

アプリ起動
```bash
adb shell monkey -p com.google.aiedge.gallery 1
```

アプリ停止
```bash
adb shell am force-stop com.google.aiedge.gallery
```

ログ表示
```bash
adb logcat -s EdgeServer:V EdgeServerManager:V
```

サーバ通信確認
```bash
adb forward tcp:8888 tcp:8888
```

サーバの状態確認
```bash
curl http://127.0.0.1:8888/health && echo
```

サーバモデル確認
```bash
curl http://127.0.0.1:8888/v1/models && echo
```


日本語プロンプトで文字化けして回答してくれない問題
```bash
curl http://127.0.0.1:8888/v1/chat/completions \
-H "Content-Type: application/json; charset=utf-8" \
-d '{"model":"auto","messages":[{"role":"user","content":"日本語で自己紹介してください。"}]}'
```