# 验证记录

日期：2026-10-08（北京时间）。以下区分客户端协议测试与真实服务推理，不把目录查询等同于所有模型实测。

## 构建 / 协议

- JDK 21；Gradle 9.3.1；Android Gradle Plugin 9.1.0；SDK 36。
- `testDebugUnitTest`：32 项测试通过。
- `assembleDebug` / 有本机私有签名的 `assembleRelease`：通过。
- `lintDebug` / `lintRelease`：0 个错误；工具版本更新提示不作为错误抑制或测试通过的替代。
- 覆盖流式 Base64 文件边界、路径前缀、相对状态/产物 URL、跨源/穿越/查询参数拒绝、无凭证重定向、未来等级与顺序、空等级、模型默认/服务默认区别、无效保存选择、识图/生图请求结构、异步终态、取消、响应有界、401/403/503 脱敏和不重发 POST。

## Android 运行

- Android 13 / API 33 arm64 模拟器：启动、连接、原生页面、模型切换、动态等级、真实文本任务、记录与 Token 加密存储。
- Android 16 / API 36 vivo：正式签名 APK 安装，设备 Token 在隐藏输入框中填写，成功加载实时目录。
- 模拟器私有 preferences 验证只包含 Token 密文与 IV，没有明文 Token。
- Android 8.0 / API 26 是最低兼容目标，经过 Lint API 检查；没有宣称在 API 26 真机上测试。

## 真实服务

- `GET /v1/models?refresh=true` 返回 `source=codex-app-server`、`stale=false`，7 个模型及各自动态等级。
- 生文：Android App 提交 `gpt-6.1-sol / low`，202 → completed，获得中文四行诗；`effectiveModel=gpt-6.1-sol`。
- 生图：使用同一设备凭证直接调用服务，`gpt-6-astra / medium`，202 → completed，实际执行主模型为 `gpt-6-astra`，并通过鉴权下载生成图片。
- 识图：使用同一设备凭证直接调用服务，`gpt-6.1-sol / low`，上传本次创建的几何参考图，202 → completed，正确识别左右两种形状与颜色。
- 另一次 App 生图请求遇到上游 `DIRECT_TRANSPORT_ERROR`，已显示失败状态；成功结果不代表服务永远可用。
- 依用户要求停止额外测试和模拟器排查，最终版本直接更新 vivo 真机。没有宣称完成真机上的全部生图、识图 UI 流程，也没有逐一实测全部模型。

## 数据边界

测试提示词与参考图为本次创建的非敏感素材。公开 Git 只包含客户端源码、文档与无凭证截图，不包含服务器配置、Service Token、签名 keystore、手机数据库、运行日志或 APK。正式 APK 单独交付，构建时不注入 Token。
