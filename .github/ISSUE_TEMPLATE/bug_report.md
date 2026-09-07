---
name: Bug report
about: Something in fast-mcp-scala does not behave as documented or as the MCP spec requires
title: ''
labels: ["bug", "needs confirmation"]
assignees: ''

---

Please search existing issues first; the bug may already be tracked.

**Bug description**
A clear and concise description of what is wrong.

**Environment**
- fast-mcp-scala version:
- Artifact / platform: `fast-mcp-scala_3` (JVM) / `fast-mcp-scala_sjs1_3` (Scala.js on Bun or Node) / `fast-mcp-scala_native0.5_3` (Scala Native)
- Scala version, JDK or Bun or Scala Native version:
- Transport: stdio / HTTP (modern 2026-07-28 or legacy session adapter), and the MCP client in use:

**Steps to reproduce**
A minimal server (a `scala-cli` file is ideal) plus the request that misbehaves. Reproducible
reports are prioritized over ones that are not.

**Expected behavior**
What you expected, with a spec reference if the issue is about protocol behavior.

**Actual behavior**
What happened instead, including any JSON-RPC error or stack trace.
