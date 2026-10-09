package com.wayne.prismtask;

import java.time.Instant;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional server-derived routing metadata. Never guesses model IDs or reasoning levels. */
public final class ImageRouting {
  private ImageRouting() {}

  public record Choice(
      String status, String model, String effort, boolean fallback, String updatedAt) {
    public boolean ready() {
      return status.equals("ready");
    }

    public boolean unsupported() {
      return status.equals("unsupported");
    }

    public boolean fresh(long now) {
      try {
        long age = now - Instant.parse(updatedAt).toEpochMilli();
        return age >= -60_000 && age <= 300_000;
      } catch (Exception ignored) {
        return false;
      }
    }

    public boolean sameTarget(Choice other) {
      return other != null
          && status.equals(other.status)
          && Objects.equals(model, other.model)
          && Objects.equals(effort, other.effort)
          && fallback == other.fallback;
    }
  }

  public static Choice find(JSONObject catalog, String id, String effort) {
    if (catalog == null) return null;
    try {
      JSONObject routing = catalog.optJSONObject("imageRouting");
      if (routing == null
          || routing.optInt("version") != 1
          || !routing.optString("source").equals("codex-task-router")
          || !routing.optString("backend").equals("direct")) return null;
      String updatedAt = routing.optString("updatedAt", "");
      JSONArray models = routing.getJSONArray("models");
      for (int i = 0; i < models.length(); i++) {
        JSONObject model = models.getJSONObject(i);
        if (!model.getString("id").equals(id)) continue;
        JSONArray choices = model.getJSONArray("choices");
        Choice match = null;
        for (int n = 0; n < choices.length(); n++) {
          JSONObject item = choices.getJSONObject(n);
          if (!item.has("reasoning")) continue;
          Object value = item.get("reasoning");
          if (value != JSONObject.NULL && !(value instanceof String)) continue;
          String selected = value == JSONObject.NULL ? null : (String) value;
          if (!Objects.equals(selected, effort)) continue;
          if (match != null) return null; // Ambiguous metadata must not gate submission.
          String status = item.getString("status");
          if (!status.equals("ready") && !status.equals("unsupported") && !status.equals("unknown"))
            return null;
          String target = Protocol.nullable(item, "effectiveModel");
          String targetEffort = Protocol.nullable(item, "effectiveReasoning");
          boolean fallback = item.optBoolean("fallback", false);
          if (status.equals("ready")) {
            if (target == null
                || target.isBlank()
                || targetEffort == null
                || targetEffort.isBlank()) return null;
            if (effort != null && !effort.equals(targetEffort)) return null;
            if (fallback != !target.equals(id)) return null;
          }
          match = new Choice(status, target, targetEffort, fallback, updatedAt);
        }
        return match;
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
