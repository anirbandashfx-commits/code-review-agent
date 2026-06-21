package com.agentaudit.codereview.controller;

import com.agentaudit.codereview.agent.AgentGraphService;
import com.agentaudit.codereview.model.AgentState;
import com.agentaudit.codereview.model.CodeIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CodeReviewController exposes the agent graph as a REST API.
 *
 * Endpoints:
 *   POST /api/v1/review        — Submit code for review (runs full graph)
 *   GET  /api/v1/review/health — Simple health check
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class CodeReviewController {

    private final AgentGraphService graphService;

    /**
     * Submit code for a full multi-node AI review.
     *
     * Request body:
     * {
     *   "sourceCode": "public class Foo { ... }",
     *   "language": "java"
     * }
     *
     * Response: ReviewResponse with all findings and the final report.
     */
    @PostMapping
    public ResponseEntity<ReviewResponse> reviewCode(@RequestBody ReviewRequest request) {
        log.info("Review request received — language={} codeLength={}",
                request.language(), request.sourceCode() != null ? request.sourceCode().length() : 0);

        if (request.sourceCode() == null || request.sourceCode().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Instant start = Instant.now();
        AgentState result = graphService.runCodeReview(request.sourceCode(), request.language());
        long durationMs = Duration.between(start, Instant.now()).toMillis();

        ReviewResponse response = ReviewResponse.from(result, durationMs);
        log.info("Review complete — sessionId={} issues={} durationMs={}",
                result.getSessionId(), response.totalIssues(), durationMs);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "agent", "code-review-agent",
                "version", "1.0.0"
        ));
    }

    // ── Request / Response records ────────────────────────────────────

    public record ReviewRequest(String sourceCode, String language) {}

    public record ReviewResponse(
            String sessionId,
            String language,
            int totalIssues,
            int criticalCount,
            int highCount,
            int mediumCount,
            int lowCount,
            List<IssueDTO> issues,
            String finalReport,
            String stopReason,
            long durationMs
    ) {
        static ReviewResponse from(AgentState state, long durationMs) {
            List<CodeIssue> issues = state.getIssues() != null ? state.getIssues() : List.of();

            return new ReviewResponse(
                    state.getSessionId(),
                    state.getLanguage(),
                    issues.size(),
                    countBySeverity(issues, CodeIssue.Severity.CRITICAL),
                    countBySeverity(issues, CodeIssue.Severity.HIGH),
                    countBySeverity(issues, CodeIssue.Severity.MEDIUM),
                    countBySeverity(issues, CodeIssue.Severity.LOW),
                    issues.stream().map(IssueDTO::from).toList(),
                    state.getFinalReport(),
                    state.getStopReason(),
                    durationMs
            );
        }

        private static int countBySeverity(List<CodeIssue> issues, CodeIssue.Severity severity) {
            return (int) issues.stream().filter(i -> i.getSeverity() == severity).count();
        }
    }

    public record IssueDTO(
            String severity,
            String category,
            String title,
            String description,
            String suggestedFix,
            Integer lineNumber,
            String detectedBy
    ) {
        static IssueDTO from(CodeIssue issue) {
            return new IssueDTO(
                    issue.getSeverity().name(),
                    issue.getCategory().name(),
                    issue.getTitle(),
                    issue.getDescription(),
                    issue.getSuggestedFix(),
                    issue.getLineNumber(),
                    issue.getDetectedBy()
            );
        }
    }
}
