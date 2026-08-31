# Long-running agent operations

This is the original monitor client: Java 21, raw `Authorization` tokens, `/monitor/*` and `RestBean.code == 200`. It does not use system-service registration, Java 8, Redis or DM database code. Server and web APIs remain unchanged.

## Runtime contract

- Collection, reporting and stall detection use three fixed daemon workers. Collection does no HTTP I/O. Scheduling waits after completion rather than catching up at a fixed rate.
- Runtime buffering retains at most 1,000 scalar samples, deduplicated by timestamp. Overflow and failed replay retain newer samples instead of evicting them for old backfill.
- Each report round sends at most 50 samples, one pending hardware description and one optional snapshot. The selected batch includes the newest sample, but is **sorted ascending before transmission**: the original server applies current-state/SSE side effects in wire order.
- Optional snapshots use four fixed slots, up to 256 KiB each; stale pending snapshots expire after five minutes. No snapshot history accumulates in the client.
- Network failures back off without sleeping on the collector: 2–60 seconds for ordinary errors, 300 seconds for 401/403, additionally limited by the configured reporting cadence. Repeated transport warnings are limited to once per minute.
- HTTP connect/read timeouts are five seconds; body consumption checks a 15-second deadline. Requests are capped at 256 KiB and responses at 64 KiB. Redirects are disabled. DNS, native calls, headers and blocked writes remain subject to the watchdog rather than a strict HTTP wall-clock guarantee.
- Child commands retain at most 256 KiB on each output stream. Bounded byte polling replaces unbounded line reads and per-command reader threads. A child that has not terminated prevents additional command creation.
- SMART/systemd targets are limited to 16 each. Process matching accepts up to 32 patterns of 256 characters, with matching input capped at 4,096 characters. Top-N sorting retains an O(N) heap, where N is the requested top count. Optional collectors are disabled by default; full OSHI process enumeration and complex administrator-supplied regexes can still be expensive.
- The watchdog checks every 30 seconds and halts the JVM after a worker makes no progress for 300 seconds. An ordinary server outage does not count as a stalled worker. Use process supervision to restart the JVM. A watchdog halt discards buffered samples and may lose final logs.
- Startup preserves registration. An exact persisted server/token match is reused offline; otherwise heartbeat recognizes an already registered token before the one-time `/register` endpoint is attempted. Registration is not attempted on an unavailable server. With no valid configuration and no console, startup fails instead of waiting indefinitely for input.

```properties
monitor.report.interval-seconds=10
monitor.watchdog.timeout-seconds=300
monitor.collect.gpu.enabled=false
monitor.collect.smart.devices=
monitor.collect.systemd.units=
monitor.collect.process.patterns=
monitor.collect.process.topN=10
```

Collector configuration is loaded from the existing external/classpath search order. Set JVM `-Dmonitor.report.interval-seconds=...` to override cadence. The default data source and all metric units remain unchanged.

## Logging and resource bounds

`src/main/resources/logback.xml` writes `MONITOR_LOG_DIR/monitor-client.log`, defaulting to `logs` under the working directory. It rolls daily and at 10 MiB, compresses archives, retains seven days and caps archives at 50 MiB. Startup also prunes old archives. The active file and temporary compression files are not included in the archive cap; asynchronous cleanup can temporarily exceed roughly 60 MiB total. This is not a filesystem quota.

The async log queue is limited to 256 events and never blocks collection: a full queue may discard logs. Message output is capped at 2,048 characters, exception output at five lines, and shutdown waits at most two seconds for log flushing. Business logs are not duplicated to stdout. A five-minute summary records liveness, pending samples and overflow loss. Verify the log directory is writable by the service account. Do not share one log file between multiple agents.

The Docker image and updated installer specify a 64 MiB heap, 16 MiB direct-memory limit and 64 MiB metaspace limit, with exit-on-OOM and a fixed fatal-error log filename. These are **not** a complete RSS bound: OSHI/JNA, native libraries, code cache, threads and subprocesses consume additional resources.

Linux installation adds a 256 MiB cgroup memory cap, 10% of one CPU, 64 tasks, 256 file descriptors, a ten-second stop timeout and control-group child cleanup. Verify support for `MemoryMax`, `CPUQuota` and `TasksMax` on the target systemd version. The installer preserves its existing root account behavior; use a deliberately provisioned least-privilege service account when optional device permissions allow it. Do not grant broad sudo just for collection.

macOS installation adds JVM limits, working/log directories and restart throttling. It does not impose Linux cgroup limits. Launchd stdout/stderr redirects go to `/dev/null` to avoid unbounded `/tmp` logs; use rolling application logs and launchd exit status for diagnostics, or run manually when troubleshooting startup failures.

## Install, update and containers

`scripts/install-client.sh` generates the service/launchd configuration; no template or installer was executed as part of this change. Linux credentials are written to a mode-0600 EnvironmentFile rather than the unit's Environment metadata. The macOS plist is mode 0600. CLI token arguments may still be visible to local process inspection and shell history.

**Existing installations:** `--update` only replaces the JAR and restarts the existing service. Re-run installation with the existing node's server/token, or manually update the service definition, to adopt the new working directory/resource/log settings. Preserve `config/server.json` in the new working directory; this enables offline restarts. Never use a fresh registration token merely to update an existing node.

`compose.yaml` is an optional container template with a read-only root filesystem, non-root UID 10001, a 32 MiB temporary filesystem, 256 MiB memory/0.1 CPU/64 PID limits, restart supervision and Docker JSON-log rotation (5 MiB × 2). Create `agent.env`, `config` and a `logs` directory writable by UID 10001 before use. Mount configuration persistently and use an already registered token/configuration for unattended container restarts. Container metrics represent the container's view; prefer host deployment for host-wide monitoring instead of privileged containers or Docker socket mounts.

## Validation and limits

Run:

```bash
mvn -f monitor-client/pom.xml --batch-mode verify
python3 scripts/test-client-package.py
bash -n scripts/install-client.sh
```

The new client CI job runs these commands. Unit tests exercise replay capacity/freshness, original batch order, failure cooldowns, registration/restart compatibility, command pipe pressure/timeout, watchdog decisions and actual log rotation. The package smoke test starts the shaded JAR only against a disposable loopback fixture; it does not forward host metrics or print credentials.

Local validation on JDK 21 passed 82 tests, packaging, the loopback JAR smoke test and shell syntax checks. This is not a 24–72-hour soak test or a resource benchmark. Test enabled collectors, permissions, RSS/CPU/FD trends, server outages, stuck workers, process restarts and log retention on deployment hardware before rollout. Java/dependency versions were retained rather than downgraded; infrequent agent upgrades do not remove the need for maintained JVM/dependency security updates.

Backfill-only rounds older than the last acknowledged live sample wait for a fresh sample before transmission. This preserves monotonically advancing current state across batches while a collector is slow.
