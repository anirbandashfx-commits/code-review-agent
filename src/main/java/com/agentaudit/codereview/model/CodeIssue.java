package com.agentaudit.codereview.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single code issue found during review.
 * Written by any graph node and accumulated in AgentState.issues.
 */
@Data
@Builder
public class CodeIssue {

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    public enum Category { SECURITY, QUALITY, PERFORMANCE, STYLE, BUG }

    private Severity severity;
    private Category category;

    /** Short title — e.g. "SQL Injection Risk" */
    private String title;

    /** Detailed explanation of the issue */
    private String description;

    /** Suggested fix in plain English or code snippet */
    private String suggestedFix;

    /** Line number in the source code, if identifiable */
    private Integer lineNumber;

    /** Which graph node discovered this issue */
    private String detectedBy;
}
