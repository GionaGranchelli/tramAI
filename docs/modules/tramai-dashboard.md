# Module: `tramai-dashboard`

> **One-liner:** Vue 3 admin UI for managing TramAI workflows, schedules, and workers.
> **Module type:** `platform`
> **Source files:** 3 — `DashboardAutoConfiguration.kt`, `DashboardMarker.kt`, `DashboardSettingsController.kt` (124 LOC)
> **Test files:** 0
> **Build:** `dev.tramai:tramai-dashboard:0.2.0`

---

## L1: Quick Start (30-second read)

### What
`tramai-dashboard` provides a Vue 3 single-page application (SPA) that serves as an admin UI for TramAI. It displays workflow runs, schedules, workers, and audit logs. The SPA is embedded as static resources served by a Spring Boot `WebMvcConfigurer`.

### Why
- Visual management of workflows without needing to call the REST API directly
- Runtime configuration bootstrapped per-deployment via `DashboardSettingsController` (API base URL, auth provider, feature flags)
- Auto-configures when the JAR is on the classpath — zero manual setup

### When to use
```
Use this module when:
- You want a graphical interface to manage workflows and schedules
- You need to view run history and audit logs
- You're running tramai-server and want quick operational visibility

Don't use this module when:
- You only need headless / programmatic access (use tramai-server API directly)
- You have your own monitoring solution (Grafana, Datadog, etc.)
```

### How to add
```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-dashboard:0.2.0")
}
```

### Where to go next
- [tramai-server](./tramai-server.md) — The REST API the dashboard consumes
- [tramai-platform](./tramai-platform.md) — Multi-tenancy layer (dashboard detects auth providers)
- [Guide: Server](../guides/server.md) — Server deployment

---

## L2: Usage Guide (5-minute read)

### Configuration
```yaml
# application.yml
tramai:
  dashboard:
    enabled: true                          # default: true
    auth:
      required: false                      # default: false
      provider: custom                     # auto-detected if not set
```

The dashboard is enabled by default when `tramai-dashboard` is on the classpath. Set `tramai.dashboard.enabled=false` to disable.

### How it works
1. The SPA's `index.html` loads `/tramai-settings.js` as a `<script>` tag
2. `DashboardSettingsController` returns a JavaScript snippet: `window.__TRAMAI__ = { apiBaseUrl, features, auth }`
3. The Vue app reads this config on mount and bootstraps against the TramAI API

### Auth provider auto-detection
The dashboard detects auth providers by checking the Spring `ApplicationContext` for well-known bean types:

| Bean class on classpath | Auth provider |
|------------------------|---------------|
| `dev.tramai.platform.ApiKeyAuthenticator` | `apikey` |
| `JwtDecoder` (Spring Security OAuth2) | `oauth` |
| `OpaqueTokenIntrospector` | `oauth` |
| `AuthenticationProvider` | `custom` |
| `SecurityFilterChain` | `spring-security` |
| None of the above | `none` |

### Feature flags
The dashboard exposes three feature flags that the SPA uses to show/hide UI elements:
- `auditLog` — always `true`
- `workerManagement` — always `true`
- `scheduleManagement` — always `true`

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy
The dashboard module follows the **embedded SPA** pattern: the static assets (Vue 3 build output) are packaged into the JAR under `META-INF/tramai-dashboard/`. A single controller serves the runtime configuration. No frontend build tooling is needed at deploy time.

### Module boundary
```
Public:
  dev.tramai.dashboard.DashboardAutoConfiguration  — @AutoConfiguration (@ConditionalOnClass)
  dev.tramai.dashboard.DashboardMarker              — Marker class for conditional activation
  dev.tramai.dashboard.DashboardSettingsController   — @RestController serving /tramai-settings.js

Package: dev.tramai.dashboard
  3 source files, no sub-packages, 124 LOC total.
```

### Dependency graph
```
tramai-dashboard
  Depends on:
    - Spring Boot Web (transitive via tramai-server) — WebMvcConfigurer, @RestController
    - jackson-databind — serializing runtime settings to JSON

  Depended on by:
    - Nothing (terminal module in the dependency chain)
```

### Inner mechanics

```
1. Spring Boot starts
2. @ConditionalOnClass(DashboardMarker::class) — checks if dashboard JAR is on classpath
3. @ConditionalOnProperty("tramai.dashboard.enabled") — checks config (default true)
4. DashboardAutoConfiguration registered:
   a. addResourceHandlers() — maps /dashboard/** → classpath:/META-INF/tramai-dashboard/
   b. dashboardSettingsController() bean — creates settings endpoint
5. Browser loads /dashboard/ → serves static index.html
6. index.html requests /tramai-settings.js
7. DashboardSettingsController builds runtime config:
   - apiBaseUrl: from request context path
   - auth.required: from tramai.dashboard.auth.required (default false)
   - auth.provider: auto-detected via ApplicationContext scan
8. Vue app mounts with window.__TRAMAI__ config
```

### Testing strategy
**Zero tests currently exist.** The module requires integration testing (Spring Boot context + static resource serving). Recommendations:
- `@WebMvcTest(DashboardSettingsController::class)` for settings endpoint
- `@SpringBootTest` with dashboard on classpath to verify static resource serving
- Auth provider auto-detection test matrix (with mock beans)

### Known limitations
- Dashboard SPA is built and committed as pre-compiled static assets — no dev server for hot reload
- Settings endpoint returns fixed feature flags (not configurable via properties)
- No WebSocket support for real-time workflow status updates
