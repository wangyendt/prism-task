package com.wayne.prismtask;

import static org.junit.Assert.*;
import org.junit.Test;

public class TaskFailureTest {
  @Test public void unsupportedReasoningNamesTheCombination() {
    String message = TaskFailure.describe("DIRECT_UNSUPPORTED_REASONING", "gpt-6.1-sol", "ultra");
    assertTrue(message.contains("gpt-6.1-sol + ultra"));
    assertTrue(message.contains("不支持"));
    assertTrue(message.contains("日后仍可再次尝试"));
  }
  @Test public void transientFailuresAreNotClassifiedAsUnsupported() {
    for (String code : new String[]{"DIRECT_RATE_LIMITED", "DIRECT_AUTH_FAILED", "DIRECT_TRANSPORT_ERROR", "DIRECT_SERVER_ERROR"}) {
      String message = TaskFailure.describe(code, "gpt-6.1-sol", "ultra");
      assertFalse(message.startsWith("当前接口不支持"));
    }
  }
  @Test public void rawErrorMessagesAndCredentialLikeCodesAreNeverReflected() {
    assertEquals("FAILED", TaskFailure.code("Bearer private-secret-value"));
    String message = TaskFailure.describe("Bearer private-secret-value", "id\nAuthorization: Bearer secret", "ultra");
    assertFalse(message.contains("private-secret-value"));
    assertFalse(message.contains("Authorization"));
  }
  @Test public void imageSelectionAndWireNeverBlockOrSubstituteUltra() throws Exception {
    assertNull(Protocol.imageSelectionError("gpt-6.1-sol"));
    var body = Protocol.request("paint", "gpt-6.1-sol", "ultra", null, true, "auto", "auto");
    assertEquals("gpt-6.1-sol", body.getString("model"));
    assertEquals("ultra", body.getString("reasoning"));
    assertNull(Protocol.imageSelectionError("future-model"));
    assertNotNull(Protocol.imageSelectionError(""));
  }
}
