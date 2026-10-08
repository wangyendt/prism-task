package com.wayne.prismtask;

import static org.junit.Assert.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class ApiTest {
  final String base = "https://example.test/services/codex-task";

  static class Fake implements Api.Transport {
    List<URI> urls = new ArrayList<>();
    List<String> methods = new ArrayList<>();
    List<String> tokens = new ArrayList<>();
    List<byte[]> bodies = new ArrayList<>();
    Deque<Api.Reply> replies = new ArrayDeque<>();

    Fake reply(int status, String body) {
      replies.add(new Api.Reply(status, body.getBytes(StandardCharsets.UTF_8)));
      return this;
    }

    public Api.Reply send(URI url, String method, String token, byte[] body, int max) {
      urls.add(url);
      methods.add(method);
      tokens.add(token);
      bodies.add(body);
      return replies.remove();
    }
  }

  @Test
  public void modelRefreshAuthenticatedAndReadOnly() throws Exception {
    Fake f = new Fake().reply(200, ProtocolTest.fixture("[]", "null").toString());
    Api api = new Api(base, "private-fixture", f);
    api.models(true);
    assertEquals(base + "/v1/models?refresh=true", f.urls.get(0).toString());
    assertEquals(List.of("GET"), f.methods);
    assertNull(f.bodies.get(0));
    assertEquals("private-fixture", f.tokens.get(0));
  }

  @Test
  public void asyncSubmitPollDownloadCancelContract() throws Exception {
    Fake f =
        new Fake()
            .reply(
                202, "{\"jobId\":\"job1\",\"status\":\"queued\",\"statusUrl\":\"/v1/jobs/job1\"}")
            .reply(200, "{\"status\":\"running\"}")
            .reply(200, "{\"status\":\"completed\",\"result\":{\"effectiveModel\":\"compatible\"}}")
            .reply(200, "image-bytes")
            .reply(200, "{\"status\":\"cancelled\"}");
    Api api = new Api(base, "private-fixture", f);
    JSONObject response = api.submit(new JSONObject().put("model", "fixture"), true);
    assertEquals("queued", response.getString("status"));
    assertEquals("running", api.poll(response.getString("statusUrl")).getString("status"));
    assertEquals(
        "compatible",
        api.poll(response.getString("statusUrl"))
            .getJSONObject("result")
            .getString("effectiveModel"));
    assertEquals(
        "image-bytes",
        new String(api.artifact("/v1/jobs/job1/artifacts/0"), StandardCharsets.UTF_8));
    api.cancel("/v1/jobs/job1");
    assertEquals(base + "/v1/image", f.urls.get(0).toString());
    assertEquals(base + "/v1/jobs/job1/cancel", f.urls.get(4).toString());
    assertEquals(List.of("POST", "GET", "GET", "GET", "POST"), f.methods);
    assertEquals(5, f.tokens.size());
  }

  @Test
  public void errorBodyNeverLeaksSecrets() throws Exception {
    for (int code : List.of(400, 401, 403, 404, 410, 413, 429, 500, 503, 302)) {
      Fake f = new Fake().reply(code, "SECRET echoed token from untrusted upstream");
      try {
        new Api(base, "private-fixture", f).models(false);
        fail();
      } catch (Api.Failure e) {
        assertEquals(code, e.status);
        assertFalse(e.getMessage().contains("SECRET"));
        assertFalse(e.getMessage().contains("private-fixture"));
      }
    }
  }

  @Test
  public void untrustedArtifactRejectedBeforeTokenSent() throws Exception {
    Fake f = new Fake();
    try {
      new Api(base, "private-fixture", f).artifact("https://evil.test/v1/jobs/x");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(f.urls.isEmpty());
    }
  }

  @Test
  public void noAutoRetryForSubmission() throws Exception {
    Fake f = new Fake().reply(503, "{}");
    try {
      new Api(base, "private-fixture", f).submit(new JSONObject(), false);
      fail();
    } catch (Api.Failure expected) {
      assertEquals(1, f.urls.size());
    }
  }

  @Test
  public void malformedModelResponseRejected() throws Exception {
    Fake f = new Fake().reply(200, "{\"models\":\"invalid\"}");
    try {
      new Api(base, "private-fixture", f).models(false);
      fail();
    } catch (JSONException expected) {
    }
  }

  @Test
  public void bodyBoundedBeforeFurtherAllocation() throws Exception {
    try {
      Api.readLimited(new ByteArrayInputStream(new byte[10]), 9);
      fail();
    } catch (IOException expected) {
      assertEquals("内容超过大小限制", expected.getMessage());
    }
    assertEquals(10, Api.readLimited(new ByteArrayInputStream(new byte[10]), 10).length);
  }
}
