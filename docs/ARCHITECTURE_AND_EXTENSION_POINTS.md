# Pathfinder Decision Engine — Architecture & Extension Points

This document details the architectural layout, core subsystems, and extension points of the **Pathfinder Decision Engine**.

---

## 1. Subsystem Overview

```
                                  [ YAML / JSON Flows ]
                                            │
                                            ▼
                                     [ FlowParser ]
                                            │
                                            ▼
                                     [ FlowRegistry ]
                                     (InMemory, Classpath, FileSystem)
                                            │
                                            ▼
[ Host Application / Client ] ──► [ WorkflowOrchestrator ]
                                            │
                     ┌──────────────────────┴──────────────────────┐
                     ▼                                             ▼
             [ DecisionEngine ]                           [ CommandRegistry ]
                     │                                             │
      ┌──────────────┼──────────────┐                              ▼
      ▼              ▼              ▼                       [ CommandHandler ]
 [ CelEvaluator ] [ SchemaVal ] [ SafeContext ]         (Microservice Spring Beans)
                     │
                     ▼
             [ ExecutionPlan ] ──► [ FlowStateRepository ]
             • FrontendSteps        (Redis / Cluster Persistence)
             • BackendSteps
             • Checkpoint
             • TerminalResult
```

---

## 2. Core Extension Points

### A. Command Execution SPI (`io.pathfinder.engine.command`)

Allows external microservices, database repositories, or third-party APIs to be triggered by state definitions.

```java
@FunctionalInterface
public interface CommandHandler {
    Map<String, Object> execute(Map<String, Object> payload) throws Exception;
}
```

* **Registration**: Register via `CommandRegistry.register(serviceId, handler)`.
* **Execution**: Automatically resolved and dispatched by `WorkflowOrchestrator`.
* **Bindings**: Outputs returned from `execute()` are keyed under `results.<stepId>` for downstream CEL transition evaluation.

---

### B. Clustered Persistence SPI (`io.pathfinder.engine.persistence`)

Enables stateful workflow execution across distributed microservice instances and HTTP roundtrips.

```java
public interface FlowStateRepository {
    void save(String flowInstanceId, FlowState state, Duration ttl);
    Optional<FlowState> find(String flowInstanceId);
    void delete(String flowInstanceId);
}
```

* **FlowState**: DTO containing `flowInstanceId`, `currentStateId`, `SessionContext`, `Checkpoint`, and timestamps.
* **Jackson 3**: Native `@JsonCreator` and `@JsonProperty` support for seamless Redis or PostgreSQL JSON column persistence.
* **Implementations**: `InMemoryFlowStateRepository` (local/testing) or custom Redis adapters (see [Spring Security Integration Guide](file:///Users/nathanshoemark/Pathfinder/docs/SPRING_SECURITY_INTEGRATION_GUIDE.md)).

---

### C. OAuth 2.1 & OIDC Bridge (`io.pathfinder.engine.oauth2`)

Utilities for bridging OAuth 2.1 / OIDC protocol parameters with Pathfinder.

* `OAuth2FlowContextHelper.createInitialContext(...)`: Pre-populates `SessionContext` with `clientId`, `userId`, `scopes`, `requestedAcr`, and environment attributes.
* `OAuth2FlowContextHelper.extractClaims(...)`: Extracts verified claims from `TerminalResult`.
* `OAuth2FlowContextHelper.extractAcr(...)` / `extractAmr(...)`: Helper methods for OIDC Token Customizers.

---

### D. Safe Input Scoping & Tampering Protection

* **CWE-915 Defense**: Untrusted form submissions (`Event.submit(payload)`) are checked against the active state's `FrontendSchemaDefinition.getJsonSchema()`.
* **Context Immutability**: Any parameters submitted by the client that collide with pre-existing server-set context keys (`userId`, `roles`, `riskScore`) are **blocked** from overwriting context values.
* **Namespace Isolation**: Full raw submissions remain isolated in the `input` namespace (`context.getInput()` or CEL `${input.field}`).

---

### E. Brute-Force & Attempt Limiting

States can define maximum interaction attempts:

```yaml
states:
  verify_code:
    type: FRONTEND
    maxAttempts: 3
    onError: account_locked_state
```

* Evaluated on each interactive turn (`Event.submit()` or `Event.resume()`).
* Tracks attempts in `SessionContext.getAttemptCount(stateId)`.
* When `attempts > maxAttempts`, automatically transitions to `onError` or yields terminal `DENIED`.

---

### F. Common Expression Language (CEL) Engine

Powered by Google CEL (`dev.cel:cel`).

* **Bindings**: Exposes `context`, `results`, `input`, `event`, `subflow`, and `outcome`.
* **Number Normalization**: Automatically promotes Java integer types (`Integer`, `Short`, `Byte`) to `Long` (`int64`) for flawless CEL numeric comparisons (`riskScore < 50`).
* **Safe Evaluation**: Unresolved variables evaluate gracefully to `false` in conditions rather than crashing the engine.
* **Program Cache**: In-memory `ConcurrentHashMap` with thread-safe compilation.
