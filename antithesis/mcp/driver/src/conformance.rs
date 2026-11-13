//! Runs the official MCP conformance harness (`@modelcontextprotocol/conformance`) against one
//! node and reads its results.
//!
//! The harness is not re-implemented here. The image holds the version pinned in the
//! repository's `conformance/package.json` and `conformance/bun.lock`; this module starts it the
//! way `scripts/conformance.sh` does, waits for it, and reads the `checks.json` it writes for
//! each scenario. Which failures count is decided the way the harness decides its exit code (see
//! [`judge`]), and the exit code itself is asserted too, so the two can never drift apart
//! unnoticed.
//!
//! The harness reports a dropped connection as a failed check, so it only runs once faults have
//! stopped.

use std::collections::{BTreeMap, BTreeSet};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::Value;

/// The revision whose requirement set the second suite runs.
pub const REVISION: &str = "2026-07-28";

/// The whole harness run may take this long. Each scenario has the harness's own 30 s timeout;
/// this bounds a run in which many of them hang. `scripts/local.sh` waits this long for every
/// run the driver plans.
const DEADLINE: Duration = Duration::from_secs(300);

/// The Skills extension scenarios (SEP-2640) of the pinned harness. They are tagged `[extension]`,
/// so neither the active suite nor the 2026-07-28 requirement set includes them, and the harness
/// takes one `--scenario` per run: [`Suite::Skills`] starts it once for each.
pub const SKILLS_SCENARIOS: [&str; 3] = ["sep-2640-skills-enumeration", "sep-2640-skills-manifest", "sep-2640-skills-directory"];

/// What the harness runs. These are the modes of `scripts/conformance.sh`.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Suite {
    /// `--suite active --expected-failures <baseline>`: every scenario at the 2025-11-25 wire.
    Active,
    /// `--requirements 2026-07-28`: the scenarios that revision requires, at its own wire.
    Requirements,
    /// `--scenario <name>` for each of [`SKILLS_SCENARIOS`], at the 2026-07-28 wire.
    Skills,
}

impl Suite {
    /// How the suite is named in property messages and logs.
    pub fn label(self) -> &'static str {
        match self {
            Suite::Active => "active suite",
            Suite::Requirements => "2026-07-28 requirements",
            Suite::Skills => "skills extension scenarios",
        }
    }

    fn slug(self) -> &'static str {
        match self {
            Suite::Active => "active",
            Suite::Requirements => "2026",
            Suite::Skills => "skills",
        }
    }
}

/// One entry of a harness result file.
#[derive(Clone, Debug)]
pub struct Check {
    pub id: String,
    pub status: String,
    pub error: Option<String>,
}

/// One scenario the harness ran, with the checks it wrote.
#[derive(Clone, Debug)]
pub struct Scenario {
    pub name: String,
    pub checks: Vec<Check>,
}

/// What the image knows about the pinned harness: built by `conformance-manifest.mjs` when the
/// image is built, from the harness's own requirement set and the repository's baselines.
#[derive(Default, Debug)]
pub struct Manifest {
    pub harness: String,
    /// Scenarios the 2026-07-28 requirement set scores.
    pub scored: BTreeSet<String>,
    /// Scenarios that set runs without scoring, each with the harness's reason.
    pub not_scored: BTreeMap<String, String>,
    /// Expected-failure entries per baseline (`jvm`, `js`), as written in the YAML.
    pub baselines: BTreeMap<String, Vec<String>>,
}

impl Manifest {
    pub fn load(dir: &Path) -> Result<Manifest, String> {
        let path = dir.join("manifest.json");
        let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        let v: Value = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
        let strings = |v: &Value| -> Vec<String> {
            v.as_array().map(|a| a.iter().filter_map(Value::as_str).map(str::to_owned).collect()).unwrap_or_default()
        };
        let req = &v["requirements"][REVISION];
        let mut m = Manifest {
            harness: v["harness"].as_str().unwrap_or("?").to_owned(),
            scored: strings(&req["scored"]).into_iter().collect(),
            ..Manifest::default()
        };
        if let Some(map) = req["not_scored"].as_object() {
            for (k, reason) in map {
                m.not_scored.insert(k.clone(), reason.as_str().unwrap_or("not scored").to_owned());
            }
        }
        if let Some(map) = v["baselines"].as_object() {
            for (k, entries) in map {
                m.baselines.insert(k.clone(), strings(entries));
            }
        }
        if m.scored.is_empty() {
            return Err(format!("{}: no scored scenarios for {REVISION}", path.display()));
        }
        Ok(m)
    }
}

/// How one scenario came out, after the baseline or the requirement set had its say.
#[derive(Debug)]
pub struct Verdict {
    pub scenario: String,
    pub checks: usize,
    /// False for a scenario the requirement set runs without scoring. Such a scenario never
    /// fails anything.
    pub scored: bool,
    /// Why it is not scored (`extension`, `pending`, ...), when it is not.
    pub reason: Option<String>,
    /// Failed checks nothing excuses. Not empty means the scenario failed.
    pub failed: Vec<Check>,
    /// Failed checks the baseline expects (`scenario` or `scenario:check`).
    pub excused: Vec<String>,
}

/// The harness's judgement of a whole run, recomputed from its result files.
#[derive(Debug, Default)]
pub struct Judgement {
    pub verdicts: Vec<Verdict>,
    /// Baseline entries that now pass. The harness exits 1 for these: the entry must be removed.
    pub stale: Vec<String>,
    /// True for a run of single scenarios ([`Suite::Skills`]): there the harness's exit code
    /// only counts `FAILURE`, while a verdict also fails on `WARNING` and `SKIPPED`.
    pub exit_counts_failures_only: bool,
}

impl Judgement {
    pub fn unexpected(&self) -> Vec<&Verdict> {
        self.verdicts.iter().filter(|v| v.scored && !v.failed.is_empty()).collect()
    }

    /// The exit code the harness gives this run. For a run of several harness processes
    /// ([`Suite::Skills`]) it is the largest of theirs.
    pub fn exit_code(&self) -> i32 {
        if self.exit_counts_failures_only {
            return i32::from(self.verdicts.iter().any(|v| v.failed.iter().any(|c| c.status == "FAILURE")));
        }
        i32::from(!self.unexpected().is_empty() || !self.stale.is_empty())
    }

    /// Whether the run passes the gate `scripts/conformance.sh` applies to this suite. For the
    /// active suite and the requirement set that is the harness's exit code. For the skills
    /// scenarios it is stricter than the exit code, which ignores warnings and skipped checks.
    pub fn passes(&self) -> bool {
        self.unexpected().is_empty() && self.stale.is_empty()
    }
}

/// Decides which scenarios failed, by the harness's own rules (read from the pinned version,
/// 0.2.0-alpha.12; `the driver's reading ... agrees with the harness's exit code` checks the
/// result against the real exit code on every run).
///
/// Active suite, with an expected-failures baseline. A check fails when its status is `FAILURE`
/// or `WARNING`. A scenario the baseline lists by name is excused whole; if it has no failing
/// check, the entry is stale. A `scenario:check` entry excuses that check id only (all of its
/// occurrences, the most severe one counting); if that check passes, the entry is stale. Every
/// other failing check is an unexpected failure. Unexpected failures and stale entries both make
/// the harness exit 1.
///
/// Requirement set, no baseline. A scenario fails when it is one the set scores and one of its
/// checks has status `FAILURE`. The set also runs scenarios it does not score (extensions,
/// pending ones); those are reported and never fail the run.
///
/// Skills scenarios, one harness process each, no baseline. The harness exits 1 for a scenario
/// with a `FAILURE` check and 0 otherwise. The gate is stricter, as in `scripts/conformance.sh`:
/// a check fails when its status is `FAILURE`, `WARNING` or `SKIPPED`. The scenarios skip their
/// checks when the server does not declare the extension or `directoryRead`; the fixture declares
/// both, so a skipped check means the scenario stopped testing something. A scenario with no
/// `SUCCESS` check fails as well (the script's `passed > 0`): the harness exits 0 for it. A
/// scenario of `expected` with no result at all fails too. `expected` names the scenarios whose
/// harness process ran to its end ([`Run::finished`]): one that was never started, or was stopped
/// at the deadline, gets no verdict, as in the other suites, where the suite's own property and
/// the per-node `sometimes` already record a harness that did not run.
pub fn judge(suite: Suite, manifest: &Manifest, baseline: &[String], scenarios: &[Scenario], expected: &[String]) -> Judgement {
    let mut out = Judgement::default();
    match suite {
        Suite::Skills => {
            out.exit_counts_failures_only = true;
            let fails = |c: &Check| matches!(c.status.as_str(), "FAILURE" | "WARNING" | "SKIPPED");
            for s in scenarios {
                let mut failed: Vec<Check> = s.checks.iter().filter(|c| fails(c)).cloned().collect();
                if !s.checks.iter().any(|c| c.status == "SUCCESS") {
                    failed.push(Check {
                        id: s.name.clone(),
                        status: NO_SUCCESS.to_owned(),
                        error: Some("the scenario recorded no successful check".to_owned()),
                    });
                }
                out.verdicts.push(Verdict { scenario: s.name.clone(), checks: s.checks.len(), scored: true, reason: None, failed, excused: Vec::new() });
            }
            for name in expected {
                if !scenarios.iter().any(|s| s.name == *name) {
                    let missing = Check {
                        id: name.to_owned(),
                        status: "FAILURE".to_owned(),
                        error: Some("the harness wrote no result for the scenario; see harness.log".to_owned()),
                    };
                    out.verdicts.push(Verdict { scenario: name.to_owned(), checks: 0, scored: true, reason: None, failed: vec![missing], excused: Vec::new() });
                }
            }
        }
        Suite::Requirements => {
            for s in scenarios {
                let scored = manifest.scored.contains(&s.name);
                let failed: Vec<Check> = s.checks.iter().filter(|c| c.status == "FAILURE").cloned().collect();
                let reason = (!scored).then(|| manifest.not_scored.get(&s.name).cloned().unwrap_or_else(|| "not scored".to_owned()));
                out.verdicts.push(Verdict { scenario: s.name.clone(), checks: s.checks.len(), scored, reason, failed, excused: Vec::new() });
            }
        }
        Suite::Active => {
            let fails = |c: &Check| c.status == "FAILURE" || c.status == "WARNING";
            let mut whole: BTreeSet<&str> = BTreeSet::new();
            let mut per_check: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
            for entry in baseline {
                match entry.split_once(':') {
                    None => {
                        whole.insert(entry.as_str());
                    }
                    Some((scenario, check)) => {
                        per_check.entry(scenario).or_default().insert(check);
                    }
                }
            }
            for s in scenarios {
                let mut v = Verdict { scenario: s.name.clone(), checks: s.checks.len(), scored: true, reason: None, failed: Vec::new(), excused: Vec::new() };
                if whole.contains(s.name.as_str()) {
                    if s.checks.iter().any(fails) {
                        v.excused.push(s.name.clone());
                    } else {
                        out.stale.push(s.name.clone());
                    }
                } else if let Some(expected) = per_check.get(s.name.as_str()) {
                    let collapsed = collapse(&s.checks);
                    for c in collapsed.iter().filter(|c| fails(c)) {
                        if expected.contains(c.id.as_str()) {
                            v.excused.push(format!("{}:{}", s.name, c.id));
                        } else {
                            v.failed.push(c.clone());
                        }
                    }
                    for id in expected {
                        if collapsed.iter().any(|c| c.id == *id && c.status == "SUCCESS") {
                            out.stale.push(format!("{}:{id}", s.name));
                        }
                    }
                } else {
                    v.failed = s.checks.iter().filter(|c| fails(c)).cloned().collect();
                }
                out.verdicts.push(v);
            }
        }
    }
    out
}

/// One check per id, the most severe occurrence winning (the last among equals). `INFO` checks
/// carry no verdict and are dropped.
fn collapse(checks: &[Check]) -> Vec<Check> {
    let rank = |status: &str| match status {
        "FAILURE" => 3,
        "WARNING" => 2,
        "SUCCESS" => 1,
        _ => 0,
    };
    let mut out: Vec<Check> = Vec::new();
    for c in checks.iter().filter(|c| c.status != "INFO") {
        match out.iter_mut().find(|o| o.id == c.id) {
            Some(o) if rank(&c.status) >= rank(&o.status) => *o = c.clone(),
            Some(_) => {}
            None => out.push(c.clone()),
        }
    }
    out
}

/// The status of the check the driver adds to a skills scenario that recorded no `SUCCESS`
/// check. It is not a harness status: the harness exits 0 for such a scenario.
pub const NO_SUCCESS: &str = "NO_SUCCESS";

/// A harness run as the driver saw it.
#[derive(Debug)]
pub struct Run {
    /// The harness's exit code. None when it could not be started or did not finish in time.
    /// For the skills scenarios, the largest exit code of the harness's three runs.
    pub exit: Option<i32>,
    /// Why there is no exit code.
    pub problem: Option<String>,
    pub seconds: f64,
    pub scenarios: Vec<Scenario>,
    /// The scenarios of a single-scenario suite ([`Suite::Skills`]) whose harness process ran to
    /// its end and gave an exit code. Empty for the other suites.
    pub finished: Vec<String>,
    /// The end of the harness's own output.
    pub log_tail: String,
    /// Where the result files and the harness's output are.
    pub dir: PathBuf,
}

impl Run {
    /// A run that could not even be attempted.
    pub fn not_started(problem: String) -> Run {
        Run { exit: None, problem: Some(problem), seconds: 0.0, scenarios: Vec::new(), finished: Vec::new(), log_tail: String::new(), dir: PathBuf::new() }
    }
}

/// Runs the harness against `upstream` (`host:port` of a node) and reads what it wrote.
///
/// The harness is given a loopback URL that a relay in this process forwards to the node. Its
/// `dns-rebinding-protection` scenario refuses any other kind of URL: it tests that a server
/// reached as `127.0.0.1` rejects a foreign `Host` and accepts its own. The relay copies bytes
/// both ways and changes nothing, so the server sees `Host: 127.0.0.1:<port>`, a name the
/// workload's `allowedHosts` already lists.
pub fn run(suite: Suite, home: &Path, node: &str, upstream: &str, baseline: Option<&Path>) -> Run {
    let dir = PathBuf::from(format!("/tmp/conformance/{}-{node}", suite.slug()));
    let _ = std::fs::remove_dir_all(&dir);
    let started = Instant::now();
    let fail = |problem: String, dir: PathBuf| Run {
        exit: None,
        problem: Some(problem),
        seconds: started.elapsed().as_secs_f64(),
        scenarios: Vec::new(),
        finished: Vec::new(),
        log_tail: String::new(),
        dir,
    };
    if let Err(e) = std::fs::create_dir_all(&dir) {
        return fail(format!("cannot create {}: {e}", dir.display()), dir);
    }
    let bin = home.join("node_modules/.bin/conformance");
    if !bin.exists() {
        return fail(format!("the harness is not installed at {}", bin.display()), dir);
    }
    let port = match relay(upstream.to_owned()) {
        Ok(port) => port,
        Err(e) => return fail(format!("cannot start the loopback relay: {e}"), dir),
    };
    let log_path = dir.join("harness.log");
    let log = match std::fs::File::create(&log_path) {
        Ok(file) => file,
        Err(e) => return fail(format!("cannot create {}: {e}", log_path.display()), dir),
    };
    // One harness process for a suite; one per scenario for the skills scenarios, which the
    // harness only runs when they are named. They share the result directory, the log and the
    // deadline.
    let invocations: Vec<Vec<String>> = match suite {
        Suite::Active => {
            let mut args = vec!["--suite".to_owned(), "active".to_owned()];
            if let Some(baseline) = baseline {
                args.push("--expected-failures".to_owned());
                args.push(baseline.display().to_string());
            }
            vec![args]
        }
        Suite::Requirements => vec![vec!["--requirements".to_owned(), REVISION.to_owned()]],
        Suite::Skills => SKILLS_SCENARIOS.iter().map(|s| vec!["--scenario".to_owned(), (*s).to_owned()]).collect(),
    };
    let mut problem = None;
    let mut exit: Option<i32> = Some(0);
    let mut finished: Vec<String> = Vec::new();
    for args in &invocations {
        let (out, err) = match log.try_clone().and_then(|a| Ok((a, log.try_clone()?))) {
            Ok(pair) => pair,
            Err(e) => return fail(format!("cannot write {}: {e}", log_path.display()), dir),
        };
        let mut cmd = Command::new(&bin);
        cmd.current_dir(home).arg("server").arg("--url").arg(format!("http://127.0.0.1:{port}/mcp")).args(args);
        cmd.arg("-o").arg(&dir).stdin(Stdio::null()).stdout(Stdio::from(out)).stderr(Stdio::from(err));
        let mut child = match cmd.spawn() {
            Ok(child) => child,
            Err(e) => return fail(format!("cannot start {}: {e}", bin.display()), dir),
        };
        let code = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status.code(),
                Ok(None) if started.elapsed() > DEADLINE => {
                    let _ = child.kill();
                    let _ = child.wait();
                    problem = Some(format!("the harness did not finish within {} s and was stopped", DEADLINE.as_secs()));
                    break None;
                }
                Ok(None) => std::thread::sleep(Duration::from_millis(100)),
                Err(e) => {
                    problem = Some(format!("waiting for the harness failed: {e}"));
                    break None;
                }
            }
        };
        if let (Suite::Skills, Some(_), Some(name)) = (suite, code, args.get(1)) {
            finished.push(name.clone());
        }
        exit = match (exit, code) {
            (Some(worst), Some(code)) => Some(worst.max(code)),
            _ => None,
        };
        if exit.is_none() {
            break;
        }
    }
    if exit.is_none() && problem.is_none() {
        problem = Some("the harness was ended by a signal".to_owned());
    }
    let text = std::fs::read_to_string(&log_path).unwrap_or_default();
    let tail: String = {
        let chars: Vec<char> = text.chars().collect();
        chars[chars.len().saturating_sub(1500)..].iter().collect()
    };
    Run { exit, problem, seconds: started.elapsed().as_secs_f64(), scenarios: read_results(&dir), finished, log_tail: tail, dir }
}

/// Reads `<dir>/server-<scenario>-<timestamp>/checks.json` for every scenario, in the order they
/// ran. The harness creates a scenario's directory before running it and writes `checks.json`
/// when it returns; a directory without one is a scenario the harness could not run, which the
/// harness itself counts as one failed check named after the scenario.
fn read_results(dir: &Path) -> Vec<Scenario> {
    let mut found: Vec<(String, String, PathBuf)> = Vec::new();
    for entry in std::fs::read_dir(dir).into_iter().flatten().flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if !entry.path().is_dir() {
            continue;
        }
        if let Some((scenario, stamp)) = scenario_of(&name) {
            found.push((stamp, scenario, entry.path()));
        }
    }
    found.sort();
    found
        .into_iter()
        .map(|(_, name, path)| {
            let checks = std::fs::read_to_string(path.join("checks.json"))
                .ok()
                .and_then(|text| serde_json::from_str::<Value>(&text).ok())
                .and_then(|v| v.as_array().cloned())
                .map(|items| {
                    items
                        .iter()
                        .map(|c| Check {
                            id: c["id"].as_str().unwrap_or("?").to_owned(),
                            status: c["status"].as_str().unwrap_or("?").to_owned(),
                            error: c["errorMessage"].as_str().map(str::to_owned),
                        })
                        .collect()
                })
                .unwrap_or_else(|| {
                    vec![Check {
                        id: name.clone(),
                        status: "FAILURE".to_owned(),
                        error: Some("the harness could not run the scenario (no checks.json); see harness.log".to_owned()),
                    }]
                });
            Scenario { name, checks }
        })
        .collect()
}

/// Splits `server-<scenario>-2026-11-13T00-24-20-188Z` into the scenario and its timestamp. The
/// timestamp has a fixed length; scenario names may themselves end in digits and dashes
/// (`json-schema-2020-12`).
fn scenario_of(dir_name: &str) -> Option<(String, String)> {
    const STAMP: usize = "2026-11-13T00-24-20-188Z".len();
    let rest = dir_name.strip_prefix("server-")?;
    if !rest.is_ascii() || rest.len() < STAMP + 2 {
        return None;
    }
    let (scenario, stamp) = rest.split_at(rest.len() - STAMP);
    let bytes = stamp.as_bytes();
    if bytes[10] != b'T' || bytes[STAMP - 1] != b'Z' {
        return None;
    }
    Some((scenario.strip_suffix('-')?.to_owned(), stamp.to_owned()))
}

/// Listens on a loopback port and forwards every connection to `upstream`, byte for byte.
/// Answers the port. The listener lives until the driver exits.
fn relay(upstream: String) -> std::io::Result<u16> {
    let listener = TcpListener::bind("127.0.0.1:0")?;
    let port = listener.local_addr()?.port();
    std::thread::spawn(move || {
        for client in listener.incoming().flatten() {
            let upstream = upstream.clone();
            std::thread::spawn(move || forward(client, &upstream));
        }
    });
    Ok(port)
}

/// Copies both directions until the server's side ends. A connection that cannot be made
/// upstream is dropped, which the harness sees as a failed request.
fn forward(client: TcpStream, upstream: &str) {
    let Some(addr) = upstream.to_socket_addrs().ok().and_then(|mut a| a.next()) else { return };
    let Ok(server) = TcpStream::connect_timeout(&addr, Duration::from_secs(5)) else { return };
    let (Ok(mut client_in), Ok(mut server_out)) = (client.try_clone(), server.try_clone()) else { return };
    let (mut server_in, mut client_out) = (server, client);
    // Client to server. When the client stops sending, the server is told so and may still
    // finish its answer.
    let up = std::thread::spawn(move || {
        copy(&mut client_in, &mut server_out);
        let _ = server_out.shutdown(Shutdown::Write);
    });
    // Server to client. When the server is done, or the client is gone, the connection is over
    // for both.
    copy(&mut server_in, &mut client_out);
    let _ = client_out.shutdown(Shutdown::Both);
    let _ = server_in.shutdown(Shutdown::Both);
    let _ = up.join();
}

fn copy(from: &mut TcpStream, to: &mut TcpStream) {
    let mut buf = [0u8; 16 * 1024];
    loop {
        match from.read(&mut buf) {
            Ok(0) | Err(_) => return,
            Ok(n) => {
                if to.write_all(&buf[..n]).is_err() {
                    return;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(id: &str, status: &str) -> Check {
        Check { id: id.to_owned(), status: status.to_owned(), error: None }
    }

    fn scenario(name: &str, checks: Vec<Check>) -> Scenario {
        Scenario { name: name.to_owned(), checks }
    }

    fn manifest() -> Manifest {
        Manifest {
            harness: "test".to_owned(),
            scored: ["tools-list".to_owned()].into_iter().collect(),
            not_scored: [("tasks-lifecycle".to_owned(), "extension".to_owned())].into_iter().collect(),
            baselines: BTreeMap::new(),
        }
    }

    fn skills() -> Vec<String> {
        SKILLS_SCENARIOS.iter().map(|s| (*s).to_owned()).collect()
    }

    fn failed(j: &Judgement) -> Vec<String> {
        j.unexpected().iter().map(|v| v.scenario.clone()).collect()
    }

    #[test]
    fn active_without_a_baseline_fails_on_failure_and_on_warning() {
        let run = [
            scenario("a", vec![check("a", "SUCCESS"), check("wire-schema-valid", "SUCCESS")]),
            scenario("b", vec![check("b", "FAILURE")]),
            scenario("c", vec![check("c", "WARNING")]),
            scenario("d", vec![check("d", "INFO"), check("d2", "SKIPPED")]),
        ];
        let j = judge(Suite::Active, &manifest(), &[], &run, &[]);
        assert_eq!(failed(&j), ["b", "c"]);
        assert_eq!(j.exit_code(), 1);
        assert_eq!(judge(Suite::Active, &manifest(), &[], &run[..1], &[]).exit_code(), 0);
    }

    #[test]
    fn a_baselined_scenario_is_excused_and_stale_once_it_passes() {
        let baseline = ["b".to_owned()];
        let failing = [scenario("b", vec![check("b", "FAILURE")])];
        let j = judge(Suite::Active, &manifest(), &baseline, &failing, &[]);
        assert!(failed(&j).is_empty());
        assert_eq!(j.verdicts[0].excused, ["b"]);
        assert_eq!(j.exit_code(), 0);

        let passing = [scenario("b", vec![check("b", "SUCCESS")])];
        let j = judge(Suite::Active, &manifest(), &baseline, &passing, &[]);
        assert_eq!(j.stale, ["b"]);
        assert_eq!(j.exit_code(), 1);
    }

    #[test]
    fn a_baselined_check_excuses_only_that_check() {
        let baseline = ["s:known".to_owned()];
        let run = [scenario("s", vec![check("known", "SUCCESS"), check("known", "FAILURE"), check("other", "FAILURE")])];
        let j = judge(Suite::Active, &manifest(), &baseline, &run, &[]);
        assert_eq!(failed(&j), ["s"]);
        assert_eq!(j.verdicts[0].failed.len(), 1);
        assert_eq!(j.verdicts[0].failed[0].id, "other");
        assert_eq!(j.verdicts[0].excused, ["s:known"]);
        assert!(j.stale.is_empty());

        let fixed = [scenario("s", vec![check("known", "SUCCESS"), check("other", "SUCCESS")])];
        let j = judge(Suite::Active, &manifest(), &baseline, &fixed, &[]);
        assert_eq!(j.stale, ["s:known"]);
        assert_eq!(j.exit_code(), 1);

        // A baselined check that never ran is tolerated.
        let absent = [scenario("s", vec![check("other", "SUCCESS")])];
        assert_eq!(judge(Suite::Active, &manifest(), &baseline, &absent, &[]).exit_code(), 0);
    }

    #[test]
    fn the_requirement_set_scores_only_what_it_lists() {
        let run = [
            scenario("tools-list", vec![check("x", "WARNING")]),
            scenario("tasks-lifecycle", vec![check("y", "FAILURE")]),
            scenario("unlisted", vec![check("z", "FAILURE")]),
        ];
        let j = judge(Suite::Requirements, &manifest(), &[], &run, &[]);
        assert!(failed(&j).is_empty());
        assert_eq!(j.exit_code(), 0);
        assert_eq!(j.verdicts[1].reason.as_deref(), Some("extension"));
        assert_eq!(j.verdicts[2].reason.as_deref(), Some("not scored"));

        let broken = [scenario("tools-list", vec![check("x", "FAILURE")])];
        let j = judge(Suite::Requirements, &manifest(), &[], &broken, &[]);
        assert_eq!(failed(&j), ["tools-list"]);
        assert_eq!(j.exit_code(), 1);
    }

    #[test]
    fn the_skills_scenarios_fail_on_failure_warning_skip_and_absence() {
        let all = |status: &str| -> Vec<Scenario> {
            SKILLS_SCENARIOS.iter().map(|s| scenario(s, vec![check("x", "SUCCESS"), check("y", status), check("z", "INFO")])).collect()
        };
        let j = judge(Suite::Skills, &manifest(), &[], &all("SUCCESS"), &skills());
        assert!(j.passes());
        assert_eq!(j.exit_code(), 0);
        assert_eq!(j.verdicts.len(), 3);
        assert!(j.verdicts.iter().all(|v| v.scored));

        // The harness exits 0 for a warning and for a skipped check; the gate does not pass.
        for status in ["WARNING", "SKIPPED"] {
            let j = judge(Suite::Skills, &manifest(), &[], &all(status), &skills());
            assert!(!j.passes(), "{status}");
            assert_eq!(j.exit_code(), 0, "{status}");
            assert_eq!(failed(&j), SKILLS_SCENARIOS);
        }
        let j = judge(Suite::Skills, &manifest(), &[], &all("FAILURE"), &skills());
        assert!(!j.passes());
        assert_eq!(j.exit_code(), 1);

        // A scenario the harness left no result for fails, and so does the exit code.
        let two = &all("SUCCESS")[..2];
        let j = judge(Suite::Skills, &manifest(), &[], two, &skills());
        assert_eq!(failed(&j), [SKILLS_SCENARIOS[2]]);
        assert_eq!(j.exit_code(), 1);

        // A scenario that was never started (the one before it hit the deadline) gets no verdict.
        let j = judge(Suite::Skills, &manifest(), &[], two, &skills()[..2]);
        assert!(j.passes());
        assert_eq!(j.verdicts.len(), 2);
        let j = judge(Suite::Skills, &manifest(), &[], &[], &[]);
        assert!(j.verdicts.is_empty());
    }

    #[test]
    fn a_skills_scenario_with_no_successful_check_fails() {
        // The harness exits 0 for both; `scripts/conformance.sh skills` requires `passed > 0`.
        for checks in [vec![], vec![check("z", "INFO")]] {
            let run: Vec<Scenario> = SKILLS_SCENARIOS.iter().map(|s| scenario(s, checks.clone())).collect();
            let j = judge(Suite::Skills, &manifest(), &[], &run, &skills());
            assert!(!j.passes());
            assert_eq!(j.exit_code(), 0);
            assert_eq!(failed(&j), SKILLS_SCENARIOS);
            assert!(j.verdicts.iter().all(|v| v.failed.len() == 1 && v.failed[0].status == NO_SUCCESS));
        }
    }

    #[test]
    fn the_other_suites_pass_exactly_when_the_harness_exits_zero() {
        let good = [scenario("tools-list", vec![check("x", "SUCCESS")])];
        let bad = [scenario("tools-list", vec![check("x", "FAILURE")])];
        for suite in [Suite::Active, Suite::Requirements] {
            assert!(judge(suite, &manifest(), &[], &good, &[]).passes());
            assert!(!judge(suite, &manifest(), &[], &bad, &[]).passes());
        }
        let stale = judge(Suite::Active, &manifest(), &["tools-list".to_owned()], &good, &[]);
        assert!(!stale.passes());
        assert_eq!(stale.exit_code(), 1);
    }

    #[test]
    fn result_directories_name_their_scenario() {
        let split = |s: &str| scenario_of(s).map(|(name, _)| name);
        assert_eq!(split("server-tools-list-2026-11-13T00-24-20-188Z").as_deref(), Some("tools-list"));
        assert_eq!(split("server-json-schema-2020-12-2026-11-13T00-24-20-188Z").as_deref(), Some("json-schema-2020-12"));
        assert_eq!(split("server-sep-2640-skills-manifest-2026-11-13T00-24-20-188Z").as_deref(), Some("sep-2640-skills-manifest"));
        assert_eq!(split("harness.log"), None);
        assert_eq!(split("server-tools-list"), None);
    }
}
