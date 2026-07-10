# Engineering Contracts

This document contains repository-level engineering contracts that must be versioned with the project. Local Trellis files under `.trellis/`, `.agents/`, `AGENTS.md`, and `CLAUDE.md` may help AI-assisted workflow, but they are not guaranteed to be available to every clone of the repository.

## Source Of Truth

- Business schema is managed only by Flyway migrations under `monitor-server/src/main/resources/db/migration/`.
- Root `database.sql` is a non-destructive database bootstrap helper only. It must not contain business tables or `DROP TABLE`.
- Build and runtime versions come from `monitor-server/pom.xml`, `monitor-client/pom.xml`, and `monitor-web/package.json`.
- User-facing current roadmap lives in `EVOLUTION.md`.

## Backend Contracts

- REST controllers return `RestBean<T>` for normal API responses.
- Client-visible `ResponseStatusException` paths must preserve the real HTTP status through `ValidationController`.
- Multi-table write/delete workflows must use Spring transactions when partial writes would create orphaned records.
- Destructive REST endpoints must use `DELETE`; do not retain a `GET` compatibility route for the same mutation.
- `readonly` API Tokens allow only safe read operations. A route that exposes registration tokens or another privileged capability must be explicitly excluded even when it uses `GET`.
- Runtime metric writes go through `TimeSeriesAdapter`; service code must not instantiate an InfluxDB or VictoriaMetrics client directly.
- Schema changes require a Flyway migration plus matching DTO/VO/frontend updates where applicable.
- Sensitive fields are encrypted at rest through `CryptoUtils`; never log JWTs, API tokens, email codes, SSH passwords, OIDC secrets, API token hashes, or raw authorization headers.
- `client_ssh.password`, OIDC `client_secret_enc`, probe `headers_enc`, and probe `basic_auth_password_enc` are sensitive fields.
- `GET /api/monitor/ssh` returns SSH host, port, username, and `passwordConfigured` only. It must never return or decrypt `client_ssh.password`; an empty password in an update retains an existing encrypted password.

## Client Deletion Contract

Deleting a client is a MySQL-side hard delete with retained TSDB history:

- Remove `client`, `client_detail`, and `client_ssh`.
- Remove `alert_rule` and `alert_history` rows bound to the deleted client.
- Remove the deleted client id from `status_page_config.client_ids`.
- Remove the deleted client id from sub-account `account.clients` JSON arrays.
- Invalidate server-local client, token, runtime, heartbeat, and status-page summary caches.
- Publish a client-list SSE refresh.
- Do not delete InfluxDB/VictoriaMetrics history in this workflow. TSDB history physical deletion requires a separate retention/cleanup task with an explicit rollback plan.

## Account Deletion Contract

- `POST /api/user/sub/create`, `GET /api/user/sub/list`, and `DELETE /api/user/sub/{uid}` require JWT-authenticated administrators; API Token-authenticated requests are rejected.
- `DELETE /api/user/sub/{uid}` can delete only a default-role sub-account and rejects self-deletion.
- The transaction deletes bound `api_token` and `account_oidc_binding` rows before deleting the `account` row.
- `DELETE /api/monitor/{clientId}` is restricted to administrators and follows the client deletion contract above.

## Frontend Contracts

- Normal REST calls go through `src/net/index.js`.
- Query strings are built with `withQuery`.
- SSE uses `src/net/sse.js`.
- WebSocket URL construction and basic socket lifecycle helpers live in `src/net/ws.js`.
- Components that open SSE, WebSocket, timers, workers, ECharts instances, or xterm instances must close/dispose them in `onBeforeUnmount`.
- User-facing UI copy is Chinese.
- Do not leave `console.log` in production component code.

## Configuration Defaults

- `.env.example` should prefer production-safe defaults.
- `CORS_ORIGIN=` means no permissive origin is configured by default. Local development may explicitly set `CORS_ORIGIN=*`.
- `PASSWORD_POLICY=basic` is the default example policy. Local development may explicitly use `none`.
- `application-dev.yml` may point to localhost Docker defaults, but must not include real private-network endpoints or real credentials.
- Production profile values should be supplied by environment variables. Startup rejects missing/template values, weak JWT keys, invalid API Token/SSH Base64 keys, and reused secrets.

## Performance Backlog

The remaining v2.0 performance work is split into two tasks:

1. `client list/details cache`
   - Add a Redis read-model cache for host list, host details, and status-page candidate data.
   - Keep Caffeine as short-lived in-process cache.
   - Define invalidation for registration, deletion, rename, node changes, `client_detail` updates, status-page config updates, and sub-account permission changes.

2. `query/index audit`
   - Maintain indexes from real query paths.
   - Current index audit is captured in Flyway `V5__v2-0-performance-indexes.sql`.
   - Re-run the audit when adding new paginated or filter-heavy queries.

## monitor-client Offline Replay

`monitor-client` uses an in-process `LinkedBlockingDeque` for short network outages. It is bounded to 1000 runtime samples and is lost when the process exits or the host restarts. A reliable offline replay queue requires a separate JSONL disk-backed queue task.

## Verification Baseline

Use the smallest command that covers touched modules:

- Server unit tests: `cd monitor-server && mvn test`
- Server integration and coverage: `cd monitor-server && mvn verify`
- Client tests: `cd monitor-client && mvn test`
- Web lint/tests/build: `cd monitor-web && pnpm run lint && pnpm run test -- --run && pnpm run build`
- E2E: `cd monitor-web && pnpm run e2e`

When reporting verification, distinguish commands actually run from static review or skipped checks.
