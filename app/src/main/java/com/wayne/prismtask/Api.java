package com.wayne.prismtask;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** HTTPS only, no redirects, no logging of requests/headers/raw errors. */
public final class Api {
  public static final int MAX_IMAGE = 20 * 1024 * 1024, MAX_IMAGES = 50 * 1024 * 1024;

  public static class Failure extends IOException {
    public final int status;

    public Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  public interface Transport {
    Reply send(URI url, String method, String token, byte[] body, int maxBytes) throws IOException;

    default Reply sendFile(URI url, String token, File body, int maxBytes) throws IOException {
      return send(url, "POST", token, java.nio.file.Files.readAllBytes(body.toPath()), maxBytes);
    }
  }

  public record Reply(int status, byte[] data) {}

  private final String base, token;
  private final Transport transport;

  public Api(String base, String token) {
    this(base, token, new HttpsTransport());
  }

  public Api(String base, String token, Transport transport) {
    this.base = Protocol.base(base);
    this.token = token;
    this.transport = transport;
  }

  public Protocol.Catalog models(boolean refresh) throws IOException, JSONException {
    return Protocol.parseCatalog(
        json(
            Protocol.endpoint(base, "v1/models" + (refresh ? "?refresh=true" : "")),
            "GET",
            null,
            200));
  }

  public JSONObject submit(JSONObject body, boolean image) throws IOException, JSONException {
    JSONObject result =
        json(Protocol.endpoint(base, image ? "v1/image" : "v1/text"), "POST", body, 202);
    return submission(result);
  }

  public JSONObject submit(File body, boolean image) throws IOException, JSONException {
    Reply reply =
        transport.sendFile(
            Protocol.endpoint(base, image ? "v1/image" : "v1/text"), token, body, 8 * 1024 * 1024);
    if (reply.status != 202) throw failure(reply.status);
    return submission(new JSONObject(new String(reply.data, StandardCharsets.UTF_8)));
  }

  private JSONObject submission(JSONObject result) throws JSONException {
    String jobId = result.getString("jobId");
    if (!jobId.matches("[A-Za-z0-9_-]{1,100}")) throw new JSONException("Invalid job ID");
    Protocol.serviceLink(base, result.getString("statusUrl"));
    return result;
  }

  public JSONObject poll(String url) throws IOException, JSONException {
    return json(Protocol.serviceLink(base, url), "GET", null, 200);
  }

  public void cancel(String url) throws IOException, JSONException {
    json(Protocol.serviceLink(base, url + "/cancel"), "POST", new JSONObject(), 200);
  }

  public byte[] artifact(String url) throws IOException {
    Reply reply = transport.send(Protocol.serviceLink(base, url), "GET", token, null, MAX_IMAGES);
    if (reply.status != 200) throw failure(reply.status);
    return reply.data;
  }

  private JSONObject json(URI url, String method, JSONObject body, int expected)
      throws IOException, JSONException {
    Reply reply =
        transport.send(
            url,
            method,
            token,
            body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8),
            8 * 1024 * 1024);
    if (reply.status != expected) throw failure(reply.status);
    return new JSONObject(new String(reply.data, StandardCharsets.UTF_8));
  }

  private static Failure failure(int status) {
    String message =
        switch (status) {
          case 401 -> "Token 无效或已被吊销，请在连接设置中更新";
          case 403 -> "该 Token 没有此操作权限，请使用允许 text,image 的设备 Token";
          case 404, 410 -> "任务或图片已过期，或服务已重启；本地已保存的结果仍可查看";
          case 413 -> "上传内容过大，请减少参考图片";
          case 429 -> "服务请求较频繁，请稍后重试";
          case 503 -> "服务暂不可用，已保留本地目录与选择";
          case 400 -> "服务拒绝了请求参数，请检查模型、思考等级和图片设置";
          default -> "服务请求失败（HTTP " + status + "），请检查连接";
        };
    return new Failure(status, message);
  }

  public static byte[] readLimited(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if (out.size() + n > limit) throw new IOException("内容超过大小限制");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  public static final class HttpsTransport implements Transport {
    @Override
    public Reply send(URI url, String method, String token, byte[] body, int maxBytes)
        throws IOException {
      return exchange(url, method, token, body, null, maxBytes);
    }

    @Override
    public Reply sendFile(URI url, String token, File body, int maxBytes) throws IOException {
      return exchange(url, "POST", token, null, body, maxBytes);
    }

    private Reply exchange(
        URI url, String method, String token, byte[] body, File file, int maxBytes)
        throws IOException {
      HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(45000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      try {
        if (body != null || file != null) {
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type", "application/json");
          connection.setFixedLengthStreamingMode(file == null ? body.length : file.length());
          try (OutputStream out = connection.getOutputStream()) {
            if (file == null) out.write(body);
            else
              try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
              }
          }
        }
        int status = connection.getResponseCode();
        // Never parse or reflect a server error body, which may echo credentials or private data.
        if (status < 200 || status >= 300) return new Reply(status, new byte[0]);
        try (InputStream in = connection.getInputStream()) {
          return new Reply(status, readLimited(in, maxBytes));
        }
      } finally {
        connection.disconnect();
      }
    }
  }
}
