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
# Code Review Agent

> A LangGraph-style multi-node AI agent built with Spring Boot + Java 21 that analyses source code through specialist LLM nodes and delivers structured reliability findings — including 5 documented production failure modes.

[![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen?style=flat-square)](https://spring.io/projects/spring-boot)
[![LiteLLM](https://img.shields.io/badge/LiteLLM-gateway-blue?style=flat-square)](https://litellm.ai)
[![OpenRouter](https://img.shields.io/badge/OpenRouter-multi--model-purple?style=flat-square)](https://openrouter.ai)
[![Valkey](https://img.shields.io/badge/Valkey-cache-red?style=flat-square)](https://valkey.io)
[![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)](LICENSE)

---

## What this is

Most AI agent tutorials show you a working happy path. This project shows you what breaks in production — and how to find it before your users do.

This is a **portfolio + audit demonstration project** built alongside [AgentAudit](https://agentaudit.io), an AI agent reliability audit service. The agent is real and fully runnable. It also contains **5 deliberate reliability flaws** that mirror the most common issues found in production LangGraph and multi-agent systems.

The companion `AUDIT_REPORT.md` is the actual audit deliverable — an 8-section reliability report with scored findings, compound failure math, and a prioritised fix roadmap.

---

## Agent graph

```
POST /api/v1/review
         │
         ▼
 ┌────────────────────────────────────────────┐
 │           AgentGraphService                │
 │        (LangGraph-style orchestrator)      │
 │                                            │
 │  [START]                                   │
 │     │                                      │
 │     ▼                                      │
 │  SyntaxAnalyserNode                        │
 │     │                                      │
 │     ▼                                      │
 │  SecurityScanNode                          │
 │     │                                      │
 │     ▼                                      │
 │  QualityReviewNode                         │
 │     │                                      │
 │     ▼                                      │
 │  SummaryNode ──► finalReport               │
 │     │                                      │
 │   [END]                                    │
 └────────────────────────────────────────────┘
         │                      │
         ▼                      ▼
   LiteLLMService         ValkeyCacheService
   (LLM gateway)          (session + cache)
         │
         ▼
   OpenRouter API
```

Each node reads from a shared `AgentState` object, calls LiteLLM with a focused prompt, writes structured findings back, and passes control to the next node via conditional edges — the same pattern as LangGraph's `StateGraph` in Python, implemented in plain Java.

---

## The 5 production flaws (audit demo)

| # | File | Flaw | Production impact |
|---|---|---|---|
| 1 | `AgentState.java` | `issues` list initialised as `null` → NPE when LLM call returns null | Silent graph crash, no useful error |
| 2 | `ReviewNodes.java` | No per-node iteration guard | Runaway loop fires 46 extra LLM calls before graph-level guard fires |
| 3 | `LiteLLMService.java` | Fallback to OpenRouter exists but is never wired | Zero resilience against 429 rate limits |
| 4 | `LiteLLMService.java` | `ValkeyCacheService` is never called for LLM responses | Every identical prompt hits the API — 40–60% cost overspend |
| 5 | `application.properties` | Debug logging commented out | Tool call inputs/outputs invisible in production traces |

All flaws are labelled `// AUDIT FLAW #N` in the source and fully documented in [`AUDIT_REPORT.md`](AUDIT_REPORT.md).

---

## Quick start

### Prerequisites
- Java 21
- Maven 3.9+
- Docker (for Valkey)
- OpenRouter API key (free at [openrouter.ai](https://openrouter.ai))

### 1. Start Valkey
```bash
docker run -d --name valkey -p 6379:6379 valkey/valkey:latest
```

### 2. Configure
Edit `src/main/resources/application.properties`:
```properties
litellm.base-url=https://openrouter.ai/api/v1
litellm.api-key=sk-or-YOUR-KEY-HERE
litellm.model.primary=meta-llama/llama-3.1-8b-instruct:free
openrouter.api-key=sk-or-YOUR-KEY-HERE
```

### 3. Build & run
```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

### 4. Submit code for review
```bash
curl -X POST http://localhost:8080/api/v1/review \
  -H "Content-Type: application/json" \
  -d '{
    "sourceCode": "public class UserService { private String dbPassword = \"admin123\"; public void connect() { System.out.println(dbPassword); } }",
    "language": "java"
  }'
```

**Windows PowerShell:**
```powershell
Invoke-WebRequest -Uri "http://localhost:8080/api/v1/review" `
  -Method POST -ContentType "application/json" `
  -Body '{"sourceCode": "public class Foo { String pw = \"admin\"; }", "language": "java"}' `
  | Select-Object -Expand Content
```

### 5. Watch the graph execute
In the server logs you'll see each node fire in sequence:
```
[syntax-analyser]  Calling LiteLLM...
[syntax-analyser]  Syntax analysis complete
[security-scan]    Calling LiteLLM...
[security-scan]    Security scan complete
[quality-review]   Calling LiteLLM...
[quality-review]   Quality review complete
[summary]          Building final report...
Graph END — sessionId=xxx issues=4 durationMs=5210
```

---

## API reference

### `POST /api/v1/review`

**Request**
```json
{
  "sourceCode": "string — raw source code",
  "language": "java | python | javascript | ..."
}
```

**Response**
```json
{
  "sessionId": "uuid",
  "language": "java",
  "totalIssues": 4,
  "criticalCount": 1,
  "highCount": 2,
  "mediumCount": 1,
  "lowCount": 0,
  "issues": [
    {
      "severity": "CRITICAL",
      "category": "SECURITY",
      "title": "Hardcoded credential",
      "description": "Database password stored as a string literal",
      "suggestedFix": "Use environment variables or a secrets manager",
      "lineNumber": 2,
      "detectedBy": "security-scan"
    }
  ],
  "finalReport": "# Code Review Report\n...",
  "stopReason": "Complete",
  "durationMs": 5210
}
```

### `GET /api/v1/review/health`
```json
{ "status": "UP", "agent": "code-review-agent", "version": "1.0.0" }
```

---

## Project structure

```
src/main/java/com/agentaudit/codereview/
├── CodeReviewAgentApplication.java
├── agent/
│   ├── AgentGraphService.java      ← orchestrator / edge logic
│   ├── ReviewNodes.java            ← Syntax + Security + Quality nodes
│   └── SummaryNode.java            ← final synthesis node
├── config/AppConfig.java
├── controller/CodeReviewController.java
├── model/
│   ├── AgentState.java             ← shared graph state
│   └── CodeIssue.java
└── service/
    ├── LiteLLMService.java         ← LLM gateway (flaws #3, #4, #5)
    └── ValkeyCacheService.java     ← Valkey/Redis cache wrapper
```

---

## Stack

| Layer | Technology | Why |
|---|---|---|
| Framework | Spring Boot 3.2, Java 21 | Enterprise-grade, familiar to most teams |
| Agent orchestration | LangGraph-style StateGraph (Java) | Shows the pattern in a language Python tutorials ignore |
| LLM gateway | LiteLLM → OpenRouter | Provider-agnostic routing, cost control |
| Cache / session | Valkey (Redis-compatible) | Fast state checkpointing between nodes |
| API | Spring MVC REST | Standard, testable, documentable |

---

## The audit report

[`AUDIT_REPORT.md`](AUDIT_REPORT.md) is a full 8-section AI agent reliability audit of this codebase — the same format used by [AgentAudit](https://agentaudit.io) for client engagements.

It includes:
- Scored findings per section (42/100 overall — not production-ready)
- Compound failure probability calculation
- Copy-paste fixes with estimated effort per issue
- Prioritised roadmap: fix this week / fix this sprint / tech debt

---

## About

Built by **Anirban Das** — Software Consultant specialising in AI agent reliability.

[LinkedIn](https://www.linkedin.com/in/anirbandas1986) · [AgentAudit](https://agentaudit.io)

> If your team is shipping a LangGraph or multi-agent system to production, I run fixed-scope reliability audits — tool call validation, cost leak detection, cascade failure analysis. $497 flat, 5-day delivery.
