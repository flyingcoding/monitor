# Monitor Client

Standalone Java 21 host agent for the original monitor server. The wire protocol remains `/monitor/*`, raw agent Authorization tokens and success code 200. First-time registration and persisted connection configuration remain supported.

Collection is isolated from network retries. Runtime samples, snapshots, subprocess output and logs are bounded; worker stalls terminate the process for supervisor recovery. See [long-running operations](deploy/README.md) for exact limits, registration/restart behavior, installation migration and validation.

Build/test with `mvn verify` in this module. From the repository root, run `python3 scripts/test-client-package.py` after packaging for a loopback-only shaded-JAR smoke test. The client is not a Spring Boot service and is not launched through `spring-boot:run`.
