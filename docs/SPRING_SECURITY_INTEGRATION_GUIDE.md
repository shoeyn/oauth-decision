# Pathfinder Decision Engine — Spring Security OAuth 2.1 Integration Guide

This guide details how to integrate the **Pathfinder Decision Engine** (`io.pathfinder:pathfinder-engine`) into an enterprise OAuth 2.1 / OIDC Authorization Server such as [spring-auth-server](file:///Users/nathanshoemark/SpringSecurity/spring-auth-server) (built on Java 25, Spring Boot 4, Spring Security 7, Redis, PostgreSQL, and AWS KMS).

---

## 1. Architectural Topology & Sequence

Pathfinder is a pure, domain-agnostic step compiler and decision engine. It does not touch databases, execute network calls, or depend on OAuth 2.1 abstractions. 

In an enterprise OAuth deployment:
1. **Dynamic Risk Assessment**: Evaluates IP, device, and behavioural scores via backend microservice steps.
2. **Conditional Step-Up Challenges**: Bypasses or enforces multi-factor challenges based on user `data` and client `config`.
3. **Reusable Subflows**: Delegated verification flows (e.g. Passkeys, SMS OTP, Biometrics) are invoked seamlessly and return to calling flows.
4. **Server-Driven UI**: Emits JSON Schema and UI Schema descriptors for Rails or frontend clients to render.
5. **Verified Claims Issuance**: Resolves final claims (`sub`, `acr`, `amr`) to be injected into JWTs by Spring Security's `OAuth2TokenCustomizer`.

```
[ User Browser ]
       │
       ▼
1. GET /oauth2/authorize?client_id=portal&acr_values=urn:pathfinder:auth:level2
       │
       ▼
[ Spring Authorization Server ] ── (Checks user session in Redis)
       │
       ├──► [ Pathfinder DecisionEngine ]
       │         │
       │         └──► 2. Emits Plan: BackendStep('check_device_risk')
       │
       ├──► 3. Spring dispatches to FraudEngineService -> riskScore = 75 (High)
       │
       ├──► 4. Resumes DecisionEngine.evaluate(..., Event.resume(results))
       │         │
       │         └──► 5. Yields Checkpoint & FrontendStep: 'otp_entry_screen'
       │
       ▼ 6. Persists context to Redis & redirects to Rails IdP (/challenges/otp?tx=UUID)
[ Rails Identity Provider ]
       │
       ├──► 7. Renders JSON Schema UI (6-digit OTP prompt)
       │
       ▼ 8. User submits OTP
[ Rails Identity Provider ] ──► POST /api/challenges/otp { tx: UUID, otpCode: "123456" }
                                           │
                                           ▼
                            [ Spring Authorization Server ]
                                           │
                                           ├──► Loads SessionContext & Checkpoint from Redis
                                           │
                                           ├──► [ Pathfinder DecisionEngine ]
                                           │         │
                                           │         ├──► Validates JSON Schema & Scopes Input
                                           │         ├──► Emits BackendStep: 'verify_code'
                                           │         │       └──► Spring executes OtpService -> valid: true
                                           │         └──► Terminal: SUCCESS (acr: level2, amr: ["pwd","otp"])
                                           │
                                           ▼
                            [ Spring Authorization Server ]
                                           │
                                           ├──► Injects verified claims in OAuth2TokenCustomizer
                                           ├──► Persists Grant to PostgreSQL
                                           └──► Mints JARM Authorization Response (code=...)
```

---

## 2. Core Integration Components in Pathfinder

| Component | Class | Description |
|---|---|---|
| **Decision Engine** | `io.pathfinder.engine.core.DecisionEngine` | Pure functional look-ahead compiler evaluating state charts and CEL conditions. |
| **Session Context** | `io.pathfinder.engine.runtime.SessionContext` | Immutable snapshot of runtime user `data`, client `config`, transient attributes, and execution call stack. |
| **Flow Simulation** | `io.pathfinder.engine.runtime.FlowSimulation` | Full trajectory path projection tool (`engine.simulate(flow, context, decisions)`). |
| **Safe Input Scoping** | `DecisionEngine` | Automatically prevents Mass Assignment (CWE-915) by verifying declared JSON Schema properties. |
| **Attempt Limiting** | `StateDefinition.getMaxAttempts()` | Built-in brute-force protection with configurable `onError` fallback transitions. |
| **Flow Registry** | `io.pathfinder.engine.registry.FlowRegistry` | Pluggable flow discovery via `ClasspathFlowRegistry` or `FileSystemFlowRegistry`. |

---

## 3. Step-by-Step Implementation in Spring Security

### Step 1: Add Pathfinder Dependency

In `spring-auth-server/pom.xml`:

```xml
<dependency>
    <groupId>io.pathfinder</groupId>
    <artifactId>pathfinder-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

### Step 2: Spring Configuration for Pathfinder

Create `com.example.authserver.config.PathfinderConfig`:

```java
package com.example.authserver.config;

import io.pathfinder.engine.core.DecisionEngine;
import io.pathfinder.engine.registry.ClasspathFlowRegistry;
import io.pathfinder.engine.registry.FlowRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PathfinderConfig {

  @Bean
  public FlowRegistry pathfinderFlowRegistry() {
    return new ClasspathFlowRegistry()
        .withResource("/flows/oauth_stepup_auth.yaml")
        .withResource("/flows/parent_login_flow.yaml")
        .withResource("/flows/mfa_totp_subflow.yaml");
  }

  @Bean
  public DecisionEngine pathfinderDecisionEngine(FlowRegistry flowRegistry) {
    return new DecisionEngine(flowRegistry);
  }
}
```

---

### Step 3: Implement Backend Command Dispatching

Pathfinder emits pure `BackendStep` descriptors containing `service` and `payload`. The host Spring service executes these using Spring-managed beans:

```java
package com.example.authserver.service;

import io.pathfinder.engine.runtime.BackendStep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepExecutionService {

  private final FraudEngineClient fraudEngineClient;
  private final OtpService otpService;
  private final UserDirectoryService userDirectoryService;

  public Map<String, Object> executeBackendSteps(List<BackendStep> steps) {
    Map<String, Object> results = new HashMap<>();

    for (BackendStep step : steps) {
      Map<String, Object> payload = step.getPayload();
      Map<String, Object> stepResult = switch (step.getService()) {
        case "fraud-engine" ->
            Map.of("riskScore", fraudEngineClient.calculateRisk((String) payload.get("userId"), (String) payload.get("ip")));
        case "otp-service" ->
            Map.of("valid", otpService.verifyCode((String) payload.get("userId"), (String) payload.get("code")));
        case "user-directory" ->
            userDirectoryService.fetchProfile((String) payload.get("userId"));
        default ->
            throw new IllegalArgumentException("Unknown backend service: " + step.getService());
      };
      results.put(step.getStepId(), stepResult);
    }

    return results;
  }
}
```

---

### Step 4: Intercepting `/oauth2/authorize` with Pathfinder

Create a Spring Security filter or endpoint to enforce Pathfinder workflows before issuing authorization codes:

```java
package com.example.authserver.security;

import com.example.authserver.service.StepExecutionService;
import io.pathfinder.engine.core.DecisionEngine;
import io.pathfinder.engine.runtime.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
public class PathfinderStepUpAuthorizationFilter extends OncePerRequestFilter {

  private final DecisionEngine engine;
  private final StepExecutionService stepExecutionService;
  private final StringRedisTemplate redisTemplate;
  private final String railsChallengeUrl;
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  private static final String REDIS_PREFIX = "pathfinder:tx:";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/oauth2/authorize");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }

    String clientId = request.getParameter("client_id");
    String txId = request.getParameter("flow_tx");

    // 1. Resuming existing workflow from interactive challenge submission
    if (txId != null && !txId.isBlank()) {
      String cachedJson = redisTemplate.opsForValue().get(REDIS_PREFIX + txId);
      if (cachedJson != null) {
        FlowTransaction tx = objectMapper.readValue(cachedJson, FlowTransaction.class);
        request.setAttribute("PATHFINDER_CLAIMS", tx.claims());
        filterChain.doFilter(request, response);
        return;
      }
    }

    // 2. Initialize Pathfinder session with clean data and client config separation
    Map<String, Object> sessionData = Map.of(
        "userId", auth.getName(),
        "ip", request.getRemoteAddr()
    );

    Map<String, Object> clientConfig = Map.of(
        "clientId", clientId != null ? clientId : "default",
        "requireMfa", "urn:pathfinder:auth:level2".equalsIgnoreCase(request.getParameter("acr_values"))
    );

    SessionContext session = new SessionContext(sessionData, clientConfig);

    // 3. Initial evaluation turn
    ExecutionPlan plan = engine.evaluate("oauth-stepup-auth", null, session, Event.start());

    // 4. Execution loop: dispatch backend steps until a checkpoint or terminal state
    while (!plan.isTerminal() && plan.hasBackendSteps() && !plan.hasFrontendSteps()) {
      Map<String, Object> results = stepExecutionService.executeBackendSteps(plan.getBackendSteps());
      plan = engine.evaluate(
          "oauth-stepup-auth",
          plan.getCheckpoint().getResumeState(),
          plan.getUpdatedContext(),
          Event.resume(results)
      );
    }

    // 5. If terminal, handle success, failure redirect, or hard UI dropout
    if (plan.isTerminal()) {
      TerminalResult term = plan.getTerminalResult();

      // Case A: Terminal Success -> attach verified claims for OAuth2TokenCustomizer
      if ("SUCCESS".equalsIgnoreCase(term.getStatus())) {
        request.setAttribute("PATHFINDER_CLAIMS", term.getClaims());
        filterChain.doFilter(request, response);
        return;
      }

      // Case B: Redirect Failure URL Dropout (e.g. user denied consent, client cancellation URL)
      if (term.isRedirect()) {
        log.warn("OAuth flow terminated with redirect failure to: {}", term.getRedirectUrl());
        response.sendRedirect(term.getRedirectUrl());
        return;
      }

      // Case C: Hard UI Dropout (e.g. account suspension, fraud lockout, brute-force max attempts)
      if (term.isUiDropout()) {
        log.error("OAuth flow terminated with hard UI lockout: {}", term.getErrorDescription());
        FrontendStep lockoutScreen = plan.getFrontendSteps().isEmpty() ? null : plan.getFrontendSteps().getFirst();
        request.setAttribute("LOCKOUT_SCREEN", lockoutScreen);
        request.setAttribute("ERROR_DESCRIPTION", term.getErrorDescription());
        request.getRequestDispatcher("/auth/lockout").forward(request, response);
        return;
      }

      // Fallback fail-closed
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization step-up policy denied access");
      return;
    }

    // 6. If plan halted at an interactive frontend challenge, persist to Redis & redirect to Rails IdP
    if (plan.hasFrontendSteps()) {
      String newTx = UUID.randomUUID().toString();
      FlowTransaction tx = new FlowTransaction(
          newTx,
          plan.getCheckpoint().getResumeState(),
          plan.getUpdatedContext(),
          Collections.emptyMap()
      );
      redisTemplate.opsForValue().set(REDIS_PREFIX + newTx, objectMapper.writeValueAsString(tx), Duration.ofMinutes(10));

      String returnUrl = request.getRequestURL() + "?" + request.getQueryString() + "&flow_tx=" + newTx;
      response.sendRedirect(railsChallengeUrl + "?tx=" + newTx + "&return_to=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8));
      return;
    }

    // Fallback fail-closed
    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization step-up policy denied access");
  }

  public record FlowTransaction(String txId, String resumeState, SessionContext context, Map<String, Object> claims) {}
}
```

---

### Step 5: Minting Verified Claims in `TokenCustomizerConfig`

Update Spring Security's `OAuth2TokenCustomizer` to inject claims verified by Pathfinder into issued access and ID tokens:

```java
package com.example.authserver.config;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration(proxyBeanMethods = false)
public class TokenCustomizerConfig {

  @Bean
  @SuppressWarnings("unchecked")
  public OAuth2TokenCustomizer<JwtEncodingContext> pathfinderTokenCustomizer() {
    return (context) -> {
      Map<String, Object> claims = (Map<String, Object>) context.get(HttpServletRequest.class)
          .getAttribute("PATHFINDER_CLAIMS");

      if (claims != null) {
        if (claims.containsKey("acr")) {
          context.getClaims().claim("acr", claims.get("acr"));
        }
        if (claims.containsKey("amr")) {
          context.getClaims().claim("amr", claims.get("amr"));
        }
      }
    };
  }
}
```

---

## 5. Handling Flow Dropouts: Redirect Failure URLs vs Hard UI Dropouts

In OAuth 2.1 and identity orchestration, flow termination due to an error, policy rejection, or cancellation falls into **two distinct architectural patterns**:

```
                       ┌─────────────────────────┐
                       │ Pathfinder Evaluator /  │
                       │   Terminal State        │
                       └────────────┬────────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
[ Redirect Failure URL ]                          [ Hard UI Dropout ]
• term.isRedirect() == true                       • term.isUiDropout() == true
• action: REDIRECT                                • action: UI_DROPOUT
• RFC 6749 Section 4.1.2.1                        • Security Lockout (OWASP/RFC 6819)
• User declined consent or cancelled              • Account suspended, brute-force, fraud
• 302 Redirect to Client App                      • Browser STAYS in UI (no redirect)
• Returns ?error=access_denied&state=xyz          • Renders terminal lockout screen
```

### Pattern 1: Redirect Failure URL (RFC 6749 Section 4.1.2.1)

When a user legitimately cancels or declines a step (such as rejecting consent or cancelling WebAuthn passkey registration), OAuth 2.1 requires the Authorization Server to redirect the user-agent back to the client application's registered `redirect_uri` with standard error query parameters:
* `error`: Standard OAuth 2.1 error code (e.g. `access_denied`, `login_required`, `consent_required`).
* `error_description`: Human-readable error message.
* `state`: The client application's anti-CSRF state parameter passed in the original authorization request.

#### YAML Definition with CEL Dynamic Interpolation
```yaml
  consent_rejected:
    type: TERMINAL
    terminal:
      status: DENIED
      error: access_denied
      errorDescription: "The resource owner denied the consent request."
      redirectUrl: "${config.redirectUri}?error=access_denied&error_description=User+declined+consent&state=${data.state}"
```

#### Spring Security Host Handling
```java
TerminalResult term = plan.getTerminalResult();
if (term.isRedirect()) {
    log.info("Redirecting back to client failure URL: {}", term.getRedirectUrl());
    response.sendRedirect(term.getRedirectUrl());
    return;
}
```

---

### Pattern 2: Hard UI Dropout (Fatal Security Lockouts)

Under RFC 6819 and OWASP security guidelines, when a **fatal security event** occurs, the Authorization Server **MUST NOT** redirect the browser back to the client application. Doing so would leak security state, expose the user to open-redirect risks, or inform an adversary about brute-force lockout thresholds.

Fatal security events include:
* Exceeding maximum failed OTP / password attempts (`maxAttempts` exceeded).
* Fraud risk score exceeding maximum threshold (`riskScore >= 90`).
* Account frozen, banned, or marked for investigation.
* Mismatched or invalid client `redirect_uri`.

For these events, Pathfinder stays in the UI by emitting a terminal `schemas:` block alongside `status: DENIED`.

#### YAML Definition with Terminal UI Schema
```yaml
  account_lockout:
    type: TERMINAL
    schemas:
      - screenId: account_suspended_screen
        title: "Account Suspended"
        description: "Your account has been locked due to excessive failed verification attempts. Please contact security support."
        jsonSchema:
          type: object
    terminal:
      status: DENIED
      action: UI_DROPOUT
      error: account_locked
      errorDescription: "Maximum verification attempts exceeded."
```

#### Spring Security / MVC Controller Handling
```java
TerminalResult term = plan.getTerminalResult();
if (term.isUiDropout()) {
    log.error("Hard UI Dropout triggered: error={}, desc={}", term.getError(), term.getErrorDescription());
    
    // Terminal UI screens are preserved in plan.getFrontendSteps()
    FrontendStep lockoutScreen = plan.getFrontendSteps().isEmpty() ? null : plan.getFrontendSteps().getFirst();
    
    request.setAttribute("lockoutScreen", lockoutScreen);
    request.setAttribute("errorMessage", term.getErrorDescription());
    request.getRequestDispatcher("/WEB-INF/views/lockout.jsp").forward(request, response);
    return;
}
```

---

## 6. Testing & Flow Simulation in CI/CD

Spring applications can use Pathfinder's `simulate(...)` method in unit and integration tests to verify both happy paths and dropout trajectories without launching a server:

### A. Testing Happy Path
```java
@Test
void verifyHighRiskStepUpAuthenticationPath() {
    SessionContext context = new SessionContext(
        Map.of("userId", "alice", "riskScore", 75), // High risk
        Map.of("clientId", "banking_app", "requireMfa", true)
    );

    Map<String, Object> decisions = Map.of(
        "check_device_risk", Map.of("riskScore", 75),
        "otp_entry_screen", Map.of("otpCode", "123456"),
        "verify_code", Map.of("valid", true)
    );

    FlowSimulation sim = engine.simulate("oauth-stepup-auth", context, decisions);

    assertThat(sim.isSuccess()).isTrue();
    assertThat(sim.getExecutionPath()).containsExactly(
        "evaluate_auth",
        "trigger_stepup_mfa",
        "verify_otp",
        "issue_stepup_token"
    );
    assertThat(sim.getTerminalResult().getClaims()).containsEntry("acr", "urn:pathfinder:auth:level2");
}
```

### B. Testing Redirect Failure URL Dropout
```java
@Test
void verifyConsentRejectionRedirectDropout() {
    SessionContext context = new SessionContext(
        Map.of("userId", "bob", "state", "xyz987"),
        Map.of("redirectUri", "https://client.example.com/callback")
    );

    Map<String, Object> decisions = Map.of(
        "consent_screen", Map.of("action", "DECLINE")
    );

    FlowSimulation sim = engine.simulate("oauth_consent_flow", context, decisions);

    assertThat(sim.isCompleted()).isTrue();
    assertThat(sim.isSuccess()).isFalse();
    
    TerminalResult term = sim.getTerminalResult();
    assertThat(term.isRedirect()).isTrue();
    assertThat(term.getRedirectUrl())
        .isEqualTo("https://client.example.com/callback?error=access_denied&error_description=User+declined+consent&state=xyz987");
}
```

### C. Testing Hard UI Dropout
```java
@Test
void verifyFraudLockoutHardUiDropout() {
    SessionContext context = new SessionContext(
        Map.of("userId", "mallory", "ip", "203.0.113.1"),
        Map.of("clientId", "portal")
    );

    Map<String, Object> decisions = Map.of(
        "calculate_fraud_score", Map.of("score", 95) // Critical fraud score
    );

    FlowSimulation sim = engine.simulate("oauth-stepup-auth", context, decisions);

    assertThat(sim.isCompleted()).isTrue();
    assertThat(sim.isSuccess()).isFalse();

    TerminalResult term = sim.getTerminalResult();
    assertThat(term.isUiDropout()).isTrue();
    assertThat(term.getError()).isEqualTo("account_locked");
    assertThat(term.getRedirectUrl()).isNull();

    // Verify the terminal error screen was emitted
    assertThat(sim.getScreens()).extracting("screenId")
        .contains("account_suspended_screen");
}
```
