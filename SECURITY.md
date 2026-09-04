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
- `allowedHosts` enables the DNS-rebinding guard (rejects unexpected `Host`/`Origin` with 403).
- Modern Tasks IDs are bearer handles: possession grants access to that task
  ([docs/tasks.md](docs/tasks.md)).
