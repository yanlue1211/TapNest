# TapNest 覆盖安装与签名

## 为什么旧版每次都要求卸载？

Android 同时检查应用包名、签名证书以及版本号。原来的 GitHub Actions 使用每次新生成的临时 debug 签名，导致两次构建虽然同为 `com.tapnest.pet`，但签名不同，系统拒绝覆盖安装。**不能通过提高版本号或修改代码绕过 Android 的签名校验。**

旧调试包的私钥没有在仓库保存，无法从旧 APK 里恢复私钥。因此，改为稳定签名后，旧调试包**仍需一次性迁移**。迁移前在旧 App 使用「备份全部」，确认 ZIP 保存到手机；不要直接卸载，更不要在没有有效备份时清除数据。新签名首次安装后，使用「恢复备份」重新导入。已经安装新的稳定签名版本后，后续下载同一签名的 APK 可以直接覆盖更新，角色数据留在 App 私有存储中。

## 生成并保管持久签名（一次性配置）

使用信任的本地设备生成**自己独有**的 release keystore（不要放入 GitHub 公共仓库或发到聊天）：

```sh
keytool -genkeypair -v -keystore tapnest-release.jks -alias tapnest -keyalg RSA -keysize 3072 -validity 10000
```

将 keystore 的二进制文件安全备份。若丢失密钥，将来再次无法覆盖更新；因此，至少保存一份离线备份。

在 GitHub 仓库的 Settings → Secrets and variables → Actions → New repository secret，创建四个仓库密钥：

- `SIGNING_KEYSTORE_BASE64`：本地用 `base64` 编码后的整份 keystore，**保持单行**
- `SIGNING_STORE_PASSWORD`：生成 keystore 时的口令
- `SIGNING_KEY_ALIAS`：这里使用 `tapnest`
- `SIGNING_KEY_PASSWORD`：签名私钥口令

这是 GitHub Actions 加密密钥，不是公开代码。**不要**将 `.jks`、密码、Base64 内容提交或上传到公共仓库。

配置完成后，push 到 main / 手动运行 TapNest Android CI，会验证签名有效后才生成工作流产物 `Tap-Nest-APK`，压缩包内部文件名 `Tap Nest.apk`。

如果没有配置签名，工作流仍能编译、测试并上传 `app-debug` 调试包，但**不会**把临时签名调试包命名为可升级的 `Tap Nest.apk`。调试包只适合首次安装、测试或作为与原版并存的隔离版本。

## 安全性

- Android 不支持不同签名的同包名 APK 无损覆盖安装。
- 备份应在原 App 内导出且导出后验证其文件存在，再考虑迁移。
- 包名不同的「安全恢复版」可与原版并排安装，但两个 App 的内部角色数据不会自动共享。
- 发布/分享 APK 前确认许可证约束以及签名密钥保护；不同证书不能互相升级。
