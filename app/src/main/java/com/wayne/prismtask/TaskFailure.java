package com.wayne.prismtask;

/** User-facing errors from stable codes only; never reflects raw upstream bodies or credentials. */
public final class TaskFailure {
  private TaskFailure() {}

  public static String code(String value) {
    return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "FAILED";
  }

  public static String describe(String value, String model, String effort) {
    String selection = safe(model, "所选模型") + " + " + (effort == null ? "服务默认等级" : safe(effort, "所选等级"));
    return switch (code(value)) {
      case "DIRECT_UNSUPPORTED_MODEL", "DIRECT_UNSUPPORTED_REASONING", "DIRECT_UNSUPPORTED_IMAGE_COMBINATION" ->
          "当前接口不支持 " + selection + " 组合，请选择其他模型或思考等级。不会自动换模型，日后仍可再次尝试。";
      case "DIRECT_UNSUPPORTED_IMAGE_MODEL" -> "当前接口不支持请求的绘图模型，请更换绘图模型或使用上游默认设置。";
      case "DIRECT_RATE_LIMITED" -> "上游请求限流，请稍后再试；这不代表所选组合不支持。";
      case "DIRECT_AUTH_FAILED", "AUTH_NOT_FOUND", "AUTH_INVALID", "AUTH_EXPIRED", "AUTH_REFRESH_FAILED" ->
          "服务器的 Codex 登录已失效或没有访问权限，请更新服务器登录；不代表组合不支持。";
      case "DIRECT_SERVER_ERROR" -> "OpenAI 上游暂时异常，请稍后重试，已保留原选择。";
      case "DIRECT_TRANSPORT_ERROR", "TIMEOUT" -> "连接上游中断或超时，请稍后手动重试；不会自动换模型。";
      case "DIRECT_UPSTREAM_BLOCKED" -> "上游入口拒绝了连接，请检查服务器网络和访问状态；不代表组合不支持。";
      case "DIRECT_REQUEST_REJECTED", "DIRECT_HTTP_ERROR", "USAGE_ERROR" ->
          "服务或上游拒绝了请求参数，请检查图片尺寸、质量及输入内容；未确认是模型组合问题。";
      case "DIRECT_EMPTY_IMAGE" -> "上游未返回图片，请调整提示词或组合后重试；不会自动换模型。";
      case "CANCELLED" -> "任务已取消，原选择和提示词已保留。";
      default -> "任务失败（" + code(value) + "），原请求已保留，可调整后手动重试。";
    };
  }

  private static String safe(String value, String fallback) {
    return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : fallback;
  }
}
