package com.wayne.prismtask;

import android.app.Application;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class PrismApp extends Application {
  public static final String DEFAULT_BASE = "https://wangye.xin/services/codex-task";

  public interface Listener {
    void changed(String event);
  }

  public final List<Listener> listeners = new CopyOnWriteArrayList<>();
  public final List<Job> jobs = new ArrayList<>();
  public final List<InputImage> inputs = new ArrayList<>();
  final ScheduledExecutorService io = Executors.newScheduledThreadPool(3);
  final Handler main = new Handler(Looper.getMainLooper());
  SharedPreferences prefs;
  Vault vault;
  public String base,
      model = "",
      effort,
      prompt = "",
      mode = "text",
      quality = "auto",
      size = "1024x1024";
  public String notice = "", connectionError = "";
  public boolean manual, preview, refreshing, submitting, importing, localCatalog;
  public Protocol.Catalog catalog;
  long lastRefresh;
  volatile Job active;
  private ScheduledFuture<?> polling;
  private int failures;

  public record InputImage(File file, String name, String mime, int bytes) {}

  public static final class Job {
    public String id, base, url, mode, prompt, model, effort, status, message = "";
    public long created;
    public boolean tracking = true;
    public JSONObject result = new JSONObject();
    public List<File> files = new ArrayList<>();
    public List<File> references = new ArrayList<>();

    public String title() {
      return prompt.length() > 70 ? prompt.substring(0, 70) + "…" : prompt;
    }

    JSONObject json() throws JSONException {
      JSONArray paths = new JSONArray();
      for (File f : files) paths.put(f.getName());
      JSONArray refs = new JSONArray();
      for (File f : references) refs.put(f.getName());
      return new JSONObject()
          .put("id", id)
          .put("base", base)
          .put("url", url)
          .put("mode", mode)
          .put("prompt", prompt)
          .put("model", model)
          .put("effort", effort == null ? JSONObject.NULL : effort)
          .put("status", status)
          .put("tracking", tracking)
          .put("created", created)
          .put("result", result)
          .put("files", paths)
          .put("references", refs)
          .put("message", message);
    }

    static Job parse(JSONObject j, File dir) throws JSONException {
      Job x = new Job();
      x.id = j.getString("id");
      if (!x.id.matches("[A-Za-z0-9_-]{1,100}")) throw new JSONException("Invalid job ID");
      x.base = j.getString("base");
      x.url = j.getString("url");
      x.mode = j.getString("mode");
      x.prompt = j.getString("prompt");
      x.model = j.getString("model");
      x.effort = Protocol.nullable(j, "effort");
      x.status = j.getString("status");
      x.created = j.getLong("created");
      x.tracking = j.optBoolean("tracking", true);
      x.result = j.optJSONObject("result");
      if (x.result == null) x.result = new JSONObject();
      x.message = j.optString("message", "");
      JSONArray paths = j.optJSONArray("files");
      if (paths != null)
        for (int i = 0; i < paths.length(); i++) {
          String name = paths.getString(i);
          if (!name.matches("[A-Za-z0-9_.-]+")) continue;
          File f = new File(dir, name);
          if (f.isFile()) x.files.add(f);
        }
      JSONArray refs = j.optJSONArray("references");
      if (refs != null)
        for (int i = 0; i < refs.length(); i++) {
          String name = refs.getString(i);
          if (!name.matches("[A-Za-z0-9_.-]+")) continue;
          File f = new File(dir, name);
          if (f.isFile()) x.references.add(f);
        }
      return x;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    prefs = getSharedPreferences("prism", MODE_PRIVATE);
    vault = new Vault(this);
    base = prefs.getString("base", DEFAULT_BASE);
    loadSelection();
    loadCatalog();
    io.execute(
        () -> {
          List<Job> restored = new ArrayList<>();
          try {
            File file = new File(getFilesDir(), "jobs.json");
            if (file.isFile()) {
              JSONArray list =
                  new JSONArray(
                      new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
              for (int i = 0; i < list.length(); i++)
                restored.add(Job.parse(list.getJSONObject(i), artifactDir()));
            }
          } catch (Exception ignored) {
          }
          main.post(
              () -> {
                jobs.addAll(restored);
                emit("job");
                for (Job j : jobs)
                  if (j.tracking && j.base.equals(base) && !Protocol.terminal(j.status)) {
                    active = j;
                    resume(j);
                    break;
                  }
              });
        });
    // Input drafts are deliberately ephemeral. Remove abandoned private upload files after process
    // restart.
    io.execute(
        () -> {
          File[] abandoned =
              getCacheDir()
                  .listFiles((dir, name) -> name.startsWith("request-") && name.endsWith(".json"));
          if (abandoned != null) for (File f : abandoned) f.delete();
          File dir = new File(getCacheDir(), "inputs");
          File[] old = dir.listFiles();
          if (old != null) for (File f : old) f.delete();
        });
  }

  void emit(String event) {
    for (Listener listener : listeners) listener.changed(event);
  }

  private String key() {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
      return hex.substring(0, 20);
    } catch (Exception e) {
      return Integer.toHexString(base.hashCode());
    }
  }

  void loadSelection() {
    model = prefs.getString("model_" + key(), "");
    effort = prefs.getString("effort_" + key() + "_" + model, null);
    manual = prefs.getBoolean("manual_" + key(), false);
  }

  void loadCatalog() {
    catalog = null;
    localCatalog = false;
    try {
      String raw = prefs.getString("catalog_" + key(), "");
      if (!raw.isBlank()) {
        catalog = Protocol.parseCatalog(new JSONObject(raw));
        localCatalog = true;
      }
    } catch (Exception ignored) {
    }
  }

  public void select(String id, boolean byHand) {
    model = id;
    manual = byHand;
    String savedKey = "effort_" + key() + "_" + model;
    Protocol.Model m = catalog == null ? null : catalog.find(id);
    effort =
        prefs.contains(savedKey)
            ? prefs.getString(savedKey, null)
            : (m == null ? null : m.defaultLevel());
    remember();
    emit("selection");
  }

  public void chooseEffort(String selected) {
    effort = selected;
    remember();
    emit("selection");
  }

  private void remember() {
    if (preview) return;
    SharedPreferences.Editor editor =
        prefs.edit().putString("model_" + key(), model).putBoolean("manual_" + key(), manual);
    // A separate marker preserves an explicitly selected service default (null) across model
    // switches.
    editor.putString("effort_" + key() + "_" + model, effort == null ? "" : effort).apply();
  }

  public String selectedEffort() {
    return effort == null || effort.isEmpty() ? null : effort;
  }

  public String selectionError() {
    return mode.equals("image") ? Protocol.imageSelectionError(model)
        : Protocol.selectionError(catalog, model, selectedEffort(), manual);
  }

  public boolean hasToken() {
    try {
      return !vault.read().isBlank();
    } catch (Exception e) {
      connectionError = "安全存储读取失败，请重新填写 Token";
      return false;
    }
  }

  synchronized Api api(String endpoint) throws Exception {
    if (!endpoint.equals(base)) throw new Api.Failure(400, "服务连接已切换，请返回原服务后重新查询");
    String token = vault.read();
    if (token.isBlank()) throw new Api.Failure(401, "请先在连接设置中填写设备 Token");
    return new Api(endpoint, token);
  }

  public synchronized boolean configure(String endpoint, String token) {
    if (submitting || refreshing) {
      error("请先等待当前提交或目录查询结束");
      return false;
    }
    try {
      String normalized = Protocol.base(endpoint);
      if (active != null && !Protocol.terminal(active.status) && !normalized.equals(base)) {
        error("有正在跟踪的任务，请先等待完成或取消后再切换服务");
        return false;
      }
      if (!normalized.equals(base) && token == null) {
        error("切换服务时请重新输入 Token，避免将凭证发送到其它服务");
        return false;
      }
      if (token != null) {
        if (token.chars().anyMatch(Character::isWhitespace) || token.length() > 4096) {
          error("Token 中含空白字符或长度异常");
          return false;
        }
        vault.write(token);
      }
      base = normalized;
      prefs.edit().putString("base", base).apply();
      preview = false;
      loadSelection();
      loadCatalog();
      connectionError = "";
      notice = "连接已保存";
      lastRefresh = 0;
      emit("settings");
      refresh(false);
      return true;
    } catch (IllegalArgumentException e) {
      error(e.getMessage());
      return false;
    } catch (Exception e) {
      error("安全存储写入失败，Token 未保存，请重试");
      return false;
    }
  }

  public void refresh(boolean force) {
    if (preview || refreshing) return;
    if (force && System.currentTimeMillis() - lastRefresh < 15000) {
      error("手动刷新间隔至少 15 秒，稍后再试");
      return;
    }
    if (!hasToken()) {
      connectionError = "填写 Token 后可拉取实时模型目录";
      emit("catalog");
      return;
    }
    String endpoint = base;
    refreshing = true;
    connectionError = "";
    if (force) lastRefresh = System.currentTimeMillis();
    emit("catalog");
    io.execute(
        () -> {
          try {
            Protocol.Catalog fetched = api(endpoint).models(force);
            main.post(
                () -> {
                  if (!base.equals(endpoint) || preview) return;
                  catalog = fetched;
                  localCatalog = false;
                  refreshing = false;
                  prefs.edit().putString("catalog_" + key(), fetched.raw().toString()).apply();
                  // Never silently replace a previously saved selection.
                  if (model.isBlank()) {
                    Protocol.Model m = fetched.models().get(0);
                    model = m.id();
                    effort = m.defaultLevel();
                    manual = false;
                    remember();
                  }
                  notice = fetched.stale() ? "实时刷新失败，服务返回旧目录" : "模型目录已更新";
                  emit("catalog");
                });
          } catch (Exception e) {
            String message = friendly(e);
            main.post(
                () -> {
                  if (!base.equals(endpoint) || preview) return;
                  refreshing = false;
                  localCatalog = catalog != null;
                  connectionError = message;
                  emit("catalog");
                });
          }
        });
  }

  public void submit() {
    if (submitting || active != null && !Protocol.terminal(active.status)) {
      error("已有任务在运行，请先等待完成或取消");
      return;
    }
    if (preview) {
      error("当前为界面预览，退出预览并连接服务后可提交真实任务");
      return;
    }
    String invalid = selectionError();
    if (invalid != null) {
      error(invalid);
      return;
    }
    if (prompt.isBlank()) {
      error("写一点你希望模型完成的内容");
      return;
    }
    if (prompt.length() > 100000) {
      error("提示词过长，请缩短至 10 万字符以内");
      return;
    }
    if (mode.equals("vision") && inputs.isEmpty()) {
      error("识图需要至少一张参考图片");
      return;
    }
    Protocol.Model chosen = catalog == null ? null : catalog.find(model);
    if (!inputs.isEmpty() && chosen != null && !chosen.modalities().contains("image")) {
      error("该模型未声明图片输入能力，请选择支持 image 的模型");
      return;
    }
    if (!hasToken()) {
      error("请在连接设置中填写设备 Token");
      return;
    }
    Job job = new Job();
    job.base = base;
    job.mode = mode;
    job.prompt = prompt.trim();
    job.model = model;
    job.effort = selectedEffort();
    job.created = System.currentTimeMillis();
    job.status = "submitting";
    List<InputImage> images = List.copyOf(inputs);
    String imageSize = size, imageQuality = quality;
    submitting = true;
    notice = "正在上传并提交，请保持 App 打开";
    emit("job");
    io.execute(
        () -> {
          boolean sent = false;
          File payload = new File(getCacheDir(), "request-" + UUID.randomUUID() + ".json");
          try {
            Api client = api(job.base);
            JSONObject body =
                Protocol.request(
                    job.prompt,
                    job.model,
                    job.effort,
                    null,
                    job.mode.equals("image"),
                    imageSize,
                    imageQuality);
            List<Payload.Image> uploads = new ArrayList<>();
            for (InputImage image : images)
              uploads.add(new Payload.Image(image.file, image.name, image.mime));
            for (InputImage image : images) {
              String extension = image.file().getName().replaceFirst(".*(?=\\.[^.]+$)", "");
              File copy = new File(artifactDir(), "reference-" + UUID.randomUUID() + extension);
              job.references.add(copy);
              Files.copy(image.file().toPath(), copy.toPath());
            }
            Payload.write(payload, body, uploads);
            sent = true;
            JSONObject response = client.submit(payload, job.mode.equals("image"));
            job.id = response.getString("jobId");
            job.url = response.getString("statusUrl");
            job.status = response.getString("status");
            main.post(
                () -> {
                  submitting = false;
                  active = job;
                  jobs.add(0, job);
                  trimJobs();
                  persist();
                  emit("job");
                  failures = 0;
                  schedule(job, 1);
                });
          } catch (Exception e) {
            for (File reference : job.references) reference.delete();
            boolean uncertain = sent && !(e instanceof Api.Failure);
            String message =
                uncertain
                    ? "提交响应未收到，服务可能已创建任务。为避免重复计费，请先检查服务，再手动提交。"
                    : !sent && e instanceof IOException && !(e instanceof Api.Failure)
                        ? "参考图片或本机临时文件准备失败，请检查图片与可用空间。"
                        : friendly(e);
            main.post(
                () -> {
                  submitting = false;
                  error(message);
                  emit("job");
                });
          } finally {
            payload.delete();
          }
        });
  }

  private void trimJobs() {
    while (jobs.size() > 30) {
      Job old = jobs.remove(jobs.size() - 1);
      for (File f : old.files) f.delete();
      for (File f : old.references) f.delete();
    }
  }

  private void schedule(Job job, int delay) {
    if (polling != null) polling.cancel(false);
    polling = io.schedule(() -> poll(job), delay, TimeUnit.SECONDS);
  }

  private void poll(Job job) {
    if (active != job || Protocol.terminal(job.status)) return;
    try {
      JSONObject response = api(job.base).poll(job.url);
      String status = response.getString("status");
      if (!Set.of("queued", "running", "completed", "needs_input", "failed", "cancelled")
          .contains(status)) throw new JSONException("Unknown status");
      JSONObject raw = response.optJSONObject("result");
      JSONObject result = new JSONObject();
      if (raw != null) {
        for (String field :
            List.of(
                "text",
                "effectiveModel",
                "reasoningEffort",
                "artifacts",
                "usage",
                "questions",
                "backend",
                "taskId")) if (raw.has(field)) result.put(field, raw.get(field));
        JSONObject err = raw.optJSONObject("error");
        if (err != null) {
          String code = err.optString("code", "FAILED");
          result.put("errorCode", TaskFailure.code(code));
          result.put("errorMessage", TaskFailure.describe(code, job.model, job.effort));
        }
      }
      JSONObject finalResult = result;
      main.post(
          () -> {
            if (active != job) return;
            job.status = status;
            job.result = finalResult;
            job.message = "";
            failures = 0;
            persist();
            emit("job");
            if (Protocol.terminal(status)) {
              active = null;
              if (status.equals("completed")) download(job);
              else if (status.equals("needs_input"))
                job.message = "服务需要更多输入；此版本仅提供 Direct 测试，请调整提示词后创建新任务";
              persist();
              emit("job");
            } else schedule(job, status.equals("queued") ? 3 : 2);
          });
    } catch (Exception e) {
      String message = friendly(e);
      boolean stop = e instanceof Api.Failure f && Set.of(401, 403, 404, 410).contains(f.status);
      main.post(
          () -> {
            if (active != job) return;
            job.message = message + (stop ? "；点击重新查询可恢复跟踪" : "；自动重连中，不会重复提交");
            persist();
            emit("job");
            if (!stop) schedule(job, Math.min(30, 3 * ++failures));
          });
    }
  }

  public void resume(Job job) {
    if (preview || !base.equals(job.base)) {
      error("请先连接此任务所属的服务");
      return;
    }
    if (active != null && active != job && !Protocol.terminal(active.status)) {
      error("请先完成当前任务的跟踪");
      return;
    }
    if (Protocol.terminal(job.status)) {
      if (job.status.equals("completed")) download(job);
      return;
    }
    active = job;
    job.tracking = true;
    failures = 0;
    schedule(job, 0);
    notice = "正在重新查询任务";
    emit("job");
  }

  public void stopTracking(Job job) {
    job.tracking = false;
    if (active == job) {
      active = null;
      if (polling != null) polling.cancel(false);
    }
    job.message = "已停止本机跟踪，服务端任务状态尚未确认；可随时重新查询。";
    persist();
    emit("job");
  }

  public void cancel(Job job) {
    if (!base.equals(job.base)) {
      error("请先连接此任务所属的服务");
      return;
    }
    io.execute(
        () -> {
          try {
            api(job.base).cancel(job.url);
            main.post(
                () -> {
                  if (!Protocol.terminal(job.status)) {
                    active = job;
                    schedule(job, 0);
                  }
                });
          } catch (Exception e) {
            String message = friendly(e);
            main.post(() -> error(message));
          }
        });
  }

  public File artifactDir() {
    File dir = new File(getFilesDir(), "artifacts");
    dir.mkdirs();
    return dir;
  }

  private final Set<String> downloading = new HashSet<>();

  public void download(Job job) {
    if (!job.base.equals(base) || preview || !downloading.add(job.id)) return;
    job.message = "正在下载图片产物";
    emit("job");
    io.execute(
        () -> {
          List<File> files = new ArrayList<>();
          String failure = "";
          try {
            JSONArray artifacts = job.result.optJSONArray("artifacts");
            if (artifacts != null)
              for (int i = 0; i < artifacts.length(); i++) {
                JSONObject item = artifacts.getJSONObject(i);
                if (!item.optString("kind").equals("image")) continue;
                String mime = item.optString("mimeType", "image/png");
                String ext =
                    switch (mime) {
                      case "image/jpeg" -> ".jpg";
                      case "image/webp" -> ".webp";
                      case "image/gif" -> ".gif";
                      default -> ".png";
                    };
                File out = new File(artifactDir(), job.id + "-" + i + ext);
                if (!out.isFile()) {
                  byte[] bytes = api(job.base).artifact(item.getString("downloadUrl"));
                  File part = new File(out + ".part");
                  Files.write(part.toPath(), bytes);
                  Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                files.add(out);
              }
          } catch (Exception e) {
            failure = friendly(e);
          }
          String error = failure;
          main.post(
              () -> {
                downloading.remove(job.id);
                job.files = files;
                job.message = error;
                persist();
                emit("job");
              });
        });
  }

  public void importImages(List<Uri> uris) {
    if (importing) return;
    importing = true;
    emit("attachment");
    List<InputImage> existing = List.copyOf(inputs);
    io.execute(
        () -> {
          List<InputImage> added = new ArrayList<>();
          String failed = "";
          try {
            int total = existing.stream().mapToInt(InputImage::bytes).sum();
            File dir = new File(getCacheDir(), "inputs");
            dir.mkdirs();
            for (Uri uri : uris) {
              String mime = getContentResolver().getType(uri);
              if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
                  .contains(mime == null ? "" : mime))
                throw new IOException("请选择 PNG、JPEG、WebP 或 GIF 图片");
              byte[] bytes;
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException();
                bytes = Api.readLimited(in, Api.MAX_IMAGE);
              }
              total += bytes.length;
              if (total > Api.MAX_IMAGES) throw new IOException("参考图片合计超过 50 MiB，请减少图片");
              android.graphics.BitmapFactory.Options bounds =
                  new android.graphics.BitmapFactory.Options();
              bounds.inJustDecodeBounds = true;
              android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
              if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new IOException("图片格式损坏，请重新选择");
              String extension =
                  switch (mime) {
                    case "image/jpeg" -> ".jpg";
                    case "image/webp" -> ".webp";
                    case "image/gif" -> ".gif";
                    default -> ".png";
                  };
              File out = new File(dir, UUID.randomUUID() + extension);
              Files.write(out.toPath(), bytes);
              added.add(
                  new InputImage(
                      out,
                      "reference-" + (existing.size() + added.size() + 1) + extension,
                      mime,
                      bytes.length));
            }
          } catch (Exception e) {
            for (InputImage image : added) image.file.delete();
            added.clear();
            failed =
                e instanceof IOException && e.getMessage() != null && !e.getMessage().contains(":")
                    ? e.getMessage()
                    : "图片读取失败，请重新选择";
          }
          String message = failed;
          main.post(
              () -> {
                inputs.addAll(added);
                importing = false;
                if (!message.isEmpty()) error(message);
                emit("attachment");
              });
        });
  }

  public void removeInput(InputImage image) {
    if (submitting) {
      error("上传期间请保留参考图片，提交结束后可移除");
      return;
    }
    inputs.remove(image);
    image.file.delete();
    emit("attachment");
  }

  public void clearHistory() {
    if (active != null || submitting || !downloading.isEmpty()) {
      error("任务运行或下载期间请保留记录");
      return;
    }
    List<Job> removed = List.copyOf(jobs);
    jobs.clear();
    persist();
    emit("job");
    io.execute(
        () -> {
          for (Job job : removed) {
            for (File f : job.files) f.delete();
            for (File f : job.references) f.delete();
          }
        });
  }

  void persist() {
    if (preview) return;
    try {
      JSONArray list = new JSONArray();
      for (Job job : jobs) list.put(job.json());
      String data = list.toString();
      // Serial disk writes preserve task ordering, and atomic rename avoids partially written
      // history.
      historyWriter.execute(
          () -> {
            try {
              File temp = new File(getFilesDir(), "jobs.tmp");
              Files.write(temp.toPath(), data.getBytes(StandardCharsets.UTF_8));
              Files.move(
                  temp.toPath(),
                  new File(getFilesDir(), "jobs.json").toPath(),
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
              main.post(() -> error("任务记录保存失败，请检查可用空间"));
            }
          });
    } catch (JSONException ignored) {
    }
  }

  private final ExecutorService historyWriter = Executors.newSingleThreadExecutor();

  public void enterPreview(boolean enabled) {
    if (active != null || submitting || refreshing) {
      error("请先等待当前操作结束");
      return;
    }
    preview = enabled;
    if (enabled) {
      try {
        catalog =
            Protocol.parseCatalog(
                new JSONObject(
                    "{\"source\":\"ui-preview\",\"updatedAt\":null,\"stale\":false,\"models\":[{\"id\":\"demo-prism\",\"displayName\":\"Prism"
                        + " · 演示模型\",\"reasoningLevels\":[\"light\",\"focused\",\"deep\"],\"defaultReasoning\":\"focused\",\"inputModalities\":[\"text\",\"image\"]}]}"));
        model = "demo-prism";
        effort = "focused";
        manual = false;
        localCatalog = false;
      } catch (Exception ignored) {
      }
    } else {
      loadSelection();
      loadCatalog();
      refresh(false);
    }
    emit("settings");
  }

  public void error(String message) {
    notice = message;
    emit("error");
  }

  static String friendly(Exception e) {
    if (e instanceof Api.Failure) return e.getMessage();
    if (e instanceof java.net.UnknownHostException) return "服务域名解析失败，请检查手机网络或 DNS";
    if (e instanceof java.net.SocketTimeoutException) return "连接服务超时，请检查网络后重新查询";
    if (e instanceof javax.net.ssl.SSLException) return "HTTPS 握手或证书验证失败，请检查服务证书与系统时间";
    if (e instanceof IllegalArgumentException) return "服务地址或响应地址不符合预期，请检查 HTTPS 与路径前缀";
    if (e instanceof JSONException) return "服务响应格式异常，已保留已有目录与任务";
    return "网络连接中断或超时，请检查服务与网络";
  }
}
