package com.wayne.prismtask;

import static org.junit.Assert.*;

import java.time.Instant;
import org.json.*;
import org.junit.Test;

public class ImageRoutingTest {
  JSONObject catalog(JSONArray choices) throws Exception {
    return new JSONObject()
        .put(
            "imageRouting",
            new JSONObject()
                .put("version", 1)
                .put("source", "codex-task-router")
                .put("backend", "direct")
                .put("updatedAt", Instant.now().toString())
                .put(
                    "models",
                    new JSONArray()
                        .put(new JSONObject().put("id", "future").put("choices", choices))));
  }

  JSONObject ready(Object selected, String model, String effort, boolean fallback)
      throws Exception {
    return new JSONObject()
        .put("reasoning", selected)
        .put("status", "ready")
        .put("effectiveModel", model)
        .put("effectiveReasoning", effort)
        .put("fallback", fallback);
  }

  @Test
  public void readsFutureEffortsAndServerChosenTargetWithoutEnums() throws Exception {
    ImageRouting.Choice r =
        ImageRouting.find(
            catalog(
                new JSONArray().put(ready("future-effort", "replacement", "future-effort", true))),
            "future",
            "future-effort");
    assertNotNull(r);
    assertEquals("replacement", r.model());
    assertTrue(r.fallback());
    assertTrue(r.fresh(System.currentTimeMillis()));
  }

  @Test
  public void serviceDefaultIsNotModelDefaultOrFirstChoice() throws Exception {
    JSONArray choices =
        new JSONArray()
            .put(ready("low", "future", "low", false))
            .put(ready(JSONObject.NULL, "future", "medium", false));
    ImageRouting.Choice r = ImageRouting.find(catalog(choices), "future", null);
    assertEquals("medium", r.effort());
  }

  @Test
  public void unsupportedRouteDoesNotInventEffectiveModel() throws Exception {
    ImageRouting.Choice r =
        ImageRouting.find(
            catalog(
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("reasoning", "max")
                            .put("status", "unsupported")
                            .put("errorCode", "USAGE_ERROR"))),
            "future",
            "max");
    assertTrue(r.unsupported());
    assertNull(r.model());
  }

  @Test
  public void missingUnknownVersionOrBackendMetadataDoesNotGateOldServers() throws Exception {
    assertNull(ImageRouting.find(new JSONObject(), "future", "low"));
    JSONObject c = catalog(new JSONArray().put(ready("low", "future", "low", false)));
    c.getJSONObject("imageRouting").put("version", 2);
    assertNull(ImageRouting.find(c, "future", "low"));
    c.getJSONObject("imageRouting").put("version", 1).put("backend", "sdk");
    assertNull(ImageRouting.find(c, "future", "low"));
  }

  @Test
  public void rejectsAmbiguousTargetsOrUnexpectedEffortReplacement() throws Exception {
    assertNull(
        ImageRouting.find(
            catalog(new JSONArray().put(ready("max", "replacement", "high", true))),
            "future",
            "max"));
    JSONObject ready = ready("low", "future", "low", false);
    assertNull(ImageRouting.find(catalog(new JSONArray().put(ready).put(ready)), "future", "low"));
    assertNull(
        ImageRouting.find(
            catalog(new JSONArray().put(ready("low", "replacement", "low", false))),
            "future",
            "low"));
  }

  @Test
  public void localAgeOrTargetChangeInvalidatesConfirmation() {
    ImageRouting.Choice old =
        new ImageRouting.Choice("ready", "target", "low", true, "2000-01-01T00:00:00Z");
    assertFalse(old.fresh(System.currentTimeMillis()));
    assertFalse(
        old.sameTarget(new ImageRouting.Choice("ready", "other", "low", true, old.updatedAt())));
    assertTrue(
        old.sameTarget(
            new ImageRouting.Choice("ready", "target", "low", true, Instant.now().toString())));
  }
}
