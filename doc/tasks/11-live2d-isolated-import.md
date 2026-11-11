# Live2D 模型：与 PNG 绑骨完全独立的导入入口（开发计划）

## 需求界面

「导入宠物」中保留现有三个完全独立的路径，不要求用户把 Live2D 拆成 PNG，亦不自动将现有桌宠转换格式：

- **创建多图 PNG 绑骨桌宠**：现有编辑器、Rig、形态与全部历史功能保持原样。
- **自绘单张 PNG**：现有轻量导入和弹跳行为保持原样。
- **导入 Live2D 模型**：单独入口，选择本地 Cubism 模型 ZIP，调用隔离的 Live2D 运行器，不走 `LayeredPetPlayer` 或 `TapNestRig`。

Live2D 首版以 **Cubism 3/4/5** 的 `.model3.json`、`.moc3`、`Textures/*.png`、`*.motion3.json`、`*.exp3.json` 为主。旧 `.model.json`/Cubism 2 需另定兼容方案，不默默假装可用。

## 实现约束

1. 官方 Live2D **Cubism SDK for Java** 有 Android 支持；但其 **Cubism Core 二进制是专有组件，不在 GitHub 公开 Framework/Samples 仓库**。必须先合法取得 SDK Core，确认分发/使用范围后才能打包真正可播放的 APK。不要把未知来源的 Cubism Core 直接提交到这个公开仓库。
2. 加独立 `Live2DImporter`：接受用户选取 ZIP，用现有 `ZipExtractor` 的 ZipSlip/大小限制，在私有临时目录验证 manifest 及所有引用资源均为包内相对路径；检查资源是否存在与模型版本兼容；原子完成导入且失败回滚新目录；不可覆盖已有角色文件。
3. 模型加载器和 `Live2DRenderer` 必须与现有 `PetEngine.PetLoader.Layered/Png/Gif/CodexPet` 并列，不混用 PNG 绑骨系统。采用官方 SDK 渲染或合规适配层，最终出图与现有前台悬浮窗同步；支持息屏停帧、资源释放、手势位置/缩放和自动恢复。
4. `FileManager` 的单只导出、完整备份、恢复及删除策略必须支持新模型 ZIP 的**原样文件引用**，不得把 `.moc3` 当作 PNG 或跳过贴图；兼容旧 `PetInfo` 序列化/备份。现有其他四类角色不受到额外迁移。
5. 优先导入用户**已经取得使用权限**的 Live2D 模型；模型的分发授权与 SDK 授权是两回事。禁止在 APK 中预装无授权商业模型。
6. 首版验收：在鸿蒙 Android 设备上完整从 ZIP 导入→显示模型→播放待机/眨眼/动作→拖拽缩放→切出后台→恢复→导出备份→重装后恢复。不能只显示一个「导入成功」提示就当作功能完成。
7. 在 Core 合法取得且上述渲染能力能够实现**前**，不要展示会成功导入但无法播放的假入口。

官方资料：
- https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-java/
- https://docs.live2d.com/en/cubism-sdk-manual/cubism-core/
- https://github.com/Live2D/CubismJavaSamples

**上方为初期架构记录。以下为首版已编码功能与仍待验证的项目；安装及播放验收以实际手机为准。**

## 2026-11-11 首版独立模块实现状态

- ✅ 新增独立类型 `PetType.LIVE2D`，不把 Live2D 拆成 PNG 图层，不变更旧序列化字段。
- ✅ 导入 ZIP 并校验 `.model3.json` / `.moc3` / 贴图与动作引用；防路径穿越、超量文件和残缺素材；失败清理新建文件。
- ✅ 独立 WebViewAssetLoader 本地只读模型沙盒；透明 Android 悬浮窗口；手势移动、双击弹伙伴面板、长按 5 秒关闭、捏合缩放。
- ✅ 便于未来移植的 `live2d-web/` 单独 PixiJS/untitled 引擎源码；GitHub Actions 打包 JS 到 APK；**不捆绑专有 Cubism Core**。
- ✅ 单只导出、完整备份、从备份恢复 Live2D；仍使用原来 `pets.json` 和 `PetInfo`，对 PNG/GIF/绑骨/Codex 数据路径最小入侵。
- ⚠️ 官方 Hosted Cubism Core 必须联网加载；无网络时提示失败，不会读取云端任何个人数据。模型素材仅在本机，允许发送的只有下载 Core 的常规网络请求。
- ⚠️ 尚未在 HarmonyOS 7 真机验证 GPU/WebView/WebGL/动作兼容性；**自动构建成功不能视为真机验收**。
- ⚠️ 首版交互使用模型自带 TapBody / Tap / Touch / TapHead 动作组和 expression；复杂自定义参数及多轨道动作编辑留作后续版本。
- ⚠️ 模型顶部透明占位和触摸命中当前按窗口边界处理；精细碰撞与透明点透改进后续验证，不修改已有 PNG 行为。
- ⚠️ 尽管 MIT 前端引擎可依法复用，Cubism Core 和导入模型仍需分别遵守其许可证；公开发行 APK 前复核 Live2D 的 Expandable Application 条款。

**代码入口**
- `app/src/main/java/com/deskpet/data/Live2DModelFiles.kt`
- `app/src/main/java/com/deskpet/overlay/Live2DOverlayView.kt`
- `live2d-web/src/viewer.js`
- `app/src/main/assets/live2d/index.html`

**验证步骤**（不清空任何现有桌宠）
1. 在「导入宠物」选择「🌸 导入 Live2D 模型」；选择自己拥有合法使用权的 Cubism 3/4/5 `.zip`。
2. 选中这个新宠物，开启悬浮窗，观察透明背景、模型动作/眨眼；拖动、捏合、轻点、双击和长按五秒关闭。
3. 关闭再开启 App；切换别的宠物再切回来；息屏再亮屏。
4. 测试「导出角色」和「备份全部」→确认 ZIP 内含 `model3.json`/`moc3`/贴图；在测试环境里验证恢复，不可先卸载已有应用。
5. 无网络时检查能否给出 Core 加载失败提示；不能因为失败删除角色数据。


