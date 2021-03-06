/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.tracing.decision.aggregator;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import io.cloudevents.json.Json;
import io.cloudevents.v1.CloudEventBuilder;
import io.cloudevents.v1.CloudEventImpl;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.ast.DecisionNode;
import org.kie.dmn.api.core.ast.InputDataNode;
import org.kie.dmn.core.ast.DecisionServiceNodeImpl;
import org.kie.dmn.feel.util.Pair;
import org.kie.kogito.tracing.decision.event.common.InternalMessageType;
import org.kie.kogito.tracing.decision.event.common.Message;
import org.kie.kogito.tracing.decision.event.evaluate.EvaluateDecisionResult;
import org.kie.kogito.tracing.decision.event.evaluate.EvaluateEvent;
import org.kie.kogito.tracing.decision.event.evaluate.EvaluateEventType;
import org.kie.kogito.tracing.decision.event.trace.TraceEvent;
import org.kie.kogito.tracing.decision.event.trace.TraceEventType;
import org.kie.kogito.tracing.decision.event.trace.TraceExecutionStep;
import org.kie.kogito.tracing.decision.event.trace.TraceExecutionStepType;
import org.kie.kogito.tracing.decision.event.trace.TraceHeader;
import org.kie.kogito.tracing.decision.event.trace.TraceInputValue;
import org.kie.kogito.tracing.decision.event.trace.TraceOutputValue;
import org.kie.kogito.tracing.decision.event.trace.TraceResourceId;
import org.kie.kogito.tracing.decision.event.trace.TraceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.kogito.tracing.decision.event.evaluate.EvaluateEventType.AFTER_EVALUATE_DECISION_SERVICE;
import static org.kie.kogito.tracing.decision.event.evaluate.EvaluateEventType.BEFORE_EVALUATE_DECISION_SERVICE;

public class DefaultAggregator implements Aggregator<TraceEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAggregator.class);

    private static final String UNKNOWN_SOURCE_URL = "__UNKNOWN_SOURCE__";

    private static final String EXPRESSION_ID_KEY = "expressionId";
    private static final String MATCHES_KEY = "matches";
    private static final String NODE_ID_KEY = "nodeId";
    private static final String NODE_NAME_KEY = "nodeName";
    private static final String SELECTED_KEY = "selected";
    private static final String VARIABLE_ID_KEY = "variableId";

    @Override
    public CloudEventImpl<TraceEvent> aggregate(DMNModel model, String executionId, List<EvaluateEvent> events) {
        return events == null || events.isEmpty()
               ? buildNotEnoughDataCloudEvent(model, executionId)
               : buildDefaultCloudEvent(model, executionId, events);
    }

    private static CloudEventImpl<TraceEvent> buildNotEnoughDataCloudEvent(DMNModel model, String executionId) {
        TraceHeader header = new TraceHeader(
                TraceEventType.DMN,
                executionId,
                null,
                null,
                null,
                TraceResourceId.from(model),
                Stream.of(
                        Message.from(InternalMessageType.NOT_ENOUGH_DATA),
                        model == null ? Message.from(InternalMessageType.DMN_MODEL_NOT_FOUND) : null
                ).filter(Objects::nonNull).collect(Collectors.toList())
        );

        TraceEvent event = new TraceEvent(header, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        return CloudEventBuilder.<TraceEvent>builder()
                .withType(TraceEvent.class.getName())
                .withId(executionId)
                .withSource(URI.create(URLEncoder.encode(UNKNOWN_SOURCE_URL, StandardCharsets.UTF_8)))
                .withData(event)
                .build();
    }

    private static CloudEventImpl<TraceEvent> buildDefaultCloudEvent(DMNModel model, String executionId, List<EvaluateEvent> events) {
        EvaluateEvent firstEvent = events.get(0);
        EvaluateEvent lastEvent = events.get(events.size() - 1);

        List<TraceInputValue> inputs = buildTraceInputValues(model, firstEvent);

        List<TraceOutputValue> outputs = buildTraceOutputValues(model, lastEvent);

        Pair<List<TraceExecutionStep>, List<Message>> executionStepsPair = buildTraceExecutionSteps(model, executionId, events);

        TraceHeader header = new TraceHeader(
                TraceEventType.DMN,
                executionId,
                firstEvent.getTimestamp(),
                lastEvent.getTimestamp(),
                computeDurationMillis(firstEvent, lastEvent),
                TraceResourceId.from(firstEvent),
                Stream.of(
                        model == null ? Stream.of(Message.from(InternalMessageType.DMN_MODEL_NOT_FOUND)) : Stream.<Message>empty(),
                        executionStepsPair.getRight().stream(),
                        lastEvent.getResult().getMessages().stream()
                                .filter(m -> m.getSourceId() == null || m.getSourceId().isBlank())
                ).flatMap(Function.identity()).collect(Collectors.toList())
        );

        // complete event
        TraceEvent event = new TraceEvent(header, inputs, outputs, executionStepsPair.getLeft());

        return CloudEventBuilder.<TraceEvent>builder()
                .withType(TraceEvent.class.getName())
                .withId(executionId)
                .withSource(buildSource(firstEvent))
                .withData(event)
                .build();
    }

    private static URI buildSource(EvaluateEvent event) {
        return event.getType() == BEFORE_EVALUATE_DECISION_SERVICE || event.getType() == AFTER_EVALUATE_DECISION_SERVICE
               ? URI.create(String.format("%s/%s", urlEncode(event.getModelName()), urlEncode(event.getNodeName())))
               : URI.create(urlEncode(event.getModelName()));
    }

    private static List<TraceInputValue> buildTraceInputValues(DMNModel model, EvaluateEvent firstEvent) {
        if (model == null) {
            return firstEvent.getContext().entrySet().stream()
                    .map(DefaultAggregator::traceInputFrom)
                    .collect(Collectors.toList());
        }
        if (firstEvent.getType() == EvaluateEventType.BEFORE_EVALUATE_DECISION_SERVICE) {
            // cast to DecisionServiceNodeImpl here is required to have access to getInputParameters method
            Optional<DecisionServiceNodeImpl> optNode = model.getDecisionServices().stream()
                    .filter(ds -> ds.getId().equals(firstEvent.getNodeId()))
                    .findFirst()
                    .filter(DecisionServiceNodeImpl.class::isInstance)
                    .map(DecisionServiceNodeImpl.class::cast);

            if (optNode.isPresent()) {
                return optNode.get().getInputParameters().values().stream()
                        .filter(InputDataNode.class::isInstance)
                        .map(InputDataNode.class::cast)
                        .map(i -> traceInputFrom(i, firstEvent.getContext()))
                        .collect(Collectors.toList());
            }
        }
        return model.getInputs().stream()
                .map(i -> traceInputFrom(i, firstEvent.getContext()))
                .collect(Collectors.toList());
    }

    private static List<TraceOutputValue> buildTraceOutputValues(DMNModel model, EvaluateEvent lastEvent) {
        return lastEvent.getResult().getDecisionResults().stream()
                .map(dr -> traceOutputFrom(dr, model))
                .collect(Collectors.toList());
    }

    private static Pair<List<TraceExecutionStep>, List<Message>> buildTraceExecutionSteps(DMNModel model, String executionId, List<EvaluateEvent> events) {
        try {
            return new Pair<>(buildTraceExecutionStepsHierarchy(model, events), Collections.emptyList());
        } catch (IllegalStateException e) {
            LOG.error(String.format("IllegalStateException during aggregation of evaluation %s", executionId), e);
            return new Pair<>(buildTraceExecutionStepsList(model, events), List.of(Message.from(InternalMessageType.NO_EXECUTION_STEP_HIERARCHY, e)));
        }
    }

    private static List<TraceExecutionStep> buildTraceExecutionStepsHierarchy(DMNModel model, List<EvaluateEvent> events) {
        List<TraceExecutionStep> executionSteps = new ArrayList<>(events.size() / 2);
        Deque<DefaultAggregatorStackEntry> stack = new ArrayDeque<>(events.size() / 2);
        for (int i = 1; i < events.size() - 1; i++) {
            processEvaluateEventInHierarchy(model, stack, executionSteps, events.get(i));
        }
        if (!stack.isEmpty()) {
            throw new IllegalStateException("Can't match all after events with corresponding before events");
        }
        return executionSteps;
    }

    private static void processEvaluateEventInHierarchy(DMNModel model, Deque<DefaultAggregatorStackEntry> stack, List<TraceExecutionStep> executionSteps, EvaluateEvent event) {
        LOG.trace("Started aggregating event {} (execution steps: {}, stack size: {})", event.getType(), executionSteps.size(), stack.size());
        if (event.getType().isBefore()) {
            stack.push(new DefaultAggregatorStackEntry(event));
        } else {
            if (stack.isEmpty() || !stack.peek().isValidAfterEvent(event)) {
                throw new IllegalStateException(String.format("Can't match %s after event with corresponding before event", event.getType()));
            }
            DefaultAggregatorStackEntry stackEntry = stack.pop();
            TraceExecutionStep step = buildTraceExecutionStep(model, stackEntry, event);
            if (step == null) {
                throw new IllegalStateException(String.format("Can't build TraceExecutionStep for a %s event", event.getType()));
            }
            if (stack.isEmpty()) {
                executionSteps.add(step);
            } else {
                stack.peek().addChild(step);
            }
        }
        LOG.trace("Finished aggregating event {} (execution steps: {}, stack size: {})", event.getType(), executionSteps.size(), stack.size());
    }

    private static List<TraceExecutionStep> buildTraceExecutionStepsList(DMNModel model, List<EvaluateEvent> events) {
        return events.stream()
                .filter(e -> e.getType().isAfter())
                .map(e -> buildTraceExecutionStep(model, null, e))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static TraceExecutionStep buildTraceExecutionStep(DMNModel model, DefaultAggregatorStackEntry stackEntry, EvaluateEvent afterEvent) {
        TraceExecutionStepType type = TraceExecutionStepType.from(afterEvent.getType());
        if (type == null) {
            return null;
        }

        long duration = Optional.ofNullable(stackEntry)
                .map(DefaultAggregatorStackEntry::getBeforeEvent)
                .map(beforeEvent -> computeDurationMillis(beforeEvent, afterEvent))
                .orElse(0L);

        List<TraceExecutionStep> children = Optional.ofNullable(stackEntry)
                .map(DefaultAggregatorStackEntry::getChildren)
                .orElse(Collections.emptyList());

        switch (type) {
            case DMN_BKM_EVALUATION:
            case DMN_DECISION_SERVICE:
            case DMN_BKM_INVOCATION:
                return buildDefaultTraceExecutionStep(duration, afterEvent, children, type);
            case DMN_CONTEXT_ENTRY:
                return buildDmnContextEntryTraceExecutionStep(duration, afterEvent, children, model);
            case DMN_DECISION:
                return buildDmnDecisionTraceExecutionStep(duration, afterEvent, children);
            case DMN_DECISION_TABLE:
                return buildDmnDecisionTableTraceExecutionStep(duration, afterEvent, children, model);
            default:
                return null;
        }
    }

    private static TraceExecutionStep buildDefaultTraceExecutionStep(long duration, EvaluateEvent afterEvent, List<TraceExecutionStep> children, TraceExecutionStepType type) {
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put(NODE_ID_KEY, afterEvent.getNodeId());

        return new TraceExecutionStep(type, duration, afterEvent.getNodeName(), null, Collections.emptyList(), additionalData, children);
    }

    private static TraceExecutionStep buildDmnContextEntryTraceExecutionStep(long duration, EvaluateEvent afterEvent, List<TraceExecutionStep> children, DMNModel model) {
        Object result = afterEvent.getContextEntryResult().getExpressionResult();

        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put(EXPRESSION_ID_KEY, afterEvent.getContextEntryResult().getExpressionId());
        additionalData.put(VARIABLE_ID_KEY, afterEvent.getContextEntryResult().getVariableId());
        Optional.ofNullable(model)
                .map(m -> m.getDecisionByName(afterEvent.getNodeName()))
                .map(DecisionNode::getId)
                .ifPresentOrElse(
                        id -> additionalData.put(NODE_ID_KEY, id),
                        () -> additionalData.put(NODE_NAME_KEY, afterEvent.getNodeName())
                );

        return new TraceExecutionStep(TraceExecutionStepType.DMN_CONTEXT_ENTRY, duration, afterEvent.getContextEntryResult().getVariableName(), result, Collections.emptyList(), additionalData, children);
    }

    private static TraceExecutionStep buildDmnDecisionTraceExecutionStep(long duration, EvaluateEvent afterEvent, List<TraceExecutionStep> children) {
        List<Message> messages = afterEvent.getResult().getMessages().stream()
                .filter(m -> afterEvent.getNodeId().equals(m.getSourceId()))
                .collect(Collectors.toList());

        Object result = afterEvent.getResult().getDecisionResults().stream()
                .filter(dr -> dr.getDecisionId().equals(afterEvent.getNodeId()))
                .findFirst()
                .map(EvaluateDecisionResult::getResult)
                .orElse(null);

        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put(NODE_ID_KEY, afterEvent.getNodeId());

        return new TraceExecutionStep(TraceExecutionStepType.DMN_DECISION, duration, afterEvent.getNodeName(), result, messages, additionalData, children);
    }

    private static TraceExecutionStep buildDmnDecisionTableTraceExecutionStep(long duration, EvaluateEvent afterEvent, List<TraceExecutionStep> children, DMNModel model) {
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put(MATCHES_KEY, afterEvent.getDecisionTableResult().getMatches());
        additionalData.put(SELECTED_KEY, afterEvent.getDecisionTableResult().getSelected());
        Optional.ofNullable(model)
                .map(m -> m.getDecisionByName(afterEvent.getNodeName()))
                .map(DecisionNode::getId)
                .ifPresentOrElse(
                        id -> additionalData.put(NODE_ID_KEY, id),
                        () -> additionalData.put(NODE_NAME_KEY, afterEvent.getNodeName())
                );

        return new TraceExecutionStep(TraceExecutionStepType.DMN_DECISION_TABLE, duration, afterEvent.getDecisionTableResult().getDecisionTableName(), null, Collections.emptyList(), additionalData, children);
    }

    private static long computeDurationMillis(EvaluateEvent beginEvent, EvaluateEvent endEvent) {
        return Math.round((endEvent.getNanoTime() - beginEvent.getNanoTime()) / 1000000.0);
    }

    private static TraceInputValue traceInputFrom(InputDataNode node, Map<String, Object> context) {
        JsonNode value = Optional.ofNullable(context.get(node.getName()))
                .<JsonNode>map(Json.MAPPER::valueToTree)
                .orElse(null);

        return new TraceInputValue(
                node.getId(),
                node.getName(),
                TraceType.from(node.getType()),
                value,
                Collections.emptyList()
        );
    }

    private static TraceInputValue traceInputFrom(Map.Entry<String, Object> contextEntry) {
        return new TraceInputValue(
                null,
                contextEntry.getKey(),
                null,
                Json.MAPPER.valueToTree(contextEntry.getValue()),
                Collections.emptyList()
        );
    }

    private static TraceOutputValue traceOutputFrom(EvaluateDecisionResult decisionResult, DMNModel model) {
        TraceType type = Optional.ofNullable(model)
                .map(m -> m.getDecisionById(decisionResult.getDecisionId()))
                .map(DecisionNode::getResultType)
                .map(TraceType::from)
                .orElse(null);

        JsonNode value = Optional.ofNullable(decisionResult.getResult())
                .<JsonNode>map(Json.MAPPER::valueToTree)
                .orElse(null);

        return new TraceOutputValue(
                decisionResult.getDecisionId(),
                decisionResult.getDecisionName(),
                decisionResult.getEvaluationStatus().name(),
                type,
                value,
                decisionResult.getMessages()
        );
    }

    private static String urlEncode(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }
}
