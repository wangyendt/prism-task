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

App 设置选择的是处理提示词并调用 image_generation 工具的主模型。服务使用 classic Responses 格式保留所选主模型和等级，不再主动替换成 gpt-5.5。`effectiveModel` 是服务发给上游的主模型，不是底层绘图版本或上游内部调度证明。

## 生图提交与错误

客户端不依赖旧 `imageRouting` 字段，不维护能力矩阵，也不拦截包括 ultra 在内的目录选择。目录只用于选项展示；生图以实时请求结果为准。POST 返回 202 后，轮询的任务结果可能返回 failed：

```json
{
  "status": "failed",
  "result": {
    "status": "failed",
    "error": {
      "code": "DIRECT_UNSUPPORTED_REASONING",
      "message": "Upstream does not support the requested reasoning effort",
      "retryable": false
    }
  }
}
```

`DIRECT_UNSUPPORTED_MODEL`、`DIRECT_UNSUPPORTED_REASONING`、`DIRECT_UNSUPPORTED_IMAGE_MODEL`、`DIRECT_UNSUPPORTED_IMAGE_COMBINATION` 表示明确的模型/等级/绘图工具拒绝。其它参数拒绝、鉴权、限流、网络及服务错误分别解释，不误判为组合不支持。

App 用经过校验的错误码构造中文提示与任务自己保存的选择，不展示或持久化上游原始错误正文、details 或凭证。失败记录只属于该任务，不禁止未来提交；不会自动降级、重试创建任务或变更已保存的模型/等级。提供“调整组合再试”回到编辑页，由用户明确提交。

服务保留可选 imageModel 绘图工具参数；App 当前省略它，由上游选择，实际版本未知。

## 全屏图片与本机参考图

每张参考图、历史记录中的参考图以及生成产物均可进入同一全屏图库。支持双指缩放（适配尺寸到 8 倍）、单指拖动、双击放大/复位、放大后单击复位、适配时左右滑动切图、90° 预览旋转、显式复位、保存及分享。适配状态单击隐藏/显示工具栏，关闭按钮或系统返回退出。

大图预览经过采样且至多解码 800 万像素，尊重 EXIF 方向；预览旋转和缩放不修改原文件。GIF 显示静态首帧。保存、分享输出原始文件。

新任务提交时把参考图复制到私有 artifacts 目录，纳入最近 30 条记录，淘汰或清除任务时同步删除。参考图片属于未额外加密的本机数据；Android 备份仍禁用。历史 0.1.0 记录没有持久化参考图，升级不会伪造或补回这些图片。草稿分享仅通过已有 cache/shared FileProvider 路径复制指定文件，不开放整个输入目录。
