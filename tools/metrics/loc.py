#!/usr/bin/env python3
"""Reproducible line counts for Glass Launcher, per language and per category.

    python3 tools/metrics/loc.py            # Markdown tables
    python3 tools/metrics/loc.py --json     # machine-readable

Only the Python standard library is used. Files come from `git ls-files` when run inside a git checkout (so untracked
build output is never counted), otherwise from a directory walk that skips build/, .gradle/, target/ and .git/.

Categories (first matching rule wins; see CATEGORY_RULES):
  production  code that ships in the APK or runs on the device: app/src/main/{java,kotlin,res,AndroidManifest.xml},
              the packaged WebView assets (app/src/main/assets: index.html, apps.js, js/, css/, nativeui/), Rust
              glass-core and glass-jni sources *outside* their #[cfg(test)] modules
  test        app/src/test, app/src/androidTest, Rust #[cfg(test)] modules
  tooling     tools/, rust/glass-cli (host CLI for parity/bench), build scripts, CI, *.bat helpers
  legacy      app/src/main/assets_baseline and assets_phase1..3: historical snapshots, NOT a source set, not packaged
  docs        *.md and other prose
  binary      images, audio, fonts, jars (bytes only, no lines)

Counted per file: physical lines, non-blank lines, and code lines (non-blank lines that are not wholly comments).
Comment stripping is a simple lexer-free heuristic: // and /* */ for Java/Kotlin/JS/Rust/CSS/Gradle, <!-- --> for
HTML/XML, # for Python/PowerShell/TOML/YAML, REM/:: for .bat. Strings containing comment markers can make the code
count slightly off; physical and non-blank counts are exact.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))

LANG_BY_EXT = {
    '.kt': 'Kotlin', '.kts': 'Kotlin', '.java': 'Java', '.rs': 'Rust', '.js': 'JavaScript', '.html': 'HTML',
    '.css': 'CSS', '.xml': 'XML', '.json': 'JSON', '.gradle': 'Gradle', '.toml': 'TOML', '.yml': 'YAML',
    '.yaml': 'YAML', '.ps1': 'PowerShell', '.bat': 'Batch', '.py': 'Python', '.md': 'Markdown', '.txt': 'Text',
    '.properties': 'Properties', '.lock': 'Lockfile',
}
BINARY_EXT = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.mp3', '.wav', '.ogg', '.ttf', '.otf', '.jar', '.ico', '.so'}
SKIP_DIRS = {'.git', 'build', '.gradle', 'target', 'node_modules', '.idea'}

# (category, regex on the repo-relative path with forward slashes). First match wins.
CATEGORY_RULES = [
    ('binary', r'\.(png|jpe?g|webp|gif|mp3|wav|ogg|ttf|otf|jar|ico|so)$'),
    ('legacy', r'^app/src/main/assets_(baseline|phase\d+)/'),
    ('test', r'^app/src/(test|androidTest)/'),
    ('docs', r'\.md$'),
    ('production', r'^app/src/main/(java|kotlin|res)/'),
    ('production', r'^app/src/main/AndroidManifest\.xml$'),
    ('production', r'^app/src/main/assets/(index\.html|apps\.js|js/|css/|nativeui/)'),
    ('production', r'^rust/glass-(core|jni)/src/'),
    ('tooling', r'^rust/glass-cli/'),
    ('tooling', r'^tools/'),
    ('tooling', r'^\.github/'),
    ('tooling', r'(^|/)(build\.gradle|rust\.gradle|settings\.gradle|gradle\.properties|gradlew(\.bat)?|Cargo\.(toml|lock)|rust-toolchain\.toml)$'),
    ('tooling', r'^gradle/'),
    ('tooling', r'\.bat$'),
]

# Production surface split used by the migration docs (applies to production files only).
SURFACE_RULES = [
    ('WebView UI (HTML/CSS/JS assets)', r'^app/src/main/assets/(index\.html|apps\.js|js/|css/)'),
    ('WebView host (Java: MainActivity, GlassBridge)', r'^app/src/main/java/com/glasslauncher/(MainActivity|GlassBridge)\.java$'),
    ('Shared Android glue (Java: AppDiscovery, RustCore)', r'^app/src/main/java/'),
    ('Native UI + Android glue (Kotlin)', r'^app/src/main/kotlin/'),
    ('Rust core + JNI (non-test)', r'^rust/'),
    ('Native UI config (home.json)', r'^app/src/main/assets/nativeui/'),
    ('Manifest/resources', r'.'),
]


def list_files():
    try:
        out = subprocess.run(['git', '-C', ROOT, 'ls-files', '-z'], check=True, capture_output=True).stdout
        files = [f for f in out.decode('utf-8').split('\0') if f]
        if files:
            return sorted(files)
    except (OSError, subprocess.CalledProcessError):
        pass
    files = []
    for d, dirs, names in os.walk(ROOT):
        dirs[:] = sorted(x for x in dirs if x not in SKIP_DIRS)
        for n in names:
            files.append(os.path.relpath(os.path.join(d, n), ROOT).replace(os.sep, '/'))
    return sorted(files)


def category(path):
    for cat, rx in CATEGORY_RULES:
        if re.search(rx, path):
            return cat
    return 'other'


def surface(path):
    for name, rx in SURFACE_RULES:
        if re.search(rx, path):
            return name
    return 'other'


def language(path):
    base = os.path.basename(path)
    if base in ('gradlew',):
        return 'Shell'
    if path.endswith('.java.txt'):
        return 'Java (text copy)'
    return LANG_BY_EXT.get(os.path.splitext(path)[1].lower(), 'Other')


C_LIKE = {'Kotlin', 'Java', 'Rust', 'JavaScript', 'CSS', 'Gradle', 'Java (text copy)'}
HASH = {'Python', 'PowerShell', 'TOML', 'YAML', 'Properties', 'Shell'}
MARKUP = {'HTML', 'XML'}


def code_lines(lines, lang):
    """Non-blank lines that are not wholly comments."""
    n = 0
    in_block = False
    for raw in lines:
        s = raw.strip()
        if not s:
            continue
        if lang in C_LIKE:
            rest = s
            has_code = False
            while rest:
                if in_block:
                    end = rest.find('*/')
                    if end < 0:
                        rest = ''
                        break
                    rest = rest[end + 2:].strip()
                    in_block = False
                    continue
                if rest.startswith('//') and lang != 'CSS':
                    break
                if rest.startswith('/*'):
                    in_block = True
                    rest = rest[2:]
                    continue
                has_code = True
                break
            n += 1 if has_code else 0
        elif lang in MARKUP:
            rest = s
            has_code = False
            while rest:
                if in_block:
                    end = rest.find('-->')
                    if end < 0:
                        rest = ''
                        break
                    rest = rest[end + 3:].strip()
                    in_block = False
                    continue
                if rest.startswith('<!--'):
                    in_block = True
                    rest = rest[4:]
                    continue
                has_code = True
                break
            n += 1 if has_code else 0
        elif lang in HASH:
            n += 0 if s.startswith('#') else 1
        elif lang == 'Batch':
            low = s.lower()
            n += 0 if (low.startswith('rem ') or low == 'rem' or s.startswith('::')) else 1
        else:
            n += 1
    return n


def split_rust_tests(lines):
    """(non-test lines, test lines): everything from the first top-level #[cfg(test)] to the end of file is tests,
    which is how every module in rust/ is laid out."""
    for i, l in enumerate(lines):
        if l.startswith('#[cfg(test)]'):
            return lines[:i], lines[i:]
    return lines, []


def count(lines, lang):
    return {'files': 1, 'physical': len(lines), 'nonblank': sum(1 for l in lines if l.strip()),
            'code': code_lines(lines, lang)}


def add(acc, key, c):
    a = acc.setdefault(key, {'files': 0, 'physical': 0, 'nonblank': 0, 'code': 0})
    for k in a:
        a[k] += c.get(k, 0)


def main():
    as_json = '--json' in sys.argv
    by_cat_lang, by_cat, by_surface, binary = {}, {}, {}, {'files': 0, 'bytes': 0}
    per_file = []
    for path in list_files():
        full = os.path.join(ROOT, path)
        if not os.path.isfile(full):
            continue
        cat = category(path)
        if cat == 'binary' or os.path.splitext(path)[1].lower() in BINARY_EXT:
            binary['files'] += 1
            binary['bytes'] += os.path.getsize(full)
            continue
        with open(full, 'rb') as fh:
            data = fh.read()
        text = data.decode('utf-8', errors='replace').replace('\r\n', '\n')
        lines = text.split('\n')
        if lines and lines[-1] == '':
            lines = lines[:-1]
        lang = language(path)
        parts = [(cat, lines)]
        if lang == 'Rust' and cat == 'production':
            main_l, test_l = split_rust_tests(lines)
            parts = [('production', main_l)] + ([('test', test_l)] if test_l else [])
        for i, (c, ls) in enumerate(parts):
            cnt = count(ls, lang)
            if i > 0:
                cnt['files'] = 0      # the file itself is already counted once
            add(by_cat_lang, (c, lang), cnt)
            add(by_cat, c, cnt)
            if c == 'production':
                add(by_surface, surface(path), cnt)
            per_file.append({'path': path, 'category': c, 'language': lang, **cnt})

    prod = {lang: v for (c, lang), v in by_cat_lang.items() if c == 'production'}
    prod_code = sum(v['code'] for v in prod.values()) or 1
    if as_json:
        print(json.dumps({'by_category': by_cat,
                          'by_category_language': {f'{c}/{l}': v for (c, l), v in sorted(by_cat_lang.items())},
                          'production_by_surface': by_surface, 'binary': binary, 'files': per_file}, indent=1))
        return
    print('### Production code by language (ships in the APK / runs on the device)\n')
    print('| Language | Files | Physical | Non-blank | Code | Share of production code |')
    print('|---|---:|---:|---:|---:|---:|')
    for lang, v in sorted(prod.items(), key=lambda kv: -kv[1]['code']):
        print(f"| {lang} | {v['files']} | {v['physical']} | {v['nonblank']} | {v['code']} | {100.0 * v['code'] / prod_code:.1f} % |")
    t = by_cat.get('production', {})
    print(f"| **Total** | {t.get('files', 0)} | {t.get('physical', 0)} | {t.get('nonblank', 0)} | {t.get('code', 0)} | 100 % |")
    print('\n### Production code by surface\n')
    print('| Surface | Files | Code | Share |')
    print('|---|---:|---:|---:|')
    for name, _ in SURFACE_RULES:
        if name in by_surface:
            v = by_surface[name]
            print(f"| {name} | {v['files']} | {v['code']} | {100.0 * v['code'] / prod_code:.1f} % |")
    print('\n### Everything else (not production)\n')
    print('| Category | Language | Files | Physical | Code |')
    print('|---|---|---:|---:|---:|')
    for (c, lang), v in sorted(by_cat_lang.items()):
        if c != 'production':
            print(f"| {c} | {lang} | {v['files']} | {v['physical']} | {v['code']} |")
    print(f"| binary | (images/audio/fonts) | {binary['files']} | {binary['bytes']} bytes | - |")


if __name__ == '__main__':
    main()
