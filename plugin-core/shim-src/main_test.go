package main

import (
	"bytes"
	"path/filepath"
	"runtime"
	"testing"
)

func TestParseExitFrame_Valid(t *testing.T) {
	cases := []struct {
		body    string
		wantN   int
		wantOut string
	}{
		{"EXIT 0\n", 0, ""},
		{"EXIT 0\nhello", 0, "hello"},
		{"EXIT 1\nNo matches\n", 1, "No matches\n"},
		{"EXIT 127\nbinary\x00data\xff", 127, "binary\x00data\xff"},
		{"EXIT -1\nneg", -1, "neg"},
	}
	for _, tc := range cases {
		gotN, gotOut, ok := parseExitFrame([]byte(tc.body))
		if !ok {
			t.Errorf("parseExitFrame(%q) = (_,_,false), want ok", tc.body)
			continue
		}
		if gotN != tc.wantN || !bytes.Equal(gotOut, []byte(tc.wantOut)) {
			t.Errorf("parseExitFrame(%q) = (%d,%q,_), want (%d,%q,_)",
				tc.body, gotN, gotOut, tc.wantN, tc.wantOut)
		}
	}
}

func TestParseExitFrame_Invalid(t *testing.T) {
	cases := [][]byte{
		nil,
		[]byte(""),
		[]byte("EXIT"),
		[]byte("EXIT 0"),         // no newline
		[]byte("hello\n"),        // wrong prefix
		[]byte("EXIT abc\nbody"), // non-numeric
		[]byte("exit 0\nbody"),   // case-sensitive
	}
	for _, body := range cases {
		if _, _, ok := parseExitFrame(body); ok {
			t.Errorf("parseExitFrame(%q) returned ok, want !ok", body)
		}
	}
}

func TestPathsEqual(t *testing.T) {
	if !pathsEqual("/a/b", "/a/b/") {
		t.Error("trailing slash should be normalized")
	}
	if !pathsEqual("/a/b", "/a/./b") {
		t.Error("./ should be normalized")
	}
	if pathsEqual("/a/b", "/a/c") {
		t.Error("different paths must not be equal")
	}
	if runtime.GOOS == "windows" {
		if !pathsEqual("C:\\Foo\\Bar", "c:\\foo\\bar") {
			t.Error("Windows paths must compare case-insensitively")
		}
	}
}

// TestSelfDir_Returns_Something is a smoke test ensuring selfDir() doesn't
// panic and returns a non-empty path on the test host.
func TestSelfDir_Returns_Something(t *testing.T) {
	dir, err := selfDir()
	if err != nil {
		t.Fatalf("selfDir() error = %v", err)
	}
	if dir == "" {
		t.Fatal("selfDir() returned empty path")
	}
	if !filepath.IsAbs(dir) {
		t.Logf("selfDir() returned non-absolute path %q (acceptable on some platforms)", dir)
	}
}

// TestPathWithoutSelf_RemovesSelf verifies the PATH-stripping helper actually
// removes the directory containing the current binary.
func TestPathWithoutSelf_RemovesSelf(t *testing.T) {
	got := pathWithoutSelf()
	selfDirPath, err := selfDir()
	if err != nil {
		t.Skipf("selfDir() failed: %v", err)
	}
	sep := string(filepath.ListSeparator)
	for _, p := range splitPath(got, sep) {
		if pathsEqual(p, selfDirPath) {
			t.Errorf("pathWithoutSelf still contains %q", selfDirPath)
		}
	}
}

func splitPath(s, sep string) []string {
	var out []string
	start := 0
	for i := 0; i+len(sep) <= len(s); i++ {
		if s[i:i+len(sep)] == sep {
			out = append(out, s[start:i])
			start = i + len(sep)
		}
	}
	out = append(out, s[start:])
	return out
}
