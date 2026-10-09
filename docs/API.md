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

App 设置选择的是处理提示词并调用 image_generation 工具的主模型。结果展示服务实际返回的 effectiveModel；它不是底层绘图模型版本。托管绘图工具没有披露具体版本。服务对被标记为 Responses Lite 的模型使用兼容主模型 gpt-5.5；gpt-6-astra 路由有特别处理。缓存条目可能随目录刷新变化，同一模型今天是否回退不能仅凭型号推断。客户端不硬编码这套回退规则，也不把“6 系列”视为统一的生图能力保证。

## 可选动态生图路由（0.2.0）

hx470 服务已在 `GET /v1/models` 响应中增补 `imageRouting`。字段来源是 codex-task 当前实际使用的 Direct 生图解析函数，而非 App 静态模型表。目录只查询元数据，不创建推理任务。

```json
{
  "imageRouting": {
    "version": 1,
    "source": "codex-task-router",
    "backend": "direct",
    "updatedAt": "2026-10-09T03:25:00Z",
    "defaultReasoning": "medium",
    "models": [{
      "id": "MODEL_ID",
      "choices": [
        {
          "reasoning": "LEVEL",
          "status": "ready",
          "effectiveModel": "TARGET_MODEL_ID",
          "effectiveReasoning": "LEVEL",
          "fallback": true
        },
        {"reasoning": null, "status": "unsupported", "errorCode": "USAGE_ERROR"}
      ]
    }]
  }
}
```

- `reasoning=null` 单独表示请求省略 reasoning，即服务默认，不能拿模型默认替代。
- `ready` 表示服务预检通过并选定主模型，不保证上游生图一定成功。
- `unsupported` 表示解析阶段拒绝组合，不发送上游请求；`unknown` 表示元数据计算失败，不能推断有效目标。
- 切换选择即展示目标主模型和等级；回退需要确认后才提交，确认期间目标或选择变化会要求重新确认。
- 服务仍是最终校验者。目录来自本机/降级缓存或路由超过 5 分钟时明确标注缓存；缓存的不支持组合要求先刷新。
- 旧服务缺少字段、未来 schema 版本、手动 ID 没有匹配项或格式异常时退化为“未知”，不凭模型名猜测。
- 服务拓展没有改动实际回退规则。参考实现补丁见 [codex-task-image-routing.patch](codex-task-image-routing.patch)；适用于原始 codex-task 0.2.17 源码。

## 全屏图片与本机参考图

每张参考图、历史记录中的参考图以及生成产物均可进入同一全屏图库。支持双指缩放（适配尺寸到 8 倍）、单指拖动、双击放大/复位、放大后单击复位、适配时左右滑动切图、90° 预览旋转、显式复位、保存及分享。适配状态单击隐藏/显示工具栏，关闭按钮或系统返回退出。

大图预览经过采样且至多解码 800 万像素，尊重 EXIF 方向；预览旋转和缩放不修改原文件。GIF 显示静态首帧。保存、分享输出原始文件。

新任务提交时把参考图复制到私有 artifacts 目录，纳入最近 30 条记录，淘汰或清除任务时同步删除。参考图片属于未额外加密的本机数据；Android 备份仍禁用。历史 0.1.0 记录没有持久化参考图，升级不会伪造或补回这些图片。草稿分享仅通过已有 cache/shared FileProvider 路径复制指定文件，不开放整个输入目录。
