# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| latest `1.x` release | ✅ security fixes as patch releases |
| `1.0.0-RC*`, `0.x` | ❌ upgrade to the latest `1.x` |

## Reporting a vulnerability

Please report security issues privately through
[GitHub's private vulnerability reporting](https://github.com/TJC-LP/fast-mcp-scala/security/advisories/new)
for this repository. Do **not** open a public issue, discussion, or pull request for a suspected
vulnerability.

Include what you can of:

- a description of the issue and its impact
- the affected artifact and version (`fast-mcp-scala_3`, `fast-mcp-scala_sjs1_3`, or
  `fast-mcp-scala_native0.5_3`)
- steps or a minimal server that reproduces it
- a suggested fix, if you have one

You will receive an acknowledgement within five business days. Confirmed issues are fixed in a
patch release, disclosed through a GitHub Security Advisory, and credited in the
[CHANGELOG](CHANGELOG.md) unless you prefer otherwise. Critical (P0) issues follow the
response targets in [ROADMAP.md](ROADMAP.md).

## Scope

fast-mcp-scala is a **server** library. It implements the MCP wire protocol, transports, and
request routing. It does not implement an authorization server, token issuance, or credential
storage; deployments must put their own authorization in front of the MCP endpoint.

Hardening knobs worth knowing when you deploy over HTTP
([docs/transports.md](docs/transports.md)):

- `host` defaults to `127.0.0.1`; opt in to external exposure explicitly.
- `allowedHosts` enables the DNS-rebinding / browser-CSRF guard: the `Host` hostname must be
  listed, and a present `Origin` is matched as a full origin (`scheme://host:port`) against the
  request `Host` or the `allowedOrigins` allow-list; cross-port loopback origins, `null`, and
  malformed ports are refused with 403. It guards against browser-driven attacks only; it is not
  authentication.
- Every POST must carry `Content-Type: application/json` (415 otherwise), evaluated before the
  body is read or a session is minted.
- `maxRequestBodyBytes` (1 MiB) caps request bodies on every backend (413 before decoding), and
  `limits: LimitSettings` bounds every inbound frame on every transport — frame size 4 MiB, JSON
  depth 64, object width 1024 (`-32700` before dispatch), resource URI length 8192 and
  subscriptions per session 1024 (`-32602`). The limits cannot be disabled, only moved.
- `maxSessions` (`Some(1000)`) caps the legacy session store; at the cap the longest-idle session
  without a live GET is evicted — or, when every stored session holds a GET stream, the
  longest-idle one that has been idle longer than `sessionIdleTimeout` — so an unauthenticated
  flood of `initialize` requests can evict idle legitimate sessions. Front non-loopback deployments
  with `allowedHosts`/`allowedOrigins` and an authenticating proxy.
- Transport failures and defects answer a fixed JSON-RPC error (400/413/500) with no exception
  text, stack trace, or path, on the JVM and on Bun regardless of `NODE_ENV`.
- Modern Tasks IDs are bearer handles: possession grants access to that task
  ([docs/tasks.md](docs/tasks.md)). Per-client task caps are keyed on the peer address the HTTP
  transport supplies (`TaskOwnerKey.Transport`); behind a reverse proxy every client collapses to
  one address, so use `TaskOwnerKey.Custom` with an authenticated principal. `_meta` and
  `clientInfo` are client-controlled and are never used as a key.
- `scanAnnotations` binds each `@Tool` / `@Resource` / `@Prompt` to the exact annotated overload,
  and duplicate registrations within one object are compile-time errors, so the advertised schema
  and the invoked handler always come from the same declaration.

The CI and release pipeline is hardened against supply-chain tampering: the conformance harness is
resolved only from the committed, integrity-hashed `conformance/bun.lock`; every GitHub Action is
pinned to a full commit SHA and kept current by Dependabot; the release workflow separates
verification, publishing, and the GitHub release into least-privilege jobs; and the Mill launcher
distribution is verified against `.github/mill-dist.sha256` before the first `./mill` in every job
([DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md)).
