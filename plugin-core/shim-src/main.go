// agentbridge-shim — cross-platform PATH shim for ACP agents.
//
// Mirrors the POSIX bash version (see agentbridge-shim.sh) so that a single
// static binary covers Linux, macOS, and Windows without dragging in cmd.exe
// or bash. Behaviour:
//
//  1. Determine the command name from filepath.Base(os.Args[0]) (e.g. "cat",
//     "git", "grep.exe"). Strips the .exe suffix on Windows.
//  2. Read AGENTBRIDGE_SHIM_PORT and AGENTBRIDGE_SHIM_TOKEN. If either is
//     missing → fall through immediately (no IDE running).
//  3. POST argv as application/x-www-form-urlencoded to
//     http://127.0.0.1:$port/shim-exec with header X-Shim-Token: $token.
//  4. On HTTP 200 the body is "EXIT <n>\n<stdout-bytes>". Print stdout, exit n.
//     On 204, any other status, or network error → fall through.
//  5. Fall through = re-exec the real binary of the same name found on PATH
//     after removing our own directory. Argv and stdin/stdout/stderr are
//     forwarded unchanged; the child's exit code is propagated.
//
// This binary is bundled per OS+arch under
// plugin-core/src/main/resources/agentbridge/shim/bin/<goos>-<goarch>/
// and extracted at runtime by ShimManager, which copies one .exe per shimmed
// command name (cat.exe, head.exe, grep.exe, …).
package main

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const exitFallthroughError = 127

func main() {
	cmdName := commandName()
	args := os.Args[1:]

	// Try the IDE round-trip. If it returns (handled, exitCode), we're done.
	if handled, exitCode := tryRedirect(cmdName, args); handled {
		os.Exit(exitCode)
	}

	// Fall through: re-exec the real binary from a PATH that excludes us.
	os.Exit(execReal(cmdName, args))
}

// commandName returns the lowercased basename of os.Args[0] without the .exe
// suffix on Windows. The shim dispatches purely by this name.
func commandName() string {
	base := filepath.Base(os.Args[0])
	if runtime.GOOS == "windows" {
		base = strings.TrimSuffix(strings.ToLower(base), ".exe")
	}
	return base
}

// tryRedirect attempts the HTTP round-trip to /shim-exec. Returns
// (true, exitCode) when the IDE handled the call, (false, _) when the caller
// should fall through to the real binary.
func tryRedirect(cmdName string, args []string) (bool, int) {
	port := os.Getenv("AGENTBRIDGE_SHIM_PORT")
	token := os.Getenv("AGENTBRIDGE_SHIM_TOKEN")
	if port == "" || token == "" {
		return false, 0
	}

	form := url.Values{}
	form.Add("argv", cmdName)
	for _, a := range args {
		form.Add("argv", a)
	}
	body := form.Encode()

	endpoint := "http://127.0.0.1:" + port + "/shim-exec"
	req, err := http.NewRequest(http.MethodPost, endpoint, strings.NewReader(body))
	if err != nil {
		return false, 0
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("X-Shim-Token", token)

	// Loopback only — short timeouts. We deliberately do NOT follow redirects;
	// the protocol is one-shot.
	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: (&net.Dialer{
				Timeout:   2 * time.Second,
				KeepAlive: -1,
			}).DialContext,
			DisableKeepAlives: true,
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return false, 0
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNoContent {
		// Explicit fall-through.
		return false, 0
	}
	if resp.StatusCode != http.StatusOK {
		// Bad token (401), malformed (400), server error (5xx) — fall through
		// so the agent still gets a working command.
		return false, 0
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return false, 0
	}

	exitCode, payload, ok := parseExitFrame(respBody)
	if !ok {
		// Malformed body — fall through rather than emit garbage.
		return false, 0
	}

	// Write payload to stdout. Errors here are unrecoverable; mirror what the
	// real binary would do.
	_, _ = os.Stdout.Write(payload)
	return true, exitCode
}

// parseExitFrame validates and splits the wire format "EXIT <n>\n<bytes>".
// Returns (exitCode, payload, true) on success, (_, _, false) otherwise.
func parseExitFrame(body []byte) (int, []byte, bool) {
	const prefix = "EXIT "
	if !bytes.HasPrefix(body, []byte(prefix)) {
		return 0, nil, false
	}
	rest := body[len(prefix):]
	nl := bytes.IndexByte(rest, '\n')
	if nl < 0 {
		return 0, nil, false
	}
	n, err := strconv.Atoi(string(rest[:nl]))
	if err != nil {
		return 0, nil, false
	}
	return n, rest[nl+1:], true
}

// execReal locates the real cmdName on PATH, with the directory containing
// this shim removed (to prevent infinite recursion), and runs it with the
// original argv + stdio. Returns the child's exit code, or
// exitFallthroughError when no real binary is found.
func execReal(cmdName string, args []string) int {
	stripped := pathWithoutSelf()

	realPath, err := lookPathWith(cmdName, stripped)
	if err != nil {
		fmt.Fprintf(os.Stderr, "agentbridge-shim: %s: command not found on PATH (after removing shim dir)\n", cmdName)
		return exitFallthroughError
	}

	cmd := exec.Command(realPath, args...) // #nosec G204 -- shim is a thin wrapper; argv comes from caller
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = append(os.Environ(), "PATH="+stripped)

	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode()
		}
		fmt.Fprintf(os.Stderr, "agentbridge-shim: failed to exec %s: %v\n", realPath, err)
		return exitFallthroughError
	}
	return cmd.ProcessState.ExitCode()
}

// pathWithoutSelf returns $PATH with the directory containing os.Args[0]
// removed (case-insensitive on Windows). This is what the spawned real
// binary sees, and is what lookPathWith uses to find it.
func pathWithoutSelf() string {
	selfDir, err := selfDir()
	if err != nil {
		return os.Getenv("PATH")
	}
	sep := string(os.PathListSeparator)
	parts := strings.Split(os.Getenv("PATH"), sep)
	out := parts[:0]
	for _, p := range parts {
		if pathsEqual(p, selfDir) {
			continue
		}
		out = append(out, p)
	}
	return strings.Join(out, sep)
}

// selfDir returns the absolute directory of the running shim binary,
// resolving symlinks. Falls back to filepath.Dir(os.Args[0]) when the
// executable cannot be located.
func selfDir() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return filepath.Dir(os.Args[0]), nil
	}
	resolved, err := filepath.EvalSymlinks(exe)
	if err != nil {
		resolved = exe
	}
	return filepath.Dir(resolved), nil
}

// pathsEqual compares two filesystem paths, case-insensitively on Windows.
func pathsEqual(a, b string) bool {
	if runtime.GOOS == "windows" {
		return strings.EqualFold(filepath.Clean(a), filepath.Clean(b))
	}
	return filepath.Clean(a) == filepath.Clean(b)
}

// lookPathWith mimics exec.LookPath but uses the supplied PATH instead of
// the process environment. Implemented locally because exec.LookPath has no
// "alternative PATH" overload and we cannot mutate the parent env.
func lookPathWith(name, path string) (string, error) {
	// Windows: exec.LookPath honours PATHEXT only when scanning os.Getenv("PATH").
	// We swap PATH temporarily, restore on return.
	oldPath := os.Getenv("PATH")
	if err := os.Setenv("PATH", path); err != nil {
		return "", err
	}
	defer func() { _ = os.Setenv("PATH", oldPath) }()
	return exec.LookPath(name)
}
