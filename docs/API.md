# CodexTask 协议接入

Base URL 是服务根地址，包括反向代理前缀；所有请求使用 `Authorization: Bearer TOKEN`。只支持 HTTPS，跟随 3xx 重定向被禁用。

| 操作 | 接口 | 成功状态 |
| --- | --- | --- |
| 查询目录 | `GET v1/models` | 200 |
| 手动刷新 | `GET v1/models?refresh=true` | 200 |
| 生文 / 识图 | `POST v1/text` | 202 |
| 生图 / 参考图生成 | `POST v1/image` | 202 |
| 查询任务 | `GET v1/jobs/JOB_ID` | 200 |
| 取消任务 | `POST v1/jobs/JOB_ID/cancel` | 200 |
| 下载产物 | 响应中 `artifacts[].downloadUrl` | 200 |

## 目录

`source, updatedAt, stale, models`；每个模型包含 `id, displayName, reasoningLevels, defaultReasoning, inputModalities`。

模型与等级均动态解析，前端没有模型枚举。API 格式错误、空目录或 HTTP 错误不会覆盖已有目录。响应中的 `warning` 不原样显示，避免未经脱敏的上游诊断内容进入界面。

选定模型声明的默认等级会明确传入请求；“服务默认”省略 `reasoning`。无等级模型不添加虚构选项。已保存等级不再受支持时，保持该值并提示重新选择，未修正前禁止提交。手动 ID 模式允许显式自定义等级，但兼容性由服务校验，不保证每个字符串都可用。

## 请求

```json
{
  "backend": "direct",
  "prompt": "PROMPT",
  "model": "MODEL_ID",
  "reasoning": "LEVEL_FROM_CATALOG",
  "images": [
    { "name": "reference.png", "mimeType": "image/png", "dataBase64": "BASE64" }
  ]
}
```

无图片时省略 `images`。生图额外传 `count=1, size, quality`。不传手机文件路径、服务端工作目录、SDK 权限或任务执行参数。

响应的 `statusUrl=/v1/jobs/...` 是**服务相对路径**，不是公网主机根路径。客户端拼接时保留 Base URL 的路径前缀。完整地址必须同 HTTPS scheme、host、port 且处于该服务的 `v1/jobs/` 子树；拒绝路径穿越、编码路径、查询串及其它主机，避免向产物 URL 泄露 Bearer Token。

## 结果与故障

只复制需要的结果字段：`text, effectiveModel, reasoningEffort, artifacts, usage, questions, backend, taskId`，错误仅保留格式受限的错误代码。HTTP 错误按状态映射固定中文提示，不展示未经脱敏的响应 body、堆栈或请求头。

目录 401 需更新凭证；403 需正确设备 scope；503 保留缓存。轮询网络失败会逐渐延长重试间隔，最长 30 秒；401/403/404/410 停止自动查询并提供手动恢复。提交失败且没有确定响应时明确提示可能已创建任务，不自动重发 POST。

`needs_input` 会展示问题，但这个版本只提供 Direct 测试，不提供 SDK task/resume 工作流。文本渲染使用原生 TextView，支持基础标题、列表、代码块；不是完整 Markdown/HTML 浏览器。

## 本机数据

Token 使用非导出 Android Keystore AES 密钥及随机 IV，通过 AES/GCM 加密写入私有 SharedPreferences，无明文 fallback。更换服务需重新输入 Token。Android 备份和设备迁移备份禁用。

最近 30 条任务以 JSON 写入私有目录，单写入队列和临时文件 rename 防止部分写入；下载图片保存在私有 artifacts 目录。分享仅暴露选定产物的 FileProvider URI，临时赋予读取权限。原始输入图片保存于 private cache，草稿不永久保存。

## 生图模型的两层含义

App 设置选择的是处理提示词并调用 image_generation 工具的主模型。结果展示服务实际返回的 effectiveModel；它不是底层绘图模型版本。托管绘图工具没有披露具体版本。当前服务对被标记为 Responses Lite 的部分模型（包括 Luna）使用兼容主模型 gpt-5.5；gpt-6-astra 路由有特别处理。客户端不硬编码这套回退规则，也不把“6 系列”视为统一的生图能力保证。
