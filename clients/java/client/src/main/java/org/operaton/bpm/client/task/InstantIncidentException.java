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
package org.operaton.bpm.client.task;

/**
 * Throw this exception from an {@link ExternalTaskHandler#execute} method to skip
 * the configured retry behavior and create an incident immediately. When auto-retry
 * is enabled, the client will call
 * {@link ExternalTaskService#handleFailure(ExternalTask, String, String, int, long)}
 * with retries set to 0, causing the engine to create an incident right away.
 *
 * <p>This is useful for unrecoverable errors where retrying would be futile
 * (e.g., configuration errors, missing required data that will not appear on retry).</p>
 *
 * <p>Usage example:</p>
 * <pre>
 * handler((task, service) -&gt; {
 *     if (criticalConfigMissing) {
 *         throw new InstantIncidentException("Required config not found");
 *     }
 *     service.complete(task);
 * });
 * </pre>
 *
 * @since 2.2
 */
public class InstantIncidentException extends RuntimeException {

  /**
   * Creates an instant incident exception with no message.
   */
  public InstantIncidentException() {
    super();
  }

  /**
   * Creates an instant incident exception with a message.
   *
   * @param message error message passed to the engine
   */
  public InstantIncidentException(String message) {
    super(message);
  }

  /**
   * Creates an instant incident exception with a message and root cause.
   *
   * @param message error message passed to the engine
   * @param cause   root cause of the error
   */
  public InstantIncidentException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates an instant incident exception with a root cause.
   *
   * @param cause root cause of the error
   */
  public InstantIncidentException(Throwable cause) {
    super(null, cause);
  }
}
