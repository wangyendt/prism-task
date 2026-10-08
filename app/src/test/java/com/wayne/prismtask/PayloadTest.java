package com.wayne.prismtask;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class PayloadTest {
  @Test
  public void streamedPayloadPreservesUnicodeAndBase64Padding() throws Exception {
    File input = File.createTempFile("prism-input", ".png"),
        out = File.createTempFile("prism-payload", ".json");
    try {
      byte[] bytes = {1, 2, 3, 4, 5};
      Files.write(input.toPath(), bytes);
      Payload.write(
          out,
          new JSONObject().put("prompt", "中文 \"quoted\"").put("backend", "direct"),
          List.of(
              new Payload.Image(input, "参考\".png", "image/png"),
              new Payload.Image(input, "second.png", "image/png")));
      JSONObject json =
          new JSONObject(
              new String(
                  Files.readAllBytes(out.toPath()), java.nio.charset.StandardCharsets.UTF_8));
      assertEquals("中文 \"quoted\"", json.getString("prompt"));
      assertEquals(2, json.getJSONArray("images").length());
      assertEquals("参考\".png", json.getJSONArray("images").getJSONObject(0).getString("name"));
      assertArrayEquals(
          bytes,
          Base64.getDecoder()
              .decode(json.getJSONArray("images").getJSONObject(1).getString("dataBase64")));
    } finally {
      input.delete();
      out.delete();
    }
  }

  @Test
  public void noImagesMeansNoImagesProperty() throws Exception {
    File out = File.createTempFile("prism-payload", ".json");
    try {
      Payload.write(out, new JSONObject().put("model", "future"), List.of());
      JSONObject json =
          new JSONObject(
              new String(
                  Files.readAllBytes(out.toPath()), java.nio.charset.StandardCharsets.UTF_8));
      assertFalse(json.has("images"));
    } finally {
      out.delete();
    }
  }

  @Test
  public void oversizeFileRejectedBeforeEncoding() throws Exception {
    File input = File.createTempFile("prism-input", ".png"),
        out = File.createTempFile("prism-payload", ".json");
    try (RandomAccessFile f = new RandomAccessFile(input, "rw")) {
      f.setLength(Api.MAX_IMAGE + 1L);
    }
    try {
      Payload.write(
          out, new JSONObject(), List.of(new Payload.Image(input, "test.png", "image/png")));
      fail();
    } catch (IOException expected) {
    } finally {
      input.delete();
      out.delete();
    }
  }

  @Test
  public void diskPayloadUsesSameAsyncContract() throws Exception {
    File out = File.createTempFile("prism-payload", ".json");
    try {
      Payload.write(out, new JSONObject().put("prompt", "hello"), List.of());
      ApiTest.Fake fake =
          new ApiTest.Fake()
              .reply(
                  202,
                  "{\"jobId\":\"job1\",\"statusUrl\":\"/v1/jobs/job1\",\"status\":\"queued\"}");
      assertEquals(
          "queued",
          new Api(ProtocolTest.BASE, "private-fixture", fake)
              .submit(out, false)
              .getString("status"));
      assertEquals(
          "hello",
          new JSONObject(new String(fake.bodies.get(0), java.nio.charset.StandardCharsets.UTF_8))
              .getString("prompt"));
    } finally {
      out.delete();
    }
  }
}
