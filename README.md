# Code Review Agent — AgentAudit Portfolio Project

A **LangGraph-style multi-node AI agent** built with Spring Boot 3.2 + Java 21.
Analyses source code through three specialist LLM-powered nodes in sequence,
then synthesises a structured final report.

Built and audited by **Anirban Das** as a portfolio piece for AgentAudit —
AI Agent Reliability Audits.

---

## Architecture

```
POST /api/v1/review
        │
        ▼
┌─────────────────────────────────────────────────────────┐
│                   AgentGraphService                      │
│              (LangGraph-style orchestrator)              │
│                                                         │
│  [START]                                                │
│     │                                                   │
│     ▼                                                   │
│  SyntaxAnalyserNode ──► SecurityScanNode                │
│                               │                         │
│                               ▼                         │
│                        QualityReviewNode                │
│                               │                         │
│                               ▼                         │
│                          SummaryNode                    │
│                               │                         │
│                             [END]                       │
└─────────────────────────────────────────────────────────┘
        │                           │
        ▼                           ▼
  LiteLLMService              ValkeyCacheService
  (LLM gateway)               (session + cache)
        │
        ▼
  OpenRouter API
  (model provider)
```

Each node:
1. Reads from `AgentState` (the shared state object)
2. Calls `LiteLLMService.complete()` with a focused prompt
3. Parses the response and writes findings back to `AgentState`
4. Returns updated state to the orchestrator

---

## Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2, Java 21 |
| Agent orchestration | LangGraph-style graph (AgentGraphService) |
| LLM gateway | LiteLLM proxy → OpenRouter |
| Cache / session | Valkey (Redis-compatible, via Spring Data Redis) |
| API | REST (Spring MVC) |
| Build | Maven 3.9 |

---

## Prerequisites

1. **Java 21** (`java -version`)
2. **Maven 3.9+** (`mvn -version`)
3. **Valkey or Redis** running on `localhost:6379`
   ```bash
   # Docker (easiest)
   docker run -d -p 6379:6379 valkey/valkey:latest
   ```
4. **LiteLLM proxy** (or use OpenRouter directly — see config below)
   ```bash
   pip install litellm
   litellm --model openrouter/anthropic/claude-haiku-4-5
   ```
5. **OpenRouter API key** — free at https://openrouter.ai

---

## Quick Start

```bash
# 1. Clone / open the project
cd code-review-agent

# 2. Set your API keys
export LITELLM_API_KEY=your-key
export OPENROUTER_API_KEY=your-key

# 3. Build
mvn clean package -DskipTests

# 4. Run
mvn spring-boot:run

# 5. Test
curl -X POST http://localhost:8080/api/v1/review \
  -H "Content-Type: application/json" \
  -d '{
    "language": "java",
    "sourceCode": "public class Foo { public static void main(String[] args) { String pw = \"admin123\"; System.out.println(pw); } }"
  }'
```

---

## Deliberate Reliability Flaws (for audit demo)

This project contains **5 intentional flaws** that the audit report documents.
They are labelled `// AUDIT FLAW #N` throughout the codebase.

| Flaw | File | Description |
|---|---|---|
| #1 | `AgentState.java`, `ReviewNodes.java` | Null LLM response propagates to NPE |
| #2 | `SecurityScanNode`, `QualityReviewNode` | No per-node iteration guard |
| #3 | `LiteLLMService.java` | Fallback to OpenRouter never wired |
| #4 | `LiteLLMService.java`, `application.properties` | Valkey cache never called; TTL=0 |
| #5 | `application.properties`, `LiteLLMService.java` | Tool call I/O not logged |

The companion file `AUDIT_REPORT.md` is the $497 deliverable — a full
8-section reliability audit of this codebase.

---

## Project Structure

```
src/main/java/com/agentaudit/codereview/
├── CodeReviewAgentApplication.java     # Spring Boot entry point
├── agent/
│   ├── AgentGraphService.java          # Graph orchestrator (LangGraph equivalent)
│   ├── ReviewNodes.java                # Syntax, Security, Quality nodes
│   └── SummaryNode.java                # Final synthesis node
├── config/
│   └── AppConfig.java                  # ObjectMapper + Redis beans
├── controller/
│   └── CodeReviewController.java       # REST API
├── model/
│   ├── AgentState.java                 # Shared state (LangGraph StateGraph equivalent)
│   └── CodeIssue.java                  # Issue data model
└── service/
    ├── LiteLLMService.java             # LLM gateway (contains flaws #3, #4, #5)
    └── ValkeyCacheService.java         # Valkey/Redis wrapper
```

---

## API Reference

### POST /api/v1/review

```json
// Request
{
  "sourceCode": "public class Foo { ... }",
  "language": "java"
}

// Response
{
  "sessionId": "uuid",
  "language": "java",
  "totalIssues": 7,
  "criticalCount": 2,
  "highCount": 3,
  "mediumCount": 2,
  "lowCount": 0,
  "issues": [...],
  "finalReport": "# Code Review Report\n...",
  "stopReason": "Complete",
  "durationMs": 4821
}
```

### GET /api/v1/review/health
```json
{ "status": "UP", "agent": "code-review-agent", "version": "1.0.0" }
```

---

*Part of the AgentAudit portfolio — anirban@agentaudit.io*
