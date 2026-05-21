# Maven Setup

Use this page when your project builds with Maven and you want copy-paste dependency setup.

## The Rule Of Thumb

Start with:

- `tramai-standalone` for plain JVM applications
- `tramai-spring` for Spring Boot applications

Then add:

- one provider module such as `tramai-openai`, `tramai-anthropic`, or `tramai-ollama`

Optionally add:

- `tramai-observability` for OpenTelemetry
- `tramai-orchestration` for persisted workflows

Do not start from `tramai-core` unless you are building against TramAI internals or extending the library.

## Import The BOM

Always import the BOM first so all TramAI modules stay on the same version.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Common Setups

### Standalone + OpenAI

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>
</dependencies>
```

### Spring Boot + OpenAI

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>
</dependencies>
```

### Standalone + Anthropic

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-anthropic</artifactId>
  </dependency>
</dependencies>
```

### Standalone + Ollama

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-ollama</artifactId>
  </dependency>
</dependencies>
```

### Spring Boot + OpenTelemetry

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-observability</artifactId>
  </dependency>
</dependencies>
```

### Standalone + Orchestration

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>

  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-orchestration</artifactId>
  </dependency>
</dependencies>
```

## What Each Module Means

| Module | Use it when |
| --- | --- |
| `tramai-standalone` | You want the normal non-Spring runtime entry point. |
| `tramai-spring` | You want Spring Boot bean registration and configuration binding. |
| `tramai-openai` | You want OpenAI or OpenAI-compatible providers. |
| `tramai-anthropic` | You want Anthropic models. |
| `tramai-ollama` | You want local Ollama models. |
| `tramai-observability` | You want OpenTelemetry integration. |
| `tramai-orchestration` | You want typed workflows with checkpoint/resume. |
| `tramai-testing` | You want deterministic test helpers in test scope. |

## Test Scope Example

```xml
<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-testing</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## What Not To Do

- do not import versions separately for every TramAI module
- do not depend on all modules by default
- do not start with `tramai-core` and expect it to be the full runtime
- do not forget the provider module

## Next Step

After dependencies are in place:

- read [Getting Started](./getting-started.md)
- then choose [Standalone Usage](./standalone-usage.md) or [Spring Boot Integration](./spring-boot.md)
