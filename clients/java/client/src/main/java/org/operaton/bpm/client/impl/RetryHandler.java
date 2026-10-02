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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.client.task.ExternalTaskBusinessError;
import org.operaton.bpm.client.task.ExternalTaskService;
import org.operaton.bpm.client.task.InstantIncidentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles automatic retry behavior for external task handler exceptions.
 *
 * <p>This class consolidates the retry logic previously provided by the
 * external-task-retry-aspect library (viadee) directly into the external task client.
 * It supports two retry configuration formats:</p>
 *
 * <ul>
 *   <li><strong>Repeating cycle</strong>: {@code R3/PT5M} -- 3 retries, 5 minutes apart</li>
 *   <li><strong>Interval list</strong>: {@code PT5M,PT10M,PT1H} -- 3 retries with increasing intervals</li>
 * </ul>
 *
 * <p>The retry configuration is resolved in this order:</p>
 * <ol>
 *   <li>BPMN extension property on the external task (default property name: {@code RETRY_CONFIG})</li>
 *   <li>Default retry time cycle configured on the client builder</li>
 *   <li>Hardcoded fallback: {@code R3/PT5M}</li>
 * </ol>
 *
 * @since 2.2
 */
public class RetryHandler {

  private static final Logger LOG = LoggerFactory.getLogger(RetryHandler.class);

  static final String FALLBACK_RETRY_TIME_CYCLE = "R3/PT5M";
  static final long FALLBACK_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

  /**
   * Matches a comma-separated list of ISO 8601 durations, e.g. {@code PT5M,PT10M,PT1H}.
   */
  static final Pattern RETRY_LIST_PATTERN = Pattern.compile(
      "^([Pp](?:\\d+[Dd])?(?:[Tt](?!$)(?:\\d+[Hh])?(?:\\d+[Mm])?(?:\\d+[Ss])?)?)" +
      "(,([Pp](?:\\d+[Dd])?(?:[Tt](?!$)(?:\\d+[Hh])?(?:\\d+[Mm])?(?:\\d+[Ss])?)?))*$");

  /**
   * Matches a repeating ISO 8601 duration, e.g. {@code R3/PT5M}.
   */
  static final Pattern RETRY_CYCLE_PATTERN = Pattern.compile(
      "^(?:[Rr](?<times>\\d+)/)(?<interval>(?:[Pp](?:\\d+[Yy])?)(?:\\d+[Mm])?(?:\\d+[Dd])?" +
      "(?:[Tt](?!$)(?:\\d+[Hh])?(?:\\d+[Mm])?(?:\\d+[Ss])?)?)$");

  private final RetryConfiguration configuration;

  public RetryHandler(RetryConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Handles an exception thrown by an external task handler.
   *
   * <p>Dispatches to the appropriate engine API method based on the exception type:</p>
   * <ul>
   *   <li>{@link ExternalTaskBusinessError} -- calls {@code handleBpmnError()}</li>
   *   <li>{@link InstantIncidentException} -- calls {@code handleFailure()} with 0 retries</li>
   *   <li>Any other exception -- computes retries and interval, calls {@code handleFailure()}</li>
   * </ul>
   *
   * @param externalTask    the task being processed
   * @param externalTaskService the service to report failures/errors
   * @param exception       the exception thrown by the handler
   */
  public void handleException(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception exception) {
    if (exception instanceof ExternalTaskBusinessError businessError) {
      handleBusinessError(externalTask, externalTaskService, businessError);
    } else if (exception instanceof InstantIncidentException) {
      handleInstantIncident(externalTask, externalTaskService, exception);
    } else {
      handleRetryableFailure(externalTask, externalTaskService, exception);
    }
  }

  void handleBusinessError(ExternalTask externalTask, ExternalTaskService externalTaskService,
      ExternalTaskBusinessError businessError) {
    LOG.error("BusinessError in topic '{}', Code: {}, Message: {}",
        externalTask.getTopicName(), businessError.getErrorCode(), businessError.getErrorMessage());

    if (businessError.getVariables() == null) {
      externalTaskService.handleBpmnError(externalTask,
          businessError.getErrorCode(), businessError.getErrorMessage());
    } else {
      externalTaskService.handleBpmnError(externalTask,
          businessError.getErrorCode(), businessError.getErrorMessage(), businessError.getVariables());
    }
  }

  void handleInstantIncident(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception exception) {
    String errorMessage = formatErrorMessage(exception);
    String stackTrace = getStackTrace(exception);

    LOG.error("{} in topic '{}'. Creating incident immediately (0 retries).",
        exception.getClass().getSimpleName(), externalTask.getTopicName(), exception);

    externalTaskService.handleFailure(externalTask, errorMessage, stackTrace, 0, 0);
  }

  void handleRetryableFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception exception) {
    String retryTimeCycle = resolveRetryTimeCycle(externalTask);
    int remainingRetries = computeRemainingRetries(externalTask.getRetries(), retryTimeCycle);
    long nextRetryInterval = computeNextRetryInterval(remainingRetries, retryTimeCycle);

    String errorMessage = formatErrorMessage(exception);
    String stackTrace = getStackTrace(exception);

    logFailure(externalTask.getTopicName(), exception, remainingRetries, nextRetryInterval);

    externalTaskService.handleFailure(externalTask, errorMessage, stackTrace, remainingRetries, nextRetryInterval);
  }

  /**
   * Resolves the retry time cycle for a task, checking:
   * <ol>
   *   <li>BPMN extension property (e.g., {@code RETRY_CONFIG})</li>
   *   <li>Client-level default</li>
   * </ol>
   */
  String resolveRetryTimeCycle(ExternalTask externalTask) {
    String extensionValue = externalTask.getExtensionProperty(configuration.getRetryConfigIdentifier());
    if (extensionValue != null && !extensionValue.isBlank()) {
      return extensionValue.replace(" ", "").toUpperCase();
    }
    return configuration.getDefaultRetryTimeCycle();
  }

  /**
   * Computes the remaining retries based on the current retry count and configuration.
   *
   * <p>If the task has no retries set (first failure), the total retry count is
   * determined from the retry time cycle configuration. If the task already has
   * retries, they are decremented by 1 (minimum 0).</p>
   *
   * @param currentRetries  the current retry count from the task (may be null on first failure)
   * @param retryTimeCycle  the retry time cycle configuration string
   * @return the number of remaining retries
   */
  int computeRemainingRetries(Integer currentRetries, String retryTimeCycle) {
    if (currentRetries != null) {
      // Task already has a retry count -- decrement
      return Math.max(0, currentRetries - 1);
    }
    // First failure -- determine total retries from configuration
    return parseRetryCount(retryTimeCycle, true);
  }

  /**
   * Parses the total number of retries from a retry time cycle string.
   */
  int parseRetryCount(String retryTimeCycle, boolean tryDefault) {
    Matcher listMatcher = RETRY_LIST_PATTERN.matcher(retryTimeCycle);
    Matcher cycleMatcher = RETRY_CYCLE_PATTERN.matcher(retryTimeCycle);

    if (listMatcher.matches()) {
      // e.g., "PT10M" = 1 retry, "PT10M,PT1H" = 2 retries
      return retryTimeCycle.split(",").length;
    } else if (cycleMatcher.matches()) {
      // e.g., "R5/PT10M" = 5 retries
      return Integer.parseInt(cycleMatcher.group("times"));
    } else if (tryDefault) {
      return parseRetryCount(configuration.getDefaultRetryTimeCycle(), false);
    } else {
      return parseRetryCount(FALLBACK_RETRY_TIME_CYCLE, false);
    }
  }

  /**
   * Computes the next retry interval in milliseconds based on remaining retries
   * and the retry time cycle configuration.
   *
   * <p>For interval lists (e.g., {@code PT5M,PT10M,PT1H}), the interval is
   * selected based on which retry attempt this is. For repeating cycles
   * (e.g., {@code R3/PT5M}), the same interval is used for every retry.</p>
   */
  long computeNextRetryInterval(int remainingRetries, String retryTimeCycle) {
    return computeNextRetryInterval(remainingRetries, retryTimeCycle, true);
  }

  private long computeNextRetryInterval(int remainingRetries, String retryTimeCycle, boolean tryDefault) {
    if (remainingRetries <= 0) {
      return 0L;
    }

    String normalized = retryTimeCycle.replace(" ", "").toUpperCase();

    Matcher listMatcher = RETRY_LIST_PATTERN.matcher(normalized);
    Matcher cycleMatcher = RETRY_CYCLE_PATTERN.matcher(normalized);

    if (listMatcher.matches()) {
      String[] intervals = normalized.split(",");
      int length = intervals.length;

      // e.g., PT1M,PT2M,PT1H,PT2H,P3D = 5 retries (length = 5)
      //       1.   2.   3.   4.   5.   retry
      // e.g., 4 remaining retries -> next retry: PT2M (2nd element)
      // calc: 5 (length) - 4 (remaining) = 1 (= 2nd array position)
      if (remainingRetries >= length) {
        return parseIntervalToMillis(intervals[0]);
      } else {
        return parseIntervalToMillis(intervals[length - remainingRetries]);
      }
    } else if (cycleMatcher.matches()) {
      // e.g., R3/PT5M -- all retries use the same interval
      return parseIntervalToMillis(cycleMatcher.group("interval"));
    } else if (tryDefault) {
      return computeNextRetryInterval(remainingRetries, configuration.getDefaultRetryTimeCycle(), false);
    } else {
      return computeNextRetryInterval(remainingRetries, FALLBACK_RETRY_TIME_CYCLE, false);
    }
  }

  /**
   * Parses an ISO 8601 duration string to milliseconds.
   */
  long parseIntervalToMillis(String interval) {
    try {
      return Duration.parse(interval).getSeconds() * 1000L;
    } catch (DateTimeParseException e) {
      LOG.warn("Failed to parse retry interval '{}', using fallback of {}ms", interval, FALLBACK_INTERVAL_MS);
      return FALLBACK_INTERVAL_MS;
    }
  }

  /**
   * Formats an error message from an exception, including special handling
   * for {@link InstantIncidentException} with root causes.
   */
  String formatErrorMessage(Throwable exception) {
    if (!(exception instanceof InstantIncidentException)) {
      return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    if (exception.getMessage() == null) {
      if (exception.getCause() == null) {
        return "InstantIncident";
      } else {
        return String.format("%s: %s (InstantIncident)",
            exception.getCause().getClass().getSimpleName(),
            exception.getCause().getMessage());
      }
    } else {
      if (exception.getCause() == null) {
        return "InstantIncident: " + exception.getMessage();
      } else {
        return String.format("%s: %s (InstantIncident: %s)",
            exception.getCause().getClass().getSimpleName(),
            exception.getCause().getMessage(),
            exception.getMessage());
      }
    }
  }

  /**
   * Extracts the stack trace as a string. For {@link InstantIncidentException},
   * prefers the root cause stack trace if available.
   */
  String getStackTrace(Throwable throwable) {
    if (throwable instanceof InstantIncidentException && throwable.getCause() != null) {
      return getStackTrace(throwable.getCause());
    }
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw, true);
    throwable.printStackTrace(pw);
    return sw.toString();
  }

  private void logFailure(String topicName, Throwable throwable, int remainingRetries, long nextRetryInterval) {
    String retryInfo = remainingRetries == 0 ? "" : ", next retry in " + nextRetryInterval + "ms";
    LOG.error("{}: {} in topic '{}'. {} retry(s) left{}",
        throwable.getClass().getSimpleName(),
        throwable.getMessage(),
        topicName,
        remainingRetries,
        retryInfo,
        throwable);
  }
}
