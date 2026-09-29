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

---

### F. Failure Dropouts: Redirect Failure URLs vs Hard UI Dropouts

When an error, user cancellation, or security violation occurs, Pathfinder provides first-class support for two distinct terminal dropout patterns:

```
+-----------------------------------------------------------------------------------+
|                            TERMINAL STATE OUTCOMES                                |
+------------------------------------------+----------------------------------------+
| 1. Redirect Failure URL                  | 2. Hard UI Dropout                     |
| (RFC 6749 Section 4.1.2.1)               | (Fatal Security / Fraud Lockout)       |
+------------------------------------------+----------------------------------------+
| • User declined consent or cancelled     | • Brute-force max attempts exceeded    |
| • 302 Redirect to Client redirectUri     | • Risk/fraud threshold exceeded        |
| • Returns error & state query params     | • Stays in UI; NO redirect to client   |
| • Evaluated via dynamic CEL template     | • Emits terminal error UI Schema       |
| • terminalResult.isRedirect() == true    | • terminalResult.isUiDropout() == true |
+------------------------------------------+----------------------------------------+
```

#### 1. Redirect Failure URL (`terminalResult.isRedirect()`)

The terminal state declares a `redirectUrl` (or aliases `redirect_url`, `failureUrl`, `failure_url`). The URL string is parsed through Google CEL, dynamically interpolating `config` and `data` parameters:

```yaml
states:
  consent_declined:
    type: TERMINAL
    terminal:
      status: DENIED
      action: REDIRECT
      error: access_denied
      errorDescription: "Resource owner declined consent."
      redirectUrl: "${config.redirectUri}?error=access_denied&error_description=Consent+declined&state=${data.state}"
```

In the host application:
```java
TerminalResult term = plan.getTerminalResult();
if (term.isRedirect()) {
    response.sendRedirect(term.getRedirectUrl());
}
```

In simulation traces:
```
└── [TERMINAL: DENIED] consent_declined [Redirect ➔ https://client.example.com/callback?error=access_denied&state=xyz]
```

#### 2. Hard UI Dropout (`terminalResult.isUiDropout()`)

When an account is suspended or a severe security rule is violated, OAuth specifications and OWASP security guidelines prohibit redirecting back to the client application. The state specifies `action: UI_DROPOUT` (or omits `redirectUrl`) and provides a terminal `schemas:` block:

```yaml
states:
  fraud_lockout:
    type: TERMINAL
    schemas:
      - screenId: fraud_blocked_screen
        title: "Access Blocked"
        description: "Your session was suspended due to anomalous activity. Please contact support."
        jsonSchema:
          type: object
    terminal:
      status: DENIED
      action: UI_DROPOUT
      error: account_locked
      errorDescription: "Anomalous risk score detected."
```

In the host application:
```java
TerminalResult term = plan.getTerminalResult();
if (term.isUiDropout()) {
    // plan.getFrontendSteps() preserves the terminal error screen
    FrontendStep lockoutScreen = plan.getFrontendSteps().getFirst();
    renderTerminalErrorScreen(lockoutScreen, term.getErrorDescription());
}
```

In simulation traces:
```
└── [TERMINAL: DENIED] fraud_lockout [UI Dropout: Stays in UI] (screen: fraud_blocked_screen)
```

#### 3. Flow Simulation & CI/CD Verification
Both dropout patterns are fully traceable in `FlowSimulation`:
```java
FlowSimulation sim = engine.simulate(flow, context, decisions);

// Assert redirect failure URL dropout
assertThat(sim.getTerminalResult().isRedirect()).isTrue();
assertThat(sim.getTerminalResult().getRedirectUrl()).contains("error=access_denied");

// Assert hard UI dropout
assertThat(sim.getTerminalResult().isUiDropout()).isTrue();
assertThat(sim.getScreens()).extracting("screenId").contains("fraud_blocked_screen");
```

---

### G. Server-Driven UI Dynamic CEL Interpolation (`step.getData()`)

Frontend screens defined in YAML are not limited to static strings. The Decision Engine dynamically interpolates Google CEL expressions embedded within screen `title`, `description`, and custom `data` (props/parameters) maps:

```yaml
states:
  prompt_mfa:
    type: FRONTEND
    schemas:
      - screenId: otp_challenge
        title: "Verify Identity for ${config.clientName}"
        description: "Security passcode sent to ${data.emailMasked}."
        data:
          destination: "${data.emailMasked}"
          channel: "SMS"
          maxTries: "${config.maxOtpAttempts}"
        jsonSchema:
          type: object
          required: [otpCode]
          properties:
            otpCode:
              type: string
              pattern: "^[0-9]{6}$"
```

When evaluated, the emitted `FrontendStep` contains fully resolved strings and a resolved `data` map:
```java
FrontendStep step = plan.getFrontendSteps().getFirst();
System.out.println(step.getTitle());       // "Verify Identity for Banking Portal"
System.out.println(step.getDescription()); // "Security passcode sent to a***@example.com."
System.out.println(step.getData());        // {destination: "a***@example.com", channel: "SMS", maxTries: 3}
```

This allows UI frontends (such as Rails ERB, React, or mobile native apps) to display rich, contextual information without hardcoding business copy or re-querying user profiles.

---

### H. Fluent `SessionContext.builder()`

To simplify constructing session contexts in Java host applications, `SessionContext` provides a fluent builder:

```java
SessionContext context = SessionContext.builder()
    .data("userId", "user_123")
    .data("emailMasked", "a***@example.com")
    .data("riskScore", 85)
    .config("clientId", "mobile-app")
    .config("requireMfa", true)
    .transientData("ephemeralAuthNonce", "xyz-789")
    .build();
```

The builder:
* Automatically initialises empty immutable maps if none are provided.
* Allows individual key-value pairs or bulk map insertion (`.data(map)`, `.config(map)`).
* Protects against null pointers while maintaining the strict immutability contract of `SessionContext`.

---

### I. Cross-Service Redis Serialization Resilience

When deploying Pathfinder across distributed architectures (e.g. Spring Boot auth servers, Rails UI frontends, and Python microservices communicating over Redis DB 0), runtime payloads are subject to schema evolution, additional host attributes, and varying casing conventions.

Pathfinder guarantees zero deserialization failures through built-in resilience:
1. **`@JsonIgnoreProperties(ignoreUnknown = true)`**:
   Applied to `SessionContext`, `ExecutionPlan`, `Checkpoint`, `FrontendStep`, `BackendStep`, `StackFrame`, and `TerminalResult`. Host applications can attach extraneous metadata (such as tracing IDs, spans, or server timestamps) without breaking Pathfinder.
2. **Multi-Convention Aliases (`@JsonAlias`)**:
   - `data` parameter in `SessionContext`: accepts `"data"`, `"context"`, `"user"`, and `"attributes"`.
   - `data` parameter in `FrontendStep`: accepts `"data"`, `"props"`, `"parameters"`, `"initialData"`, and `"initial_data"`.
   - `redirectUrl` in `TerminalResult`: accepts `"redirectUrl"`, `"redirect_url"`, `"failureUrl"`, and `"failure_url"`.
   - `transitions` in `StateDefinition`: accepts `"on"` and `"transitions"`.


