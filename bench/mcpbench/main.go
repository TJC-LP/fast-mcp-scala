// Closed-loop MCP streamable-HTTP load generator for the fast-mcp-scala bench server.
//
//	-mode modern    MCP 2026-07-28: no session, one self-describing request per POST
//	-mode legacy    2025-11-25 session flow: initialize once per worker, then tools/call
//	-mode stateless 2025-11-25 against a `stateless = true` server: no session
//
// Each worker owns one keep-alive connection and sends the next request only when the previous
// reply has been read to the end of its body (closed loop). JSON-RPC ids are unique per worker;
// every reply is checked for its request id, a result and the absence of isError, and a reply that
// fails the check is counted as an error and excluded from the latencies. The reply may be JSON or
// an SSE body; both are accepted.
//
// By default every call is `add {a: 40, b: 2}` (a few hundred bytes each way). With -payload N the
// call is `echo {text: <N bytes>}`, so request and reply both carry the payload.
//
// Output: one JSON object on stdout with the request count, requests per second and latency
// percentiles for the measured window. Standard library only.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const legacyVersion = "2025-11-25"
const modernVersion = "2026-07-28"

type call struct {
	url, mode, tool, args, session string
	c                              *http.Client
}

func (k *call) post(body string, method string) (string, string, int, error) {
	req, _ := http.NewRequest("POST", k.url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	switch k.mode {
	case "modern":
		req.Header.Set("Mcp-Protocol-Version", modernVersion)
		req.Header.Set("Mcp-Method", method)
		if method == "tools/call" {
			req.Header.Set("Mcp-Name", k.tool)
		}
	case "legacy":
		if k.session != "" {
			req.Header.Set("Mcp-Session-Id", k.session)
			req.Header.Set("Mcp-Protocol-Version", legacyVersion)
		}
	case "stateless":
		req.Header.Set("Mcp-Protocol-Version", legacyVersion)
	}
	resp, err := k.c.Do(req)
	if err != nil {
		return "", "", 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	return string(b), resp.Header.Get("Mcp-Session-Id"), resp.StatusCode, err
}

func (k *call) frame(buf *bytes.Buffer, id int) {
	buf.Reset()
	if k.mode == "modern" {
		fmt.Fprintf(buf, `{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":%q,"arguments":{%s},"_meta":{"io.modelcontextprotocol/protocolVersion":%q,"io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"mcpbench","version":"0"}}}}`, id, k.tool, k.args, modernVersion)
	} else {
		fmt.Fprintf(buf, `{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":%q,"arguments":{%s}}}`, id, k.tool, k.args)
	}
}

func main() {
	url := flag.String("url", "http://127.0.0.1:8077/mcp", "endpoint")
	mode := flag.String("mode", "modern", "modern | legacy | stateless")
	workers := flag.Int("c", 1, "concurrent workers")
	warm := flag.Duration("warmup", 5*time.Second, "warmup")
	dur := flag.Duration("d", 10*time.Second, "measure")
	label := flag.String("label", "", "arm label copied into the output")
	payload := flag.Int("payload", 0, "call echo with a text argument of this many bytes instead of add")
	flag.Parse()

	tool, args := "add", `"a":40,"b":2`
	if *payload > 0 {
		tool, args = "echo", `"text":"`+strings.Repeat("x", *payload)+`"`
	}

	var measuring, stop atomic.Bool
	var errs atomic.Int64
	lat := make([][]time.Duration, *workers)
	var ready, wg sync.WaitGroup
	start := make(chan struct{})

	for w := 0; w < *workers; w++ {
		ready.Add(1)
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			k := &call{url: *url, mode: *mode, tool: tool, args: args,
				c: &http.Client{Transport: &http.Transport{MaxIdleConnsPerHost: 1, DisableCompression: true}}}
			if *mode == "legacy" {
				init := `{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"` + legacyVersion +
					`","capabilities":{},"clientInfo":{"name":"mcpbench","version":"0"}}}`
				body, session, status, err := k.post(init, "initialize")
				if err != nil || status != 200 || session == "" {
					fmt.Fprintf(os.Stderr, "worker %d initialize failed: status=%d err=%v body=%q\n", w, status, err, body)
					os.Exit(2)
				}
				k.session = session
				if _, _, status, err = k.post(`{"jsonrpc":"2.0","method":"notifications/initialized"}`, "notifications/initialized"); err != nil || status != 202 {
					fmt.Fprintf(os.Stderr, "worker %d initialized failed: status=%d err=%v\n", w, status, err)
					os.Exit(2)
				}
			}
			ready.Done()
			<-start
			var buf bytes.Buffer
			for id := 1; !stop.Load(); id++ {
				k.frame(&buf, id)
				t0 := time.Now()
				body, _, status, err := k.post(buf.String(), "tools/call")
				d := time.Since(t0)
				ok := err == nil && status == 200 && strings.Contains(body, fmt.Sprintf(`"id":%d`, id)) && strings.Contains(body, `"result"`) && !strings.Contains(body, `"isError":true`)
				if !ok {
					if errs.Add(1) == 1 {
						fmt.Fprintf(os.Stderr, "first error: status=%d err=%v body=%q\n", status, err, body)
					}
					if errs.Load() > 1000 {
						os.Exit(3)
					}
					continue
				}
				if measuring.Load() {
					lat[w] = append(lat[w], d)
				}
			}
		}(w)
	}
	ready.Wait()
	close(start)
	time.Sleep(*warm)
	measuring.Store(true)
	t0 := time.Now()
	time.Sleep(*dur)
	measuring.Store(false)
	elapsed := time.Since(t0)
	stop.Store(true)
	wg.Wait()

	var all []time.Duration
	for _, l := range lat {
		all = append(all, l...)
	}
	sort.Slice(all, func(i, j int) bool { return all[i] < all[j] })
	pct := func(p float64) float64 {
		if len(all) == 0 {
			return 0
		}
		return float64(all[int(float64(len(all)-1)*p)].Microseconds()) / 1000
	}
	slow := 0
	for _, d := range all {
		if d > 500*time.Millisecond {
			slow++
		}
	}
	out, _ := json.Marshal(map[string]any{
		"arm": *label, "mode": *mode, "c": *workers, "payload": *payload, "requests": len(all), "errors": errs.Load(), "over_500ms": slow,
		"rps": float64(len(all)) / elapsed.Seconds(), "p50_ms": pct(0.50), "p90_ms": pct(0.90), "p99_ms": pct(0.99), "p999_ms": pct(0.999), "max_ms": pct(1.0),
	})
	fmt.Println(string(out))
}
