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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.client.task.ExternalTaskBusinessError;
import org.operaton.bpm.client.task.ExternalTaskHandler;
import org.operaton.bpm.client.task.ExternalTaskService;
import org.operaton.bpm.client.task.InstantIncidentException;
import org.operaton.bpm.client.task.impl.ExternalTaskImpl;
import org.operaton.bpm.client.topic.impl.TopicSubscriptionManager;
import org.operaton.bpm.client.variable.impl.DefaultValueMappers;
import org.operaton.bpm.client.variable.impl.TypedValues;
import org.operaton.bpm.engine.variable.value.PrimitiveValue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the integration of auto-retry handling in TopicSubscriptionManager.
 * Verifies that when auto-retry is enabled, exceptions from handlers are properly
 * dispatched to the engine via handleFailure() or handleBpmnError().
 *
 * <p>Uses {@link AutoRetryTestableManager} to expose the protected
 * {@code handleExternalTask()} method for direct testing.</p>
 */
class AutoRetryTopicSubscriptionManagerTest {

  static final String TOPIC = "testTopic";

  EngineClient engineClient;
  TypedValues typedValues;
  ExternalTaskImpl externalTask;

  @BeforeEach
  void setUp() {
    typedValues = new TypedValues(new DefaultValueMappers<PrimitiveValue<String>>(""));
    engineClient = mock(EngineClient.class);

    externalTask = new ExternalTaskImpl();
    externalTask.setTopicName(TOPIC);
    externalTask.setVariables(new HashMap<>());
    externalTask.setId("task-1");

    List<ExternalTask> taskList = new ArrayList<>();
    taskList.add(externalTask);
    when(engineClient.fetchAndLock(anyList())).thenReturn(taskList);
  }

  @Test
  void autoRetryDisabled_exceptionIsOnlyLogged() {
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, RetryConfiguration.disabled());

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new RuntimeException("test error");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient, never()).failure(
        anyString(), anyString(), anyString(), eq(3), eq(300_000L), any(), any());
  }

  @Test
  void autoRetryEnabled_regularException_callsHandleFailure() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new RuntimeException("test error");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).failure(
        eq("task-1"),
        eq("RuntimeException: test error"),
        any(String.class),
        eq(3),
        eq(300_000L),
        any(),
        any());
  }

  @Test
  void autoRetryEnabled_instantIncident_callsHandleFailureWithZeroRetries() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new InstantIncidentException("fatal");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).failure(
        eq("task-1"),
        eq("InstantIncident: fatal"),
        any(String.class),
        eq(0),
        eq(0L),
        any(),
        any());
  }

  @Test
  void autoRetryEnabled_businessError_callsHandleBpmnError() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new ExternalTaskBusinessError("MY_ERROR", "business problem");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).bpmnError(
        eq("task-1"),
        eq("MY_ERROR"),
        eq("business problem"),
        any());
  }

  @Test
  void autoRetryEnabled_businessErrorWithVariables_passesVariables() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    Map<String, Object> errorVars = Map.of("errorDetail", "some detail");
    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new ExternalTaskBusinessError("MY_ERROR", "msg", errorVars);
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).bpmnError(
        eq("task-1"),
        eq("MY_ERROR"),
        eq("msg"),
        any());
  }

  @Test
  void autoRetryEnabled_existingRetries_decrementsCorrectly() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    externalTask.setRetries(2);

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new RuntimeException("retry error");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).failure(
        eq("task-1"),
        eq("RuntimeException: retry error"),
        any(String.class),
        eq(1),
        eq(300_000L),
        any(),
        any());
  }

  @Test
  void autoRetryEnabled_withExtensionPropertyConfig() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    Map<String, String> extensionProps = new HashMap<>();
    extensionProps.put("RETRY_CONFIG", "R5/PT10M");
    externalTask.setExtensionProperties(extensionProps);

    ExternalTaskHandler throwingHandler = (task, service) -> {
      throw new RuntimeException("error");
    };

    manager.invokeHandleExternalTask(externalTask, throwingHandler);

    verify(engineClient).failure(
        eq("task-1"),
        eq("RuntimeException: error"),
        any(String.class),
        eq(5),
        eq(600_000L),
        any(),
        any());
  }

  @Test
  void autoRetryEnabled_handlerSucceeds_noFailureOrErrorCall() {
    RetryConfiguration config = new RetryConfiguration(true, "R3/PT5M", "RETRY_CONFIG");
    AutoRetryTestableManager manager = new AutoRetryTestableManager(
        engineClient, typedValues, 20000L, config);

    ExternalTaskHandler successHandler = (task, service) -> {
      // handler succeeds, no exception
    };

    manager.invokeHandleExternalTask(externalTask, successHandler);

    verify(engineClient, never()).failure(
        anyString(), anyString(), anyString(), eq(0), eq(0L), any(), any());
    verify(engineClient, never()).bpmnError(
        anyString(), anyString(), anyString(), any());
  }
}

/**
 * Testing subclass that exposes the protected {@code handleExternalTask()} method
 * for direct invocation from tests.
 */
class AutoRetryTestableManager extends TopicSubscriptionManager {

  AutoRetryTestableManager(EngineClient engineClient, TypedValues typedValues,
      long clientLockDuration, RetryConfiguration retryConfiguration) {
    super(engineClient, typedValues, clientLockDuration, retryConfiguration);
  }

  void invokeHandleExternalTask(ExternalTask externalTask, ExternalTaskHandler taskHandler) {
    handleExternalTask(externalTask, taskHandler);
  }
}
