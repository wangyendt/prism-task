package com.wayne.prismtask;

import java.net.URI;
import java.util.*;
import org.json.*;

/** Pure, unit-testable service contract. No model or effort enum is baked into the client. */
public final class Protocol {
  private Protocol() {}

  public record Model(
      String id, String name, List<String> levels, String defaultLevel, List<String> modalities) {}

  public record Catalog(
      String source, String updatedAt, boolean stale, List<Model> models, JSONObject raw) {
    public Model find(String id) {
      for (Model m : models) if (m.id.equals(id)) return m;
      return null;
    }
  }

  public static Catalog parseCatalog(JSONObject json) throws JSONException {
    JSONArray entries = json.getJSONArray("models");
    if (entries.length() == 0) throw new JSONException("Empty catalog");
    List<Model> models = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject item = entries.getJSONObject(i);
      String id = item.getString("id");
      if (id.isBlank() || !ids.add(id)) throw new JSONException("Invalid model ID");
      List<String> levels = strings(item.getJSONArray("reasoningLevels"));
      String def = nullable(item, "defaultReasoning");
      if (def != null && !levels.contains(def)) throw new JSONException("Invalid default level");
      models.add(
          new Model(
              id,
              item.optString("displayName", id),
              levels,
              def,
              strings(item.getJSONArray("inputModalities"))));
    }
    return new Catalog(
        json.getString("source"),
        nullable(json, "updatedAt"),
        json.getBoolean("stale"),
        List.copyOf(models),
        json);
  }

  private static List<String> strings(JSONArray array) throws JSONException {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      Object value = array.get(i);
      if (!(value instanceof String s) || s.isBlank())
        throw new JSONException("Invalid catalog string");
      result.add(s);
    }
    return List.copyOf(result);
  }

  public static String nullable(JSONObject object, String key) {
    return object.isNull(key) ? null : object.optString(key, null);
  }

  public static String base(String value) {
    try {
      URI uri = new URI(value.trim().replaceAll("/+$", ""));
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !uri.normalize().equals(uri)
          || uri.getRawPath().contains("%")
          || uri.getRawPath().contains("//")) throw new IllegalArgumentException();
      return uri.toString().replaceAll("/+$", "");
    } catch (Exception e) {
      throw new IllegalArgumentException("请输入完整 HTTPS 服务地址，不含用户名、查询参数或片段");
    }
  }

  public static URI endpoint(String base, String path) {
    String normalized = base(base);
    return URI.create(normalized + "/" + path.replaceFirst("^/+", ""));
  }

  /** Server root-relative /v1 paths are relative to the service, not the reverse proxy host. */
  public static URI serviceLink(String base, String link) {
    String normalized = base(base);
    URI root = URI.create(normalized + "/");
    URI candidate;
    if (link.startsWith("/v1/")) candidate = endpoint(normalized, link);
    else if (link.startsWith("v1/")) candidate = endpoint(normalized, link);
    else candidate = root.resolve(link);
    String prefix = root.getPath();
    if (!Objects.equals(root.getScheme(), candidate.getScheme())
        || !Objects.equals(root.getHost(), candidate.getHost())
        || root.getPort() != candidate.getPort()
        || candidate.getUserInfo() != null
        || candidate.getQuery() != null
        || candidate.getFragment() != null
        || !candidate.getPath().startsWith(prefix + "v1/jobs/")
        || !candidate.normalize().equals(candidate)
        || candidate.getRawPath().contains("%")
        || candidate.getPath().contains("//"))
      throw new IllegalArgumentException("服务返回了不可信的下载或任务地址");
    return candidate;
  }

  /** Image capability is decided by the live upstream, not a cached routing allowlist. */
  public static String imageSelectionError(String model) {
    return model == null || model.isBlank() ? "请选择模型或输入模型 ID" : null;
  }

  public static String selectionError(
      Catalog catalog, String model, String effort, boolean manual) {
    if (model == null || model.isBlank()) return "请选择模型或输入模型 ID";
    if (manual) return null;
    Model option = catalog == null ? null : catalog.find(model);
    if (option == null) return "该模型已不在目录中，请重新选择或切换到手动 ID";
    if (effort != null && !option.levels.contains(effort)) return "已保存的思考等级不再受支持，请重新选择";
    return null;
  }

  public static JSONObject request(
      String prompt,
      String model,
      String effort,
      JSONArray images,
      boolean image,
      String size,
      String quality)
      throws JSONException {
    JSONObject json =
        new JSONObject().put("backend", "direct").put("prompt", prompt).put("model", model);
    if (effort != null && !effort.isBlank()) json.put("reasoning", effort);
    if (images != null && images.length() > 0) json.put("images", images);
    if (image) json.put("count", 1).put("size", size).put("quality", quality);
    return json;
  }

  public static boolean terminal(String status) {
    return Set.of("completed", "failed", "cancelled", "needs_input").contains(status);
  }
}
