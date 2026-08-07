package com.tjclp.fastmcp.server

import com.tjclp.fastmcp.core.Tasks

/** Settings for the optional `io.modelcontextprotocol/tasks` extension.
  *
  * Off by default. Modern clients opt in through `clientCapabilities.extensions`; the server may
  * then return a task handle for tools whose registration declares task support. The old core task
  * draft remains available only through the legacy protocol adapter.
  *
  * Modern task IDs are bearer handles and work over stateless HTTP, Streamable HTTP, and stdio.
  * Calls made through the legacy protocol adapter remain isolated by their protocol session.
  *
  * @param enabled
  *   Master switch. When false, `tasks` capability is not advertised and `params.task` is ignored.
  * @param defaultTtlMs
  *   TTL applied when the requestor does not supply one (1 hour default).
  * @param maxTtlMs
  *   Upper bound on TTL — requestor-supplied values above this are clamped (24 hour default).
  * @param pollIntervalMs
  *   `pollInterval` value advertised back to clients in `tasks/get` responses.
  * @param maxConcurrentPerSession
  *   Resource cap; additional task creations beyond this are rejected with `-32602`.
  */
case class TaskSettings(
    enabled: Boolean = false,
    defaultTtlMs: Long = 3_600_000L,
    maxTtlMs: Long = 86_400_000L,
    pollIntervalMs: Long = Tasks.DefaultPollIntervalMs,
    maxConcurrentPerSession: Int = 64
)

/** Settings for an MCP server. HTTP-specific fields (`stateless`, `keepAliveInterval`,
  * `disallowDelete`, `httpEndpoint`) are ignored under stdio transports.
  */
case class McpServerSettings(
    debug: Boolean = false,
    logLevel: String = "INFO",
    // Spec: HTTP servers SHOULD bind to localhost by default (DNS-rebinding surface). Deployments
    // that need external exposure (e.g. containers) must set this explicitly — 0.5.0 BREAKING
    // change from the old "0.0.0.0" default.
    host: String = "127.0.0.1",
    port: Int = 8000,
    httpEndpoint: String = "/mcp",
    warnOnDuplicateResources: Boolean = true,
    warnOnDuplicateTools: Boolean = true,
    warnOnDuplicatePrompts: Boolean = true,
    dependencies: List[String] = List.empty,
    // If true, advertise templates via the resources/templates/list endpoint.
    // If false, rely on clients that derive templates from resource URIs containing `{}`.
    exposeTemplatesEndpoint: Boolean = false,
    // Legacy HTTP compatibility settings. MCP 2026-07-28 is always stateless and may use a
    // request-scoped SSE response regardless of this flag. For older versions, true disables the
    // protocol session store; false preserves the initialize/session/GET/DELETE adapter.
    stateless: Boolean = false,
    keepAliveInterval: Option[java.time.Duration] = None,
    // Streamable HTTP only: evict sessions idle longer than this (no POST/GET/DELETE activity and
    // no live GET stream). Guards the session store against abandoned clients; `None` disables.
    sessionIdleTimeout: Option[java.time.Duration] = Some(java.time.Duration.ofMinutes(30)),
    disallowDelete: Boolean = false,
    // Advertise logging. Modern requests opt in per call through `_meta`; the legacy adapter also
    // wires `logging/setLevel`. Off by default (#56 honesty).
    loggingEnabled: Boolean = false,
    // Legacy adapter only: advertise and wire resources/subscribe + resources/unsubscribe.
    // Modern subscriptions use subscriptions/listen. Off by default.
    resourcesSubscribe: Boolean = false,
    // DNS-rebinding protection (Streamable/stateless HTTP). When `Some`, an HTTP request whose
    // `Host` (or `Origin`) hostname — port ignored — is not in the set is rejected with 403.
    // `None` (default) disables host checking, preserving prior behavior.
    allowedHosts: Option[Set[String]] = None,
    // Optional io.modelcontextprotocol/tasks extension. Off by default.
    tasks: TaskSettings = TaskSettings()
)
