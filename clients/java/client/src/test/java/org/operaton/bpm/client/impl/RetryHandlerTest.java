/*
 * Copyright 2026 the Operaton contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.bpm.client.impl;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.client.task.ExternalTaskBusinessError;
import org.operaton.bpm.client.task.ExternalTaskService;
import org.operaton.bpm.client.task.InstantIncidentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryHandlerTest {

  RetryConfiguration defaultConfig;
  RetryHandler retryHandler;
  ExternalTask externalTask;
  ExternalTaskService externalTaskService;

  @BeforeEach
  void setUp() {
    defaultConfig = new RetryConfiguration(true,
        RetryConfiguration.DEFAULT_RETRY_TIME_CYCLE,
        RetryConfiguration.DEFAULT_RETRY_CONFIG_IDENTIFIER);
    retryHandler = new RetryHandler(defaultConfig);
    externalTask = mock(ExternalTask.class);
    externalTaskService = mock(ExternalTaskService.class);

    when(externalTask.getTopicName()).thenReturn("testTopic");
    when(externalTask.getId()).thenReturn("task-123");
  }

  // ---- Retry Count Parsing ----

  @Nested
  class ParseRetryCount {

    @Test
    void parsesRepeatingCycle() {
      assertThat(retryHandler.parseRetryCount("R3/PT5M", true)).isEqualTo(3);
      assertThat(retryHandler.parseRetryCount("R5/PT10M", true)).isEqualTo(5);
      assertThat(retryHandler.parseRetryCount("R1/P1D", true)).isEqualTo(1);
    }

    @Test
    void parsesIntervalList() {
      assertThat(retryHandler.parseRetryCount("PT5M", true)).isEqualTo(1);
      assertThat(retryHandler.parseRetryCount("PT5M,PT10M", true)).isEqualTo(2);
      assertThat(retryHandler.parseRetryCount("PT5M,PT10M,PT1H", true)).isEqualTo(3);
    }

    @Test
    void fallsBackToDefaultOnInvalidInput() {
      // Invalid config should fall back to default R3/PT5M = 3
      assertThat(retryHandler.parseRetryCount("INVALID", true)).isEqualTo(3);
    }
  }

  // ---- Remaining Retries Computation ----

  @Nested
  class ComputeRemainingRetries {

    @Test
    void firstFailureWithNullRetries_usesRepeatingCycleConfig() {
      // R3/PT5M -> first failure sets retries = 3 (matching viadee behavior)
      assertThat(retryHandler.computeRemainingRetries(null, "R3/PT5M")).isEqualTo(3);
    }

    @Test
    void firstFailureWithNullRetries_usesIntervalListConfig() {
      // PT5M,PT10M,PT1H -> 3 intervals = 3 retries
      assertThat(retryHandler.computeRemainingRetries(null, "PT5M,PT10M,PT1H")).isEqualTo(3);
    }

    @Test
    void subsequentFailure_decrementsRetries() {
      assertThat(retryHandler.computeRemainingRetries(3, "R3/PT5M")).isEqualTo(2);
      assertThat(retryHandler.computeRemainingRetries(2, "R3/PT5M")).isEqualTo(1);
      assertThat(retryHandler.computeRemainingRetries(1, "R3/PT5M")).isEqualTo(0);
    }

    @Test
    void zeroRetries_staysAtZero() {
      assertThat(retryHandler.computeRemainingRetries(0, "R3/PT5M")).isEqualTo(0);
    }

    @Test
    void negativeRetries_clampsToZero() {
      assertThat(retryHandler.computeRemainingRetries(-1, "R3/PT5M")).isEqualTo(0);
    }
  }

  // ---- Retry Interval Computation ----

  @Nested
  class ComputeNextRetryInterval {

    @Test
    void zeroRemainingRetries_returnsZero() {
      assertThat(retryHandler.computeNextRetryInterval(0, "R3/PT5M")).isEqualTo(0L);
    }

    @Test
    void repeatingCycle_returnsSameInterval() {
      // R3/PT5M -> 5 minutes = 300000ms for every retry
      assertThat(retryHandler.computeNextRetryInterval(3, "R3/PT5M")).isEqualTo(300_000L);
      assertThat(retryHandler.computeNextRetryInterval(2, "R3/PT5M")).isEqualTo(300_000L);
      assertThat(retryHandler.computeNextRetryInterval(1, "R3/PT5M")).isEqualTo(300_000L);
    }

    @Test
    void intervalList_returnsCorrectIntervalPerRetry() {
      // PT1M,PT2M,PT1H = 3 retries with increasing intervals
      // 3 remaining -> PT1M (60s)
      // 2 remaining -> PT2M (120s)
      // 1 remaining -> PT1H (3600s)
      assertThat(retryHandler.computeNextRetryInterval(3, "PT1M,PT2M,PT1H")).isEqualTo(60_000L);
      assertThat(retryHandler.computeNextRetryInterval(2, "PT1M,PT2M,PT1H")).isEqualTo(120_000L);
      assertThat(retryHandler.computeNextRetryInterval(1, "PT1M,PT2M,PT1H")).isEqualTo(3_600_000L);
    }

    @Test
    void intervalList_moreRemainingThanIntervals_usesFirstInterval() {
      // If remaining retries exceeds list length, use the first interval
      assertThat(retryHandler.computeNextRetryInterval(5, "PT1M,PT2M")).isEqualTo(60_000L);
    }

    @Test
    void invalidConfig_fallsBackToDefault() {
      // Invalid config should eventually fall back to R3/PT5M -> 300000ms
      assertThat(retryHandler.computeNextRetryInterval(2, "INVALID")).isEqualTo(300_000L);
    }
  }

  // ---- Interval Parsing ----

  @Nested
  class ParseIntervalToMillis {

    @Test
    void parsesMinutes() {
      assertThat(retryHandler.parseIntervalToMillis("PT5M")).isEqualTo(300_000L);
    }

    @Test
    void parsesHours() {
      assertThat(retryHandler.parseIntervalToMillis("PT2H")).isEqualTo(7_200_000L);
    }

    @Test
    void parsesDay() {
      assertThat(retryHandler.parseIntervalToMillis("P1D")).isEqualTo(86_400_000L);
    }

    @Test
    void parsesComplex() {
      // PT1H30M = 90 minutes = 5400000ms
      assertThat(retryHandler.parseIntervalToMillis("PT1H30M")).isEqualTo(5_400_000L);
    }

    @Test
    void invalidInterval_returnsFallback() {
      assertThat(retryHandler.parseIntervalToMillis("INVALID")).isEqualTo(RetryHandler.FALLBACK_INTERVAL_MS);
    }
  }

  // ---- Retry Time Cycle Resolution ----

  @Nested
  class ResolveRetryTimeCycle {

    @Test
    void usesExtensionPropertyWhenAvailable() {
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn("R5/PT10M");
      assertThat(retryHandler.resolveRetryTimeCycle(externalTask)).isEqualTo("R5/PT10M");
    }

    @Test
    void usesDefaultWhenExtensionPropertyIsNull() {
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn(null);
      assertThat(retryHandler.resolveRetryTimeCycle(externalTask)).isEqualTo("R3/PT5M");
    }

    @Test
    void usesDefaultWhenExtensionPropertyIsBlank() {
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn("  ");
      assertThat(retryHandler.resolveRetryTimeCycle(externalTask)).isEqualTo("R3/PT5M");
    }

    @Test
    void usesCustomIdentifier() {
      RetryConfiguration customConfig = new RetryConfiguration(true, "R3/PT5M", "MY_RETRY");
      RetryHandler customHandler = new RetryHandler(customConfig);

      when(externalTask.getExtensionProperty("MY_RETRY")).thenReturn("R2/PT1H");
      assertThat(customHandler.resolveRetryTimeCycle(externalTask)).isEqualTo("R2/PT1H");
    }

    @Test
    void usesCustomDefaultRetryTimeCycle() {
      RetryConfiguration customConfig = new RetryConfiguration(true, "R10/PT1M", "RETRY_CONFIG");
      RetryHandler customHandler = new RetryHandler(customConfig);

      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn(null);
      assertThat(customHandler.resolveRetryTimeCycle(externalTask)).isEqualTo("R10/PT1M");
    }
  }

  // ---- Error Message Formatting ----

  @Nested
  class FormatErrorMessage {

    @Test
    void regularException() {
      RuntimeException ex = new RuntimeException("something broke");
      assertThat(retryHandler.formatErrorMessage(ex))
          .isEqualTo("RuntimeException: something broke");
    }

    @Test
    void instantIncident_noMessageNoCause() {
      InstantIncidentException ex = new InstantIncidentException();
      assertThat(retryHandler.formatErrorMessage(ex)).isEqualTo("InstantIncident");
    }

    @Test
    void instantIncident_withMessage() {
      InstantIncidentException ex = new InstantIncidentException("oops");
      assertThat(retryHandler.formatErrorMessage(ex)).isEqualTo("InstantIncident: oops");
    }

    @Test
    void instantIncident_withCause() {
      InstantIncidentException ex = new InstantIncidentException(new IllegalStateException("bad state"));
      assertThat(retryHandler.formatErrorMessage(ex))
          .isEqualTo("IllegalStateException: bad state (InstantIncident)");
    }

    @Test
    void instantIncident_withMessageAndCause() {
      InstantIncidentException ex = new InstantIncidentException("oops",
          new IllegalStateException("bad state"));
      assertThat(retryHandler.formatErrorMessage(ex))
          .isEqualTo("IllegalStateException: bad state (InstantIncident: oops)");
    }
  }

  // ---- Exception Handling Dispatch ----

  @Nested
  class HandleException {

    @Test
    void regularException_callsHandleFailure() {
      when(externalTask.getRetries()).thenReturn(null);
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn(null);

      RuntimeException ex = new RuntimeException("error");
      retryHandler.handleException(externalTask, externalTaskService, ex);

      // R3/PT5M default: first failure -> 3 retries, 300000ms interval
      verify(externalTaskService).handleFailure(
          eq(externalTask),
          eq("RuntimeException: error"),
          any(String.class),
          eq(3),
          eq(300_000L));
    }

    @Test
    void regularException_withExistingRetries_decrementsAndCallsHandleFailure() {
      when(externalTask.getRetries()).thenReturn(2);
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn("R3/PT5M");

      RuntimeException ex = new RuntimeException("retry error");
      retryHandler.handleException(externalTask, externalTaskService, ex);

      verify(externalTaskService).handleFailure(
          eq(externalTask),
          eq("RuntimeException: retry error"),
          any(String.class),
          eq(1),
          eq(300_000L));
    }

    @Test
    void regularException_lastRetry_callsHandleFailureWithZero() {
      when(externalTask.getRetries()).thenReturn(1);
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn("R3/PT5M");

      RuntimeException ex = new RuntimeException("last chance");
      retryHandler.handleException(externalTask, externalTaskService, ex);

      verify(externalTaskService).handleFailure(
          eq(externalTask),
          eq("RuntimeException: last chance"),
          any(String.class),
          eq(0),
          eq(0L));
    }

    @Test
    void businessError_callsHandleBpmnError_withoutVariables() {
      ExternalTaskBusinessError error = new ExternalTaskBusinessError("ERR_CODE", "error msg");
      retryHandler.handleException(externalTask, externalTaskService, error);

      verify(externalTaskService).handleBpmnError(externalTask, "ERR_CODE", "error msg");
    }

    @Test
    void businessError_callsHandleBpmnError_withVariables() {
      Map<String, Object> vars = Map.of("key", "value");
      ExternalTaskBusinessError error = new ExternalTaskBusinessError("ERR_CODE", "error msg", vars);
      retryHandler.handleException(externalTask, externalTaskService, error);

      verify(externalTaskService).handleBpmnError(externalTask, "ERR_CODE", "error msg", vars);
    }

    @Test
    void instantIncident_callsHandleFailureWithZeroRetries() {
      InstantIncidentException ex = new InstantIncidentException("critical failure");
      retryHandler.handleException(externalTask, externalTaskService, ex);

      verify(externalTaskService).handleFailure(
          eq(externalTask),
          eq("InstantIncident: critical failure"),
          any(String.class),
          eq(0),
          eq(0L));
    }

    @Test
    void regularException_withIntervalListConfig_usesCorrectIntervals() {
      when(externalTask.getExtensionProperty("RETRY_CONFIG")).thenReturn("PT1M,PT5M,PT1H");

      // First failure (null retries) -> 3 retries from list length
      when(externalTask.getRetries()).thenReturn(null);
      retryHandler.handleException(externalTask, externalTaskService, new RuntimeException("err"));

      verify(externalTaskService).handleFailure(
          eq(externalTask),
          anyString(),
          any(String.class),
          eq(3),        // 3 intervals in list = 3 retries
          eq(60_000L)); // PT1M for first retry (3 remaining, first interval)
    }
  }

  // ---- Stack Trace Extraction ----

  @Nested
  class GetStackTrace {

    @Test
    void regularException_returnsStackTrace() {
      RuntimeException ex = new RuntimeException("test");
      String stackTrace = retryHandler.getStackTrace(ex);
      assertThat(stackTrace).contains("RuntimeException: test");
      assertThat(stackTrace).contains("RetryHandlerTest");
    }

    @Test
    void instantIncident_withCause_returnsCauseStackTrace() {
      IllegalStateException cause = new IllegalStateException("root cause");
      InstantIncidentException ex = new InstantIncidentException("wrapper", cause);
      String stackTrace = retryHandler.getStackTrace(ex);
      assertThat(stackTrace).contains("IllegalStateException: root cause");
    }

    @Test
    void instantIncident_withoutCause_returnsOwnStackTrace() {
      InstantIncidentException ex = new InstantIncidentException("no cause");
      String stackTrace = retryHandler.getStackTrace(ex);
      assertThat(stackTrace).contains("InstantIncidentException");
    }
  }
}
