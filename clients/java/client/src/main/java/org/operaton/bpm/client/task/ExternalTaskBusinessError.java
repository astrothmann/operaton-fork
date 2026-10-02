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

import java.util.Map;

/**
 * Throw this exception from an {@link ExternalTaskHandler#execute} method to report
 * a BPMN error in the context of the current external task. When auto-retry is enabled,
 * the client will automatically call
 * {@link ExternalTaskService#handleBpmnError(ExternalTask, String, String, Map)}
 * with the error code, message, and variables from this exception.
 *
 * <p>This removes the need for manual try-catch blocks in handlers that need to
 * signal BPMN errors.</p>
 *
 * <p>Usage example:</p>
 * <pre>
 * handler((task, service) -&gt; {
 *     if (someBusinessCondition) {
 *         throw new ExternalTaskBusinessError("ERROR_CODE", "Something went wrong");
 *     }
 *     service.complete(task);
 * });
 * </pre>
 *
 * @since 2.2
 */
public class ExternalTaskBusinessError extends RuntimeException {

  private final String errorCode;
  private final String errorMessage;
  private final Map<String, Object> variables;

  /**
   * Creates a business error with only an error code.
   *
   * @param errorCode the error code used to identify the BPMN error in the process
   */
  public ExternalTaskBusinessError(String errorCode) {
    this(errorCode, null);
  }

  /**
   * Creates a business error with an error code and message.
   *
   * @param errorCode    the error code used to identify the BPMN error in the process
   * @param errorMessage the reason for the error, passed to the process
   */
  public ExternalTaskBusinessError(String errorCode, String errorMessage) {
    this(errorCode, errorMessage, null);
  }

  /**
   * Creates a business error with an error code, message, and variables.
   *
   * @param errorCode    the error code used to identify the BPMN error in the process
   * @param errorMessage the reason for the error, passed to the process
   * @param variables    variables passed to the process when the error is caught
   */
  public ExternalTaskBusinessError(String errorCode, String errorMessage, Map<String, Object> variables) {
    super(errorMessage != null ? errorMessage : errorCode);
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.variables = variables;
  }

  /**
   * @return the error code used to identify the BPMN error in the process
   */
  public String getErrorCode() {
    return errorCode;
  }

  /**
   * @return the error message, or {@code null} if not set
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * @return the variables to pass to the process, or {@code null} if not set
   */
  public Map<String, Object> getVariables() {
    return variables;
  }
}
