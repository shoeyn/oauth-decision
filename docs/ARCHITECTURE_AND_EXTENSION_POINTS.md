# Pathfinder Decision Engine — Architecture & Extension Points

This document details the architectural layout, core subsystems, and extension points of the **Pathfinder Decision Engine**.

---

## 1. Subsystem Overview

Pathfinder is a pure, domain-agnostic step compiler and decision engine. It has zero coupling to OAuth, HTTP frameworks, or specific databases.

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
[ Host Application / Client ] ──► [ DecisionEngine ]
                                            │
                     ┌──────────────────────┼──────────────────────┐
                     ▼                      ▼                      ▼
              [ CelEvaluator ]       [ SchemaValidator ]   [ SessionContext ]
             (dev.cel:cel engine)   (JSON Schema Val)     (data & client config)
                     │
        ┌────────────┴────────────┐
        ▼                         ▼
 [ evaluate(...) ]          [ simulate(...) ]
   (Step-by-step              (Full trajectory
    runtime compiler)          path projection)
        │                         │
        ▼                         ▼
 [ ExecutionPlan ]          [ FlowSimulation ]
 • BackendSteps             • Execution path list
 • FrontendSteps            • All emitted commands
 • Checkpoint               • All emitted screens
 • TerminalResult           • Final SessionContext
                            • Visual ASCII trace
```

---

## 2. Core Capabilities

### A. Data & Client Configuration Separation

Flow decisions in Pathfinder are determined by passed-in session data and client configuration:

* **`data`**: Dynamic runtime data representing the user, transaction, risk scores, or session attributes.
* **`config`**: Client/tenant policy configuration (e.g. `clientId`, `requireMfa`, `allowedGrantTypes`, `redirectUris`).

Both are first-class, immutable maps on `SessionContext`:
```java
SessionContext context = new SessionContext(
    Map.of("username", "alice", "riskScore", 15),       // data
    Map.of("clientId", "banking-app", "requireMfa", true) // config
);
```

In Google CEL conditions and templates, both `data` and `config` are exposed directly as top-level variables:
```yaml
transitions:
  - if: "config.requireMfa == true || data.riskScore > 50"
    target: MFA_CHALLENGE
  - default: true
    target: GRANT_ACCESS
```

---

### B. Flow Simulation & Full Trajectory Projection

Callers can simulate what an entire flow trajectory would look like given initial `data`, client `config`, and mock decisions:

```java
DecisionEngine engine = new DecisionEngine(flowRegistry);

// 1. Define initial data and client configuration
SessionContext context = new SessionContext(
    Map.of("username", "alice", "riskScore", 75),
    Map.of("clientId", "portal-app", "requireMfa", true)
);

// 2. Define mock decision outcomes for backend commands or frontend forms
Map<String, Object> decisions = Map.of(
    "fetch_user_profile", Map.of("name", "Alice", "status", "ACTIVE"),
    "otp_screen", Map.of("otpCode", "123456"),
    "verify_code", Map.of("valid", true)
);

// 3. Project the complete flow
FlowSimulation simulation = engine.simulate("auth_flow", context, decisions);

// 4. Inspect projected execution
List<String> path = simulation.getExecutionPath(); // ["evaluate_auth", "mfa_challenge", "grant_access"]
boolean success = simulation.isSuccess();
TerminalResult result = simulation.getTerminalResult();

// 5. Generate human-readable visual ASCII trace
System.out.println(simulation.toVisualTrace());
```

If a required decision is omitted, the simulation safely pauses at that checkpoint (`simulation.isCompleted() == false`, `simulation.getPausedCheckpoint()`).

---

### C. Safe Input Scoping & Tampering Protection

* **CWE-915 Defense**: Untrusted form submissions (`Event.submit(payload)`) are validated against declared JSON Schema properties.
* **Context Immutability**: Any parameters submitted by the client that collide with pre-existing server-set context keys (`userId`, `roles`, `riskScore`) are **blocked** from overwriting context values.
* **Namespace Isolation**: Full raw submissions remain isolated in the `input` namespace (`context.getInput()` or CEL `${input.field}`).

---

### D. Brute-Force & Attempt Limiting

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

### E. Common Expression Language (CEL) Engine

Powered by Google CEL (`dev.cel:cel`).

* **Bindings**: Exposes `data`, `config`, `context`, `results`, `input`, `event`, `subflow`, and `outcome`.
* **Number Normalization**: Automatically promotes Java integer types (`Integer`, `Short`, `Byte`) to `Long` (`int64`) for seamless CEL numeric comparisons (`data.riskScore < 50`).
* **Safe Evaluation**: Unresolved variables evaluate gracefully to `false` in conditions rather than crashing the engine.
* **Program Cache**: In-memory `ConcurrentHashMap` with thread-safe compilation.
