//! Search snapshot preparation: the work `G.registry.entries()` in
//! `assets/js/registry.js` did per Search session, moved off the JavaScript
//! thread. JavaScript gathers live facts only (the installed-app list as
//! Android reported it, alias lists, and each provider/shortcut entry with its
//! `available()` result already evaluated); this module applies the rules,
//! orders the entries and builds the [`SearchIndex`] in one call.
//!
//! Rules ported exactly (parity fixtures: `tools/parity/fixtures/registry-entries.json`):
//! * installed app kept iff `show !== false`, id is not `com.glasslauncher`
//!   and id does not match `HIDDEN = /^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i`
//!   (a non-`u` JavaScript `/i` folds ASCII letters only);
//! * listed entry dropped if `available()` was false, or if any `supersededBy`
//!   package is in the installed list (all installed apps, hidden or not);
//! * order is snapshot order; duplicates are kept, as in JavaScript;
//! * a kept entry whose kind is neither app nor shortcut makes the snapshot
//!   unsupported (JavaScript's comparator is asymmetric for other kinds), so
//!   the caller stays on the JavaScript path.
//!
//! Wire format (UTF-8): records separated by U+001E, fields by U+001F, list
//! items inside one field by U+001D. JavaScript strips U+001C..U+001F from all
//! text first (`norm()` removes them anyway, so ranking is unaffected).
//! ```text
//! glass-snapshot<US>1
//! i<US>id<US>name<US>show(1|0)<US>alias*           installed app (built-in provider)
//! e<US>kind(app|shortcut|?)<US>available(1|0)<US>superseded<GS>by...<US>title<US>keyword*
//! ```
//! Records after the header are numbered from 0; [`Prepared::refs`] holds the
//! record number of every kept entry in index order.

use crate::search::{SearchIndex, FIELD_SEP, RECORD_SEP};
use std::collections::HashSet;

/// Separates list items (e.g. `supersededBy` packages) inside one field.
pub const LIST_SEP: char = '\u{1d}';
pub const HEADER: &str = "glass-snapshot\u{1f}1";
/// Glass never lists itself.
pub const SELF_PACKAGE: &str = "com.glasslauncher";
/// `G.apps.HIDDEN` prefixes, matched ASCII-case-insensitively.
pub const HIDDEN_PREFIXES: [&str; 3] = ["org.tizen", "com.samsung", "DU.TV.PRODA"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SnapshotError {
    /// Missing or unknown header / format version.
    BadHeader,
    /// Record `n` (0-based, after the header) is malformed.
    BadRecord(usize),
    /// Record `n` would be listed but its kind is neither app nor shortcut.
    UnsupportedKind(usize),
}

/// A prepared search session: the index plus, for each index position, the
/// snapshot record it came from.
#[derive(Debug, Clone, Default)]
pub struct Prepared {
    pub index: SearchIndex,
    pub refs: Vec<u32>,
}

impl Prepared {
    /// `refs` as the comma-separated text returned over JNI ("" when empty).
    pub fn refs_wire(&self) -> String {
        let mut out = String::with_capacity(self.refs.len() * 4);
        for (n, r) in self.refs.iter().enumerate() {
            if n > 0 {
                out.push(',');
            }
            out.push_str(&r.to_string());
        }
        out
    }
}

pub fn is_hidden(id: &str) -> bool {
    HIDDEN_PREFIXES
        .iter()
        .any(|p| id.len() >= p.len() && id.as_bytes()[..p.len()].eq_ignore_ascii_case(p.as_bytes()))
}

enum Record<'a> {
    App {
        id: &'a str,
        name: &'a str,
        show: bool,
        aliases: Vec<&'a str>,
    },
    Listed {
        kind: Option<bool>, // Some(is_app); None = unsupported kind
        available: bool,
        superseded_by: &'a str,
        title: &'a str,
        keywords: Vec<&'a str>,
    },
}

fn flag(s: Option<&str>, n: usize) -> Result<bool, SnapshotError> {
    match s {
        Some("1") => Ok(true),
        Some("0") => Ok(false),
        _ => Err(SnapshotError::BadRecord(n)),
    }
}

fn parse_record(rec: &str, n: usize) -> Result<Record<'_>, SnapshotError> {
    let mut f = rec.split(FIELD_SEP);
    match f.next() {
        Some("i") => {
            let id = f.next().ok_or(SnapshotError::BadRecord(n))?;
            let name = f.next().ok_or(SnapshotError::BadRecord(n))?;
            let show = flag(f.next(), n)?;
            Ok(Record::App {
                id,
                name,
                show,
                aliases: f.collect(),
            })
        }
        Some("e") => {
            let kind = match f.next() {
                Some("app") => Some(true),
                Some("shortcut") => Some(false),
                Some("?") => None,
                _ => return Err(SnapshotError::BadRecord(n)),
            };
            let available = flag(f.next(), n)?;
            let superseded_by = f.next().ok_or(SnapshotError::BadRecord(n))?;
            let title = f.next().ok_or(SnapshotError::BadRecord(n))?;
            Ok(Record::Listed {
                kind,
                available,
                superseded_by,
                title,
                keywords: f.collect(),
            })
        }
        _ => Err(SnapshotError::BadRecord(n)),
    }
}

/// Parses a snapshot, applies the registry rules and builds the index.
pub fn prepare(wire: &str) -> Result<Prepared, SnapshotError> {
    let mut recs = wire.split(RECORD_SEP);
    if recs.next() != Some(HEADER) {
        return Err(SnapshotError::BadHeader);
    }
    let records = recs
        .enumerate()
        .map(|(n, r)| parse_record(r, n))
        .collect::<Result<Vec<_>, _>>()?;

    let installed: HashSet<&str> = records
        .iter()
        .filter_map(|r| match r {
            Record::App { id, .. } => Some(*id),
            Record::Listed { .. } => None,
        })
        .collect();

    let mut kept: Vec<(bool, &str, &[&str])> = Vec::with_capacity(records.len());
    let mut refs = Vec::with_capacity(records.len());
    for (n, r) in records.iter().enumerate() {
        let item = match r {
            Record::App {
                id,
                name,
                show,
                aliases,
            } => {
                if !*show || *id == SELF_PACKAGE || is_hidden(id) {
                    continue;
                }
                (true, *name, aliases.as_slice())
            }
            Record::Listed {
                kind,
                available,
                superseded_by,
                title,
                keywords,
            } => {
                if !*available
                    || (!superseded_by.is_empty()
                        && superseded_by.split(LIST_SEP).any(|p| installed.contains(p)))
                {
                    continue;
                }
                let Some(is_app) = *kind else {
                    return Err(SnapshotError::UnsupportedKind(n));
                };
                (is_app, *title, keywords.as_slice())
            }
        };
        kept.push(item);
        refs.push(u32::try_from(n).map_err(|_| SnapshotError::BadRecord(n))?);
    }
    let index = SearchIndex::new(kept.iter().map(|(a, t, k)| (*a, *t, k.iter().copied())));
    Ok(Prepared { index, refs })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wire(recs: &[&str]) -> String {
        let mut w = String::from(HEADER);
        for r in recs {
            w.push(RECORD_SEP);
            w.push_str(r);
        }
        w
    }

    #[test]
    fn hidden_prefixes_fold_ascii_case_only() {
        assert!(is_hidden("org.tizen.x"));
        assert!(is_hidden("COM.SAMSUNG.tv"));
        assert!(is_hidden("du.tv.proda"));
        assert!(is_hidden("com.samsungx"));
        assert!(!is_hidden("xcom.samsung"));
        assert!(!is_hidden("org.tize"));
        assert!(!is_hidden(""));
        // U+017F (long s) upper-cases to 'S' but a non-unicode JS /i does not fold non-ASCII onto ASCII
        assert!(!is_hidden("com.\u{17f}am\u{17f}ung"));
    }

    #[test]
    fn installed_app_rules() {
        let p = prepare(&wire(&[
            "i\u{1f}com.a\u{1f}A\u{1f}1",
            "i\u{1f}com.b\u{1f}B\u{1f}0",
            "i\u{1f}com.glasslauncher\u{1f}Glass\u{1f}1",
            "i\u{1f}com.glasslauncher.hub\u{1f}Hub\u{1f}1",
            "i\u{1f}org.Tizen.y\u{1f}T\u{1f}1",
            "i\u{1f}com.a\u{1f}A\u{1f}1\u{1f}alias",
        ]))
        .unwrap();
        assert_eq!(p.refs, vec![0, 3, 5]);
        assert_eq!(p.refs_wire(), "0,3,5");
        assert_eq!(p.index.query("alias", 24), vec![2]);
    }

    #[test]
    fn listed_entry_rules() {
        let p = prepare(&wire(&[
            "i\u{1f}com.android.settings\u{1f}Settings\u{1f}0",
            "e\u{1f}shortcut\u{1f}1\u{1f}com.x\u{1d}com.android.settings\u{1f}Main\u{1f}settings",
            "e\u{1f}shortcut\u{1f}1\u{1f}com.x\u{1f}Kept\u{1f}k",
            "e\u{1f}shortcut\u{1f}0\u{1f}\u{1f}Unavailable",
            "e\u{1f}app\u{1f}1\u{1f}\u{1f}Extra App",
            "e\u{1f}?\u{1f}0\u{1f}\u{1f}Odd kind but filtered out",
        ]))
        .unwrap();
        assert_eq!(p.refs, vec![2, 4]);
        // index positions follow refs: 0 = record 2, 1 = record 4
        assert_eq!(p.index.query("extra", 24), vec![1]);
    }

    #[test]
    fn unsupported_kind_when_kept() {
        assert_eq!(
            prepare(&wire(&["e\u{1f}?\u{1f}1\u{1f}\u{1f}Odd"])).unwrap_err(),
            SnapshotError::UnsupportedKind(0)
        );
    }

    #[test]
    fn malformed_input() {
        assert_eq!(prepare("").unwrap_err(), SnapshotError::BadHeader);
        assert_eq!(
            prepare("glass-snapshot\u{1f}2").unwrap_err(),
            SnapshotError::BadHeader
        );
        for bad in [
            "",
            "x\u{1f}a",
            "i\u{1f}id\u{1f}name",
            "i\u{1f}id\u{1f}name\u{1f}yes",
            "e\u{1f}widget\u{1f}1\u{1f}\u{1f}T",
            "e\u{1f}app\u{1f}1\u{1f}",
        ] {
            assert_eq!(
                prepare(&wire(&[bad])).unwrap_err(),
                SnapshotError::BadRecord(0),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn empty_snapshot() {
        let p = prepare(HEADER).unwrap();
        assert!(p.refs.is_empty() && p.index.is_empty());
        assert_eq!(p.refs_wire(), "");
    }
}
