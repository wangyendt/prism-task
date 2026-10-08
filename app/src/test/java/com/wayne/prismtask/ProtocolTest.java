package com.wayne.prismtask;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class ProtocolTest {
  static final String BASE = "https://example.test/services/codex-task";

  static JSONObject fixture(String levels, String def) throws Exception {
    return new JSONObject(
        "{\"source\":\"test\",\"updatedAt\":null,\"stale\":false,\"models\":[{\"id\":\"future-model\",\"displayName\":\"Future\",\"reasoningLevels\":"
            + levels
            + ",\"defaultReasoning\":"
            + def
            + ",\"inputModalities\":[\"text\",\"image\"]}]}");
  }

  @Test
  public void preservesProxyPrefix() {
    assertEquals(BASE + "/v1/models", Protocol.endpoint(BASE + "///", "v1/models").toString());
  }

  @Test
  public void rootRelativeStatusKeepsPrefix() {
    assertEquals(BASE + "/v1/jobs/123", Protocol.serviceLink(BASE, "/v1/jobs/123").toString());
  }

  @Test
  public void rootRelativeArtifactKeepsPrefix() {
    assertEquals(
        BASE + "/v1/jobs/123/artifacts/0",
        Protocol.serviceLink(BASE, "/v1/jobs/123/artifacts/0").toString());
  }

  @Test
  public void acceptsAlreadyPrefixedUrl() {
    assertEquals(
        BASE + "/v1/jobs/123",
        Protocol.serviceLink(BASE, "/services/codex-task/v1/jobs/123").toString());
  }

  @Test
  public void acceptsSameServiceAbsoluteUrl() {
    assertEquals(
        BASE + "/v1/jobs/123", Protocol.serviceLink(BASE, BASE + "/v1/jobs/123").toString());
  }

  @Test
  public void blocksHostPortPathSchemeAndTraversal() {
    for (String value :
        List.of(
            "https://evil.test/v1/jobs/x",
            "//evil.test/v1/jobs/x",
            "https://example.test:443/services/codex-task/v1/jobs/x",
            "/other/v1/jobs/x",
            "/v1/jobs/../models",
            "/v1/jobs/%2e%2e/models",
            "http://example.test/services/codex-task/v1/jobs/x",
            "/v1/jobs/x?token=test")) {
      try {
        Protocol.serviceLink(BASE, value);
        fail("Should reject " + value);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void rejectsUnsafeBases() {
    for (String base :
        List.of(
            "http://example.test",
            "https://user:pass@example.test",
            "https://example.test/?token=x",
            "https://example.test/#x",
            "https://example.test/a/../b",
            "https://example.test/%61",
            "https://example.test//path")) {
      try {
        Protocol.base(base);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void retainsFutureEffortAndOrder() throws Exception {
    Protocol.Catalog c =
        Protocol.parseCatalog(fixture("[\"quantum\",\"low\",\"ultra-plus\"]", "\"quantum\""));
    assertEquals(List.of("quantum", "low", "ultra-plus"), c.models().get(0).levels());
    assertEquals("quantum", c.models().get(0).defaultLevel());
  }

  @Test
  public void supportsNullDefaultAndEmptyEfforts() throws Exception {
    Protocol.Catalog c = Protocol.parseCatalog(fixture("[]", "null"));
    assertNull(c.models().get(0).defaultLevel());
    assertTrue(c.models().get(0).levels().isEmpty());
  }

  @Test
  public void preservesStaleMetadata() throws Exception {
    JSONObject json = fixture("[]", "null").put("stale", true).put("source", "disk-cache");
    Protocol.Catalog c = Protocol.parseCatalog(json);
    assertTrue(c.stale());
    assertEquals("disk-cache", c.source());
    assertNull(c.updatedAt());
  }

  @Test(expected = JSONException.class)
  public void rejectsEmptyCatalog() throws Exception {
    Protocol.parseCatalog(
        new JSONObject("{\"source\":\"test\",\"updatedAt\":null,\"stale\":false,\"models\":[]}"));
  }

  @Test(expected = JSONException.class)
  public void rejectsInvalidEffortType() throws Exception {
    Protocol.parseCatalog(fixture("[2]", "null"));
  }

  @Test(expected = JSONException.class)
  public void rejectsInvalidDefault() throws Exception {
    Protocol.parseCatalog(fixture("[\"low\"]", "\"ultra\""));
  }

  @Test(expected = JSONException.class)
  public void rejectsDuplicateModels() throws Exception {
    JSONObject json = fixture("[]", "null");
    json.getJSONArray("models").put(json.getJSONArray("models").get(0));
    Protocol.parseCatalog(json);
  }

  @Test
  public void invalidSavedEffortRequiresReselection() throws Exception {
    Protocol.Catalog c = Protocol.parseCatalog(fixture("[\"low\"]", "\"low\""));
    assertNotNull(Protocol.selectionError(c, "future-model", "removed", false));
    assertNull(Protocol.selectionError(c, "future-model", "low", false));
  }

  @Test
  public void removedModelRequiresExplicitManualChoice() throws Exception {
    Protocol.Catalog c = Protocol.parseCatalog(fixture("[]", "null"));
    assertNotNull(Protocol.selectionError(c, "new-model", null, false));
    assertNull(Protocol.selectionError(c, "new-model", null, true));
  }

  @Test
  public void omittedEffortIsServiceDefault() throws Exception {
    JSONObject request = Protocol.request("hi", "future-model", null, null, false, "auto", "auto");
    assertFalse(request.has("reasoning"));
    assertEquals("direct", request.getString("backend"));
  }

  @Test
  public void declaredDefaultIsExplicitlyTransmitted() throws Exception {
    JSONObject request =
        Protocol.request("hi", "future-model", "quantum", null, false, "auto", "auto");
    assertEquals("quantum", request.getString("reasoning"));
  }

  @Test
  public void imageUploadAndOutputOptions() throws Exception {
    JSONArray images =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("name", "test.png")
                    .put("mimeType", "image/png")
                    .put("dataBase64", "DATA"));
    JSONObject request =
        Protocol.request("paint", "future-model", "high", images, true, "1024x1536", "high");
    assertEquals("DATA", request.getJSONArray("images").getJSONObject(0).getString("dataBase64"));
    assertEquals(1, request.getInt("count"));
    assertEquals("1024x1536", request.getString("size"));
    assertFalse(request.has("output"));
  }

  @Test
  public void visionUsesTextRequestWithoutImageGenerationOptions() throws Exception {
    JSONObject request =
        Protocol.request(
            "describe",
            "future-model",
            null,
            new JSONArray().put(new JSONObject()),
            false,
            "auto",
            "high");
    assertTrue(request.has("images"));
    assertFalse(request.has("count"));
    assertFalse(request.has("quality"));
  }

  @Test
  public void terminalStates() {
    for (String s : List.of("completed", "failed", "cancelled", "needs_input"))
      assertTrue(Protocol.terminal(s));
    assertFalse(Protocol.terminal("queued"));
    assertFalse(Protocol.terminal("running"));
  }
}
