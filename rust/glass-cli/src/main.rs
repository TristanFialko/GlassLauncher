//! Host harness for `tools/parity` and `tools/bench`.
//!
//! Line protocol (stdin → stdout), strings hex-encoded UTF-8 so any character
//! survives the pipe:
//!   glass-cli norm                      <hex>            → <hex of norm()>
//!   glass-cli query <wire-file> <max>   <hex query>      → comma-separated indices
//!   glass-cli bench <wire-file> <queries-file> <rounds>  → timing summary
//!   glass-cli prepare <snapshot-file>                     → refs line, or "ERR <reason>"
//!   glass-cli squery <snapshot-file> <max>  <hex query>  → comma-separated index positions
//!   glass-cli bench-prepare <snapshot-file> <rounds>      → snapshot preparation timing

use std::io::{self, BufRead, Write};
use std::time::Instant;

use glass_core::search::{norm, SearchIndex};
use glass_core::snapshot::prepare;

fn unhex(s: &str) -> String {
    let bytes: Vec<u8> = (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).expect("bad hex"))
        .collect();
    String::from_utf8(bytes).expect("bad utf-8")
}

fn hex(s: &str) -> String {
    s.bytes().map(|b| format!("{b:02x}")).collect()
}

fn read_wire(path: &str) -> SearchIndex {
    let raw = std::fs::read(path).expect("read wire file");
    SearchIndex::from_wire(&String::from_utf8(raw).expect("wire utf-8"))
}

fn join(v: &[usize]) -> String {
    v.iter()
        .map(|i| i.to_string())
        .collect::<Vec<_>>()
        .join(",")
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let stdin = io::stdin();
    let mut out = io::BufWriter::new(io::stdout().lock());
    match args.get(1).map(String::as_str) {
        Some("norm") => {
            for line in stdin.lock().lines() {
                writeln!(out, "{}", hex(&norm(&unhex(line.unwrap().trim())))).unwrap();
            }
        }
        Some("query") => {
            let idx = read_wire(&args[2]);
            let max: usize = args[3].parse().unwrap();
            for line in stdin.lock().lines() {
                writeln!(
                    out,
                    "{}",
                    join(&idx.query(&unhex(line.unwrap().trim()), max))
                )
                .unwrap();
            }
        }
        Some("bench") => {
            let idx = read_wire(&args[2]);
            let queries: Vec<String> = std::fs::read_to_string(&args[3])
                .unwrap()
                .lines()
                .map(|l| unhex(l.trim()))
                .collect();
            let rounds: usize = args[4].parse().unwrap();
            let t = Instant::now();
            let build_rounds = 200;
            let wire = std::fs::read_to_string(&args[2]).unwrap();
            for _ in 0..build_rounds {
                std::hint::black_box(SearchIndex::from_wire(std::hint::black_box(&wire)));
            }
            let build_us = t.elapsed().as_secs_f64() * 1e6 / build_rounds as f64;
            let mut samples = Vec::with_capacity(rounds);
            let mut sink = 0usize;
            for _ in 0..rounds {
                let t = Instant::now();
                for q in &queries {
                    sink += idx.query(std::hint::black_box(q), 24).len();
                }
                samples.push(t.elapsed().as_secs_f64() * 1e6 / queries.len() as f64);
            }
            samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let p = |f: f64| samples[((samples.len() - 1) as f64 * f).round() as usize];
            writeln!(
                out,
                "{{\"impl\":\"rust\",\"entries\":{},\"queries\":{},\"rounds\":{},\"build_us\":{:.2},\"per_query_us_median\":{:.3},\"per_query_us_p95\":{:.3},\"sink\":{}}}",
                idx.len(), queries.len(), rounds, build_us, p(0.5), p(0.95), sink
            )
            .unwrap();
        }
        Some("prepare") => {
            // snapshot file -> refs line ("ERR <reason>" if JavaScript must fall back)
            let wire = std::fs::read_to_string(&args[2]).expect("read snapshot file");
            match prepare(&wire) {
                Ok(p) => writeln!(out, "{}", p.refs_wire()).unwrap(),
                Err(e) => writeln!(out, "ERR {e:?}").unwrap(),
            }
        }
        Some("squery") => {
            let wire = std::fs::read_to_string(&args[2]).expect("read snapshot file");
            let idx = prepare(&wire).expect("valid snapshot").index;
            let max: usize = args[3].parse().unwrap();
            for line in stdin.lock().lines() {
                writeln!(
                    out,
                    "{}",
                    join(&idx.query(&unhex(line.unwrap().trim()), max))
                )
                .unwrap();
            }
        }
        Some("bench-prepare") => {
            let wire = std::fs::read_to_string(&args[2]).expect("read snapshot file");
            let rounds: usize = args[3].parse().unwrap();
            let mut samples = Vec::with_capacity(rounds);
            let mut n = 0;
            for _ in 0..rounds {
                let t = Instant::now();
                n = std::hint::black_box(
                    prepare(std::hint::black_box(&wire)).expect("valid snapshot"),
                )
                .refs
                .len();
                samples.push(t.elapsed().as_secs_f64() * 1e6);
            }
            samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let p = |f: f64| samples[((samples.len() - 1) as f64 * f).round() as usize];
            writeln!(
                out,
                "{{\"impl\":\"rust prepare\",\"snapshot_bytes\":{},\"entries\":{},\"rounds\":{},\"prepare_us_median\":{:.2},\"prepare_us_p95\":{:.2}}}",
                wire.len(), n, rounds, p(0.5), p(0.95)
            )
            .unwrap();
        }
        _ => {
            eprintln!(
                "usage: glass-cli norm | query <wire> <max> | bench <wire> <queries> <rounds> | prepare <snapshot> | squery <snapshot> <max> | bench-prepare <snapshot> <rounds>"
            );
            std::process::exit(2);
        }
    }
}
