# Vendored MCP JSON Schemas

The driver checks every JSON-RPC message a node sends against these files (`src/wire.rs`). They
are copied unchanged from the public specification repository,
<https://github.com/modelcontextprotocol/modelcontextprotocol>, at commit
`38353251fab3bdeead03dd8283b9e66076ce1a7f` (2026-11-06, the latest commit that touches either
file). That repository's `LICENSE` covers them: Apache-2.0, with MIT for contributions not yet
relicensed.

| File here | Path in the repository | SHA-256 |
|---|---|---|
| `2025-11-25.json` | `schema/2025-11-25/schema.json` | `268a5f82ba70fd7e4b6dc4aa1e64f116f74b4d0edcb69dc046829c79dd4e97e7` |
| `2026-07-28.json` | `schema/2026-07-28/schema.json` | `edbb197ed0f92e90d658866e23cf76f107df0caac5ce25c2b9e64e1ca9a6314a` |

To update, download both files at a newer commit of that repository:

```bash
sha=<commit>
for v in 2025-11-25 2026-07-28; do
  curl -fsSL -o "$v.json" \
    "https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/$sha/schema/$v/schema.json"
done
shasum -a 256 *.json
```

Then put the commit and the checksums in this file, and run `cargo test` in `antithesis/mcp/driver`.
