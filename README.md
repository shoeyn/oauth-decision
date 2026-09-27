# Decision Engine: Declarative Orchestration for OAuth & Session Flows

**Decision Engine** is a lightweight, language-agnostic decision engine designed for OAuth servers, identity verification, and multi-step session orchestration. It evaluates the current session state and context, executes look-ahead compilation, and determines what additional data needs to be gathered or what backend checks should run.

---

## Key Capabilities

1. **Declarative State Charts**: Workflows are stored as language-agnostic YAML or JSON state charts with zero host-language dependencies.
2. **Google CEL for Conditional Guards**: `if` statements and payload templates are evaluated with [Common Expression Language (CEL)](https://github.com/google/cel-spec) (`results.riskScore >= 50 && !context.user.mfaEnrolled`), ensuring identical execution semantics across Java, Ruby, and TypeScript.
3. **Server-Driven UI**: Emits standard **JSON Schema** (for input types, required fields, and regex patterns) alongside **UI Schema** (for widget selectors and placeholders) so any frontend client can dynamically render forms.
4. **Composite Step Bundling**: Bundles multiple backend commands AND multiple frontend steps together into a single `ExecutionPlan` before yielding.
5. **"Come Back To Me" Checkpoint Pattern**: Yields explicit checkpoints whenever human interaction or external async backend results are required to evaluate subsequent branches.
6. **Reusable Subflows & Call Stack**: Call independent, reusable subflows (e.g. OTP, Passkey, Consent) from any point in a flow. The engine maintains an execution call stack and dynamically returns to the exact calling point upon completion.
7. **Shared Session Context**: Subflows seamlessly read from and write to the shared session data, eliminating manual parameter mapping.
8. **Modular File Splitting (`includes`)**: Split large monolithic state machines across multiple YAML fragment files merged at parse time.
9. **Pluggable Flow Registries**: Discover and resolve flows on demand via classpath (`ClasspathFlowRegistry`), filesystem directory (`FileSystemFlowRegistry`), or in-memory map (`InMemoryFlowRegistry`).
10. **Pure Inversion of Control (IoC)**: The engine does not make network calls or touch databases; it emits pure command descriptors for the host OAuth server to execute.

---

## Architecture

### 1. Core Turn-Based Evaluation Loop

```mermaid
sequenceDiagram
    autonumber
    participant Host as OAuth Server (Host)
    participant Engine as Decision Engine
    participant Ext as Microservices (SMS / Fraud)
    participant UI as Frontend Client

    Host->>Engine: evaluate(flow, state, context, event)
    Note over Engine: Look-ahead compiler bundles:<br/>1. Backend Command (Trigger SMS)<br/>2. Frontend Form (OTP JSON Schema + UI Schema)<br/>3. Checkpoint (Verify OTP)
    Engine-->>Host: ExecutionPlan {<br/>  backendSteps: [Trigger SMS],<br/>  frontendSteps: [OTP JSON Schema + UI Schema],<br/>  checkpoint: "verify_otp_checkpoint"<br/>}
    
    par Concurrent Execution by Host
        Host->>Ext: Dispatch "Trigger SMS"
        Host->>UI: Render OTP Form via JSON Schema + UI Schema
    end

    UI-->>Host: User inputs "123456" and submits
    Note over Host: Host hits the "Come back to me" checkpoint
    Host->>Engine: evaluate(flow, "verify_otp_checkpoint", context, submitEvent)
    Note over Engine: Validates regex, evaluates verification step<br/>Emits final terminal claims
    Engine-->>Host: ExecutionPlan { terminal: SUCCESS, claims: { acr: "level2", amr: ["pwd", "otp"] } }
```

---

### 2. Reusable Subflows & Call Stack Return

```mermaid
sequenceDiagram
    autonumber
    participant Host as OAuth Server
    participant Engine as Decision Engine
    participant Registry as Flow Registry
    participant Stack as Call Stack (SessionContext)

    Note over Host,Engine: 1. Parent Flow Reaches SUBFLOW State
    Host->>Engine: evaluate("login_flow", null, context, startEvent)
    Note over Engine: Enters state "require_mfa" (type: SUBFLOW)<br/>Identifies target subflow: "reusable_otp_flow"
    Engine->>Stack: Push StackFrame { flowId: "login_flow", returnStateId: "require_mfa" }
    Engine->>Registry: getFlow("reusable_otp_flow")
    Registry-->>Engine: otpFlow
    Note over Engine: Switches active flow to "reusable_otp_flow"<br/>Enters initial state "prompt_otp" (type: FRONTEND)
    Engine-->>Host: ExecutionPlan {<br/>  checkpoint: "prompt_otp" in "reusable_otp_flow",<br/>  frontendSteps: [OTP JSON Schema + UI Schema]<br/>}

    Note over Host: 2. Human Interaction & Resume
    Host-->>Host: User inputs OTP code and submits
    Host->>Engine: evaluate("login_flow", "prompt_otp", updatedContext, submitEvent)
    Note over Engine: Context indicates active flow is "reusable_otp_flow"<br/>Validates OTP input schema & merges into shared session<br/>Executes verification backend step -> reaches otp_success (TERMINAL)
    
    Note over Engine,Stack: 3. Dynamic Return to Calling State
    Engine->>Stack: Has active call stack? YES -> Pop StackFrame
    Stack-->>Engine: StackFrame { flowId: "login_flow", returnStateId: "require_mfa" }
    Engine->>Registry: getFlow("login_flow")
    Note over Engine: Restores parent flow at "require_mfa"<br/>Matches transition: outcome == SUCCESS<br/>Transitions to "issue_tokens" (TERMINAL)
    Engine-->>Host: ExecutionPlan {<br/>  terminal: SUCCESS,<br/>  claims: { sub: "user_alice", acr: "level2" }<br/>}
```

---

## State Types Reference

| State Type | Description |
| :--- | :--- |
| `BACKEND` | Declares commands for the host to dispatch to backend microservices. |
| `FRONTEND` | Emits Server-Driven UI schemas (`jsonSchema` + `uiSchema`) for client rendering. |
| `COMPOSITE` | Combines both backend commands AND frontend screens in a single turn. |
| `DECISION_FORK` | Pure branching node evaluated with CEL conditions without external steps. |
| `SUBFLOW` | Suspends current flow, pushes a call stack frame, and delegates to a child flow. |
| `TERMINAL` | Concludes flow execution and resolves terminal status, OAuth claims, or error messages. |

---

## Transition Evaluation, Fast-Pathing & Step Bypassing

Every state defines an `on:` list of transition rules. Because the Decision Engine employs look-ahead compilation, **any bypassed step is completely skipped**—its backend commands are never dispatched and its frontend screens are never rendered.

### Evaluation Namespaces

Inside `if` conditions (evaluated via Google CEL), four distinct namespaces are available:

| Namespace | Source | Example Use Case | Example CEL Expression |
| :--- | :--- | :--- | :--- |
| `results.<cmdId>.<field>` | Asynchronous backend command responses | Fast-pathing low-risk users or failing fast on fraud | `results.fraud_check.riskScore < 30` |
| `input.<fieldName>` | User form submissions | Bypassing optional steps if the user clicks "Skip" | `input.action == 'SKIP'` |
| `context.<variable>` | Shared session context | Skipping steps if device is trusted or IP is internal | `context.deviceTrusted == true` |
| `outcome` / `subflow.<field>`| Completed subflow terminal status | Routing to success or fallback based on subflow result | `outcome: SUCCESS` or `subflow.status == 'SUCCESS'` |

### Bypassing Patterns

#### 1. Fast-Pathing (Happy Path Bypasses MFA)
```yaml
assess_risk:
  type: BACKEND
  commands:
    - id: risk_engine
      service: fraud-service
      payload: { ip: "${context.ip}" }
  on:
    - if: "results.risk_engine.score < 25"
      target: issue_tokens               # Bypasses all MFA steps entirely!
    - default: true
      target: require_mfa_challenge
```

#### 2. Failing Fast (Immediate Lockout)
```yaml
evaluate_credentials:
  type: BACKEND
  commands:
    - id: verify_pwd
      service: auth-service
  on:
    - if: "results.verify_pwd.accountLocked == true"
      target: account_locked_screen      # Bypasses remaining checks, locks immediately
    - default: true
      target: check_second_factor
```

#### 3. User-Driven Opt-Out / Skip
```yaml
prompt_biometric_setup:
  type: FRONTEND
  schemas: [ ... ]
  on:
    - if: "input.choice == 'REMIND_LATER'"
      target: dashboard_token            # Bypasses enrollment
    - default: true
      target: register_passkey_subflow
```

#### 4. Context Updates on Transition
Transitions can also mutate the shared session context using CEL expressions via `contextUpdates`:
```yaml
on:
  - if: "results.verify_code.valid == true"
    target: issue_tokens
    contextUpdates:
      mfaVerified: "true"
      authTime: "now()"
      assuranceLevel: "'urn:auth:level2'"
```

---

## Flow Definition Examples

### 1. Main Flow Calling a Reusable Subflow (`main_login_flow.yaml`)

```yaml
id: main_login_flow
version: "1.0.0"
name: "Primary Login Flow"
initialState: assess_login_risk

states:
  assess_login_risk:
    type: BACKEND
    commands:
      - id: fraud_check
        service: fraud-engine
        payload:
          ip: "${context.ip}"
          userId: "${context.userId}"
    on:
      - if: "results.fraud_check.riskScore >= 50"
        target: invoke_mfa
      - default: true
        target: issue_standard_token

  invoke_mfa:
    type: SUBFLOW
    subflow: reusable_otp_flow
    on:
      - outcome: SUCCESS
        target: issue_stepup_token
      - outcome: DENIED
        target: mfa_rejected

  issue_standard_token:
    type: TERMINAL
    terminal:
      status: SUCCESS
      claims:
        sub: "${context.userId}"
        acr: "urn:auth:level1"

  issue_stepup_token:
    type: TERMINAL
    terminal:
      status: SUCCESS
      claims:
        sub: "${context.userId}"
        acr: "urn:auth:level2"
        mfa_method: "${context.mfaMethod}"

  mfa_rejected:
    type: TERMINAL
    terminal:
      status: DENIED
      error: "Authentication failed: MFA challenge rejected"
```

### 2. Reusable Subflow (`reusable_otp_flow.yaml`)

This flow is completely independent and can be called from login, password reset, or transaction approval:

```yaml
id: reusable_otp_flow
version: "1.0.0"
initialState: prompt_otp

states:
  prompt_otp:
    type: FRONTEND
    schemas:
      - screenId: otp_entry_screen
        title: "Two-Factor Verification Required"
        description: "Enter the 6-digit passcode sent to your phone."
        jsonSchema:
          type: object
          required: [otpCode]
          properties:
            otpCode:
              type: string
              pattern: "^[0-9]{6}$"
              title: "6-Digit Security Code"
        uiSchema:
          otpCode:
            "ui:widget": "otp"
            "ui:placeholder": "123456"
            "ui:autofocus": true
    on:
      - event: SUBMIT
        target: verify_otp

  verify_otp:
    type: BACKEND
    commands:
      - id: verify_code
        service: otp-service
        payload:
          userId: "${context.userId}"
          code: "${context.otpCode}"
    on:
      - if: "results.verify_code.valid == true"
        target: otp_success
        contextUpdates:
          mfaMethod: "'SMS_OTP'"
      - default: true
        target: otp_failed

  otp_success:
    type: TERMINAL
    terminal:
      status: SUCCESS

  otp_failed:
    type: TERMINAL
    terminal:
      status: DENIED
      error: "Invalid security passcode provided."
```

### 3. Modular File Composition (`includes`)

```yaml
id: main_flow_with_modules
initialState: start
includes:
  - "/flows/common_error_handlers.yaml"
  - "/flows/consent_screens.yaml"

states:
  start:
    type: DECISION_FORK
    on:
      - if: "!context.accountActive"
        target: access_denied_screen   # Defined in common_error_handlers.yaml
      - default: true
        target: show_consent_screen    # Defined in consent_screens.yaml
```

---

## Java Host Integration

```java
// 1. Initialize Flow Registry and Decision Engine
FlowRegistry registry = new ClasspathFlowRegistry()
        .withResource("/flows/main_login_flow.yaml")
        .withResource("/flows/reusable_otp_flow.yaml");

DecisionEngine engine = new DecisionEngine(registry);

// 2. Initial Turn: OAuth request arrives
SessionContext session = new SessionContext(Map.of(
        "userId", "user_12345",
        "ip", "192.168.1.50"
));

ExecutionPlan plan = engine.evaluate("main_login_flow", null, session, Event.start());

// 3. Execution Loop
while (!plan.isTerminal()) {
    // Dispatch any backend commands in parallel
    for (BackendStep cmd : plan.getBackendSteps()) {
        microserviceClient.dispatch(cmd.getService(), cmd.getPayload());
    }

    // Render frontend forms to user if present
    if (plan.hasFrontendSteps()) {
        renderFormToUser(plan.getFrontendSteps());
        // Wait for user submission...
        UserInput input = awaitUserSubmission();
        plan = engine.evaluate(
                "main_login_flow",
                plan.getCheckpoint().getResumeState(),
                plan.getUpdatedContext(),
                Event.submit(input.getFields())
        );
    } else if (plan.hasCheckpoint()) {
        // Wait for async backend results...
        Map<String, Object> results = awaitBackendResults();
        plan = engine.evaluate(
                "main_login_flow",
                plan.getCheckpoint().getResumeState(),
                plan.getUpdatedContext(),
                Event.resume(results)
        );
    }
}

// 4. Issue OAuth Tokens or Deny
TerminalResult outcome = plan.getTerminal();
if ("SUCCESS".equals(outcome.getStatus())) {
    return oauthTokenIssuer.issue(outcome.getClaims());
} else {
    return oauthErrorResponse.deny(outcome.getError());
}
```

---

## Stateless Session Persistence & Security

### 1. Redis / Cookie JSON Roundtripping
OAuth servers run over stateless HTTP. All runtime models ([SessionContext](file:///Users/nathanshoemark/Pathfinder/src/main/java/io/pathfinder/engine/runtime/SessionContext.java), [Checkpoint](file:///Users/nathanshoemark/Pathfinder/src/main/java/io/pathfinder/engine/runtime/Checkpoint.java), [ExecutionPlan](file:///Users/nathanshoemark/Pathfinder/src/main/java/io/pathfinder/engine/runtime/ExecutionPlan.java)) feature complete Jackson `@JsonCreator` and `@JsonProperty` decorators:

```java
// Persisting to Redis or encrypted cookie between turns:
String sessionJson = objectMapper.writeValueAsString(plan.getUpdatedContext());
String checkpointJson = objectMapper.writeValueAsString(plan.getCheckpoint());

// Restoring on the next HTTP POST request:
SessionContext session = objectMapper.readValue(sessionJson, SessionContext.class);
Checkpoint checkpoint = objectMapper.readValue(checkpointJson, Checkpoint.class);
```

### 2. Sensitive Data Redaction & Logging Safety
The engine protects passwords, OTP codes, and client secrets from leaking to log aggregators:

* **Automatic Masking**: Fields matching common credential names (`password`, `otpCode`, `pin`, `secret`, `ssn`, `cvv`) are tracked as sensitive.
* **Safe Log Output**: `sessionContext.toString()` and `sessionContext.toSafeMap()` automatically replace sensitive values with `"[REDACTED]"`. Backend commands still access raw credentials when dispatching to auth services.
* **Explicit Data Scrubbing**: Once verification succeeds, credentials can be completely purged from the session dictionary:
  ```java
  SessionContext cleanSession = session.without("password", "otpCode");
  ```

### 3. Built-In Security Guarantees
* **Path Traversal Protection (CWE-22)**: File includes containing directory traversal sequences (`..`) or protocol schemes (`://`) are rejected with a `SecurityException`.
* **Circular Include Protection (CWE-674)**: Recursive includes (`flowA -> flowB -> flowA`) are detected during parse time to prevent `StackOverflowError` DoS attacks.
* **Infinite Loop & Cycle Guard**: Decision loops without external human checkpoints are halted safely after one cycle with an explicit `cycle_detected` checkpoint.
* **Bounded Program Cache (CWE-400)**: The CEL compiler's compiled AST cache is capped at 1,000 entries to prevent memory exhaustion.
* **Resilient CEL Guard Evaluation**: Conditions referencing missing properties safely evaluate to `false` rather than crashing the execution turn.

---

## Server-Driven UI & Localization Pattern

For multi-language applications (e.g. Rails / Ruby UI frontends):
* **Emit Locale Keys**: Define schemas using translation keys (e.g. `title: "screens.otp.title"`, `description: "screens.otp.description"`).
* **Client-Side Localization**: The frontend resolves text keys against its standard locale files (`en.yml`, `es.yml`) alongside any dynamic session attributes passed in the execution plan.

---

## Multi-Language Ecosystem Parity

| Layer | Java | Ruby (`decision-engine-rb`) | TypeScript (`@decision-engine/core`) |
| :--- | :--- | :--- | :--- |
| **CEL Expressions** | `dev.cel:cel` | `gem 'cel'` | `cel-js` / `@google/cel` |
| **JSON Schema Validation** | `json-schema-validator` | `gem 'json_schemer'` | `ajv` |
| **YAML / JSON Parser** | Jackson | `YAML` / `JSON` (stdlib) | `yaml` / native `JSON` |
| **Execution Loop** | Stateless pure function | Stateless pure function | Stateless pure function |
| **Call Stack** | `SessionContext` / `StackFrame` | Hash / Array stack | Object / Array stack |

---

## Running the Tests

```bash
mvn clean test
```
