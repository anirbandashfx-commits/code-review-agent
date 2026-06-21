package com.agentaudit.codereview.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentState is the single source of truth passed between every node
 * in the code review graph.
 *
 * LangGraph pattern: each node reads from this state, performs work,
 * and writes its output back into the same state object before
 * passing control to the next node via an edge.
 *
 * AUDIT NOTE — Section 2 (State Transition Correctness):
 *   The 'issues' list is mutable and shared across nodes without
 *   defensive copying. Node B can accidentally overwrite Node A's
 *   findings if it reassigns the list instead of appending to it.
 */
@Data
@Builder
public class AgentState {

    // ── Input ────────────────────────────────────────────────────────
    /** Raw source code submitted by the user for review */
    private String sourceCode;

    /** Programming language hint (java, python, js, etc.) */
    private String language;

    /** Unique ID for this review session — used as Valkey cache key */
    private String sessionId;

    // ── Graph control ────────────────────────────────────────────────
    /** Which node is currently executing */
    private String currentNode;

    /** How many node transitions have occurred — guards against infinite loops */
    private int iterationCount;

    /** Set to true by any node that wants to stop the graph */
    private boolean shouldStop;

    /** Human-readable reason the graph stopped */
    private String stopReason;

    // ── Node outputs (written by each node) ──────────────────────────
    /** Written by SyntaxAnalyserNode */
    private String syntaxAnalysis;

    /** Written by SecurityScanNode */
    private String securityAnalysis;

    /** Written by QualityReviewNode */
    private String qualityAnalysis;

    /** Written by SummaryNode — the final report delivered to the user */
    private String finalReport;

    /**
     * Accumulated issues found across all nodes.
     *
     * AUDIT FLAW #1 — Tool Call Failure (Section 1):
     *   If an upstream node (e.g. SecurityScanNode) returns null
     *   because the LLM call failed, and the next node blindly appends
     *   to this list without null-checking, we get a NullPointerException
     *   that crashes the graph silently mid-run.
     *
     *   The field is initialised as null here (not as new ArrayList<>())
     *   to demonstrate the flaw. Fix: initialise to new ArrayList<>()
     *   and add null guards in every node before appending.
     */
    private List<CodeIssue> issues; // ← FLAW: should be = new ArrayList<>()

    // ── Metadata ─────────────────────────────────────────────────────
    private Instant startedAt;
    private Instant completedAt;

    /** Token usage across all LLM calls in this session */
    private int totalTokensUsed;

    /** Raw tool call log — populated only if debug logging is enabled */
    private List<Map<String, Object>> toolCallLog;

    // ── Helpers ──────────────────────────────────────────────────────

    public boolean hasExceededMaxIterations(int maxIterations) {
        return this.iterationCount >= maxIterations;
    }

    public void incrementIteration() {
        this.iterationCount++;
    }

    /**
     * AUDIT FLAW #1 continued:
     * This helper does NOT null-check before adding, so it will throw
     * NullPointerException when 'issues' is null.
     * Fix: add  if (this.issues == null) this.issues = new ArrayList<>();
     */
    public void addIssue(CodeIssue issue) {
        this.issues.add(issue); // ← will throw NPE if issues is null
    }
}
