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

/**
 * Configuration for the auto-retry behavior of the external task client.
 *
 * <p>When auto-retry is enabled, the client automatically handles exceptions
 * thrown by {@link org.operaton.bpm.client.task.ExternalTaskHandler} implementations
 * by calling {@link org.operaton.bpm.client.task.ExternalTaskService#handleFailure}
 * with computed retry counts and intervals.</p>
 *
 * @since 2.2
 */
public class RetryConfiguration {

  /**
   * Default retry time cycle: 3 retries, 5 minutes apart.
   */
  public static final String DEFAULT_RETRY_TIME_CYCLE = "R3/PT5M";

  /**
   * Default BPMN extension property name used to look up retry configuration.
   */
  public static final String DEFAULT_RETRY_CONFIG_IDENTIFIER = "RETRY_CONFIG";

  private final boolean enabled;
  private final String defaultRetryTimeCycle;
  private final String retryConfigIdentifier;

  public RetryConfiguration(boolean enabled, String defaultRetryTimeCycle, String retryConfigIdentifier) {
    this.enabled = enabled;
    this.defaultRetryTimeCycle = defaultRetryTimeCycle != null && !defaultRetryTimeCycle.isBlank()
        ? defaultRetryTimeCycle.replace(" ", "").toUpperCase()
        : DEFAULT_RETRY_TIME_CYCLE;
    this.retryConfigIdentifier = retryConfigIdentifier != null && !retryConfigIdentifier.isBlank()
        ? retryConfigIdentifier
        : DEFAULT_RETRY_CONFIG_IDENTIFIER;
  }

  /**
   * @return whether auto-retry is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * @return the default retry time cycle (e.g., {@code "R3/PT5M"})
   */
  public String getDefaultRetryTimeCycle() {
    return defaultRetryTimeCycle;
  }

  /**
   * @return the BPMN extension property name for per-task retry configuration
   */
  public String getRetryConfigIdentifier() {
    return retryConfigIdentifier;
  }

  /**
   * Creates a disabled configuration (no auto-retry).
   */
  public static RetryConfiguration disabled() {
    return new RetryConfiguration(false, DEFAULT_RETRY_TIME_CYCLE, DEFAULT_RETRY_CONFIG_IDENTIFIER);
  }
}
