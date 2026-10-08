package com.wayne.prismtask;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Disk-backed JSON/Base64 payload: 50 MiB of images need not become several heap copies. */
final class Payload {
  record Image(File file, String name, String mime) {}

  static void write(File target, JSONObject metadata, List<Image> images)
      throws IOException, JSONException {
    long total = 0;
    for (Image image : images) {
      long bytes = image.file.length();
      if (bytes > Api.MAX_IMAGE) throw new IOException("单张图片超过 20 MiB");
      total += bytes;
      if (total > Api.MAX_IMAGES) throw new IOException("参考图片合计超过 50 MiB");
    }
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
      String json = metadata.toString();
      if (images.isEmpty()) {
        utf8(out, json);
        return;
      }
      utf8(
          out,
          json.substring(0, json.length() - 1)
              + (metadata.length() > 0 ? "," : "")
              + "\"images\":[");
      for (int i = 0; i < images.size(); i++) {
        if (i > 0) utf8(out, ",");
        Image image = images.get(i);
        String entry =
            new JSONObject().put("name", image.name).put("mimeType", image.mime).toString();
        utf8(out, entry.substring(0, entry.length() - 1) + ",\"dataBase64\":\"");
        OutputStream unclosed =
            new FilterOutputStream(out) {
              @Override
              public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
              }

              @Override
              public void close() throws IOException {
                flush();
              }
            };
        try (InputStream in = new FileInputStream(image.file);
            OutputStream encoder = Base64.getEncoder().wrap(unclosed)) {
          byte[] buffer = new byte[8192];
          int n;
          long read = 0;
          while ((n = in.read(buffer)) != -1) {
            read += n;
            if (read > Api.MAX_IMAGE) throw new IOException("图片在读取时超过大小限制");
            encoder.write(buffer, 0, n);
          }
        }
        utf8(out, "\"}");
      }
      utf8(out, "]}");
    } catch (IOException | JSONException e) {
      target.delete();
      throw e;
    }
  }

  private static void utf8(OutputStream out, String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.UTF_8));
  }
}
