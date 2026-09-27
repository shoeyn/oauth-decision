# Pathfinder Decision Engine — Spring Security OAuth 2.1 Integration Guide

This guide details how to integrate the **Pathfinder Decision Engine** (`io.pathfinder:pathfinder-engine`) into an enterprise OAuth 2.1 / OIDC Authorization Server such as [spring-auth-server](file:///Users/nathanshoemark/SpringSecurity/spring-auth-server) (built on Java 25, Spring Boot 4, Spring Security 7, Redis, PostgreSQL, and AWS KMS).

---

## 1. Architectural Topology & Sequence

In an enterprise OAuth 2.1 deployment, user authentication, risk assessment, and step-up challenges occur before issuing an authorization code. Pathfinder provides the declarative orchestration engine for:
1. Dynamic risk evaluation (e.g. device intelligence, IP fraud scores).
2. Conditional multi-factor authentication (e.g. SMS OTP, WebAuthn/FIDO2, Push).
3. Declarative consent screens and business authorization policies.
4. Cryptographic claims issuance (embedding verified `acr`, `amr`, and custom claims into access and ID tokens).

### Component Flow

```
[ User Browser ]
       │
       ▼
1. GET /oauth2/authorize?client_id=...&acr_values=urn:pathfinder:auth:level2
       │
       ▼
[ Spring Authorization Server ] ── (Checks user session in Redis)
       │
       ├──► [ Pathfinder WorkflowOrchestrator ]
       │         │
       │         ├──► 2. Runs Command: 'check_device_risk' -> FraudEngineBean
       │         │       (Risk score = 75 >= 30 -> requires step-up MFA)
       │         │
       │         └──► 3. Yields Checkpoint & FrontendStep: 'otp_entry_screen'
       │
       ▼ 4. Redirects to Rails IdP (/challenges/otp?tx=UUID)
[ Rails Identity Provider ]
       │
       ├──► 5. Renders JSON Schema UI (6-digit OTP prompt)
       │
       ▼ 6. User submits OTP
[ Rails Identity Provider ] ──► POST /api/challenges/otp { tx: UUID, otpCode: "123456" }
                                           │
                                           ▼
                            [ Spring Authorization Server ]
                                           │
                                           ├──► [ Pathfinder WorkflowOrchestrator ]
                                           │         │
                                           │         ├──► Validates JSON Schema & Scopes Input
                                           │         ├──► Runs Command: 'verify_code' -> OtpServiceBean
                                           │         └──► Terminal: SUCCESS (acr: level2, amr: ["pwd","otp"])
                                           │
                                           ▼
                            [ Spring Authorization Server ]
                                           │
                                           ├──► Persists Grant to PostgreSQL
                                           └──► Mints JARM Authorization Response (code=...)
```

---

## 2. Core Integration Components in Pathfinder

Pathfinder provides first-class primitives designed specifically for clustered Spring Security environments:

| Component | Class | Description |
|---|---|---|
| **Workflow Orchestrator** | `io.pathfinder.engine.core.WorkflowOrchestrator` | Coordinates state transitions, automatically executing backend Spring beans until interactive user input or a terminal outcome is reached. |
| **Command Execution SPI** | `io.pathfinder.engine.command.CommandHandler` | `@FunctionalInterface` allowing Spring `@Component` beans to handle backend microservice commands. |
| **Command Registry** | `io.pathfinder.engine.command.CommandRegistry` | Registry mapping command service names (e.g. `fraud-engine`, `otp-service`) to their handlers. |
| **State Persistence SPI** | `io.pathfinder.engine.persistence.FlowStateRepository` | High-availability persistence contract for saving suspended workflow states in **Redis** with TTL. |
| **Flow State DTO** | `io.pathfinder.engine.persistence.FlowState` | Jackson 3 serializable snapshot containing execution state, `SessionContext`, and checkpoints. |
| **Safe Input Scoping** | `io.pathfinder.engine.core.DecisionEngine` | Automatically prevents Mass Assignment (CWE-915) by isolating untrusted submitted form inputs and protecting server-verified context keys from overwrites. |
| **Attempt Limiting** | `StateDefinition.getMaxAttempts()` | Built-in brute-force protection with configurable `onError` fallback transitions. |
| **OAuth 2.1 Claims Bridge** | `io.pathfinder.engine.oauth2.OAuth2FlowContextHelper` | Translates OAuth 2.1 parameters to `SessionContext` and extracts `acr` and `amr` claims from `TerminalResult`. |

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

import io.pathfinder.engine.command.CommandHandler;
import io.pathfinder.engine.command.CommandRegistry;
import io.pathfinder.engine.command.InMemoryCommandRegistry;
import io.pathfinder.engine.core.DecisionEngine;
import io.pathfinder.engine.core.WorkflowOrchestrator;
import io.pathfinder.engine.persistence.FlowState;
import io.pathfinder.engine.persistence.FlowStateRepository;
import io.pathfinder.engine.registry.ClasspathFlowRegistry;
import io.pathfinder.engine.registry.FlowRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class PathfinderConfig {

  @Bean
  public FlowRegistry pathfinderFlowRegistry() {
    ClasspathFlowRegistry registry = new ClasspathFlowRegistry();
    // Auto-load step-up flow
    registry.registerResource("/flows/oauth_stepup_auth.yaml");
    return registry;
  }

  @Bean
  public CommandRegistry pathfinderCommandRegistry(ApplicationContext applicationContext) {
    CommandRegistry registry = new InMemoryCommandRegistry();
    // Automatically register any Spring bean annotated with @PathfinderService or implementing CommandHandler
    Map<String, CommandHandler> beans = applicationContext.getBeansOfType(CommandHandler.class);
    beans.forEach((beanName, handler) -> registry.register(beanName, handler));
    return registry;
  }

  @Bean
  public DecisionEngine pathfinderDecisionEngine(FlowRegistry flowRegistry) {
    return new DecisionEngine(flowRegistry);
  }

  @Bean
  public WorkflowOrchestrator pathfinderWorkflowOrchestrator(
      DecisionEngine decisionEngine, CommandRegistry commandRegistry) {
    return new WorkflowOrchestrator(decisionEngine, commandRegistry);
  }

  /**
   * Distributed Redis repository for persisting in-flight workflow instances across horizontal cluster nodes.
   */
  @Bean
  public FlowStateRepository pathfinderFlowStateRepository(
      StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {

    ObjectMapper mapper = objectMapper != null ? objectMapper : JsonMapper.shared();

    return new FlowStateRepository() {
      private static final String PREFIX = "pathfinder:flow:";

      @Override
      public void save(String flowInstanceId, FlowState state, Duration ttl) {
        try {
          String json = mapper.writeValueAsString(state);
          redisTemplate.opsForValue().set(PREFIX + flowInstanceId, json, ttl);
        } catch (Exception e) {
          throw new IllegalStateException("Failed to persist flow state to Redis: " + e.getMessage(), e);
        }
      }

      @Override
      public Optional<FlowState> find(String flowInstanceId) {
        String json = redisTemplate.opsForValue().get(PREFIX + flowInstanceId);
        if (json == null || json.isBlank()) {
          return Optional.empty();
        }
        try {
          return Optional.of(mapper.readValue(json, FlowState.class));
        } catch (Exception e) {
          return Optional.empty();
        }
      }

      @Override
      public void delete(String flowInstanceId) {
        redisTemplate.delete(PREFIX + flowInstanceId);
      }
    };
  }
}
```

---

### Step 3: Implement Backend Command Handlers as Spring Beans

Declare your business microservice beans:

```java
package com.example.authserver.service;

import io.pathfinder.engine.command.CommandHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("fraud-engine")
public class FraudEngineCommandHandler implements CommandHandler {

  @Override
  public Map<String, Object> execute(Map<String, Object> payload) {
    String userId = (String) payload.get("userId");
    String ip = (String) payload.get("ip");

    // Evaluate risk via device intelligence / anomaly detection
    int riskScore = calculateRisk(userId, ip);

    return Map.of("riskScore", (long) riskScore);
  }

  private int calculateRisk(String userId, String ip) {
    // Example: flag high risk if IP is unknown
    return "127.0.0.1".equals(ip) ? 15 : 75;
  }
}
```

And OTP verification:

```java
package com.example.authserver.service;

import io.pathfinder.engine.command.CommandHandler;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component("otp-service")
public class OtpVerificationCommandHandler implements CommandHandler {

  @Override
  public Map<String, Object> execute(Map<String, Object> payload) {
    String code = (String) payload.get("code");
    // Verify against one-time passcode store
    boolean isValid = "123456".equals(code);
    return Map.of("valid", isValid);
  }
}
```

---

### Step 4: Intercepting `/oauth2/authorize` with Pathfinder

Create a Spring Security filter or endpoint to enforce Pathfinder workflows before issuing authorization codes:

```java
package com.example.authserver.security;

import io.pathfinder.engine.core.WorkflowOrchestrator;
import io.pathfinder.engine.oauth2.OAuth2ClaimConstants;
import io.pathfinder.engine.oauth2.OAuth2FlowContextHelper;
import io.pathfinder.engine.persistence.FlowState;
import io.pathfinder.engine.persistence.FlowStateRepository;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class PathfinderStepUpAuthorizationFilter extends OncePerRequestFilter {

  private final WorkflowOrchestrator orchestrator;
  private final FlowStateRepository stateRepository;
  private final String railsChallengeUrl;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Only intercept interactive OAuth 2.1 authorization requests
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

    // Check if client requested a step-up workflow (e.g. acr_values=urn:pathfinder:auth:level2)
    String acrValues = request.getParameter("acr_values");
    String clientId = request.getParameter("client_id");
    String txId = request.getParameter("flow_tx");

    // 1. Resuming existing workflow
    if (txId != null && !txId.isBlank()) {
      Optional<FlowState> savedState = stateRepository.find(txId);
      if (savedState.isPresent()) {
        FlowState state = savedState.get();
        if (state.getCheckpoint() != null && state.getCheckpoint().isTerminal()) {
          filterChain.doFilter(request, response);
          return;
        }
      }
    }

    // 2. Initialize initial flow context
    SessionContext context =
        OAuth2FlowContextHelper.createInitialContext(
            clientId,
            auth.getName(),
            List.of(request.getParameter("scope") != null ? request.getParameter("scope").split(" ") : new String[0]),
            acrValues,
            Map.of("ip", request.getRemoteAddr())
        );

    ExecutionPlan plan = orchestrator.run("oauth-stepup-auth", null, context, Event.start());

    // 3. If plan halted at a frontend challenge, persist to Redis & redirect to Rails IdP
    if (plan.hasFrontendSteps()) {
      String newTx = UUID.randomUUID().toString();
      FlowState state = new FlowState(newTx, "oauth-stepup-auth", plan.getCurrentState(), plan.getUpdatedContext(), plan.getCheckpoint());
      stateRepository.save(newTx, state, Duration.ofMinutes(10));

      String returnUrl = request.getRequestURL() + "?" + request.getQueryString() + "&flow_tx=" + newTx;
      response.sendRedirect(railsChallengeUrl + "?tx=" + newTx + "&return_to=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8));
      return;
    }

    // 4. If terminal success, attach verified claims to request for TokenCustomizerConfig
    if (plan.isTerminal() && "SUCCESS".equalsIgnoreCase(plan.getTerminalResult().getStatus())) {
      request.setAttribute("PATHFINDER_CLAIMS", plan.getTerminalResult().getClaims());
      filterChain.doFilter(request, response);
      return;
    }

    // 5. Fail-closed on denial or error
    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization step-up policy denied access");
  }
}
```

---

### Step 5: Minting Verified Claims in `TokenCustomizerConfig`

Update [TokenCustomizerConfig.java](file:///Users/nathanshoemark/SpringSecurity/spring-auth-server/src/main/java/com/example/authserver/config/TokenCustomizerConfig.java) to inject claims verified by Pathfinder:

```java
// Inside TokenCustomizerConfig.java:

@SuppressWarnings("unchecked")
Map<String, Object> pathfinderClaims = (Map<String, Object>) context.get(OAuth2TokenContext.class)
    .getAttribute("PATHFINDER_CLAIMS");

if (pathfinderClaims != null) {
  // Inject verified Authentication Context Class Reference (acr)
  if (pathfinderClaims.containsKey(OAuth2ClaimConstants.ACR)) {
    context.getClaims().claim("acr", pathfinderClaims.get(OAuth2ClaimConstants.ACR));
  }
  // Inject Authentication Methods References (amr)
  if (pathfinderClaims.containsKey(OAuth2ClaimConstants.AMR)) {
    context.getClaims().claim("amr", pathfinderClaims.get(OAuth2ClaimConstants.AMR));
  }
}
```

---

## 4. Security Hardening & Defenses

1. **Mass-Assignment / Context Injection Defense (CWE-915)**:
   Pathfinder inspects `FrontendSchemaDefinition.getJsonSchema()` declared `properties`. Any undeclared parameters submitted by the browser are safely filtered out, and server-set context keys (`userId`, `roles`, `riskScore`) can **never** be overwritten by client payloads.
2. **Brute-Force & Rate-Limiting Protection**:
   Interactive states configured with `maxAttempts: 3` track attempts in `SessionContext.getAttemptCount(stateId)`. When attempts exceed the limit, Pathfinder automatically branches to `onError: lockout_state` or yields a `TerminalResult("DENIED")`.
3. **Fail-Closed Command Execution**:
   If any backend `CommandHandler` throws an unhandled exception or times out, Pathfinder immediately fails closed with `TerminalResult("ERROR")` unless an explicit `onError` target state is declared.
