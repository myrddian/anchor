# Client usage

> Placeholder. The client SDK design is described in [SPEC.md](../SPEC.md) §15.
> This document will be filled out alongside Phase 4 (client + shell).

## Coordinates (planned, v0.2)

```kotlin
implementation("io.aeyer:anchor-client:0.2.0")
```

Pre-v0.2 the client API is unstable and not published.

## Shape of the API

```java
var anchor = AnchorClient.builder()
    .baseUrl("http://localhost:8080")
    .timeout(Duration.ofSeconds(120))
    .build();

AnchorDocument doc = anchor.use(documentId);

// Sync, machine-style.
ValidationResult judgment = doc.validate(chunkId, "Does X inhibit Y?");

// Async, human-style — block.
AskResult result = doc.ask("Does X inhibit Y?").await();

// Async, human-style — stream.
doc.ask("Does X inhibit Y?")
    .onProposerThought(System.out::print)
    .onCriticChallenge(c -> log.info("critic: {}", c))
    .onSynthesiserThought(System.out::print)
    .onComplete(this::renderFinal)
    .start();
```

## Sections to fill in

- Full Javadoc snapshots and link targets.
- SSE reconnect-with-replay semantics and when callers should care.
- Error model: `AnchorException` hierarchy, retry guidance.
- Threading: which callbacks fire on which thread, lifecycle of the SSE
  connection.
- Worked examples: agentic loop calling `/validate`, GUI showing
  deliberation transcript.
