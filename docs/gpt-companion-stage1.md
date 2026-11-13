# 点点栖 · GPT 双形态聊天（阶段 1 / 实机待验收）

基线：在已通过 Android CI 的 `main@39a38093` 之上增量开发，原桌宠/绑骨/换装/喂食/洗澡/玩耍/长按五秒关闭等逻辑均未改变。

## 如何试用

1. 安装来自本分支 **成功构建**的测试 APK；升级前先完成点点栖数据备份。GitHub Actions 的 PR Debug APK 可能使用临时签名，**不能保证覆盖现有正式 APK**。优先使用隔离测试包先检查，避免卸载原版。
2. 进入点点栖主界面 →「GPT 聊天 · Chat / Work」，或双击现有桌宠 →「和 GPT 聊天」。
3. 点击「继续使用 ChatGPT」，系统浏览器打开 OpenAI 官方授权。检查显示的名称、账号、计划额度权限，明确同意后回到点点栖；完成授权不需要提供 API Key。
4. 选择账号实际返回的模型，发送一条短句。确认收到流式文本、在 `response.completed` 后保存并且退出重进仍有上下文。模型列表不会写死，若为空请截图错误文字。
5. 对现有角色将两个形态分别命名包含 `Chat` 和 `Work`。切换形态以后再发送消息，提示词风格随形态变化；两边共用当前账号的本地聊天历史。
6. 关闭 AI 窗口、离线使用普通桌宠，测试双击原菜单、长按换装、长按五秒关闭、喂食、洗澡和动画是否仍正常。

## 设计边界

- 登录使用官方开源客户端动态 OAuth 注册：本机固定 host ID，PKCE S256、随机 state/nonce、127.0.0.1 临时监听、OIDC RS256/JWKS 校验、加密保存 OAuth token，刷新时替换轮换 refresh token。
- 订阅额度权限单独核对；用户没批准时只允许显示登录状态、不发起请求。未提供其他模型或 API Key 回退。
- 聊天请求仅发送 `POST /v1/responses`，`store=false`、`stream=true`；仅遇 `response.completed` 才完整保存，不把失败的半段消息写入历史。
- 完整聊天保存在应用本地（按 OpenAI 账号身份隔离，Chat/Work 共用）。目前**没有外部记忆库 / ChatGPT 内置聊天和记忆同步**，也尚未将 GPT 回复映射到桌宠文字气泡或互动事件。
- 若软件显示「未授予订阅额度」或模型列表为空，不能自动切到昂贵模型。
- 为避免影响现有悬浮窗输入法与手势，第一阶段独立 Activity 提供聊天，双击会先打开现有互动菜单，再选择 GPT 聊天。
- 不记录 OAuth token 到日志、不把任何账号凭证写入源代码或 GitHub。
- 仍需鸿蒙 7 / Android 真机检验：浏览器对本机回环回调的支持、授权结果返回体验、刷新续期、悬浮窗启动 Activity 能否成功、模型路由是否支持该账号、后台/前台切换。

## 阶段 2（尚未实现）

确认授权稳定后，再考虑真正的双击快捷输入、桌宠气泡联动、AI互动开关与 GPT 专属外接记忆库。不得在阶段 1 未经真机验收时声称最终完成。

官方参考：

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery
