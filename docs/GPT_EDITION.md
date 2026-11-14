# 点点栖 GPT版：独立产品分支与移植边界

> **这是 GPT 形象小金毛专用 APK，不是糯米机的桌宠桥接工程。**
> 此文件是交接与跨窗口开发的明确边界；请不要因为同名 TapNest 把两类构建、API 或记忆系统互相覆盖。

## 产品版本

| 项目 | GPT版 | 普通点点栖 / 糯米机桌宠 |
|---|---|---|
| 代码仓库 | `yanlue1211/TapNest` | 原桌宠 `yanlue1211/TapNest` + 糯米机仓库 `yanlue1211/SullyOS-Personal-Import` |
| GPT版分支 | **`personal/gpt-edition`** | **不得拿 GPT版覆盖其他工作分支** |
| Android 包名 | **`com.tapnest.pet.gpt`** | `com.tapnest.pet` / 糯米机的独立连接方案 |
| APK 名称 | **点点栖 GPT版** | 点点栖、糯米机桌宠 |
| 测试专用包名 | `com.tapnest.pet.gpt.test` | 其他测试包名 |
| 角色身份 | **GPT**（Chat 和 Work 两套语气与衣服） | 糯米机上的 Char |
| AI 授权 | 用户本人官方 ChatGPT OAuth（订阅额度） | 糯米机角色既有的 API 配置 |
| 历史与记忆 | GPT版自己的本地消息；外接 GPT 专属记忆规划中 | 糯米机原有的上下文/记忆系统 |
| 互联 | **完全不连接糯米机 / AutoJs6 / Worker** | 糯米机桌宠独立维护 |

**任何 GPT版代码都不要直接合并进糯米机或原始桌宠 main。** 需要移植通用动画/窗口功能时，要逐项审核、单独提取，不移植 GPT OAuth token/ChatGPT 订阅调用、历史与记忆数据、GPT Chat/Work 设定、包名和工作流。反向亦然，不把糯米机 Char/API/记忆写进 GPT版。禁止直接用较旧源码重置用户近期做好的动作编辑器/身体服装/换帧或绑骨功能。

## 当前 GPT版操作（特指本分支）

1. Android 正常启动桌宠，可以不登录 GPT 继续本地互动。
2. **快速双击小金毛：** 直接在宠物附近出现仅一条（264dp 宽、58dp 高）的原生文本输入条；后台原应用不变，没有聊天记录、顶部栏或大片白底。按发送后输入条立即销毁，键盘关闭，桌宠前台服务持续执行 GPT 请求。
3. **聊天回复：** 小金毛先显示短暂思考气泡，GPT 完成后由桌宠逐页冒泡。原文保存在按 GPT 账号隔离的本地聊天文件中，Chat/Work 共用。
4. **长按 1～5 秒后松手：** 菜单里继续换装/切换形态、喂食/洗澡/玩耍、GPT 聊天、戳动 AI 开关与最低次数设置。长按 5 秒关闭保持不变。
5. **戳动：** AI 开关默认关闭。开启时，连续戳动间隔 <3 秒算同一段；停手满 3 秒只请求一次；最低次数默认为 1，可设 1～50。原预设气泡及 PNG 动画照旧。多次戳动已被原生 TapNestTapSequence 计数，第 5 下起仅在有相应素材时启用生气帧。请求忙时最多保留下一段戳动；失败和限额不重试。
6. GPT 账号登录与模型配置仍走点点栖主界面的 GPT 聊天设置页面。Work 形态只聊天，不会触发开发工具。

## 参考 SullyOS 的规则，仅学习交互语义

只读参考 `yanlue1211/SullyOS-Personal-Import` 的 `docs/personal/tapnest-v2-contract.md`、`docs/personal/tapnest-desktop-guide.md`：快速双击与慢速戳动分开、连续戳动整体计数、超过 3 秒停手再请求、最低戳动次数、生气素材、忙时排队且不重复付费。**不复制** SullyOS Chat/Char、API 选择、记忆抽取/召回、消息数据库、AutoJs6 桥、脚本或 Worker。

## 数据及升级警告

- 包名隔离，GPT版与原点点栖可并排安装；因为 Android 私有数据目录不同，**不会自动把原角色素材/存档带过来**。需要在用户备份后通过既有导入功能迁移，不能私自跨应用复制数据。
- GPT版使用 Android Keystore 单独存 OAuth 凭据。不能把 token 上传 GitHub，不使用未授权高价 API 回退。
- **调试签名每轮可能变化**，不可声称每次可以覆盖。优先使用固定签名的 GPT版发布产物；签名不同必须先备份 GPT版素材/消息。
- 正式版、GPT版、GPT版独立测试包是不同包名与数据空间：`com.tapnest.pet`、`com.tapnest.pet.gpt`、`com.tapnest.pet.gpt.test`。
- API 成功/失败不可由自动化 UI 模拟冒充真机授权验证；鸿蒙后台、悬浮窗和输入法仍需用户实测。

## 开发前校验

- 先确认 `personal/gpt-edition` 及最新提交，不要假定 `main` 是 GPT版。
- 不碰 `yanlue1211/SullyOS-Personal-Import` 代码；工作窗并发修改先比较分支再提交。
- 只给 GPT版做独立签名与独立 Actions 发布工序。
