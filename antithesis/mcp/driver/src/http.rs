//! Plain HTTP against a fast-mcp-scala node. Every call answers a value and never panics: a
//! dropped connection under network faults is an outcome the caller weighs. Status -1 means no
//! HTTP answer (connect error, client timeout, cut stream).

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use serde_json::Value;

pub struct Answer {
    pub status: i32,
    pub session: Option<String>,
    pub content_type: String,
    /// The reply carried a Content-Length (a fixed body, not a chunked stream).
    pub has_length: bool,
    pub body: String,
}

impl Answer {
    fn none(why: String) -> Self {
        Answer { status: -1, session: None, content_type: String::new(), has_length: false, body: why }
    }

    pub fn head(&self) -> String {
        self.body.chars().take(300).collect()
    }

    /// The JSON-RPC messages in the body, and how many heartbeats came with them. A
    /// `text/event-stream` body is split into its events; any other body is one JSON value.
    pub fn messages(&self) -> (Vec<Value>, usize) {
        if !self.content_type.contains("text/event-stream") {
            return (serde_json::from_str::<Value>(&self.body).ok().into_iter().collect(), 0);
        }
        let mut out = Vec::new();
        let mut pings = 0;
        for block in self.body.split("\n\n") {
            if block.lines().any(|l| l == "event: ping" || l.starts_with(": ping")) {
                pings += 1;
            }
            let data: String =
                block.lines().filter_map(|l| l.strip_prefix("data: ")).collect::<Vec<_>>().join("\n");
            if !data.is_empty() {
                if let Ok(v) = serde_json::from_str::<Value>(&data) {
                    out.push(v);
                }
            }
        }
        (out, pings)
    }
}

#[derive(Clone)]
pub struct Client {
    agent: ureq::Agent,
}

impl Client {
    pub fn new() -> Self {
        // No connection reuse: every request stands alone, so a connection a fault broke is
        // never mistaken for the server's answer to the next request.
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(2))
            .max_idle_connections(0)
            .redirects(0)
            .build();
        Client { agent }
    }

    pub fn send(
        &self,
        method: &str,
        url: &str,
        headers: &[(&str, String)],
        body: Option<&str>,
        timeout: Duration,
    ) -> Answer {
        let mut req = self.agent.request(method, url).timeout(timeout);
        for (k, v) in headers {
            req = req.set(k, v);
        }
        let result = match body {
            Some(b) => req.send_string(b),
            None => req.call(),
        };
        let resp = match result {
            Ok(r) => r,
            Err(ureq::Error::Status(_, r)) => r,
            Err(e) => return Answer::none(e.to_string()),
        };
        let status = i32::from(resp.status());
        let session = resp.header("mcp-session-id").map(str::to_owned);
        let content_type = resp.header("content-type").unwrap_or("").to_owned();
        let has_length = resp.header("content-length").is_some();
        let mut text = String::new();
        // A body cut mid-stream is no answer at all: its outcome is unknown.
        match resp.into_reader().take(8 * 1024 * 1024).read_to_string(&mut text) {
            Ok(_) => Answer { status, session, content_type, has_length, body: text },
            Err(e) => Answer::none(format!("body cut after status {status}: {e}")),
        }
    }

    /// Opens a request and answers its status without reading the body, then drops the
    /// connection: how a client abandons a stream.
    pub fn open_and_drop(&self, url: &str, headers: &[(&str, String)], timeout: Duration) -> i32 {
        let mut req = self.agent.request("GET", url).timeout(timeout);
        for (k, v) in headers {
            req = req.set(k, v);
        }
        match req.call() {
            Ok(r) => i32::from(r.status()),
            Err(ureq::Error::Status(code, _)) => i32::from(code),
            Err(_) => -1,
        }
    }
}

/// Sends raw bytes to `host:port` and answers the status of the first response, or -1. For
/// requests no HTTP client library would produce.
pub fn raw(host_port: &str, bytes: &[u8], timeout: Duration) -> (i32, String) {
    let Some(addr) = host_port.to_socket_addrs().ok().and_then(|mut a| a.next()) else {
        return (-1, "name did not resolve".into());
    };
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, Duration::from_secs(2)) else {
        return (-1, "connect failed".into());
    };
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));
    if stream.write_all(bytes).is_err() {
        return (-1, "write failed".into());
    }
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    // Read until the server closes, the timeout passes, or there is plainly enough to judge.
    loop {
        match stream.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if buf.len() > 64 * 1024 {
                    break;
                }
            }
        }
    }
    let text = String::from_utf8_lossy(&buf).into_owned();
    let status = text
        .strip_prefix("HTTP/1.1 ")
        .or_else(|| text.strip_prefix("HTTP/1.0 "))
        .and_then(|rest| rest.get(..3))
        .and_then(|code| code.parse::<i32>().ok())
        .unwrap_or(-1);
    (status, text.chars().take(300).collect())
}
