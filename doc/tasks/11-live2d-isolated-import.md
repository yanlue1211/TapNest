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

**计划仅用于分离开发任务，不代表 Live2D 已完成。**
