package com.agentaudit.codereview.agent;

import com.agentaudit.codereview.model.AgentState;
import com.agentaudit.codereview.model.CodeIssue;
import com.agentaudit.codereview.service.LiteLLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SummaryNode — the final node in the graph.
 *
 * Reads all prior node outputs from AgentState and asks the LLM
 * to synthesise a structured final report with:
 *   - Executive summary
 *   - Prioritised issue list
 *   - Recommended fix order
 *   - Estimated fix effort
 *
 * This node directly exposes FLAW #1: it tries to read
 * state.getSyntaxAnalysis(), state.getSecurityAnalysis(), and
 * state.getIssues() — all of which may be null if any prior
 * node's LLM call failed and we didn't null-guard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryNode {

    private final LiteLLMService llmService;

    static final String NODE_NAME = "summary";

    public AgentState execute(AgentState state) {
        log.info("[{}] Building final report — session={}", NODE_NAME, state.getSessionId());
        state.setCurrentNode(NODE_NAME);
        state.incrementIteration();

        String systemPrompt = """
                You are a senior engineering lead writing a code review summary report.
                Given the analysis from three specialist reviewers, produce a structured
                final report with:
                
                1. EXECUTIVE SUMMARY (2–3 sentences)
                2. CRITICAL ISSUES (must fix before production)
                3. HIGH PRIORITY ISSUES (fix within this sprint)
                4. MEDIUM/LOW ISSUES (tech debt, fix when convenient)
                5. RECOMMENDED FIX ORDER (numbered list)
                6. ESTIMATED EFFORT (hours per issue category)
                
                Be direct and actionable. No fluff.
                """;

        // Build context from all prior nodes
        // AUDIT FLAW #1: state.getSyntaxAnalysis() may be null here
        // if SyntaxAnalyserNode's LLM call failed. String.format handles
        // null as the string "null" — so the report will say "null" instead
        // of a real analysis. This is a silent data corruption, not a crash.
        String issuesSummary = buildIssuesSummary(state);

        String userPrompt = String.format("""
                Language: %s
                
                === SYNTAX ANALYSIS ===
                %s
                
                === SECURITY ANALYSIS ===
                %s
                
                === QUALITY ANALYSIS ===
                %s
                
                === STRUCTURED ISSUES (%d found) ===
                %s
                
                Write the final code review report.
                """,
                state.getLanguage(),
                state.getSyntaxAnalysis(),   // ← may print "null" silently
                state.getSecurityAnalysis(), // ← may print "null" silently
                state.getQualityAnalysis(),  // ← may print "null" silently
                state.getIssues() != null ? state.getIssues().size() : 0,
                issuesSummary);

        String report = llmService.complete(systemPrompt, userPrompt, NODE_NAME);
        state.setFinalReport(report);

        if (report == null) {
            // Even the summary call failed — set a fallback report
            state.setFinalReport(buildFallbackReport(state));
            log.error("[{}] Summary LLM call failed — using fallback report", NODE_NAME);
        }

        log.info("[{}] Summary complete", NODE_NAME);
        return state;
    }

    /**
     * Builds a plain-text summary of structured issues.
     * Null-safe — will return a message if issues list is null (FLAW #1).
     */
    private String buildIssuesSummary(AgentState state) {
        if (state.getIssues() == null) {
            return "No structured issues collected (state.issues was null — see FLAW #1 in AgentState)";
        }
        if (state.getIssues().isEmpty()) {
            return "No issues found.";
        }
        return state.getIssues().stream()
                .map(issue -> String.format("[%s][%s] %s: %s",
                        issue.getSeverity(),
                        issue.getCategory(),
                        issue.getTitle(),
                        issue.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Fallback report built from structured data when LLM is unavailable.
     * This is the "graceful degradation" that SHOULD exist in all nodes
     * but only exists here because SummaryNode has the final safety net.
     */
    private String buildFallbackReport(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Code Review Report (Fallback — LLM Unavailable)\n\n");
        sb.append("⚠️  The AI summary could not be generated. Structured findings below.\n\n");

        if (state.getIssues() != null && !state.getIssues().isEmpty()) {
            sb.append("## Issues Found\n\n");
            for (CodeIssue issue : state.getIssues()) {
                sb.append(String.format("- **[%s]** %s: %s\n",
                        issue.getSeverity(), issue.getTitle(), issue.getDescription()));
            }
        } else {
            sb.append("No structured issues available.\n");
        }

        return sb.toString();
    }
}
