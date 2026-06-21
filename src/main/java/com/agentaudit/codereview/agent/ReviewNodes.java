package com.agentaudit.codereview.agent;

import com.agentaudit.codereview.model.AgentState;
import com.agentaudit.codereview.model.CodeIssue;
import com.agentaudit.codereview.service.LiteLLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ═══════════════════════════════════════════════════════════════
 *  GRAPH NODES — the core of the LangGraph-style agent
 * ═══════════════════════════════════════════════════════════════
 *
 * Each node:
 *   1. Reads from AgentState (input)
 *   2. Calls LiteLLM with a focused prompt (tool call)
 *   3. Parses the response and writes findings back to AgentState
 *   4. Returns the updated state to AgentGraphService (the orchestrator)
 *
 * Graph topology:
 *
 *   [START]
 *      │
 *      ▼
 *   SyntaxAnalyserNode ──► SecurityScanNode ──► QualityReviewNode
 *                                                       │
 *                                                       ▼
 *                                                  SummaryNode
 *                                                       │
 *                                                     [END]
 *
 * AUDIT FLAWS embedded in these nodes (labelled inline):
 *   FLAW #1 — No null guard on LLM response before calling addIssue()
 *   FLAW #2 — No per-node iteration check; relies only on graph-level guard
 */

// ── Node 1: Syntax Analysis ───────────────────────────────────────────────

@Slf4j
@Component
@RequiredArgsConstructor
class SyntaxAnalyserNode {

    private final LiteLLMService llmService;

    static final String NODE_NAME = "syntax-analyser";

    /**
     * Analyses the source code for syntax errors and style violations.
     *
     * AUDIT FLAW #1 demonstration:
     *   llmService.complete() can return null if the LLM call fails.
     *   This node writes the null directly into state.syntaxAnalysis
     *   without checking. When SummaryNode later tries to read
     *   state.getSyntaxAnalysis(), it receives null and may NPE.
     */
    public AgentState execute(AgentState state) {
        log.info("[{}] Starting syntax analysis — session={}", NODE_NAME, state.getSessionId());
        state.setCurrentNode(NODE_NAME);
        state.incrementIteration();

        String systemPrompt = """
                You are a senior code reviewer specialising in syntax and style.
                Analyse the provided code and return a structured list of issues.
                Format each issue as: SEVERITY|TITLE|DESCRIPTION|LINE|FIX
                Use severities: CRITICAL, HIGH, MEDIUM, LOW, INFO.
                Be concise. Return 2–5 issues maximum.
                """;

        String userPrompt = String.format("""
                Language: %s
                
                Code to review:
                ```
                %s
                ```
                
                List all syntax and style issues you find.
                """, state.getLanguage(), state.getSourceCode());

        // AUDIT FLAW #1: no null check here
        String response = llmService.complete(systemPrompt, userPrompt, NODE_NAME);
        state.setSyntaxAnalysis(response); // ← null is silently stored if call failed

        // Parse response into structured CodeIssue objects
        if (response != null) {
            parseAndAddIssues(response, state, CodeIssue.Category.STYLE);
        }
        // FLAW #1: if response is null, we skip silently — no alert, no flag in state

        log.info("[{}] Syntax analysis complete", NODE_NAME);
        return state;
    }

    private void parseAndAddIssues(String response, AgentState state, CodeIssue.Category category) {
        for (String line : response.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 4) {
                try {
                    CodeIssue issue = CodeIssue.builder()
                            .severity(CodeIssue.Severity.valueOf(parts[0].trim()))
                            .category(category)
                            .title(parts[1].trim())
                            .description(parts[2].trim())
                            .suggestedFix(parts.length > 4 ? parts[4].trim() : "See description")
                            .detectedBy(NODE_NAME)
                            .build();
                    state.addIssue(issue); // ← FLAW #1: NPE if state.issues is null
                } catch (Exception e) {
                    // Parse failure is silently swallowed — FLAW #5: not logged
                }
            }
        }
    }
}

// ── Node 2: Security Scan ─────────────────────────────────────────────────

@Slf4j
@Component
@RequiredArgsConstructor
class SecurityScanNode {

    private final LiteLLMService llmService;

    static final String NODE_NAME = "security-scan";

    /**
     * Scans for security vulnerabilities: injection, auth flaws, secrets, etc.
     *
     * AUDIT FLAW #2 demonstration:
     *   This node does NOT check state.hasExceededMaxIterations() before
     *   running. In a loop scenario (e.g. if the graph is re-routed back
     *   here), it will keep firing LLM calls indefinitely until the
     *   graph-level guard (max-iterations=50) finally catches it.
     *   50 iterations × ~$0.002/call = $0.10 per runaway session.
     *   At 100 concurrent users, that's $10 in wasted API spend per minute.
     */
    public AgentState execute(AgentState state) {
        log.info("[{}] Starting security scan — session={}", NODE_NAME, state.getSessionId());
        state.setCurrentNode(NODE_NAME);
        state.incrementIteration();

        // FLAW #2: missing per-node guard:
        // if (state.hasExceededMaxIterations(MAX_NODE_ITERATIONS)) {
        //     state.setShouldStop(true);
        //     state.setStopReason("SecurityScanNode exceeded max iterations");
        //     return state;
        // }

        String systemPrompt = """
                You are a security engineer performing a code security audit.
                Look for: SQL injection, XSS, hardcoded secrets, insecure auth,
                path traversal, unsafe deserialization, and OWASP Top 10 issues.
                Format each issue as: SEVERITY|TITLE|DESCRIPTION|LINE|FIX
                Use severities: CRITICAL, HIGH, MEDIUM, LOW.
                """;

        String userPrompt = String.format("""
                Language: %s
                Perform a security review of this code:
                ```
                %s
                ```
                """, state.getLanguage(), state.getSourceCode());

        String response = llmService.complete(systemPrompt, userPrompt, NODE_NAME);
        state.setSecurityAnalysis(response);

        if (response != null) {
            parseAndAddIssues(response, state);
        }

        log.info("[{}] Security scan complete", NODE_NAME);
        return state;
    }

    private void parseAndAddIssues(String response, AgentState state) {
        for (String line : response.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 4) {
                try {
                    CodeIssue issue = CodeIssue.builder()
                            .severity(CodeIssue.Severity.valueOf(parts[0].trim()))
                            .category(CodeIssue.Category.SECURITY)
                            .title(parts[1].trim())
                            .description(parts[2].trim())
                            .suggestedFix(parts.length > 4 ? parts[4].trim() : "See description")
                            .detectedBy(NODE_NAME)
                            .build();
                    state.addIssue(issue);
                } catch (Exception e) {
                    // FLAW #5: parse errors silently swallowed, not logged
                }
            }
        }
    }
}

// ── Node 3: Quality Review ────────────────────────────────────────────────

@Slf4j
@Component
@RequiredArgsConstructor
class QualityReviewNode {

    private final LiteLLMService llmService;

    static final String NODE_NAME = "quality-review";

    /**
     * Reviews code quality: naming, complexity, test coverage gaps, docs.
     *
     * This node is the last analytical step before SummaryNode.
     * It correctly reads prior node outputs from state, but because
     * those outputs may be null (FLAW #1), building a coherent context
     * prompt is fragile.
     */
    public AgentState execute(AgentState state) {
        log.info("[{}] Starting quality review — session={}", NODE_NAME, state.getSessionId());
        state.setCurrentNode(NODE_NAME);
        state.incrementIteration();

        // Attempt to include context from prior nodes — fragile if they returned null
        String priorContext = buildPriorContext(state);

        String systemPrompt = """
                You are a senior software engineer reviewing code quality.
                Look for: poor naming, high cyclomatic complexity, missing error handling,
                lack of documentation, code duplication, and test coverage gaps.
                Format each issue as: SEVERITY|TITLE|DESCRIPTION|LINE|FIX
                """;

        String userPrompt = String.format("""
                Language: %s
                
                Code to review:
                ```
                %s
                ```
                
                Prior analysis context:
                %s
                
                Focus on quality issues not already covered above.
                """, state.getLanguage(), state.getSourceCode(), priorContext);

        String response = llmService.complete(systemPrompt, userPrompt, NODE_NAME);
        state.setQualityAnalysis(response);

        if (response != null) {
            parseAndAddIssues(response, state);
        }

        log.info("[{}] Quality review complete", NODE_NAME);
        return state;
    }

    /**
     * Builds a context string from prior node outputs.
     * Gracefully handles nulls — but only because we're using
     * null-safe string formatting here. SummaryNode is not as careful.
     */
    private String buildPriorContext(AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (state.getSyntaxAnalysis() != null) {
            sb.append("Syntax findings: ").append(state.getSyntaxAnalysis(), 0,
                    Math.min(200, state.getSyntaxAnalysis().length())).append("...\n");
        }
        if (state.getSecurityAnalysis() != null) {
            sb.append("Security findings: ").append(state.getSecurityAnalysis(), 0,
                    Math.min(200, state.getSecurityAnalysis().length())).append("...\n");
        }
        return sb.isEmpty() ? "No prior analysis available." : sb.toString();
    }

    private void parseAndAddIssues(String response, AgentState state) {
        for (String line : response.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 4) {
                try {
                    CodeIssue issue = CodeIssue.builder()
                            .severity(CodeIssue.Severity.valueOf(parts[0].trim()))
                            .category(CodeIssue.Category.QUALITY)
                            .title(parts[1].trim())
                            .description(parts[2].trim())
                            .suggestedFix(parts.length > 4 ? parts[4].trim() : "See description")
                            .detectedBy(NODE_NAME)
                            .build();
                    state.addIssue(issue);
                } catch (Exception e) {
                    // FLAW #5: parse errors silently swallowed
                }
            }
        }
    }
}
