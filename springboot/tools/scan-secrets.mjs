// 커밋 직전 비밀 값 검사기 — "올라갈 파일"(수정 · 새 파일)에 비밀 값이 들어 있는지 본다.
//
//   node tools/scan-secrets.mjs <저장소 경로> [<저장소 경로> ...]      # 예: node tools/scan-secrets.mjs .. ../../mine_metacheck
//
// · 로컬 실제 값(config/application.properties 의 비밀번호 · DB 주소 · 사용자)을 읽어 대조한다. 값은 절대 출력하지 않는다.
// · 일반 패턴(AWS 키 · sk- 키 · Bearer 토큰 · 개인 키 · .pem · amazonaws · 비밀번호 대입 · 공인 IP 모양)도 본다.
//   `?` 는 사람이 확인할 후보(JDK 버전 번호가 IP 모양으로 걸리는 식의 오탐 포함), `!!` 는 실제 값이 들어 있어 커밋하면 안 되는 것.
// · PPTX · XLSX · DOCX(XML 안의 글자)와 PDF(pdftotext 가 있을 때)도 읽는다. 그림 파일은 못 본다.
// · AI 게이트웨이 주소 · 키는 여기에 적지 않았다(이 파일도 올라가므로). 실제 키는 위 일반 패턴과 `LLM_API_KEY` 값 대조로 잡는다 —
//   `LLM_API_KEY` 를 환경변수로 쓰는 PC 에서는 그 값도 대조한다.
// 나가는 코드: 0 = 실제 값 없음, 1 = 실제 값이 들어 있음.
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EXCLUDE = [/메타체크_중간발표_덱미리보기\.html$/];
const LOCAL_CONFIGS = [path.join(HERE, '..', 'config', 'application.properties')];

// 1) 로컬 실제 값 → 대조용 리터럴 (값은 절대 출력하지 않음)
const literals = []; // { label, value }
for (const f of LOCAL_CONFIGS) {
    if (!existsSync(f)) continue;
    for (const line of readFileSync(f, 'utf8').split(/\r?\n/)) {
        if (line.trim().startsWith('#')) continue;
        const m = line.match(/^\s*([\w.\-]+)\s*[=:]\s*(.*?)\s*$/);
        if (!m) continue;
        const [, k, v] = m;
        if (/password|secret|token|key/i.test(k) && v.length >= 4 && !v.startsWith('${')) literals.push({ label: `설정값(${k})`, value: v });
        if (/url|host/i.test(k)) {
            const h = v.match(/\/\/([^:/?]+)/);
            if (h && !/^(localhost|127\.0\.0\.1)$/.test(h[1])) literals.push({ label: `설정 주소(${k})`, value: h[1] });
        }
        if (/username|user/i.test(k) && v.length >= 6) literals.push({ label: `설정 사용자(${k})`, value: v });
    }
}
if (process.env.LLM_API_KEY && process.env.LLM_API_KEY.length >= 8) literals.push({ label: '환경변수(LLM_API_KEY)', value: process.env.LLM_API_KEY });
if (process.env.LLM_BASE_URL) {
    const h = process.env.LLM_BASE_URL.match(/\/\/([^:/?]+)/);
    if (h) literals.push({ label: '환경변수(LLM_BASE_URL) 주소', value: h[1] });
}
console.log(`대조할 로컬 실제 값 ${literals.length}개 로드 (값은 출력하지 않음)`);
if (literals.length === 0) console.log('  · 로컬 설정 파일 · 환경변수가 없어 일반 패턴만 검사합니다.');

// 2) 일반 패턴
const PATTERNS = [
    ['AWS 접근 키', /\b(AKIA|ASIA)[0-9A-Z]{16}\b/],
    ['sk- 키', /\bsk-[A-Za-z0-9_-]{16,}/],
    ['Bearer 토큰', /Bearer\s+(?!\$\{)(?!<)[A-Za-z0-9._\-]{16,}/],
    ['개인 키 블록', /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
    ['.pem 경로', /[\w\-./\\:]+\.pem\b/],
    ['amazonaws 주소', /amazonaws\.com/],
    ['비밀번호 대입', /(password|passwd|pwd|secret|api[_-]?key)\s*[=:]\s*["']?(?!\$\{|none|\*|<|your|xxx|changeme)[^\s"'`,;)]{6,}/i],
    ['공인 IP 모양', /\b(?!(?:10|127|0|255)\.)(?!192\.168\.)(?!172\.(?:1[6-9]|2\d|3[01])\.)(?:\d{1,3}\.){3}\d{1,3}\b/],
];

function* zipEntries(buf) {
    let eocd = -1;
    for (let i = buf.length - 22; i >= 0; i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) return;
    const n = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);
    for (let i = 0; i < n; i++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) break;
        const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20);
        const nl = buf.readUInt16LE(p + 28), el = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32);
        const lho = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nl);
        p += 46 + nl + el + cl;
        if (!/\.(xml|rels|txt|json)$/i.test(name)) continue;
        const ln = buf.readUInt16LE(lho + 26), le = buf.readUInt16LE(lho + 28);
        const data = buf.subarray(lho + 30 + ln + le, lho + 30 + ln + le + csize);
        try { yield [name, (method === 8 ? inflateRawSync(data) : data).toString('utf8')]; } catch { /* 읽을 수 없는 항목은 건너뜀 */ }
    }
}

function textsOf(file) {
    const ext = path.extname(file).toLowerCase();
    if (['.pptx', '.xlsx', '.docx'].includes(ext)) return [...zipEntries(readFileSync(file))].map(([n, t]) => [`${file}!${n}`, t]);
    if (ext === '.pdf') {
        try { return [[file, execFileSync('pdftotext', ['-layout', file, '-'], { encoding: 'utf8', maxBuffer: 1 << 28 })]]; }
        catch { console.log(`  ! pdftotext 를 쓸 수 없어 건너뜀: ${file}`); return []; }
    }
    if (['.png', '.jpg', '.jpeg', '.gif', '.ico', '.jar', '.class', '.svg'].includes(ext)) return [];
    return [[file, readFileSync(file, 'utf8')]];
}

const repos = process.argv.slice(2);
if (repos.length === 0) {
    console.error('사용법: node tools/scan-secrets.mjs <저장소 경로> [...]');
    process.exit(2);
}

let problems = 0;
for (const repo of repos) {
    const out = execFileSync('git', ['-C', repo, '-c', 'core.quotepath=false', 'status', '--porcelain', '-z', '--untracked-files=all'], { encoding: 'utf8' });
    const root = execFileSync('git', ['-C', repo, 'rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
    const files = [];
    for (const rec of out.split('\0')) {
        if (rec.length < 4) continue;
        const st = rec.slice(0, 2), rel = rec.slice(3);
        if (st.includes('D')) continue;
        if (EXCLUDE.some((r) => r.test(rel))) continue;
        files.push(rel);
    }
    console.log(`\n== ${root}: 검사 대상 ${files.length}개`);
    for (const rel of files) {
        const full = path.join(root, rel);
        if (!existsSync(full) || statSync(full).isDirectory()) continue;
        for (const [label, text] of textsOf(full)) {
            text.split(/\r?\n/).forEach((ln, i) => {
                for (const lit of literals) if (ln.includes(lit.value)) { problems++; console.log(`  !! ${lit.label} 가 들어 있음: ${label}:${i + 1}`); }
                for (const [name, re] of PATTERNS) {
                    const m = ln.match(re);
                    if (m) console.log(`  ?  ${name}: ${label}:${i + 1}  → ${m[0].length > 40 ? m[0].slice(0, 40) + '…' : m[0]}`);
                }
            });
        }
    }
}
console.log(problems ? `\n실제 값이 들어 있는 곳 ${problems}건 — 커밋하지 말 것` : '\n로컬 실제 값(비밀번호 · 주소 · 키)은 올라갈 파일에 하나도 없음');
process.exit(problems ? 1 : 0);
