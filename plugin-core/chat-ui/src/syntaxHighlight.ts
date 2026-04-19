/**
 * Minimal syntax highlighter — tokenizes source code into spans for
 * keyword, string, comment, number, and punctuation highlighting.
 *
 * No external dependencies. Covers the most common languages found in
 * IDE projects: Java, Kotlin, JS/TS, Python, Go, Rust, C/C++, shell,
 * SQL, HTML/XML, CSS, JSON, YAML, TOML, and properties files.
 */

const KEYWORDS: Record<string, Set<string>> = {
    java: new Set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record sealed permits yield'.split(' ')),
    kotlin: new Set('abstract annotation as break by catch class companion const constructor continue crossinline data do else enum expect external false final finally for fun if import in infix init inline inner interface internal is lateinit noinline null object open operator out override package private protected public reified return sealed super suspend this throw true try typealias val var vararg when where while yield'.split(' ')),
    typescript: new Set('abstract as async await break case catch class const continue debugger declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new null of package private protected public readonly return set static super switch this throw true try type typeof undefined var void while with yield'.split(' ')),
    python: new Set('False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield'.split(' ')),
    go: new Set('break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var'.split(' ')),
    rust: new Set('as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while'.split(' ')),
    c: new Set('auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Bool _Complex _Imaginary'.split(' ')),
    shell: new Set('if then else elif fi case esac for while until do done in function select time coproc'.split(' ')),
    sql: new Set('select from where and or not in is null like between exists insert into values update set delete create drop alter table index view as join inner outer left right on order by group having union all distinct limit offset asc desc primary key foreign references default constraint unique check grant revoke'.split(' ')),
    css: new Set('important'.split(' ')),
};
// Reuse sets for language aliases
KEYWORDS.javascript = KEYWORDS.typescript;
KEYWORDS.tsx = KEYWORDS.typescript;
KEYWORDS.jsx = KEYWORDS.typescript;
KEYWORDS.kt = KEYWORDS.kotlin;
KEYWORDS.kts = KEYWORDS.kotlin;
KEYWORDS.py = KEYWORDS.python;
KEYWORDS.rs = KEYWORDS.rust;
KEYWORDS.cpp = KEYWORDS.c;
KEYWORDS.h = KEYWORDS.c;
KEYWORDS.sh = KEYWORDS.shell;
KEYWORDS.bash = KEYWORDS.shell;
KEYWORDS.zsh = KEYWORDS.shell;

const LANG_FOR_EXT: Record<string, string> = {
    java: 'java', kt: 'kotlin', kts: 'kotlin',
    js: 'javascript', mjs: 'javascript', cjs: 'javascript',
    ts: 'typescript', tsx: 'typescript', jsx: 'javascript',
    py: 'python', go: 'go', rs: 'rust',
    c: 'c', cpp: 'c', h: 'c', hpp: 'c',
    sh: 'shell', bash: 'shell', zsh: 'shell',
    sql: 'sql', css: 'css',
    json: 'json', yaml: 'yaml', yml: 'yaml', toml: 'toml',
    xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml',
    properties: 'properties', ini: 'properties', cfg: 'properties',
    md: 'markdown', markdown: 'markdown',
    gradle: 'kotlin', groovy: 'java',
};

/** Detect language from file path extension. */
export function detectLanguage(path: string): string {
    const dot = path.lastIndexOf('.');
    if (dot < 0) return '';
    const ext = path.substring(dot + 1).toLowerCase();
    return LANG_FOR_EXT[ext] ?? '';
}

// ── Escape helpers ──────────────────────────────────────────────────────

function esc(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function span(cls: string, text: string): string {
    return `<span class="sh-${cls}">${esc(text)}</span>`;
}

// ── Main highlighter ────────────────────────────────────────────────────

/**
 * Highlight source code, returning an HTML string with `<span class="sh-*">`
 * wrappers. Unknown languages get plain escaped output.
 */
export function highlight(code: string, lang: string): string {
    if (!lang) return esc(code);

    switch (lang) {
        case 'json': return highlightJson(code);
        case 'xml': return highlightXml(code);
        case 'yaml': case 'toml': return highlightYaml(code, lang);
        case 'properties': return highlightProperties(code);
        case 'css': return highlightCss(code);
        default: return highlightCode(code, lang);
    }
}

/** Generic C-family / scripting language highlighter. */
function highlightCode(code: string, lang: string): string {
    const kw = KEYWORDS[lang];
    const isPython = lang === 'python';
    const isShell = lang === 'shell';
    const out: string[] = [];
    let i = 0;
    const n = code.length;

    while (i < n) {
        const ch = code[i];

        // Line comment: // or #
        if ((ch === '/' && code[i + 1] === '/') || ((isPython || isShell) && ch === '#')) {
            const end = code.indexOf('\n', i);
            const slice = end < 0 ? code.substring(i) : code.substring(i, end);
            out.push(span('cmt', slice));
            i += slice.length;
            continue;
        }

        // Block comment: /* ... */
        if (ch === '/' && code[i + 1] === '*') {
            const end = code.indexOf('*/', i + 2);
            const slice = end < 0 ? code.substring(i) : code.substring(i, end + 2);
            out.push(span('cmt', slice));
            i += slice.length;
            continue;
        }

        // Strings: " ' `
        if (ch === '"' || ch === "'" || ch === '`') {
            let j = i + 1;
            while (j < n && code[j] !== ch) {
                if (code[j] === '\\') j++; // skip escaped char
                j++;
            }
            if (j < n) j++; // include closing quote
            out.push(span('str', code.substring(i, j)));
            i = j;
            continue;
        }

        // Python triple-quotes
        if (isPython && (code.substring(i, i + 3) === '"""' || code.substring(i, i + 3) === "'''")) {
            const q3 = code.substring(i, i + 3);
            let j = code.indexOf(q3, i + 3);
            j = j < 0 ? n : j + 3;
            out.push(span('str', code.substring(i, j)));
            i = j;
            continue;
        }

        // Numbers
        if (/[0-9]/.test(ch) && (i === 0 || /[\s,;()\[\]{}=+\-*/<>!&|^~%]/.test(code[i - 1]))) {
            let j = i;
            if (code[j] === '0' && (code[j + 1] === 'x' || code[j + 1] === 'X')) {
                j += 2;
                while (j < n && /[0-9a-fA-F_]/.test(code[j])) j++;
            } else {
                while (j < n && /[0-9._eE]/.test(code[j])) j++;
            }
            if (j < n && /[lLfFdDnuU]/.test(code[j])) j++;
            out.push(span('num', code.substring(i, j)));
            i = j;
            continue;
        }

        // Words (identifiers / keywords)
        if (/[a-zA-Z_$]/.test(ch)) {
            let j = i + 1;
            while (j < n && /[a-zA-Z0-9_$]/.test(code[j])) j++;
            const word = code.substring(i, j);
            if (kw?.has(word)) {
                out.push(span('kw', word));
            } else {
                out.push(esc(word));
            }
            i = j;
            continue;
        }

        // Annotations: @word
        if (ch === '@') {
            let j = i + 1;
            while (j < n && /[a-zA-Z0-9_.]/.test(code[j])) j++;
            out.push(span('attr', code.substring(i, j)));
            i = j;
            continue;
        }

        // Punctuation
        if ('(){}[]<>;:.,=+-*/%!&|^~?'.includes(ch)) {
            out.push(span('pun', ch));
            i++;
            continue;
        }

        // Default: plain character
        out.push(esc(ch));
        i++;
    }

    return out.join('');
}

// ── JSON highlighter ────────────────────────────────────────────────────

function highlightJson(code: string): string {
    return code.replace(
        /("(?:[^"\\]|\\.)*")\s*(:)?|(\btrue\b|\bfalse\b|\bnull\b)|(-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
        (m, str, colon, literal, num) => {
            if (str) return span(colon ? 'key' : 'str', str) + (colon ? esc(':') : '');
            if (literal) return span('kw', literal);
            if (num) return span('num', num);
            return esc(m);
        }
    );
}

// ── XML/HTML highlighter ────────────────────────────────────────────────

function highlightXml(code: string): string {
    return code.replace(
        /<!--[\s\S]*?-->|<\/?[\w:.-]+|\/?>|[\w:.-]+="[^"]*"|[\w:.-]+='[^']*'/g,
        (m) => {
            if (m.startsWith('<!--')) return span('cmt', m);
            if (m.startsWith('</') || m.startsWith('<')) return span('tag', m);
            if (m === '/>' || m === '>') return span('tag', m);
            const eq = m.indexOf('=');
            if (eq > 0) return span('attr', m.substring(0, eq)) + esc('=') + span('str', m.substring(eq + 1));
            return esc(m);
        }
    );
}

// ── YAML / TOML highlighter ────────────────────────────────────────────

function highlightYaml(code: string, lang: string): string {
    const lines = code.split('\n');
    return lines.map(line => {
        // Comments
        if (/^\s*#/.test(line)) return span('cmt', line);

        // Key: value or [section]
        const kv = lang === 'toml'
            ? line.match(/^(\s*\[[\w.-]+]|\s*[\w.-]+)\s*(=)(.*)/)
            : line.match(/^(\s*[\w.-]+)\s*(:)(.*)/);
        if (kv) {
            const val = kv[3].trim();
            let valHtml = esc(kv[3]);
            if (/^".*"$/.test(val) || /^'.*'$/.test(val)) valHtml = span('str', kv[3].trim());
            else if (/^-?\d/.test(val)) valHtml = span('num', kv[3].trim());
            else if (/^(true|false|null|~)$/i.test(val)) valHtml = span('kw', kv[3].trim());
            return span('key', kv[1]) + esc(kv[2]) + valHtml;
        }
        return esc(line);
    }).join('\n');
}

// ── Properties / INI highlighter ────────────────────────────────────────

function highlightProperties(code: string): string {
    return code.split('\n').map(line => {
        if (/^\s*[#!]/.test(line)) return span('cmt', line);
        const eq = line.match(/^(\s*[\w.-]+)\s*([=:])(.*)/);
        if (eq) return span('key', eq[1]) + esc(eq[2]) + span('str', eq[3]);
        return esc(line);
    }).join('\n');
}

// ── CSS highlighter ─────────────────────────────────────────────────────

function highlightCss(code: string): string {
    return code.replace(
        /\/\*[\s\S]*?\*\/|([.#]?[\w-]+)\s*\{|:\s*([\w#-]+(?:\([\w%,.\s-]*\))?)|"[^"]*"|'[^']*'/g,
        (m, selector, value) => {
            if (m.startsWith('/*')) return span('cmt', m);
            if (selector) return span('tag', selector) + esc(' {');
            if (value) return esc(': ') + span('str', value);
            if (m.startsWith('"') || m.startsWith("'")) return span('str', m);
            return esc(m);
        }
    );
}
