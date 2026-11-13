//! Frozen ranking contract, checked without Node.
//!
//! `tools/parity/fixtures/ranking-golden.json` holds the output of the original
//! JavaScript ranking (`registry.js` `queryJs`) for every query in the fixture,
//! recorded before the web UI was removed (see `tools/parity/fixtures/PROVENANCE.md`).
//! The Rust core must keep the same entries from the snapshot and rank every
//! query exactly as recorded, for the default limit and for a short one.

use glass_core::search::DEFAULT_MAX;
use glass_core::snapshot::prepare;
use std::path::Path;

const GOLDEN: &str = "../../tools/parity/fixtures/ranking-golden.json";

/// Just enough JSON for the fixture: objects, arrays, strings and numbers.
enum Json {
    Null,
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

struct Parser<'a> {
    s: &'a [u8],
    i: usize,
}

impl<'a> Parser<'a> {
    fn new(s: &'a [u8]) -> Self {
        Parser { s, i: 0 }
    }

    fn peek(&self) -> u8 {
        self.s[self.i]
    }

    fn ws(&mut self) {
        while self.i < self.s.len() && self.s[self.i].is_ascii_whitespace() {
            self.i += 1;
        }
    }

    fn value(&mut self) -> Json {
        self.ws();
        match self.peek() {
            b'{' => self.object(),
            b'[' => self.array(),
            b'"' => Json::Str(self.string()),
            b't' | b'f' | b'n' => {
                while self.i < self.s.len() && self.s[self.i].is_ascii_alphabetic() {
                    self.i += 1;
                }
                Json::Null
            }
            _ => self.number(),
        }
    }

    fn object(&mut self) -> Json {
        self.i += 1;
        let mut members = Vec::new();
        self.ws();
        if self.peek() == b'}' {
            self.i += 1;
            return Json::Obj(members);
        }
        loop {
            self.ws();
            let key = self.string();
            self.ws();
            self.i += 1;
            members.push((key, self.value()));
            self.ws();
            self.i += 1;
            if self.s[self.i - 1] == b'}' {
                return Json::Obj(members);
            }
        }
    }

    fn array(&mut self) -> Json {
        self.i += 1;
        let mut items = Vec::new();
        self.ws();
        if self.peek() == b']' {
            self.i += 1;
            return Json::Arr(items);
        }
        loop {
            items.push(self.value());
            self.ws();
            self.i += 1;
            if self.s[self.i - 1] == b']' {
                return Json::Arr(items);
            }
        }
    }

    fn string(&mut self) -> String {
        self.i += 1;
        let mut out = Vec::new();
        loop {
            let c = self.peek();
            self.i += 1;
            match c {
                b'"' => break,
                b'\\' => {
                    let e = self.peek();
                    self.i += 1;
                    let ch = match e {
                        b'n' => '\n',
                        b't' => '\t',
                        b'r' => '\r',
                        b'b' => '\u{8}',
                        b'f' => '\u{c}',
                        b'u' => self.unicode(),
                        other => char::from(other),
                    };
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                }
                _ => out.push(c),
            }
        }
        String::from_utf8(out).expect("fixture is UTF-8")
    }

    fn hex4(&mut self) -> u32 {
        let digits = std::str::from_utf8(&self.s[self.i..self.i + 4]).expect("hex digits");
        self.i += 4;
        u32::from_str_radix(digits, 16).expect("hex escape")
    }

    fn unicode(&mut self) -> char {
        let hi = self.hex4();
        let code = if (0xd800..0xdc00).contains(&hi) {
            self.i += 2;
            let lo = self.hex4();
            0x10000 + ((hi - 0xd800) << 10) + (lo - 0xdc00)
        } else {
            hi
        };
        char::from_u32(code).expect("no lone surrogates in the fixture")
    }

    fn number(&mut self) -> Json {
        let start = self.i;
        while self.i < self.s.len() && b"+-.eE0123456789".contains(&self.s[self.i]) {
            self.i += 1;
        }
        let text = std::str::from_utf8(&self.s[start..self.i]).expect("number");
        Json::Num(text.parse().expect("number"))
    }
}

fn golden() -> Json {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join(GOLDEN);
    let text = std::fs::read_to_string(path).expect("read ranking-golden.json");
    Parser::new(text.as_bytes()).value()
}

fn indices(s: &str) -> Vec<usize> {
    if s.is_empty() {
        return Vec::new();
    }
    s.split(',').map(|p| p.parse().expect("index")).collect()
}

#[test]
fn golden_file_is_usable() {
    let g = golden();
    assert_eq!(g.get("max").num() as usize, DEFAULT_MAX);
    assert!(g.get("cases").arr().len() >= 1000, "too few golden cases");
    assert!(!g.get("entries").arr().is_empty(), "no golden entries");
}

#[test]
fn rust_core_ranks_exactly_like_the_recorded_javascript() {
    let g = golden();
    let entries = g.get("entries").arr();
    let cases = g.get("cases").arr();
    let prepared = prepare(g.get("snapshot").str()).expect("golden snapshot is valid");
    assert_eq!(prepared.refs.len(), entries.len(), "entries kept from the snapshot");
    let max = DEFAULT_MAX;
    let mut bad = Vec::new();
    for case in cases {
        let pair = case.arr();
        let (q, want) = (pair[0].str(), indices(pair[1].str()));
        let got = prepared.index.query(q, max);
        if got != want {
            bad.push(format!("<{q}> max {max}: expected {want:?}, got {got:?}"));
        }
        let short = &want[..want.len().min(5)];
        let got = prepared.index.query(q, 5);
        if got != short {
            bad.push(format!("<{q}> max 5: expected {short:?}, got {got:?}"));
        }
    }
    let summary = format!("{} of {} rankings differ", bad.len(), cases.len() * 2);
    assert!(bad.is_empty(), "{summary}; first:\n{}", bad[..bad.len().min(8)].join("\n"));
}
