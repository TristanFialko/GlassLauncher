//! Search index and ranking — an exact port of `assets/js/registry.js`
//! (`norm`, `compact`, `subseq`, `fieldScore`, `score` and the sort comparator).
//!
//! Parity rules that matter (verified by `tools/parity` fixtures generated from
//! the real JavaScript):
//! * `norm` lowercases with full Unicode case mapping, drops every character
//!   outside `[a-z0-9 \u00c0-\uffff]` (astral characters survive because both of
//!   their UTF-16 surrogates fall inside that range), collapses JavaScript `\s`
//!   runs to one space and trims.
//! * Lengths, the fuzzy subsequence test and the title tie-break compare UTF-16
//!   code units, exactly like JavaScript strings.
//! * Ties on score and kind keep input order (stable sort), matching V8's
//!   stable `Array.prototype.sort` for the comparator used in `registry.js`.

use std::cmp::Ordering;

/// Separates entries in the wire format sent from JavaScript.
pub const RECORD_SEP: char = '\u{1e}';
/// Separates fields (`kind`, `title`, `keyword`...) inside one entry.
pub const FIELD_SEP: char = '\u{1f}';
/// `registry.js` uses `max || 24`.
pub const DEFAULT_MAX: usize = 24;

/// Whitespace as matched by a JavaScript `\s`, restricted to characters that
/// can survive the first `norm` filter (everything below U+00C0 except the
/// ASCII space has already been removed by then).
fn is_js_space_after_filter(c: char) -> bool {
    matches!(
        c,
        ' ' | '\u{1680}' | '\u{2000}'
            ..='\u{200a}'
                | '\u{2028}'
                | '\u{2029}'
                | '\u{202f}'
                | '\u{205f}'
                | '\u{3000}'
                | '\u{feff}'
    )
}

/// `norm()` from registry.js.
pub fn norm(s: &str) -> String {
    let lower = s.to_lowercase();
    let mut out = String::with_capacity(lower.len());
    let mut pending_space = false;
    for c in lower.chars() {
        let keep = c.is_ascii_lowercase() || c.is_ascii_digit() || c == ' ' || (c as u32) >= 0xC0;
        if !keep {
            continue;
        }
        if is_js_space_after_filter(c) {
            pending_space = true;
            continue;
        }
        if pending_space && !out.is_empty() {
            out.push(' ');
        }
        pending_space = false;
        out.push(c);
    }
    out
}

/// `compact()` from registry.js: `norm` output with spaces removed.
fn compact(s: &str) -> String {
    s.replace(' ', "")
}

/// `subseq()` from registry.js, over UTF-16 code units.
fn subseq(q: &[u16], t: &[u16]) -> bool {
    let mut i = 0;
    for &c in t {
        if i >= q.len() {
            break;
        }
        if c == q[i] {
            i += 1;
        }
    }
    i == q.len()
}

/// One searchable string, normalised once at index-build time.
#[derive(Debug, Clone)]
struct Field {
    t: String,
    tc: String,
}

impl Field {
    fn new(raw: &str) -> Self {
        let t = norm(raw);
        let tc = compact(&t);
        Field { t, tc }
    }
}

#[derive(Debug, Clone)]
struct Entry {
    is_app: bool,
    title: Field,
    /// Compact title as UTF-16 for the fuzzy subsequence test.
    title_tc16: Vec<u16>,
    /// Normalised title as UTF-16 for the tie-break and length penalty.
    title_t16: Vec<u16>,
    keywords: Vec<Field>,
}

struct Query {
    q: String,
    qc: String,
    qc16_len: usize,
    qc16: Vec<u16>,
}

/// `fieldScore()` from registry.js. Returns 0–100.
fn field_score(f: &Field, tc16: Option<&[u16]>, q: &Query) -> f64 {
    let t = &f.t;
    if t.is_empty() {
        return 0.0;
    }
    let tc = &f.tc;
    if *t == q.q || *tc == q.qc {
        return 100.0;
    }
    if t.starts_with(q.q.as_str()) || tc.starts_with(q.qc.as_str()) {
        return 85.0;
    }
    if t.split(' ').any(|w| w.starts_with(q.q.as_str())) {
        return 70.0;
    }
    if tc.contains(q.qc.as_str()) {
        return 50.0;
    }
    if let Some(tc16) = tc16 {
        if q.qc16_len >= 4 && subseq(&q.qc16, tc16) {
            return 20.0;
        }
    }
    0.0
}

impl Entry {
    /// `score()` from registry.js.
    fn score(&self, q: &Query) -> f64 {
        let mut best = field_score(&self.title, Some(&self.title_tc16), q);
        for k in &self.keywords {
            let s = field_score(k, None, q) * 0.92;
            if s > best {
                best = s;
            }
        }
        if best > 0.0 {
            best - f64::min(5.0, self.title_t16.len() as f64 * 0.02)
        } else {
            0.0
        }
    }
}

/// Pre-normalised search index. Build once per search session, query per keystroke.
#[derive(Debug, Clone, Default)]
pub struct SearchIndex {
    entries: Vec<Entry>,
}

impl SearchIndex {
    /// Builds an index from `(is_app, title, keywords)` tuples.
    pub fn new<'a, I, K>(items: I) -> Self
    where
        I: IntoIterator<Item = (bool, &'a str, K)>,
        K: IntoIterator<Item = &'a str>,
    {
        let entries = items
            .into_iter()
            .map(|(is_app, title, kws)| {
                let title = Field::new(title);
                Entry {
                    is_app,
                    title_tc16: title.tc.encode_utf16().collect(),
                    title_t16: title.t.encode_utf16().collect(),
                    title,
                    keywords: kws.into_iter().map(Field::new).collect(),
                }
            })
            .collect();
        SearchIndex { entries }
    }

    /// Parses the wire format produced by `registry.js` (`G.registry.wire`):
    /// entries separated by U+001E, fields by U+001F, fields being
    /// `kind, title, keyword*`. An empty string is an empty index.
    pub fn from_wire(wire: &str) -> Self {
        if wire.is_empty() {
            return SearchIndex::default();
        }
        SearchIndex::new(wire.split(RECORD_SEP).map(|rec| {
            let mut fields = rec.split(FIELD_SEP);
            let kind = fields.next().unwrap_or("");
            let title = fields.next().unwrap_or("");
            (kind == "app", title, fields)
        }))
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// `G.registry.query(text, max)`: indices of matching entries, best first.
    /// `max == 0` means the default of 24, like `max || 24`.
    pub fn query(&self, text: &str, max: usize) -> Vec<usize> {
        let q = norm(text);
        if q.is_empty() {
            return Vec::new();
        }
        let qc = compact(&q);
        let qc16: Vec<u16> = qc.encode_utf16().collect();
        let query = Query {
            qc16_len: qc16.len(),
            qc16,
            qc,
            q,
        };

        let mut hits: Vec<(usize, f64)> = self
            .entries
            .iter()
            .enumerate()
            .filter_map(|(i, e)| {
                let s = e.score(&query);
                (s > 0.0).then_some((i, s))
            })
            .collect();

        hits.sort_by(|a, b| {
            if a.1 != b.1 {
                return b.1.partial_cmp(&a.1).unwrap_or(Ordering::Equal);
            }
            let (ea, eb) = (&self.entries[a.0], &self.entries[b.0]);
            if ea.is_app != eb.is_app {
                return if ea.is_app {
                    Ordering::Less
                } else {
                    Ordering::Greater
                };
            }
            ea.title_t16.cmp(&eb.title_t16)
        });

        let max = if max == 0 { DEFAULT_MAX } else { max };
        hits.truncate(max);
        hits.into_iter().map(|(i, _)| i).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn norm_matches_registry_js_rules() {
        assert_eq!(norm("  Wi-Fi  "), "wifi");
        assert_eq!(norm("Date & time"), "date time");
        assert_eq!(norm("Disney+"), "disney");
        assert_eq!(norm("A\tB"), "ab"); // tab is removed before the \s collapse
        assert_eq!(norm("A\u{3000}\u{3000}B"), "a b"); // ideographic space collapses
        assert_eq!(norm("A\u{a0}B"), "ab"); // nbsp < U+00C0 is removed
        assert_eq!(norm("Ünïcödé"), "ünïcödé");
        assert_eq!(norm("🎮 Games"), "🎮 games");
        assert_eq!(norm("\u{feff}x\u{feff}"), "x");
        assert_eq!(norm(""), "");
    }

    fn idx() -> SearchIndex {
        SearchIndex::new(vec![
            (true, "YouTube", vec![]),
            (false, "Wi-Fi", vec!["wifi", "wireless", "network"]),
            (true, "Netflix", vec![]),
            (false, "Settings", vec!["android settings", "system"]),
            (true, "Settings", vec![]),
            (true, "Google Play Store", vec!["play store", "store"]),
        ])
    }

    #[test]
    fn exact_prefix_word_and_contains_ranks() {
        let i = idx();
        assert_eq!(i.query("youtube", 24), vec![0]);
        assert_eq!(i.query("net", 24), vec![2, 1]); // 85 prefix beats keyword prefix 85*0.92
        assert_eq!(i.query("play", 24), vec![5]);
        assert_eq!(i.query("tflix", 24), vec![2]);
    }

    #[test]
    fn app_beats_shortcut_on_equal_score() {
        assert_eq!(idx().query("settings", 24), vec![4, 3]);
    }

    #[test]
    fn fuzzy_needs_four_chars_and_titles_only() {
        let i = idx();
        assert_eq!(i.query("ntfx", 24), vec![2]);
        assert!(i.query("ntf", 24).is_empty());
        assert!(i.query("wrls", 24).is_empty()); // keyword-only subsequence never fuzzes
    }

    #[test]
    fn empty_and_max() {
        let i = idx();
        assert!(i.query("   ", 24).is_empty());
        assert_eq!(i.query("e", 1).len(), 1);
        assert_eq!(i.query("e", 0), i.query("e", 24));
    }

    #[test]
    fn wire_round_trip() {
        let w = "app\u{1f}YouTube\u{1e}shortcut\u{1f}Wi-Fi\u{1f}wifi\u{1e}app";
        let i = SearchIndex::from_wire(w);
        assert_eq!(i.len(), 3);
        assert_eq!(i.query("wifi", 24), vec![1]);
        assert!(SearchIndex::from_wire("").is_empty());
    }
}
