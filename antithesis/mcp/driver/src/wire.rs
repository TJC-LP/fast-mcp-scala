//! Checks a JSON-RPC message a node sent against the MCP specification's JSON Schema for the
//! protocol version it was sent under.
//!
//! The schemas are the specification's own files, vendored in `schema/` (source and revision in
//! `schema/SOURCE.md`). Which definition a message is held to follows the message, the way the
//! conformance harness's `wire-schema-valid` check picks it:
//!
//! - a message with a `method` (a notification or a request the server sends): the definition
//!   whose `method` is that constant, or `JSONRPCNotification` / `JSONRPCRequest` when the
//!   schema has none;
//! - an error response: the definition of the response for its error code when the schema has
//!   one (2026-07-28 does for -32020, -32021 and -32022, which fix the shape of `data`), else
//!   `JSONRPCErrorResponse`;
//! - a result response: its `result` against the result definition of the request's method
//!   (`tools/call` gives `CallToolResult`), or against `InputRequiredResult` when it says so;
//!   then the whole message against `JSONRPCResultResponse`. A result whose `resultType` is an
//!   extension's (`task`) and a method the schema has no result for (the tasks extension's
//!   methods under 2026-07-28) are only held to `JSONRPCResultResponse`.
//!
//! Formats (`uri` and the like) are checked. Not covered: HTTP headers and status codes, and
//! whether a result is the right answer; other properties judge those.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, OnceLock};

use jsonschema::{Draft, Validator};
use serde_json::{Value, json};

const MODERN: &str = "2026-07-28";
const LEGACY: &str = "2025-11-25";

/// Unions of every message one side may send. They repeat the `method` constants of their
/// members, so they are left out when definitions are looked up by method.
const UNIONS: [&str; 8] = [
    "ClientRequest",
    "ClientNotification",
    "ClientResult",
    "ClientMessage",
    "ServerRequest",
    "ServerNotification",
    "ServerResult",
    "ServerMessage",
];

/// `resultType` values an extension defines. The core schema has no shape for such a result.
const EXTENSION_RESULTS: [&str; 1] = ["task"];

struct Spec {
    root: Value,
    /// `method` constant to the definition of that request or notification.
    by_method: BTreeMap<String, String>,
    /// Request method to the definition of its result (`FooRequest` gives `FooResult`).
    result_of: BTreeMap<String, String>,
    /// Error code to the definition of that error response.
    by_code: BTreeMap<i64, String>,
    compiled: Mutex<BTreeMap<String, Result<Arc<Validator>, String>>>,
}

impl Spec {
    fn new(text: &str) -> Spec {
        let root: Value = serde_json::from_str(text).expect("a vendored schema is not JSON");
        let defs = root["$defs"].as_object().expect("a vendored schema has no $defs").clone();
        let mut spec = Spec { root, by_method: BTreeMap::new(), result_of: BTreeMap::new(), by_code: BTreeMap::new(), compiled: Mutex::new(BTreeMap::new()) };
        for (name, def) in &defs {
            if UNIONS.contains(&name.as_str()) {
                continue;
            }
            if let Some(method) = def["properties"]["method"]["const"].as_str() {
                spec.by_method.insert(method.to_owned(), name.clone());
                if let Some(base) = name.strip_suffix("Request") {
                    let result = format!("{base}Result");
                    if defs.contains_key(&result) {
                        spec.result_of.insert(method.to_owned(), result);
                    }
                }
            }
            for part in def["properties"]["error"]["allOf"].as_array().into_iter().flatten() {
                if let Some(code) = part["properties"]["code"]["const"].as_i64() {
                    spec.by_code.insert(code, name.clone());
                }
            }
        }
        spec
    }

    fn has(&self, def: &str) -> bool {
        self.root["$defs"].get(def).is_some()
    }

    /// The validator for one definition: the whole schema with a `$ref` to it at the root, so
    /// every reference inside resolves as in the original file.
    fn validator(&self, def: &str) -> Result<Arc<Validator>, String> {
        let mut compiled = self.compiled.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(v) = compiled.get(def) {
            return v.clone();
        }
        let mut schema = self.root.clone();
        schema["$ref"] = json!(format!("#/$defs/{def}"));
        let built = jsonschema::options()
            .with_draft(Draft::Draft202012)
            .should_validate_formats(true)
            .build(&schema)
            .map(Arc::new)
            .map_err(|e| format!("{def}: the schema definition does not compile: {e}"));
        compiled.insert(def.to_owned(), built.clone());
        built
    }

    /// What `instance` violates in `def`, at most six entries.
    fn check(&self, def: &str, instance: &Value) -> Vec<String> {
        let validator = match self.validator(def) {
            Ok(v) => v,
            Err(why) => return vec![why],
        };
        let mut out: Vec<String> = validator.iter_errors(instance).map(|e| format!("{def}{}: {e}", e.instance_path())).collect();
        if out.len() > 6 {
            let more = out.len() - 6;
            out.truncate(6);
            out.push(format!("... {more} more"));
        }
        out
    }
}

fn spec(version: &str) -> Option<&'static Spec> {
    static MODERN_SPEC: OnceLock<Spec> = OnceLock::new();
    static LEGACY_SPEC: OnceLock<Spec> = OnceLock::new();
    match version {
        MODERN => Some(MODERN_SPEC.get_or_init(|| Spec::new(include_str!("../schema/2026-07-28.json")))),
        LEGACY => Some(LEGACY_SPEC.get_or_init(|| Spec::new(include_str!("../schema/2025-11-25.json")))),
        _ => None,
    }
}

/// What `message` violates in the schema of `version`. Empty means valid. `request_method` is
/// the method of the request the message answers, when it is a response.
pub fn violations(version: &str, message: &Value, request_method: Option<&str>) -> Vec<String> {
    let Some(spec) = spec(version) else {
        return vec![format!("no schema for protocol version {version}")];
    };
    let Some(object) = message.as_object() else {
        return vec!["not a JSON-RPC request, notification or response (batches are not valid either)".to_owned()];
    };
    if let Some(method) = object.get("method").and_then(Value::as_str) {
        let fallback = if object.contains_key("id") { "JSONRPCRequest" } else { "JSONRPCNotification" };
        let def = spec.by_method.get(method).map(String::as_str).unwrap_or(fallback);
        return spec.check(def, message);
    }
    if let Some(error) = object.get("error").filter(|e| !e.is_null()) {
        let def = error.get("code").and_then(Value::as_i64).and_then(|code| spec.by_code.get(&code)).map(String::as_str);
        return spec.check(def.unwrap_or("JSONRPCErrorResponse"), message);
    }
    if let Some(result) = object.get("result") {
        let result_type = result.get("resultType").and_then(Value::as_str);
        let def = if result_type == Some("input_required") && spec.has("InputRequiredResult") {
            Some("InputRequiredResult")
        } else if result_type.is_some_and(|t| EXTENSION_RESULTS.contains(&t)) {
            None
        } else {
            request_method.and_then(|m| spec.result_of.get(m)).map(String::as_str)
        };
        if let Some(def) = def {
            let bad = spec.check(def, result);
            if !bad.is_empty() {
                return bad;
            }
        }
        return spec.check("JSONRPCResultResponse", message);
    }
    vec!["not a JSON-RPC request, notification or response".to_owned()]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid(version: &str, message: Value, method: Option<&str>) {
        let bad = violations(version, &message, method);
        assert!(bad.is_empty(), "{message}: {bad:?}");
    }

    fn invalid(version: &str, message: Value, method: Option<&str>) -> Vec<String> {
        let bad = violations(version, &message, method);
        assert!(!bad.is_empty(), "{message} should not be valid");
        bad
    }

    #[test]
    fn every_definition_the_lookup_can_name_compiles() {
        for version in [MODERN, LEGACY] {
            let spec = spec(version).unwrap();
            let names = spec.by_method.values().chain(spec.result_of.values()).chain(spec.by_code.values()).map(String::as_str);
            for def in names.chain(["JSONRPCRequest", "JSONRPCNotification", "JSONRPCErrorResponse", "JSONRPCResultResponse"]) {
                assert!(spec.has(def), "{version} has no {def}");
                spec.validator(def).unwrap();
            }
        }
        assert_eq!(spec(MODERN).unwrap().result_of["tools/call"], "CallToolResult");
        assert_eq!(spec(LEGACY).unwrap().result_of["initialize"], "InitializeResult");
        assert_eq!(spec(MODERN).unwrap().by_code[&-32021], "MissingRequiredClientCapabilityError");
        assert!(spec(MODERN).unwrap().has("InputRequiredResult"));
    }

    // The replies below are what the server under test sends, copied from a run.

    #[test]
    fn the_servers_tool_results_are_valid() {
        let modern = json!({"jsonrpc": "2.0", "id": 7, "result": {
            "content": [{"type": "text", "text": "echo:t"}], "isError": false, "resultType": "complete",
            "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "fast-mcp-scala-antithesis", "version": "1.0.0"}}}});
        valid(MODERN, modern, Some("tools/call"));
        let legacy = json!({"jsonrpc": "2.0", "id": 7, "result": {"content": [{"type": "text", "text": "echo:t"}], "isError": false}});
        valid(LEGACY, legacy, Some("tools/call"));
    }

    #[test]
    fn a_result_is_held_to_its_methods_definition() {
        // 2026-07-28 requires resultType on every result.
        let no_type = json!({"jsonrpc": "2.0", "id": 7, "result": {"content": [{"type": "text", "text": "x"}]}});
        invalid(MODERN, no_type.clone(), Some("tools/call"));
        valid(LEGACY, no_type, Some("tools/call"));
        // A content block of no known type.
        let bad_block = json!({"jsonrpc": "2.0", "id": 7, "result": {"content": [{"type": "text"}], "resultType": "complete"}});
        let bad = invalid(MODERN, bad_block, Some("tools/call"));
        assert!(bad[0].starts_with("CallToolResult"), "{bad:?}");
        // The same result with no method to go by only has to be a result.
        let loose = json!({"jsonrpc": "2.0", "id": 7, "result": {"content": 3, "resultType": "complete"}});
        valid(MODERN, loose.clone(), None);
        invalid(MODERN, loose, Some("tools/call"));
    }

    #[test]
    fn a_task_handle_is_only_held_to_the_envelope() {
        let handle = json!({"jsonrpc": "2.0", "id": 7, "result": {"resultType": "task", "taskId": "abc", "status": "working"}});
        valid(MODERN, handle, Some("tools/call"));
        let polled = json!({"jsonrpc": "2.0", "id": 8, "result": {"resultType": "complete", "taskId": "abc", "status": "working"}});
        valid(MODERN, polled, Some("tasks/get"));
    }

    #[test]
    fn notifications_are_held_to_their_definition() {
        let progress = json!({"jsonrpc": "2.0", "method": "notifications/progress", "params": {"progressToken": "p1", "progress": 1.0, "total": 3.0}});
        valid(MODERN, progress.clone(), None);
        valid(LEGACY, progress, None);
        let no_token = json!({"jsonrpc": "2.0", "method": "notifications/progress", "params": {"progress": 1.0}});
        invalid(MODERN, no_token.clone(), None);
        invalid(LEGACY, no_token, None);
    }

    #[test]
    fn error_responses() {
        let not_found = json!({"jsonrpc": "2.0", "id": 7, "error": {"code": -32601, "message": "Method not found: tools/nope"}});
        valid(MODERN, not_found.clone(), None);
        valid(LEGACY, not_found, None);
        let no_id = json!({"jsonrpc": "2.0", "error": {"code": -32700, "message": "Parse error"}});
        valid(MODERN, no_id.clone(), None);
        valid(LEGACY, no_id, None);
        let no_message = json!({"jsonrpc": "2.0", "id": 7, "error": {"code": -32601}});
        invalid(MODERN, no_message.clone(), None);
        invalid(LEGACY, no_message, None);
        // -32021 must say which capabilities are missing.
        let no_data = json!({"jsonrpc": "2.0", "id": 7, "error": {"code": -32021, "message": "needs sampling"}});
        let bad = invalid(MODERN, no_data, None);
        assert!(bad[0].starts_with("MissingRequiredClientCapabilityError"), "{bad:?}");
        // The schema types `id` as a string or an integer, and lets it be absent. Null is neither.
        let null_id = json!({"jsonrpc": "2.0", "id": null, "error": {"code": -32700, "message": "Parse error"}});
        invalid(MODERN, null_id.clone(), None);
        invalid(LEGACY, null_id, None);
    }

    #[test]
    fn what_is_no_message_at_all() {
        invalid(MODERN, json!([{"jsonrpc": "2.0", "id": 1, "result": {"resultType": "complete"}}]), None);
        invalid(MODERN, json!({"jsonrpc": "2.0", "id": 1}), None);
        invalid(MODERN, json!("text"), None);
        invalid("2024-11-05", json!({"jsonrpc": "2.0", "id": 1, "result": {}}), None);
    }
}
