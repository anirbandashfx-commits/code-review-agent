package com.agentaudit.codereview.agent;

import com.agentaudit.codereview.model.AgentState;
import com.agentaudit.codereview.model.CodeIssue;
import com.agentaudit.codereview.service.LiteLLMService;
import com.agentaudit.codereview.service.ValkeyCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * AgentGraphService is the graph orchestrator — the equivalent of
 * LangGraph's StateGraph.compile().invoke() in Python.
 *
 * It defines the node execution order and the conditional edges
 * that decide whether to continue, retry, or stop.
 *
 * Graph:
 *   START → SyntaxAnalyserNode → SecurityScanNode
 *         → QualityReviewNode → SummaryNode → END
 *
 * In a full LangGraph implementation you would define this as:
 *   graph.addNode("syntax",   syntaxNode::execute)
 *   graph.addNode("security", securityNode::execute)
 *   graph.addEdge("syntax",   "security")
 *   graph.addEdge("security", "quality")
 *   graph.addConditionalEdge("quality", routeAfterQuality)
 *   graph.setEntryPoint("syntax")
 *
 * Here we implement the same pattern in plain Java with explicit
 * method calls and state checks as the "edges".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGraphService {

    private final SyntaxAnalyserNode syntaxNode;
    private final SecurityScanNode securityNode;
    private final QualityReviewNode qualityNode;
    private final SummaryNode summaryNode;
    private final ValkeyCacheService cacheService;
    private final ObjectMapper objectMapper;

    @Value("${agent.max-iterations:50}")
    private int maxIterations;

    @Value("${agent.timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * Entry point — runs the full graph for a code review request.
     *
     * @param sourceCode  Raw source code to review
     * @param language    Programming language (java, python, etc.)
     * @return            Completed AgentState with all findings
     */
    public AgentState runCodeReview(String sourceCode, String language) {
        String sessionId = UUID.randomUUID().toString();

        // Initialise state
        // AUDIT FLAW #1: issues list is NOT initialised here.
        // AgentState.builder() leaves 'issues' as null.
        // Fix: .issues(new ArrayList<>())
        AgentState state = AgentState.builder()
                .sessionId(sessionId)
                .sourceCode(sourceCode)
                .language(language != null ? language : "unknown")
                .currentNode("START")
                .iterationCount(0)
                .shouldStop(false)
                .startedAt(Instant.now())
                // .issues(new ArrayList<>())  ← FLAW #1: this line is commented out
                .build();

        log.info("Graph START — sessionId={} language={}", sessionId, language);

        // Checkpoint initial state to Valkey
        // AUDIT FLAW #4: this call succeeds but with TTL=0, session never expires
        checkpointState(state);

        try {
            // ── EDGE: START → syntax-analyser ───────────────────────
            state = syntaxNode.execute(state);
            checkpointState(state);

            // ── CONDITIONAL EDGE: syntax → security (or STOP) ───────
            if (shouldStopGraph(state, "after syntax")) {
                return finalise(state, "Stopped after syntax analysis");
            }

            // ── EDGE: syntax-analyser → security-scan ───────────────
            state = securityNode.execute(state);
            checkpointState(state);

            if (shouldStopGraph(state, "after security")) {
                return finalise(state, "Stopped after security scan");
            }

            // ── EDGE: security-scan → quality-review ─────────────────
            state = qualityNode.execute(state);
            checkpointState(state);

            if (shouldStopGraph(state, "after quality")) {
                return finalise(state, "Stopped after quality review");
            }

            // ── EDGE: quality-review → summary ───────────────────────
            state = summaryNode.execute(state);
            checkpointState(state);

        } catch (NullPointerException e) {
            // AUDIT FLAW #1: this NPE is caught here, but only at the top level.
            // By the time we reach this catch, we don't know WHICH node failed
            // or what state was corrupted. A proper fix is null-guarding in each node.
            log.error("NPE in graph execution — likely null LLM response propagated into state. sessionId={}", sessionId, e);
            state.setShouldStop(true);
            state.setStopReason("NullPointerException: " + e.getMessage() + " — see FLAW #1 in AgentState.addIssue()");
        } catch (Exception e) {
            log.error("Unexpected graph error — sessionId={}", sessionId, e);
            state.setShouldStop(true);
            state.setStopReason("Unexpected error: " + e.getMessage());
        }

        return finalise(state, state.getStopReason() != null ? state.getStopReason() : "Complete");
    }

    // ── Edge condition logic ─────────────────────────────────────────────

    /**
     * Decides whether the graph should stop at the current edge.
     * This is the equivalent of LangGraph's conditional edge function.
     *
     * Returns true (stop) if:
     *   - state.shouldStop flag was set by a node
     *   - max iterations exceeded
     *
     * AUDIT FLAW #2: the iteration check uses maxIterations=50.
     * With 4 nodes and one pass, we use 4 iterations. The guard
     * only fires if the graph is somehow re-routed 50 times —
     * by which point significant API spend has already occurred.
     * A better value is 8–10 (2× the expected number of nodes).
     */
    private boolean shouldStopGraph(AgentState state, String checkpoint) {
        if (state.isShouldStop()) {
            log.warn("Graph stop requested at checkpoint '{}' — reason: {}", checkpoint, state.getStopReason());
            return true;
        }
        if (state.hasExceededMaxIterations(maxIterations)) {
            log.error("Max iterations ({}) exceeded at checkpoint '{}' — force stopping", maxIterations, checkpoint);
            state.setShouldStop(true);
            state.setStopReason("Max iterations exceeded");
            return true;
        }
        return false;
    }

    private AgentState finalise(AgentState state, String reason) {
        state.setCompletedAt(Instant.now());
        state.setCurrentNode("END");
        if (state.getStopReason() == null) {
            state.setStopReason(reason);
        }
        log.info("Graph END — sessionId={} reason='{}' issues={}",
                state.getSessionId(), reason,
                state.getIssues() != null ? state.getIssues().size() : "null (FLAW #1)");
        return state;
    }

    private void checkpointState(AgentState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            cacheService.saveSessionState(state.getSessionId(), json);
        } catch (Exception e) {
            // Checkpoint failure is non-fatal — graph continues
            log.warn("Failed to checkpoint state for session {}: {}", state.getSessionId(), e.getMessage());
        }
    }
}
