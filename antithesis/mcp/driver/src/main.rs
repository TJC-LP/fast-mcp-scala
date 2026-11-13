//! Hegel driver for the fast-mcp-scala Antithesis workload.
//!
//! It speaks MCP over HTTP to every node in `MCP_NODES` (the same server on different HTTP
//! backends), on both protocol paths: 2026-07-28 (one self-describing request per POST) and
//! legacy sessions (initialize, then calls with `Mcp-Session-Id`). Every call carries a fresh
//! token, and every tool returns it, so each reply is judged against exactly one request.
//!
//! All randomness is drawn from hegel, after `/workload-active` appears.

mod http;

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, RwLock, RwLockReadGuard};
use std::time::{Duration, Instant};

use antithesis_sdk::{antithesis_init, assert_always, assert_reachable, assert_sometimes, lifecycle};
use hegel::TestCase;
use hegel::generators as gs;
use hegel::stateful::machine;
use serde_json::{Value, json};

use http::{Answer, Client};

const MODERN: &str = "2026-07-28";
const LEGACY: &str = "2025-11-25";
const TASKS_EXT: &str = "io.modelcontextprotocol/tasks";
const USER_HEADER: &str = "x-test-user";
/// Sessions the driver keeps per node. Far below the server's cap (32), so the cap only evicts
/// when the flood rule fills it on purpose.
const SESSIONS_PER_NODE: usize = 6;
const MAX_TASKS: usize = 48;
/// More than the server's `maxSessions`.
const FLOOD: usize = 40;

struct Node {
    name: String,
    base: String,
}

impl Node {
    fn url(&self) -> String {
        format!("{}/mcp", self.base)
    }
    fn host_port(&self) -> String {
        self.base.trim_start_matches("http://").to_owned()
    }
    fn host(&self) -> String {
        self.host_port().split(':').next().unwrap_or("").to_owned()
    }
}

#[derive(Clone)]
struct Sess {
    node: usize,
    sid: String,
    /// The node's boot id when the session was opened, if a reply has shown it.
    boot: Option<String>,
    /// The driver's sequence number when the session was opened. A flood of the node that
    /// started later may have evicted it at the cap.
    opened: u64,
}

struct TaskRec {
    node: usize,
    id: String,
    token: String,
    boot: Option<String>,
    terminal: Option<String>,
}

struct Driver {
    http: Client,
    nodes: Vec<Node>,
    seq: AtomicU64,
    sessions: Mutex<Vec<Sess>>,
    /// Sessions the driver ended itself (DELETE). Workers share sessions, so one may still be
    /// using a session another has just ended; its 404 is then expected.
    ended: Mutex<Vec<String>>,
    /// Per node: the sequence number at which the latest flood of its session store ended, or
    /// `u64::MAX` while one is running. A session opened before that point may have been evicted
    /// at the cap, including one opened while the flood was still running.
    flooded: Vec<AtomicU64>,
    /// Per node: the boot id under which a flood last filled its session store (`"?"` when the
    /// node could not say). The store stays at its cap until the node restarts, so from then on
    /// every initialize there evicts some session, in an order the driver cannot know: under
    /// faults it does not know which of its requests reached the server, or when.
    full_under: Vec<Mutex<Option<String>>>,
    tasks: Mutex<Vec<TaskRec>>,
    /// Rules hold this shared while they act; the eventually takes it exclusively.
    quiet: RwLock<()>,
    ending: AtomicBool,
    started: Instant,
    eventually_off: bool,
    eventually_after: Option<Duration>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// First value under `key` anywhere in `v`.
fn find<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    match v {
        Value::Object(map) => map.get(key).or_else(|| map.values().find_map(|c| find(c, key))),
        Value::Array(items) => items.iter().find_map(|c| find(c, key)),
        _ => None,
    }
}

/// The final reply to request `id` among `frames`: whether it is an error, and its text.
fn final_reply(frames: &[Value], id: u64) -> Option<(bool, String)> {
    let reply = frames.iter().find(|f| f.get("id").and_then(Value::as_u64) == Some(id))?;
    if let Some(err) = reply.get("error") {
        return Some((true, err.to_string()));
    }
    let result = reply.get("result")?;
    let is_error = result.get("isError").and_then(Value::as_bool).unwrap_or(false);
    let text = result
        .get("content")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("text"))
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| result.to_string());
    Some((is_error, text))
}

/// The routing headers of a 2026-07-28 request.
fn push_modern(headers: &mut Vec<(&'static str, String)>, method: &str, name: &str) {
    headers.push(("mcp-protocol-version", MODERN.to_owned()));
    headers.push(("mcp-method", method.to_owned()));
    headers.push(("mcp-name", name.to_owned()));
}

/// No request, however wrong, may be answered with a 500.
fn no_500(status: i32, details: &Value) {
    assert_always!(status != 500, "the server never answers 500", details);
}

/// The value after `key=` in a `a:b=c:d=e` reply text.
fn field(text: &str, key: &str) -> Option<String> {
    text.split(':').find_map(|part| part.strip_prefix(&format!("{key}=")).map(str::to_owned))
}

impl Driver {
    fn next(&self) -> u64 {
        self.seq.fetch_add(1, Ordering::SeqCst) + 1
    }

    /// None once the timeline is ending; otherwise the shared hold a rule keeps while it acts.
    fn enter(&self) -> Option<RwLockReadGuard<'_, ()>> {
        if self.ending.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(100));
            return None;
        }
        let g = self.quiet.read().unwrap_or_else(|e| e.into_inner());
        if self.ending.load(Ordering::SeqCst) { None } else { Some(g) }
    }

    fn base_headers() -> Vec<(&'static str, String)> {
        vec![
            ("content-type", "application/json".to_owned()),
            ("accept", "application/json, text/event-stream".to_owned()),
        ]
    }

    /// A 2026-07-28 request. `meta` is merged into `_meta` (a progress token, extra capabilities).
    fn modern(&self, node: usize, id: u64, method: &str, name: &str, mut params: Value, meta: Value, timeout: Duration) -> Answer {
        let mut m = json!({
            "io.modelcontextprotocol/protocolVersion": MODERN,
            "io.modelcontextprotocol/clientCapabilities": {},
            "io.modelcontextprotocol/clientInfo": {"name": "antithesis-driver", "version": "1"},
        });
        if let (Some(dst), Some(src)) = (m.as_object_mut(), meta.as_object()) {
            for (k, v) in src {
                dst.insert(k.clone(), v.clone());
            }
        }
        params["_meta"] = m;
        let body = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}).to_string();
        let mut headers = Self::base_headers();
        headers.push(("mcp-protocol-version", MODERN.to_owned()));
        headers.push(("mcp-method", method.to_owned()));
        headers.push(("mcp-name", name.to_owned()));
        headers.push((USER_HEADER, format!("u{id}")));
        self.http.send("POST", &self.nodes[node].url(), &headers, Some(&body), timeout)
    }

    fn modern_tool(&self, node: usize, id: u64, tool: &str, args: Value, meta: Value, timeout: Duration) -> Answer {
        self.modern(node, id, "tools/call", tool, json!({"name": tool, "arguments": args}), meta, timeout)
    }

    /// A legacy request on a session. `meta`, when not null, becomes `params._meta`.
    fn legacy_tool(&self, s: &Sess, id: u64, tool: &str, args: Value, meta: Value, timeout: Duration) -> Answer {
        let mut params = json!({"name": tool, "arguments": args});
        if !meta.is_null() {
            params["_meta"] = meta;
        }
        let body = json!({"jsonrpc": "2.0", "id": id, "method": "tools/call", "params": params}).to_string();
        let mut headers = Self::base_headers();
        headers.push(("mcp-session-id", s.sid.clone()));
        headers.push(("mcp-protocol-version", LEGACY.to_owned()));
        headers.push((USER_HEADER, format!("u{id}")));
        self.http.send("POST", &self.nodes[s.node].url(), &headers, Some(&body), timeout)
    }

    /// Opens a legacy session: initialize, then the initialized notification. Answers the
    /// initialize status alongside, for the caller's checks.
    fn open_session(&self, node: usize, timeout: Duration) -> (i32, Option<Sess>) {
        let id = self.next();
        let body = json!({"jsonrpc": "2.0", "id": id, "method": "initialize", "params": {
            "protocolVersion": LEGACY, "capabilities": {},
            "clientInfo": {"name": "antithesis-driver", "version": "1"}}})
        .to_string();
        let a = self.http.send("POST", &self.nodes[node].url(), &Self::base_headers(), Some(&body), timeout);
        self.judge(node, &a, "initialize");
        let Some(sid) = a.session.clone().filter(|_| a.status == 200) else {
            return (a.status, None);
        };
        let mut headers = Self::base_headers();
        headers.push(("mcp-session-id", sid.clone()));
        let note = json!({"jsonrpc": "2.0", "method": "notifications/initialized"}).to_string();
        let ack = self.http.send("POST", &self.nodes[node].url(), &headers, Some(&note), timeout);
        if ack.status != 202 {
            return (a.status, None);
        }
        (a.status, Some(Sess { node, sid, boot: None, opened: id }))
    }

    /// A session on `node` for a rule to use: one the driver holds, or a new one.
    fn session_for(&self, node: usize, pick: u64) -> Option<Sess> {
        {
            let all = lock(&self.sessions);
            let mine: Vec<&Sess> = all.iter().filter(|s| s.node == node).collect();
            if mine.len() >= SESSIONS_PER_NODE {
                return Some(mine[(pick as usize) % mine.len()].clone());
            }
        }
        let (_, opened) = self.open_session(node, Duration::from_secs(5));
        let s = opened?;
        lock(&self.sessions).push(s.clone());
        Some(s)
    }

    fn forget_session(&self, sid: &str) {
        lock(&self.sessions).retain(|s| s.sid != sid);
    }

    /// The node's boot id, asked over the 2026-07-28 path. None when it does not answer.
    fn boot_of(&self, node: usize) -> Option<String> {
        let id = self.next();
        let a = self.modern_tool(node, id, "stats", json!({"token": format!("t{id}")}), json!({}), Duration::from_secs(2));
        let (frames, _) = a.messages();
        let (_, text) = final_reply(&frames, id)?;
        field(&text, "boot")
    }

    /// What every answer must satisfy, whatever was sent.
    fn judge(&self, node: usize, a: &Answer, what: &str) {
        let details = json!({"node": self.nodes[node].name, "request": what, "status": a.status, "body": a.head()});
        no_500(a.status, &details);
        assert_sometimes!(a.status == -1, "a request gets no answer", &details);
    }

    /// A session answered 404: that is only right if its node restarted since it was opened, the
    /// driver deleted it, or the node's session store was at its cap (a flood ran while the session
    /// existed, or filled the store earlier under the same boot). When the node cannot say,
    /// nothing is concluded. The details carry the session id: the server logs "evicted session
    /// <id>" for every cap eviction, which settles a counterexample.
    fn judge_lost_session(&self, s: &Sess) {
        self.forget_session(&s.sid);
        let now = self.boot_of(s.node);
        // Read after the 404: a DELETE or a flood that explains it has been recorded by now.
        let ended = lock(&self.ended).iter().any(|sid| sid == &s.sid);
        let full_under = lock(&self.full_under[s.node]).clone();
        let store_full = match (&full_under, &s.boot) {
            (Some(full), Some(boot)) => full == "?" || full == boot,
            (Some(_), None) => true,
            (None, _) => false,
        };
        let evictable = store_full || self.flooded[s.node].load(Ordering::SeqCst) > s.opened;
        let details = json!({"node": self.nodes[s.node].name, "sid": s.sid, "opened_under": s.boot, "boot_now": now,
            "store_full_under": full_under, "evictable": evictable, "ended_by_driver": ended});
        if let (Some(then), Some(now)) = (&s.boot, &now) {
            let restarted = then != now;
            assert_always!(
                restarted || evictable || ended,
                "a live session is lost only when its node restarted, the session cap evicted it, or it was deleted",
                &details
            );
        }
    }

    /// Remember the boot id a reply showed, on the session that carried the call.
    fn note_boot(&self, sid: &str, text: &str) {
        if let Some(boot) = field(text, "boot") {
            for s in lock(&self.sessions).iter_mut().filter(|s| s.sid == sid && s.boot.is_none()) {
                s.boot = Some(boot.clone());
            }
        }
    }

    /// One call of `tool`, on either path. Answers the reply text when the call was answered 200
    /// with a result for this request; every other outcome is judged here.
    #[allow(clippy::too_many_arguments)]
    fn call(&self, node: usize, modern: bool, pick: u64, tool: &str, args: Value, meta: Value, timeout: Duration) -> Option<(u64, Answer, Vec<Value>, usize, Option<Sess>)> {
        let id = self.next();
        let mut full = args;
        full["token"] = json!(format!("t{id}"));
        let (a, sess) = if modern {
            (self.modern_tool(node, id, tool, full, meta, timeout), None)
        } else {
            let s = self.session_for(node, pick)?;
            (self.legacy_tool(&s, id, tool, full, meta, timeout), Some(s))
        };
        self.judge(node, &a, tool);
        if a.status == -1 {
            return None;
        }
        if a.status == 404 {
            if let Some(s) = &sess {
                self.judge_lost_session(s);
                return None;
            }
        }
        let details = json!({"node": self.nodes[node].name, "tool": tool, "modern": modern, "status": a.status, "body": a.head()});
        // 408: the socket backend's answer to a request that arrived too slowly, which a
        // network fault can cause.
        assert_always!(a.status == 200 || a.status == 408, "a well-formed call is answered 200", &details);
        if a.status != 200 {
            return None;
        }
        let (frames, pings) = a.messages();
        let answered = frames.iter().any(|f| f.get("id").and_then(Value::as_u64) == Some(id));
        if let Some(s) = &sess {
            // A legacy call answered 200 whose stream ended without a reply: terminating a
            // session (DELETE, eviction at the cap) interrupts its calls in flight, and by then
            // the response may already be open, so that is how such a call ends. It is right
            // only then: the session must be gone. Judged on every legacy call, so the property
            // is exercised whether or not a call is ever cut.
            let gone = if answered { None } else { self.session_gone(s) };
            let details = json!({"node": self.nodes[node].name, "tool": tool, "answered": answered, "session_gone": gone, "body": a.head()});
            assert_always!(
                answered || gone != Some(false),
                "a legacy call ends without a reply only when its session was terminated",
                &details
            );
            assert_sometimes!(!answered && gone == Some(true), "a call in flight is cut by its session's termination", &details);
            if !answered {
                return None;
            }
        }
        Some((id, a, frames, pings, sess))
    }

    /// Whether the session is gone (404 to a fresh call). None when the node does not answer.
    fn session_gone(&self, s: &Sess) -> Option<bool> {
        let id = self.next();
        let probe = self.legacy_tool(s, id, "echo", json!({"token": format!("t{id}")}), Value::Null, Duration::from_secs(3));
        match probe.status {
            -1 => None,
            404 => {
                self.forget_session(&s.sid);
                Some(true)
            }
            _ => Some(false),
        }
    }

    /// Stops faults for good, waits for the nodes, checks what must hold once faults stop, and
    /// ends the timeline.
    fn eventually(&self) {
        let _all = self.quiet.write().unwrap_or_else(|e| e.into_inner());
        if self.ending.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Ok(stop) = std::env::var("ANTITHESIS_STOP_FAULTS") {
            let _ = std::process::Command::new(stop).arg("3600").status();
        }
        println!("driver: eventually: faults stopped; waiting for the nodes");
        let deadline = Instant::now() + Duration::from_secs(120);
        let mut serving = vec![false; self.nodes.len()];
        let mut running = vec![-1i64; self.nodes.len()];
        // Handlers run at most 5 s, and a dropped connection is noticed within a few seconds, so
        // a count that is still above zero well after that is a handler nobody will ever stop.
        while Instant::now() < deadline {
            for n in 0..self.nodes.len() {
                let id = self.next();
                let a = self.modern_tool(n, id, "stats", json!({"token": format!("t{id}")}), json!({}), Duration::from_secs(3));
                let (frames, _) = a.messages();
                if let Some((false, text)) = final_reply(&frames, id) {
                    serving[n] = true;
                    running[n] = field(&text, "running").and_then(|v| v.parse().ok()).unwrap_or(-1);
                } else {
                    serving[n] = false;
                }
            }
            if serving.iter().all(|s| *s) && running.iter().all(|r| *r == 0) {
                break;
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        println!("driver: eventually: assertions start");
        for n in 0..self.nodes.len() {
            let details = json!({"node": self.nodes[n].name, "serving": serving[n], "running": running[n]});
            assert_always!(serving[n], "every node serves requests again once faults stop", &details);
            if !serving[n] {
                continue;
            }
            assert_always!(running[n] == 0, "no handler is still running once requests stop", &details);

            // A new session can be opened, and a GET stream that was dropped frees its slot.
            let (status, opened) = self.open_session(n, Duration::from_secs(5));
            let details = json!({"node": self.nodes[n].name, "status": status});
            assert_always!(opened.is_some(), "a new session can be opened once faults stop", &details);
            if let Some(s) = opened {
                let headers = vec![("accept", "text/event-stream".to_owned()), ("mcp-session-id", s.sid.clone())];
                let first = self.http.open_and_drop(&self.nodes[n].url(), &headers, Duration::from_secs(3));
                // The server notices the dropped stream on its next heartbeat or liveness probe.
                let mut second = first;
                let reopen_by = Instant::now() + Duration::from_secs(20);
                while first == 200 && Instant::now() < reopen_by {
                    std::thread::sleep(Duration::from_secs(2));
                    second = self.http.open_and_drop(&self.nodes[n].url(), &headers, Duration::from_secs(3));
                    if second == 200 {
                        break;
                    }
                }
                let details = json!({"node": self.nodes[n].name, "first": first, "second": second});
                // 405: this backend has no GET channel (Bun), so there is no slot to free.
                assert_always!(first == 200 || first == 405, "a session's GET stream opens once faults stop", &details);
                assert_always!(second == 200 || first == 405, "a dropped GET stream frees its slot", &details);
            }
        }
        println!("driver: eventually: ending the timeline");
        std::process::exit(0);
    }
}

#[hegel::concurrent_state_machine]
impl Driver {
    /// Ends the timeline behind a rare draw (or after a set time, for the local loop).
    #[rule]
    fn maybe_eventually(&self, tc: TestCase) {
        let drawn = tc.draw(gs::weighted_booleans(0.0005));
        if self.eventually_off || self.ending.load(Ordering::SeqCst) {
            return;
        }
        let fire = match self.eventually_after {
            Some(after) => self.started.elapsed() >= after,
            None => drawn,
        };
        if fire {
            self.eventually();
        }
    }

    /// echo / whoami on either path: the reply is this request's, and the handler saw this
    /// request's header and session.
    #[rule(weight = 4.0)]
    fn identity(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let modern = tc.draw(gs::booleans());
        let pick = tc.draw(gs::integers::<u64>());
        let who = tc.draw(gs::booleans());
        let timeout = tc.draw(gs::sampled_from(vec![Duration::from_secs(2), Duration::from_secs(10)]));
        let Some(_g) = self.enter() else { return };
        let tool = if who { "whoami" } else { "echo" };
        let Some((id, a, frames, _, sess)) = self.call(node, modern, pick, tool, json!({}), json!({}), timeout) else { return };
        let reply = final_reply(&frames, id);
        let details = json!({"node": self.nodes[node].name, "tool": tool, "modern": modern, "id": id, "body": a.head()});
        let own = matches!(&reply, Some((false, text)) if text.starts_with(&format!("{tool}:t{id}")));
        assert_always!(own, "a reply carries its own request's id and its own result", &details);
        assert_always!(frames.len() == 1, "a call that emits nothing is answered with exactly one message", &details);
        assert_sometimes!(a.has_length, "a reply that is complete at once is sent with a Content-Length", &details);
        let Some((false, text)) = reply else { return };
        if who {
            let user = field(&text, "user").unwrap_or_default();
            assert_always!(user == format!("u{id}"), "a handler sees the request headers of its own request", &details);
            let session = field(&text, "session").unwrap_or_default();
            let expected = sess.as_ref().map(|s| s.sid.clone()).unwrap_or_else(|| "-".to_owned());
            assert_always!(
                session == expected,
                "protocolSessionId is the session's id on a legacy session and absent otherwise",
                &details
            );
            if let Some(s) = &sess {
                self.note_boot(&s.sid, &text);
            }
        }
    }

    /// A call that streams notifications before its result.
    #[rule(weight = 2.0)]
    fn progress(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let modern = tc.draw(gs::booleans());
        let pick = tc.draw(gs::integers::<u64>());
        let steps = tc.draw(gs::integers::<u64>().max_value(6));
        let Some(_g) = self.enter() else { return };
        let token = format!("p{}", self.next());
        let meta = json!({"progressToken": token});
        let Some((id, a, frames, _, _)) =
            self.call(node, modern, pick, "progress", json!({"steps": steps}), meta, Duration::from_secs(10))
        else {
            return;
        };
        let seen: Vec<u64> = frames
            .iter()
            .filter(|f| f.get("method").and_then(Value::as_str) == Some("notifications/progress"))
            .filter_map(|f| find(f, "progress").and_then(Value::as_f64).map(|p| p as u64))
            .collect();
        let expected: Vec<u64> = (1..=steps).collect();
        let last_is_reply = frames.last().and_then(|f| f.get("id")).and_then(Value::as_u64) == Some(id);
        let text_ok = matches!(final_reply(&frames, id), Some((false, t)) if t == format!("progress:t{id}:{steps}"));
        let details = json!({"node": self.nodes[node].name, "modern": modern, "steps": steps, "seen": seen, "frames": frames.len(), "body": a.head()});
        assert_always!(
            seen == expected && last_is_reply && text_ok && frames.len() as u64 == steps + 1,
            "a streamed reply delivers its notifications in order and then its result",
            &details
        );
        assert_sometimes!(steps > 0 && !a.has_length, "a reply with notifications is sent as a stream", &details);
    }

    /// A silent call, sometimes abandoned by the client before the reply.
    #[rule(weight = 2.0)]
    fn slow(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let modern = tc.draw(gs::booleans());
        let pick = tc.draw(gs::integers::<u64>());
        let millis = tc.draw(gs::sampled_from(vec![0u64, 300, 900, 1600]));
        let timeout = tc.draw(gs::sampled_from(vec![Duration::from_millis(400), Duration::from_secs(6)]));
        let Some(_g) = self.enter() else { return };
        let abandoned = timeout < Duration::from_millis(millis);
        let Some((id, a, frames, pings, _)) =
            self.call(node, modern, pick, "slow", json!({"millis": millis}), json!({}), timeout)
        else {
            assert_sometimes!(abandoned, "a client abandons a call before its reply arrives", &json!({"millis": millis}));
            return;
        };
        let details = json!({"node": self.nodes[node].name, "modern": modern, "millis": millis, "pings": pings, "body": a.head()});
        let own = matches!(final_reply(&frames, id), Some((false, t)) if t == format!("slow:t{id}"));
        assert_always!(own, "a slow call's reply is its own", &details);
        assert_sometimes!(millis >= 900 && pings > 0, "a silent call's reply opens with a heartbeat", &details);
    }

    /// Starts a call that may run as a task.
    #[rule]
    fn task_start(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let millis = tc.draw(gs::sampled_from(vec![0u64, 200, 1000, 3000]));
        // A client that does not declare the tasks extension must be answered directly.
        let declares = tc.draw(gs::weighted_booleans(0.7));
        let Some(_g) = self.enter() else { return };
        let meta = if declares {
            json!({"io.modelcontextprotocol/clientCapabilities": {"extensions": {TASKS_EXT: {}}}})
        } else {
            json!({})
        };
        let Some((id, a, frames, _, _)) =
            self.call(node, true, 0, "task_echo", json!({"millis": millis}), meta, Duration::from_secs(10))
        else {
            return;
        };
        let details = json!({"node": self.nodes[node].name, "millis": millis, "declares": declares, "body": a.head()});
        let reply = frames.iter().find(|f| f.get("id").and_then(Value::as_u64) == Some(id));
        let task_id = reply.and_then(|r| find(r, "taskId")).and_then(Value::as_str).map(str::to_owned);
        assert_sometimes!(task_id.is_some(), "an optional-task tool answers with a task handle", &details);
        assert_always!(
            declares || task_id.is_none(),
            "a client that did not declare the tasks extension is never handed a task",
            &details
        );
        match task_id {
            Some(tid) => {
                let boot = self.boot_of(node);
                let mut tasks = lock(&self.tasks);
                if tasks.len() >= MAX_TASKS {
                    tasks.remove(0);
                }
                tasks.push(TaskRec { node, id: tid, token: format!("t{id}"), boot, terminal: None });
            }
            None => {
                let own = matches!(final_reply(&frames, id), Some((false, t)) if t == format!("task:t{id}"));
                assert_always!(own, "a task-capable tool answered directly returns its own result", &details);
            }
        }
    }

    /// Polls a task the driver started.
    #[rule(weight = 2.0)]
    fn task_poll(&self, tc: TestCase) {
        let pick = tc.draw(gs::integers::<u64>());
        let cancel = tc.draw(gs::weighted_booleans(0.3));
        let Some(_g) = self.enter() else { return };
        let (node, tid, token, boot, terminal) = {
            let tasks = lock(&self.tasks);
            if tasks.is_empty() {
                return;
            }
            let t = &tasks[(pick as usize) % tasks.len()];
            (t.node, t.id.clone(), t.token.clone(), t.boot.clone(), t.terminal.clone())
        };
        let meta = json!({"io.modelcontextprotocol/clientCapabilities": {"extensions": {TASKS_EXT: {}}}});
        let method = if cancel && terminal.is_none() { "tasks/cancel" } else { "tasks/get" };
        let id = self.next();
        let a = self.modern(node, id, method, &tid, json!({"taskId": tid}), meta, Duration::from_secs(5));
        self.judge(node, &a, method);
        if a.status == -1 || method == "tasks/cancel" {
            return;
        }
        let (frames, _) = a.messages();
        let reply = frames.iter().find(|f| f.get("id").and_then(Value::as_u64) == Some(id));
        let details = json!({"node": self.nodes[node].name, "task": tid, "was": terminal, "status": a.status, "body": a.head()});
        let status = reply.and_then(|r| r.get("result")).and_then(|r| find(r, "status")).and_then(Value::as_str).map(str::to_owned);
        // A handle the node no longer knows is right only if the node restarted since. When
        // either boot id is unknown, nothing is concluded.
        let restarted = match (&status, &boot) {
            (None, Some(then)) => self.boot_of(node).map(|now| &now != then),
            _ => None,
        };
        assert_always!(
            status.is_some() || restarted != Some(false),
            "a task handle stays valid while its node keeps running",
            &details
        );
        let Some(status) = status else {
            lock(&self.tasks).retain(|t| t.id != tid);
            return;
        };
        if let Some(was) = &terminal {
            assert_always!(&status == was, "a terminal task status never changes", &details);
        }
        if status == "completed" {
            let body = reply.map(Value::to_string).unwrap_or_default();
            assert_always!(
                body.contains(&format!("task:{token}")),
                "a completed task returns the result of its own call",
                &details
            );
        }
        assert_sometimes!(status == "working", "a task is polled while it is still working", &details);
        assert_sometimes!(status == "cancelled", "a task is cancelled", &details);
        if matches!(status.as_str(), "completed" | "failed" | "cancelled") {
            for t in lock(&self.tasks).iter_mut().filter(|t| t.id == tid) {
                t.terminal = Some(status.clone());
            }
        }
    }

    /// Ends a session; it must then be gone.
    #[rule]
    fn session_delete(&self, tc: TestCase) {
        let pick = tc.draw(gs::integers::<u64>());
        let Some(_g) = self.enter() else { return };
        let s = {
            let all = lock(&self.sessions);
            if all.is_empty() {
                return;
            }
            all[(pick as usize) % all.len()].clone()
        };
        self.forget_session(&s.sid);
        {
            // Recorded before the DELETE is sent, so no worker can see its effect unexplained.
            let mut ended = lock(&self.ended);
            if ended.len() >= 256 {
                ended.remove(0);
            }
            ended.push(s.sid.clone());
        }
        let headers = vec![("mcp-session-id", s.sid.clone())];
        let a = self.http.send("DELETE", &self.nodes[s.node].url(), &headers, None, Duration::from_secs(5));
        self.judge(s.node, &a, "DELETE");
        if a.status != 200 {
            return;
        }
        let id = self.next();
        let after = self.legacy_tool(&s, id, "echo", json!({"token": format!("t{id}")}), Value::Null, Duration::from_secs(5));
        self.judge(s.node, &after, "echo after DELETE");
        let details = json!({"node": self.nodes[s.node].name, "status": after.status, "body": after.head()});
        assert_always!(after.status == 404 || after.status == -1, "a deleted session answers 404", &details);
    }

    /// Opens a session's GET stream and drops it, as a client that goes away does.
    #[rule]
    fn get_stream(&self, tc: TestCase) {
        let pick = tc.draw(gs::integers::<u64>());
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let Some(_g) = self.enter() else { return };
        let Some(s) = self.session_for(node, pick) else { return };
        let headers = vec![("accept", "text/event-stream".to_owned()), ("mcp-session-id", s.sid.clone())];
        let status = self.http.open_and_drop(&self.nodes[node].url(), &headers, Duration::from_secs(2));
        let details = json!({"node": self.nodes[node].name, "status": status});
        no_500(status, &details);
        // 409 while the previous stream's disconnect has not been noticed yet; 404 is judged by
        // the next call on the session; 405 on a backend with no GET channel (Bun, by design:
        // its per-request streams carry all server-to-client traffic).
        assert_always!(
            matches!(status, 200 | 404 | 405 | 409 | -1),
            "a GET on a session answers 200, 404, 405 or 409",
            &details
        );
        assert_sometimes!(status == 200, "a session's GET stream opens", &details);
        assert_sometimes!(status == 409, "a second GET finds the first still attached", &details);
    }

    /// Fills a node's session store past its cap. Every initialize is admitted (evicting an idle
    /// session) or refused with 503.
    #[rule]
    fn flood(&self, tc: TestCase) {
        let drawn = tc.draw(gs::weighted_booleans(0.02));
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        if !drawn {
            return;
        }
        let Some(_g) = self.enter() else { return };
        // Recorded before the first initialize of the flood is sent.
        self.flooded[node].store(u64::MAX, Ordering::SeqCst);
        let boot = self.boot_of(node).unwrap_or_else(|| "?".to_owned());
        *lock(&self.full_under[node]) = Some(boot);
        let mut admitted = 0;
        for _ in 0..FLOOD {
            let (status, opened) = self.open_session(node, Duration::from_secs(3));
            let details = json!({"node": self.nodes[node].name, "status": status});
            assert_always!(
                matches!(status, 200 | 503 | 408 | -1),
                "an initialize at the session cap is admitted or refused with 503",
                &details
            );
            if opened.is_some() {
                admitted += 1;
            }
        }
        self.flooded[node].store(self.next(), Ordering::SeqCst);
        assert_sometimes!(admitted == FLOOD, "a flood of sessions is admitted by evicting idle ones", &json!({"admitted": admitted}));
    }

    /// Requests that are wrong in one way each. All must be refused with a 4xx.
    #[rule(weight = 2.0)]
    fn malformed(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let variant = tc.draw(gs::integers::<usize>().max_value(11));
        let Some(_g) = self.enter() else { return };
        let id = self.next();
        let mut headers = Self::base_headers();
        let modern_meta = json!({"io.modelcontextprotocol/protocolVersion": MODERN, "io.modelcontextprotocol/clientCapabilities": {}});
        let modern_call = |name: &str| {
            json!({"jsonrpc": "2.0", "id": id, "method": "tools/call",
                   "params": {"name": name, "arguments": {"token": "x"}, "_meta": modern_meta}})
            .to_string()
        };
        let (what, body) = match variant {
            0 => ("truncated JSON", "{\"jsonrpc\":".to_owned()),
            1 => ("empty body", String::new()),
            2 => ("a batch", format!("[{}]", modern_call("echo"))),
            3 => {
                push_modern(&mut headers, "tools/nope", "echo");
                ("an unknown method", json!({"jsonrpc": "2.0", "id": id, "method": "tools/nope", "params": {"_meta": modern_meta}}).to_string())
            }
            4 => {
                push_modern(&mut headers, "tools/call", "whoami");
                ("Mcp-Name that contradicts the body", modern_call("echo"))
            }
            5 => {
                headers.retain(|(k, _)| *k != "accept");
                headers.push(("accept", "text/plain".to_owned()));
                push_modern(&mut headers, "tools/call", "echo");
                ("an Accept without the MCP types", modern_call("echo"))
            }
            6 => {
                headers.retain(|(k, _)| *k != "content-type");
                headers.push(("content-type", "text/plain".to_owned()));
                push_modern(&mut headers, "tools/call", "echo");
                ("a Content-Type that is not JSON", modern_call("echo"))
            }
            7 => ("JSON nested past the depth limit", format!("{}1{}", "[".repeat(200), "]".repeat(200))),
            8 => ("an object as the id", "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}".to_owned()),
            9 => ("a legacy call with no session", json!({"jsonrpc": "2.0", "id": id, "method": "tools/list"}).to_string()),
            10 => {
                push_modern(&mut headers, "tools/call", "echo");
                ("a body over the size cap", json!({"jsonrpc": "2.0", "id": id, "method": "tools/call",
                    "params": {"name": "echo", "arguments": {"token": "x".repeat(1_200_000)}, "_meta": modern_meta}}).to_string())
            }
            _ => {
                headers.push(("mcp-session-id", format!("no-such-session-{id}")));
                ("a call on an unknown session", json!({"jsonrpc": "2.0", "id": id, "method": "tools/list"}).to_string())
            }
        };
        let a = self.http.send("POST", &self.nodes[node].url(), &headers, Some(&body), Duration::from_secs(5));
        self.judge(node, &a, what);
        let details = json!({"node": self.nodes[node].name, "request": what, "status": a.status, "body": a.head()});
        assert_always!(
            a.status == -1 || (400..500).contains(&a.status),
            "a malformed request is refused with a 4xx status",
            &details
        );
    }

    /// Regression probes for Claude Security findings. Each expects the hardened behaviour: it
    /// failed before its finding was fixed (TJC-5087) and a failure now is a regression.
    #[rule]
    fn probe(&self, tc: TestCase) {
        let node = tc.draw(gs::integers::<usize>().max_value(self.nodes.len() - 1));
        let variant = tc.draw(gs::integers::<usize>().max_value(3));
        let colour = tc.draw(gs::sampled_from(vec!["red", "green", "blue", "purple", ""]));
        let Some(_g) = self.enter() else { return };
        let n = &self.nodes[node];
        let id = self.next();
        let call = json!({"jsonrpc": "2.0", "id": id, "method": "tools/call", "params": {
            "name": "echo", "arguments": {"token": format!("t{id}")},
            "_meta": {"io.modelcontextprotocol/protocolVersion": MODERN, "io.modelcontextprotocol/clientCapabilities": {}}}})
        .to_string();
        let common = |eol: &str| {
            format!(
                "Host: {h}{eol}Content-Type: application/json{eol}Accept: application/json, text/event-stream{eol}\
                 Mcp-Protocol-Version: {MODERN}{eol}Mcp-Method: tools/call{eol}Connection: close{eol}",
                h = n.host()
            )
        };
        match variant {
            // Finding: the socket server's request-head parser accepts bare LF line endings.
            0 if n.name == "socket" => {
                let req = format!("POST /mcp HTTP/1.1\n{}Mcp-Name: echo\nContent-Length: {}\n\n{}", common("\n"), call.len(), call);
                let (status, head) = http::raw(&n.host_port(), req.as_bytes(), Duration::from_secs(3));
                let details = json!({"node": n.name, "status": status, "head": head});
                no_500(status, &details);
                assert_always!(status == 400 || status == -1, "a request head with bare LF line endings is refused", &details);
            }
            // Finding: the socket server's chunked-body parser accepts a bare LF after a chunk size.
            1 if n.name == "socket" => {
                let req = format!(
                    "POST /mcp HTTP/1.1\r\n{}Mcp-Name: echo\r\nTransfer-Encoding: chunked\r\n\r\n{:x}\n{}\r\n0\r\n\r\n",
                    common("\r\n"),
                    call.len(),
                    call
                );
                let (status, head) = http::raw(&n.host_port(), req.as_bytes(), Duration::from_secs(3));
                let details = json!({"node": n.name, "status": status, "head": head});
                no_500(status, &details);
                assert_always!(status == 400 || status == -1, "a chunked body with bare LF framing is refused", &details);
            }
            // Finding: repeated Mcp-Name headers are resolved first-wins, so a second, conflicting
            // value goes unnoticed when the first matches the body.
            0 | 1 | 2 => {
                let req = format!(
                    "POST /mcp HTTP/1.1\r\n{}Mcp-Name: echo\r\nMcp-Name: whoami\r\nContent-Length: {}\r\n\r\n{}",
                    common("\r\n"),
                    call.len(),
                    call
                );
                let (status, head) = http::raw(&n.host_port(), req.as_bytes(), Duration::from_secs(3));
                let details = json!({"node": n.name, "status": status, "head": head});
                no_500(status, &details);
                assert_always!(status == 400 || status == -1, "a request with conflicting duplicate routing headers is refused", &details);
            }
            // Finding: declared input-schema constraints are not enforced on tools/call.
            _ => {
                let Some((id, a, frames, _, _)) =
                    self.call(node, true, 0, "color", json!({"color": colour}), json!({}), Duration::from_secs(5))
                else {
                    return;
                };
                let valid = matches!(colour, "red" | "green" | "blue");
                let reply = final_reply(&frames, id);
                let details = json!({"node": n.name, "colour": colour, "body": a.head()});
                if valid {
                    let own = matches!(&reply, Some((false, t)) if *t == format!("color:t{id}:{colour}"));
                    assert_always!(own, "a call whose arguments satisfy the schema succeeds", &details);
                } else {
                    let refused = matches!(&reply, Some((true, _)));
                    assert_always!(refused, "a tools/call argument outside the declared input schema is rejected", &details);
                }
            }
        }
    }
}

/// Retries `step` until it succeeds, printing what it waits for whenever that changes. No
/// deadline of its own: the setup timeout bounds it.
fn wait_until(what: &str, mut step: impl FnMut() -> Result<(), String>) {
    let mut last = String::new();
    loop {
        match step() {
            Ok(()) => {
                println!("driver: {what}: done");
                return;
            }
            Err(why) => {
                if why != last {
                    println!("driver: waiting for {what}: {why}");
                    last = why;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

fn startup() -> Driver {
    antithesis_init();
    let spec = std::env::var("MCP_NODES")
        .unwrap_or_else(|_| "netty=http://mcp-netty:8000,socket=http://mcp-socket:8000,bun=http://mcp-bun:8000".to_owned());
    let nodes: Vec<Node> = spec
        .split(',')
        .filter_map(|entry| entry.split_once('='))
        .map(|(name, base)| Node { name: name.trim().to_owned(), base: base.trim().trim_end_matches('/').to_owned() })
        .collect();
    assert!(!nodes.is_empty(), "MCP_NODES names no node");
    let flooded = nodes.iter().map(|_| AtomicU64::new(0)).collect();
    let full_under = nodes.iter().map(|_| Mutex::new(None)).collect();
    let driver = Driver {
        http: Client::new(),
        nodes,
        seq: AtomicU64::new(0),
        sessions: Mutex::new(Vec::new()),
        ended: Mutex::new(Vec::new()),
        flooded,
        full_under,
        tasks: Mutex::new(Vec::new()),
        quiet: RwLock::new(()),
        ending: AtomicBool::new(false),
        started: Instant::now(),
        eventually_off: std::env::var("DRIVER_EVENTUALLY").is_ok_and(|v| v == "off"),
        eventually_after: std::env::var("DRIVER_EVENTUALLY_AFTER_S").ok().and_then(|v| v.parse().ok()).map(Duration::from_secs),
    };
    // A node is up when it answers a tool call, not when its port is open.
    for n in 0..driver.nodes.len() {
        wait_until(&format!("{} to answer a tool call", driver.nodes[n].name), || {
            match driver.boot_of(n) {
                Some(_) => Ok(()),
                None => Err("no reply to stats".to_owned()),
            }
        });
    }
    let names: Vec<&str> = driver.nodes.iter().map(|n| n.name.as_str()).collect();
    lifecycle::setup_complete(&json!({"nodes": names}));
    println!("driver: setup complete; waiting for /workload-active");
    while !std::path::Path::new("/workload-active").exists() {
        std::thread::sleep(Duration::from_millis(100));
    }
    assert_reachable!("the workload starts", &json!({"nodes": names}));
    println!("driver: the workload starts");
    Driver { started: Instant::now(), ..driver }
}

#[hegel::main(phases = [hegel::Phase::Generate])]
fn main(tc: TestCase) {
    let driver = startup();
    machine(driver).min_concurrency(2).max_concurrency(8).steps(i64::MAX).run_concurrent(tc);
}
