//! Frozen ranking contract, checked without Node.
//!
//! `tools/parity/fixtures/ranking-golden.json` holds the output of the original
//! JavaScript ranking (`registry.js` `queryJs`) for every query in the fixture.
//! It was captured from the legacy JavaScript while that code was still in the
//! tree; see `tools/parity/fixtures/PROVENANCE.md`. The Rust core must keep the
//! same entries from the snapshot and rank every query exactly as recorded, for
//! the default limit and for a short one.
//!
//! The fixture is read with a small strict JSON parser (RFC 8259 grammar), so
//! the crate keeps zero dependencies and `cargo test --locked` needs no registry.

use glass_core::search::DEFAULT_MAX;
use glass_core::snapshot::prepare;
use std::collections::HashSet;
use std::path::Path;

const GOLDEN: &str = "../../tools/parity/fixtures/ranking-golden.json";

/// Shape recorded when the fixture was frozen (`ranking-golden.js --write`).
/// Regenerating the fixture must update these on purpose.
const ENTRIES: usize = 228;
const CASES: usize = 2522;
const SHORT_MAX: usize = 5;

/// Deeper input is rejected instead of risking the stack; the fixture uses 3.
const MAX_DEPTH: usize = 64;

#[derive(Debug, PartialEq)]
enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    fn get(&self, key: &str) -> &Json {
        match self {
            Json::Obj(m) => &m.iter().find(|(k, _)| k == key).expect(key).1,
            _ => panic!("not an object"),
        }
    }

    fn arr(&self) -> &[Json] {
        match self {
            Json::Arr(v) => v,
            _ => panic!("not an array"),
        }
    }

    fn str(&self) -> &str {
        match self {
            Json::Str(s) => s,
            _ => panic!("not a string"),
        }
    }

    fn num(&self) -> f64 {
        match self {
            Json::Num(n) => *n,
            _ => panic!("not a number"),
        }
    }
}

type Res<T> = Result<T, String>;

/// A string byte that needs no escape handling (JSON forbids raw control characters).
fn plain(c: u8) -> bool {
    c != b'"' && c != b'\\' && c >= 0x20
}

struct Parser<'a> {
    s: &'a [u8],
    i: usize,
    d: usize,
}

impl<'a> Parser<'a> {
    fn new(s: &'a [u8]) -> Self {
        Parser { s, i: 0, d: 0 }
    }

    fn err<T>(&self, what: &str) -> Res<T> {
        Err(format!("{what} at byte {}", self.i))
    }

    fn peek(&self) -> Option<u8> {
        self.s.get(self.i).copied()
    }

    fn bump(&mut self) -> Res<u8> {
        let Some(c) = self.peek() else {
            return self.err("unexpected end of input");
        };
        self.i += 1;
        Ok(c)
    }

    fn eat(&mut self, want: u8) -> Res<()> {
        if self.peek() != Some(want) {
            return self.err(&format!("expected {:?}", char::from(want)));
        }
        self.i += 1;
        Ok(())
    }

    fn ws(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.i += 1;
        }
    }

    /// One complete document: a value and nothing but whitespace after it.
    fn document(mut self) -> Res<Json> {
        let v = self.value()?;
        self.ws();
        if self.i != self.s.len() {
            return self.err("trailing data");
        }
        Ok(v)
    }

    fn value(&mut self) -> Res<Json> {
        self.ws();
        match self.peek() {
            Some(b'{') => self.object(),
            Some(b'[') => self.array(),
            Some(b'"') => self.string().map(Json::Str),
            Some(b't') => self.word("true", Json::Bool(true)),
            Some(b'f') => self.word("false", Json::Bool(false)),
            Some(b'n') => self.word("null", Json::Null),
            Some(b'-' | b'0'..=b'9') => self.number(),
            Some(_) => self.err("unexpected character"),
            None => self.err("unexpected end of input"),
        }
    }

    fn word(&mut self, w: &str, v: Json) -> Res<Json> {
        if !self.s[self.i..].starts_with(w.as_bytes()) {
            return self.err("invalid literal");
        }
        self.i += w.len();
        Ok(v)
    }

    fn enter(&mut self) -> Res<()> {
        self.d += 1;
        if self.d > MAX_DEPTH {
            return self.err("nesting too deep");
        }
        Ok(())
    }

    fn object(&mut self) -> Res<Json> {
        self.enter()?;
        self.eat(b'{')?;
        let mut members: Vec<(String, Json)> = Vec::new();
        self.ws();
        if self.peek() == Some(b'}') {
            self.i += 1;
            self.d -= 1;
            return Ok(Json::Obj(members));
        }
        loop {
            self.ws();
            if self.peek() != Some(b'"') {
                return self.err("expected a string key");
            }
            let key = self.string()?;
            if members.iter().any(|(k, _)| *k == key) {
                return self.err("duplicate key");
            }
            self.ws();
            self.eat(b':')?;
            let value = self.value()?;
            members.push((key, value));
            self.ws();
            match self.bump()? {
                b',' => {}
                b'}' => break,
                _ => return self.err("expected ',' or '}'"),
            }
        }
        self.d -= 1;
        Ok(Json::Obj(members))
    }

    fn array(&mut self) -> Res<Json> {
        self.enter()?;
        self.eat(b'[')?;
        let mut items = Vec::new();
        self.ws();
        if self.peek() == Some(b']') {
            self.i += 1;
            self.d -= 1;
            return Ok(Json::Arr(items));
        }
        loop {
            items.push(self.value()?);
            self.ws();
            match self.bump()? {
                b',' => {}
                b']' => break,
                _ => return self.err("expected ',' or ']'"),
            }
        }
        self.d -= 1;
        Ok(Json::Arr(items))
    }

    fn string(&mut self) -> Res<String> {
        self.eat(b'"')?;
        let mut out = String::new();
        loop {
            let start = self.i;
            while self.peek().is_some_and(plain) {
                self.i += 1;
            }
            let Ok(run) = std::str::from_utf8(&self.s[start..self.i]) else {
                return self.err("invalid UTF-8 in string");
            };
            out.push_str(run);
            match self.bump()? {
                b'"' => return Ok(out),
                b'\\' => out.push(self.escape()?),
                _ => return self.err("control character in string"),
            }
        }
    }

    fn escape(&mut self) -> Res<char> {
        let c = match self.bump()? {
            b'"' => '"',
            b'\\' => '\\',
            b'/' => '/',
            b'b' => '\u{8}',
            b'f' => '\u{c}',
            b'n' => '\n',
            b'r' => '\r',
            b't' => '\t',
            b'u' => return self.unicode(),
            _ => return self.err("invalid escape"),
        };
        Ok(c)
    }

    fn hex4(&mut self) -> Res<u32> {
        let s = self.s;
        let Some(digits) = s.get(self.i..self.i + 4) else {
            return self.err("expected four hex digits");
        };
        let mut n = 0;
        for &b in digits {
            let Some(v) = char::from(b).to_digit(16) else {
                return self.err("expected four hex digits");
            };
            n = n * 16 + v;
        }
        self.i += 4;
        Ok(n)
    }

    fn unicode(&mut self) -> Res<char> {
        let hi = self.hex4()?;
        if (0xdc00..=0xdfff).contains(&hi) {
            return self.err("lone low surrogate");
        }
        if !(0xd800..=0xdbff).contains(&hi) {
            return self.code(hi);
        }
        if !self.s[self.i..].starts_with(b"\\u") {
            return self.err("lone high surrogate");
        }
        self.i += 2;
        let lo = self.hex4()?;
        if !(0xdc00..=0xdfff).contains(&lo) {
            return self.err("high surrogate without a low one");
        }
        self.code(0x10000 + ((hi - 0xd800) << 10) + (lo - 0xdc00))
    }

    fn code(&self, n: u32) -> Res<char> {
        match char::from_u32(n) {
            Some(c) => Ok(c),
            None => self.err("invalid code point"),
        }
    }

    fn digits(&mut self) -> usize {
        let start = self.i;
        while self.peek().is_some_and(|c| c.is_ascii_digit()) {
            self.i += 1;
        }
        self.i - start
    }

    fn number(&mut self) -> Res<Json> {
        let start = self.i;
        if self.peek() == Some(b'-') {
            self.i += 1;
        }
        match self.peek() {
            Some(b'0') => self.i += 1,
            Some(b'1'..=b'9') => {
                self.digits();
            }
            _ => return self.err("invalid number"),
        }
        if self.peek() == Some(b'.') {
            self.i += 1;
            if self.digits() == 0 {
                return self.err("expected digits after '.'");
            }
        }
        if matches!(self.peek(), Some(b'e' | b'E')) {
            self.i += 1;
            if matches!(self.peek(), Some(b'+' | b'-')) {
                self.i += 1;
            }
            if self.digits() == 0 {
                return self.err("expected exponent digits");
            }
        }
        let Ok(text) = std::str::from_utf8(&self.s[start..self.i]) else {
            return self.err("invalid number");
        };
        match text.parse() {
            Ok(n) => Ok(Json::Num(n)),
            Err(_) => self.err("invalid number"),
        }
    }
}

fn parse(s: &str) -> Res<Json> {
    Parser::new(s.as_bytes()).document()
}

fn golden() -> Json {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join(GOLDEN);
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) => panic!("read {}: {e}", path.display()),
    };
    match parse(&text) {
        Ok(g) => g,
        Err(e) => panic!("ranking-golden.json is not valid JSON: {e}"),
    }
}

fn indices(s: &str) -> Vec<usize> {
    if s.is_empty() {
        return Vec::new();
    }
    s.split(',').map(|p| p.parse().expect("index")).collect()
}

#[test]
fn golden_file_has_the_recorded_shape() {
    let g = golden();
    assert!(g.get("generated_from").str().contains("queryJs()"));
    assert_eq!(g.get("max").num() as usize, DEFAULT_MAX);
    let entries = g.get("entries").arr();
    assert_eq!(entries.len(), ENTRIES, "golden entries");
    let mut ids = HashSet::new();
    for e in entries {
        let id = e.get("id").str();
        assert!(ids.insert(id), "duplicate entry id {id}");
    }
    let cases = g.get("cases").arr();
    assert_eq!(cases.len(), CASES, "golden cases");
    let mut queries = HashSet::new();
    for case in cases {
        let pair = case.arr();
        assert_eq!(pair.len(), 2, "a case is [query, ranking]");
        let q = pair[0].str();
        assert!(queries.insert(q), "duplicate query <{q}>");
        let want = indices(pair[1].str());
        assert!(want.len() <= DEFAULT_MAX, "<{q}>: too many results");
        let mut seen = HashSet::new();
        for &i in &want {
            assert!(i < ENTRIES, "<{q}>: entry {i} out of range");
            assert!(seen.insert(i), "<{q}>: entry {i} repeated");
        }
    }
}

#[test]
fn rust_core_ranks_exactly_like_the_recorded_javascript() {
    let g = golden();
    let entries = g.get("entries").arr();
    let cases = g.get("cases").arr();
    let snapshot = g.get("snapshot").str();
    let prepared = prepare(snapshot).expect("golden snapshot");
    let kept = prepared.refs.len();
    assert_eq!(kept, entries.len(), "entries kept from the snapshot");
    let max = DEFAULT_MAX;
    let mut bad = Vec::new();
    for case in cases {
        let pair = case.arr();
        let (q, want) = (pair[0].str(), indices(pair[1].str()));
        let got = prepared.index.query(q, max);
        if got != want {
            bad.push(format!("<{q}> max {max}: expected {want:?}, got {got:?}"));
        }
        let short = &want[..want.len().min(SHORT_MAX)];
        let got = prepared.index.query(q, SHORT_MAX);
        if got != short {
            bad.push(format!("<{q}> max 5: expected {short:?}, got {got:?}"));
        }
    }
    let summary = format!("{} of {} rankings differ", bad.len(), cases.len() * 2);
    let first = bad[..bad.len().min(8)].join("\n");
    assert!(bad.is_empty(), "{summary}; first:\n{first}");
}

#[test]
fn parser_reads_strict_json() {
    let nums = parse(" [1, -2.5e3, -0.5, 1.5E+2] ");
    let want = "Ok(Arr([Num(1.0), Num(-2500.0), Num(-0.5), Num(150.0)]))";
    assert_eq!(format!("{nums:?}"), want);
    let words = parse("[true,false,null]");
    let want = "Ok(Arr([Bool(true), Bool(false), Null]))";
    assert_eq!(format!("{words:?}"), want);
    assert!(matches!(parse("true"), Ok(Json::Bool(true))));
    let obj = parse(r#"{"a": [], "b": {"c": "d"}}"#);
    let want = r#"Ok(Obj([("a", Arr([])), ("b", Obj([("c", Str("d"))]))]))"#;
    assert_eq!(format!("{obj:?}"), want);
    let esc = parse(r#""q\"\\\/\b\f\n\r\t\u00e9\ud83c\udfae""#);
    let want = "q\"\\/\u{8}\u{c}\n\r\t\u{e9}\u{1f3ae}";
    assert_eq!(esc, Ok(Json::Str(want.to_string())));
    let raw = parse("\"日本 🎮\"");
    assert_eq!(raw, Ok(Json::Str("日本 🎮".to_string())));
}

/// One malformed document per line; each must be rejected.
const MALFORMED: &str = r#"
[
[1,]
[1 2]
[,1]
{"a" 1}
{"a":1,}
{1:2}
{"a":1,"a":2}
"abc
"\x"
"\u12"
"\u12g4"
"\ud83c"
"\udfae"
"\ud83c\u0041"
01
1.
-
1e
+1
.5
tru
nul
[1] x
truex
]
}
"#;

#[test]
fn parser_rejects_malformed_json() {
    for s in MALFORMED.lines().filter(|l| !l.is_empty()) {
        assert!(parse(s).is_err(), "accepted {s:?}");
    }
    assert!(parse("").is_err());
    assert!(parse("  ").is_err());
    assert!(parse("\"a\u{1}b\"").is_err());
    assert!(Parser::new(b"\"\xff\"").document().is_err());
    let deep = "[".repeat(1000) + &"]".repeat(1000);
    assert!(parse(&deep).is_err());
    let ok = "[".repeat(MAX_DEPTH) + &"]".repeat(MAX_DEPTH);
    assert!(parse(&ok).is_ok());
}
