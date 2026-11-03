#!/usr/bin/env node
// 샘플 뽑기 — 로컬 AI 서버(tools/start-local-ai.ps1)에 단원별 출제를 요청해 샘플 보고서와 사람 검토표를 만든다 (11/03).
//
//   node tools/make-samples.mjs --label haiku-sonnet --note "1·2단계 Haiku급 / 3단계 Sonnet급"
//   node tools/make-samples.mjs --label x --report-only          # 서버 호출 없이 저장된 응답으로 보고서만 다시 만든다
//   node tools/make-samples.mjs --compare samples/모델A samples/모델B   # 두 실행을 나란히 비교
//
// 하는 일: 단원 × 단계마다 POST /api/v1/ai/units/{단원}/questions 를 한 번씩 부른다(칸 = 개념 3개 × count 문항).
//   응답은 칸마다 samples/<label>/raw/ 에 바로 저장한다 — 중간에 끊겨도 --resume 으로 이어서 받아 요금을 아낀다.
//   만드는 것: samples.json(구조화) · samples.md(읽는 보고서, 노션에 붙여 넣기 가능) · review.csv(사람이 O/X 를 채우는 검토표)
// 하지 않는 것: 정답 판정. 2차 풀이는 같은 모델 계열이 푼 것이라 독립 검증이 아니다 — 정확도는 review.csv 를 사람이 채워야 나온다.
//
// 비용 안전장치: ① 호출 전에 규모를 보여 주고 y 를 받는다(--yes 로 생략) ② 127.0.0.1 이 아닌 서버는 --allow-remote 가 있어야 부른다
//   ③ 연속 2번 실패하면 멈춘다.   외부 패키지 없음 (Node 18+).
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_OUT = path.resolve(HERE, '..', 'samples');
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);
export const CIRCLED = ['①', '②', '③', '④'];
export const REVIEW_COLUMNS = ['정답맞음', '정답하나', '범위안', '단계맞음', '해설정확'];

export class UsageError extends Error {}

// ───────── 인자 ─────────
export function parseArgs(argv) {
  const o = {
    base: 'http://127.0.0.1:18080', units: ['sys-os', 'sys-net'], levels: [1, 2, 3], count: 1, label: null, note: '',
    out: DEFAULT_OUT, timeout: 300, yes: false, resume: false, reportOnly: false, allowRemote: false, compare: null, help: false,
  };
  const flags = { '--yes': 'yes', '--resume': 'resume', '--report-only': 'reportOnly', '--allow-remote': 'allowRemote', '--help': 'help', '-h': 'help' };
  const values = new Set(['--base', '--units', '--levels', '--count', '--label', '--note', '--out', '--timeout']);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a in flags) o[flags[a]] = true;
    else if (a === '--compare') {
      if (argv[i + 1] === undefined || argv[i + 2] === undefined) throw new UsageError('--compare 는 폴더 두 개가 필요합니다.');
      o.compare = [argv[++i], argv[++i]];
    } else if (values.has(a)) {
      if (argv[i + 1] === undefined) throw new UsageError(`${a} 에 값이 없습니다.`);
      const v = argv[++i];
      if (a === '--units') o.units = v.split(',').map((s) => s.trim()).filter(Boolean);
      else if (a === '--levels') o.levels = [...new Set(v.split(',').map((s) => Number(s.trim())))].sort();
      else if (a === '--count') o.count = Number(v);
      else if (a === '--timeout') o.timeout = Number(v);
      else o[a.slice(2)] = v;
    } else throw new UsageError('알 수 없는 옵션: ' + a);
  }
  if (o.help || o.compare) return o;
  if (!o.units.length || o.units.some((u) => !/^[a-z][a-z0-9-]*$/.test(u))) throw new UsageError('--units 는 단원 id 를 쉼표로 (예: sys-os,sys-net). 목록은 GET /api/v1/ai/units');
  if (!o.levels.length || o.levels.some((l) => ![1, 2, 3].includes(l))) throw new UsageError('--levels 는 1,2,3 중에서 고르세요.');
  if (!Number.isInteger(o.count) || o.count < 1 || o.count > 3) throw new UsageError('--count(칸마다 문항 수)는 1~3 입니다.');
  if (!(o.timeout > 0)) throw new UsageError('--timeout 은 양수(초)여야 합니다.');
  if (o.label === null) o.label = 'run-' + stamp().replace(/-/g, '').replace(' ', '-').replace(':', '');
  if (!/^[0-9A-Za-z가-힣_-][0-9A-Za-z가-힣._-]*$/.test(o.label)) throw new UsageError('--label 은 글자 · 숫자 · . _ - 만 쓸 수 있고 점으로 시작할 수 없습니다(출력 폴더 이름이 됩니다).');
  let url;
  try { url = new URL(o.base); } catch { throw new UsageError('--base 가 주소 모양이 아닙니다: ' + o.base); }
  if (!LOCAL_HOSTS.has(url.hostname) && !o.allowRemote) {
    throw new UsageError(`${url.hostname} 은 이 PC 가 아닙니다. 로그인 없는 서버에 요금이 붙는 호출을 보내는 것이라 막았습니다. 정말 필요하면 --allow-remote 를 붙이세요.`);
  }
  o.base = o.base.replace(/\/+$/, '');
  return o;
}

// ───────── 서버 호출 ─────────
async function http(method, url, body, timeoutSec) {
  let res;
  try {
    res = await fetch(url, { method, headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(timeoutSec * 1000) });
  } catch (e) {
    if (e?.name === 'TimeoutError') return { status: 0, ok: false, error: `${timeoutSec}초 안에 응답이 없습니다(--timeout 으로 늘릴 수 있음)` };
    return { status: 0, ok: false, error: '서버에 연결하지 못했습니다: ' + (e?.cause?.code || e?.message || e) };
  }
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 본문이 JSON 이 아님 */ }
  return { status: res.status, ok: res.ok, json, error: res.ok ? null : (json?.error || text.slice(0, 200)) };
}

async function confirm(question) {
  if (!process.stdin.isTTY) return false;
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((r) => rl.question(question, r));
  rl.close();
  return /^(y|yes|예|네|ㅇ)$/i.test(answer.trim());
}

// ───────── 응답 → 표 ─────────
const sum = (a) => a.reduce((x, y) => x + y, 0);
const len = (s) => [...String(s ?? '')].length;
const pct = (a, b) => (b ? `${Math.round((a / b) * 100)}%` : '-');
const countBy = (a) => a.reduce((m, k) => ((m[k] = (m[k] || 0) + 1), m), {});
const stamp = (d = new Date()) => { const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`; };

/** raw 응답들(성공한 것만)을 칸(cells)과 문항(items)으로 편다. 단원은 raws 에 나온 순서, 그 안에서 개념 · 단계 순. */
export function flatten(raws) {
  const cells = [];
  const items = [];
  const order = new Map();
  raws.forEach((raw) => { if (!order.has(raw.response.unit.id)) order.set(raw.response.unit.id, order.size); });
  const sortLv = (a, b) => order.get(a.unit) - order.get(b.unit) || a.conceptId.localeCompare(b.conceptId) || a.level - b.level;
  for (const raw of raws) {
    const r = raw.response;
    for (const cell of r.cells) {
      const res = cell.result;
      cells.push({
        unit: r.unit.id, unitName: r.unit.name, conceptId: cell.conceptId, conceptName: cell.conceptName, level: cell.level, status: res.status,
        requested: res.requested, passed: res.questions.length, attempts: res.attempts, skipped: res.skipped || [], exampleIds: res.exampleIds || [],
      });
      res.questions.forEach((q, i) => items.push({
        id: `${r.unit.id}.${cell.conceptId}.lv${cell.level}.${i + 1}`, unit: r.unit.id, unitName: r.unit.name, conceptId: cell.conceptId,
        conceptName: cell.conceptName, level: cell.level, n: i + 1, status: res.status, q,
      }));
    }
  }
  cells.sort(sortLv);
  items.sort((a, b) => sortLv(a, b) || a.n - b.n);
  return { cells, items };
}

/** 정답 보기 길이 ÷ 나머지 보기 평균 길이 (정답이 길면 1 보다 크다). */
export function lengthRatio(q) {
  const others = q.o.filter((_, i) => i !== q.a).map(len);
  const mean = sum(others) / (others.length || 1);
  return mean ? len(q.o[q.a]) / mean : 0;
}

export function metrics(s) {
  const { cells, items } = s;
  const attempts = cells.flatMap((c) => c.attempts);
  const errs = attempts.flatMap((a) => a.errors || []).map((e) => String(e).replace(/\s+/g, ' ').slice(0, 70));
  const verified = items.filter((i) => i.q.verify);
  const ratios = items.map((i) => lengthRatio(i.q));
  return {
    cells: cells.length,
    byStatus: countBy(cells.map((c) => c.status)),
    requested: sum(cells.map((c) => c.requested)),
    passed: items.length,
    firstTry: cells.filter((c) => c.status === 'pass').length,
    retried: cells.filter((c) => c.attempts.length > 1).length,
    avgAttemptMs: attempts.length ? Math.round(sum(attempts.map((a) => a.ms)) / attempts.length) : 0,
    topErrors: Object.entries(countBy(errs)).sort((a, b) => b[1] - a[1]).slice(0, 8),
    verifyRan: verified.length,
    verifyUnverified: verified.filter((i) => i.q.verify.verified === false).length,
    verifyStageNotOk: verified.filter((i) => i.q.verify.stageOk === false).length,
    verifyIssue: verified.filter((i) => i.q.verify.issue).length,
    answerLongest: items.filter((i) => { const l = i.q.o.map(len); return l[i.q.a] === Math.max(...l) && l.filter((x) => x === l[i.q.a]).length === 1; }).length,
    ratioOver125: ratios.filter((r) => r >= 1.25).length,
    avgRatio: ratios.length ? sum(ratios) / ratios.length : 0,
    withCode: items.filter((i) => i.q.code && String(i.q.code).trim()).length,
    mock: items.some((i) => /\[모의/.test(i.q.q + i.q.x)),
  };
}

// ───────── 보고서 ─────────
const fence = (text) => { const f = String(text).includes('```') ? '~~~~' : '```'; return `${f}\n${text}\n${f}`; };
const oneLine = (s, n) => { const t = String(s ?? '').replace(/\s+/g, ' ').trim(); return [...t].length > n ? [...t].slice(0, n).join('') + '…' : t; };

export function renderReport(s) {
  const m = metrics(s);
  const L = [];
  L.push(`# 샘플 보고서 — ${s.meta.label}`, '');
  if (m.mock) L.push('> ⚠ **가짜 모델 응답입니다(`[모의]` 표시).** 서버를 mock 모드로 띄운 결과라 파이프라인 확인용일 뿐, 문제 품질과 무관합니다.', '');
  L.push(`- 만든 때: ${s.meta.createdAt} · 서버: ${s.meta.base} · 단원: ${s.meta.units.join(', ')} · 단계: ${s.meta.levels.join(', ')} · 칸마다 ${s.meta.count}문항`);
  if (s.meta.note) L.push(`- 메모: ${s.meta.note}`);
  L.push('- 이 보고서는 서버가 낸 결과를 그대로 옮긴 것입니다. **정답이 맞는지는 사람이 확인해야 합니다** — 2차 풀이는 같은 모델 계열이 푼 것이라 독립 검증이 아닙니다. 아래 "사람 검토" 칸이나 `review.csv` 에 O / X 를 적으세요.', '');

  L.push('## 요약', '');
  L.push(`| 단원 | 개념 | 단계 | 통과/요청 | 상태 | 시도 | 쓴 예시 |`, '|---|---|---|---|---|---|---|');
  for (const c of s.cells) L.push(`| ${c.unitName} | ${c.conceptName} | ${c.level} | ${c.passed}/${c.requested} | ${c.status} | ${c.attempts.length}회 · ${sum(c.attempts.map((a) => a.ms))}ms | ${c.exampleIds.join(', ')} |`);
  if (s.failures.length) {
    L.push('', '**실패한 요청**', '');
    for (const f of s.failures) L.push(`- ${f.unit} ${f.level}단계: ${f.status ? 'HTTP ' + f.status + ' — ' : ''}${f.error}`);
  }
  L.push('', '## 검사 지표', '');
  L.push(`- 문항 통과: ${m.passed}/${m.requested} (${pct(m.passed, m.requested)}) · 한 번에 통과한 칸: ${m.firstTry}/${m.cells} (${pct(m.firstTry, m.cells)}) · 재생성이 일어난 칸: ${m.retried} · 상태: ${Object.entries(m.byStatus).map(([k, v]) => `${k} ${v}`).join(', ') || '-'}`);
  L.push(`- 시도 한 번 평균 ${m.avgAttemptMs}ms`);
  L.push(`- 2차 풀이: 실행 ${m.verifyRan}문항 · 검증 못 하고 통과 ${m.verifyUnverified} · 판정 단계가 요청과 다름 ${m.verifyStageNotOk} · 지적(issue) 있음 ${m.verifyIssue}`);
  L.push(`- 정답이 가장 긴 보기: ${m.answerLongest}/${m.passed} (우연이면 약 25%) · 정답/나머지 길이비 평균 ${m.avgRatio.toFixed(2)} · 1.25배 이상 ${m.ratioOver125}문항`);
  L.push(`- 코드·SQL·표 칸(\`code\`)을 쓴 문항: ${m.withCode}/${m.passed}`);
  if (m.topErrors.length) {
    L.push('- 재생성을 부른 검사 결과(많은 순):');
    for (const [e, n] of m.topErrors) L.push(`  - ${n}회 — ${e}`);
  }

  L.push('', '## 문항', '');
  for (const it of s.items) {
    const q = it.q;
    const cell = s.cells.find((c) => c.unit === it.unit && c.conceptId === it.conceptId && c.level === it.level);
    L.push(`### ${it.unitName} · ${it.conceptName} · ${it.level}단계 — #${it.n}  \`${it.id}\``, '');
    L.push(`상태 ${it.status} · 시도 ${cell.attempts.length}회 · 다룬 개념: ${(q.concepts || []).join(', ')} · 쓴 예시: ${cell.exampleIds.join(', ')}`, '');
    L.push(...String(q.q).split('\n').map((l) => '> ' + l), '');
    if (q.code && String(q.code).trim()) L.push(fence(q.code), '');
    q.o.forEach((o, i) => L.push(`- ${CIRCLED[i]} ${o}${i === q.a ? '  ✅ **정답**' : ''}`));
    L.push('');
    if (q.steps?.length) { L.push('**풀이 단계**', ...q.steps.map((st, i) => `${i + 1}. ${st}`), ''); }
    const dist = Object.entries(q.distractors || {}).sort((a, b) => Number(a[0]) - Number(b[0]));
    if (dist.length) { L.push('**오답이 노린 오개념**', ...dist.map(([k, v]) => `- ${CIRCLED[Number(k)]} ${v}`), ''); }
    if (q.x) L.push(`**해설** ${q.x}`, '');
    if (q.ev) L.push(`**근거** ${q.ev}`, '');
    if (q.verify) {
      const v = q.verify;
      L.push(`**2차 풀이** ${v.verified ? '검증함' : '검증 못 함(통과시킴)'}${v.stage != null ? ` · 판정 단계 ${v.stage}${v.stageOk === false ? ' (요청과 다름)' : ''}` : ''}${v.issue ? ` · 지적: ${v.issue}` : ''}${v.note ? ` · ${v.note}` : ''}`, '');
    }
    if (q.shuffleMode && q.shuffleMode !== 'random') L.push(`**보기 섞기** ${q.shuffleMode}${q.shuffleNote ? ' — ' + q.shuffleNote : ''}`, '');
    const errs = cell.attempts.flatMap((a) => (a.errors || []).map((e) => `${a.n}차: ${e}`));
    if (errs.length) L.push('**이 칸에서 걸린 검사**', ...errs.map((e) => `- ${e}`), '');
    L.push('**사람 검토**', '- [ ] 정답이 맞다  - [ ] 정답이 하나뿐이다  - [ ] 시험 범위 안이다  - [ ] 단계에 맞다  - [ ] 해설이 정확하다', '', '---', '');
  }
  return L.join('\n');
}

// ───────── 검토표 (CSV) ─────────
const csvCell = (v) => { const t = String(v ?? ''); return /[",\n\r]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t; };

export function renderReviewCsv(s) {
  const head = ['id', '단원', '개념', '단계', '상태', '지문(앞부분)', ...REVIEW_COLUMNS, '메모'];
  const rows = s.items.map((i) => [i.id, i.unitName, i.conceptName, i.level, i.status, oneLine(i.q.q, 40), ...REVIEW_COLUMNS.map(() => ''), '']);
  return '﻿' + [head, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  const t = text.replace(/^﻿/, '');
  for (let i = 0; i < t.length; i++) {
    const ch = t[i];
    if (quoted) {
      if (ch === '"' && t[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') quoted = false; else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(cell); cell = ''; } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && t[i + 1] === '\n') i++;
      row.push(cell); cell = ''; rows.push(row); row = [];
    } else cell += ch;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  const [head, ...body] = rows.filter((r) => r.some((c) => c !== ''));
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h, r[i] ?? ''])));
}

const YES = /^(o|y|yes|1|예|네|ㅇ|맞음|○)$/i;
const NO = /^(x|n|no|0|아니오|아니요|틀림|×)$/i;

/** 검토표에서 칸별 O/X 를 센다. 비워 둔 칸은 세지 않는다. */
export function tallyReview(rows) {
  const out = {};
  for (const col of REVIEW_COLUMNS) {
    const vals = rows.map((r) => String(r[col] ?? '').trim()).filter(Boolean);
    out[col] = { yes: vals.filter((v) => YES.test(v)).length, no: vals.filter((v) => NO.test(v)).length, total: rows.length };
  }
  return out;
}

// ───────── 두 실행 비교 ─────────
export function renderCompare(a, b, reviewA, reviewB) {
  const ma = metrics(a);
  const mb = metrics(b);
  const L = [`# 모델 비교 — ${a.meta.label} vs ${b.meta.label}`, ''];
  if (ma.mock || mb.mock) L.push('> ⚠ 둘 중 하나 이상이 **가짜 모델 응답**입니다. 비교 결과를 믿지 마세요.', '');
  L.push(`- A: ${a.meta.label} (${a.meta.createdAt}) ${a.meta.note ? '— ' + a.meta.note : ''}`, `- B: ${b.meta.label} (${b.meta.createdAt}) ${b.meta.note ? '— ' + b.meta.note : ''}`);
  L.push('- **같은 칸(단원 × 개념 × 단계)에 같은 요청을 보냈지만 예시 고르기가 무작위라 프롬프트가 글자까지 같지는 않습니다.** 문항 수가 적으면 차이가 우연일 수 있습니다.', '');
  const row = (name, f) => `| ${name} | ${f(ma)} | ${f(mb)} |`;
  L.push('## 자동 지표', '', '| 지표 | A | B |', '|---|---|---|');
  L.push(row('문항 통과', (m) => `${m.passed}/${m.requested} (${pct(m.passed, m.requested)})`));
  L.push(row('한 번에 통과한 칸', (m) => `${m.firstTry}/${m.cells} (${pct(m.firstTry, m.cells)})`));
  L.push(row('재생성이 일어난 칸', (m) => m.retried));
  L.push(row('시도 한 번 평균(ms)', (m) => m.avgAttemptMs));
  L.push(row('2차 풀이가 못 한 문항', (m) => `${m.verifyUnverified}/${m.verifyRan}`));
  L.push(row('2차 풀이 판정 단계 불일치', (m) => `${m.verifyStageNotOk}/${m.verifyRan}`));
  L.push(row('2차 풀이 지적', (m) => `${m.verifyIssue}/${m.verifyRan}`));
  L.push(row('정답이 가장 긴 보기', (m) => `${m.answerLongest}/${m.passed} (${pct(m.answerLongest, m.passed)})`));
  L.push(row('정답/나머지 길이비 평균', (m) => m.avgRatio.toFixed(2)));
  L.push(row('code 칸을 쓴 문항', (m) => `${m.withCode}/${m.passed}`));
  L.push('', '## 칸별 상태', '', '| 단원 | 개념 | 단계 | A | B |', '|---|---|---|---|---|');
  const keys = new Map();
  for (const c of [...a.cells, ...b.cells]) keys.set(`${c.unit}|${c.conceptId}|${c.level}`, c);
  const find = (s, c) => s.cells.find((x) => x.unit === c.unit && x.conceptId === c.conceptId && x.level === c.level);
  for (const c of keys.values()) {   // A 에 나온 순서, B 에만 있는 칸은 뒤에
    const fa = find(a, c);
    const fb = find(b, c);
    L.push(`| ${c.unitName} | ${c.conceptName} | ${c.level} | ${fa ? `${fa.status} (${fa.passed}/${fa.requested})` : '-'} | ${fb ? `${fb.status} (${fb.passed}/${fb.requested})` : '-'} |`);
  }
  L.push('', '## 사람 검토 결과 (review.csv 에 O / X 를 적은 만큼)', '');
  if (!reviewA || !reviewB) {
    L.push('검토표를 읽지 못했습니다(두 폴더 모두 `review.csv` 가 있어야 합니다).');
  } else {
    const ta = tallyReview(reviewA);
    const tb = tallyReview(reviewB);
    L.push('| 항목 | A: O / 답한 수 / 전체 | B: O / 답한 수 / 전체 |', '|---|---|---|');
    for (const col of REVIEW_COLUMNS) {
      const f = (t) => `${t[col].yes} / ${t[col].yes + t[col].no} / ${t[col].total}${t[col].yes + t[col].no ? ` (${pct(t[col].yes, t[col].yes + t[col].no)})` : ''}`;
      L.push(`| ${col} | ${f(ta)} | ${f(tb)} |`);
    }
    L.push('', '"답한 수"가 전체보다 적으면 아직 안 채운 문항이 있다는 뜻입니다. **정확도는 `정답맞음` 이 기준입니다**(문서의 "정확도가 높은 쪽을 고른다").');
  }
  return L.join('\n') + '\n';
}

// ───────── 파일 ─────────
const rawName = (unit, level) => `${unit}-lv${level}.json`;
const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^﻿/, ''));

function readRaws(rawDir) {
  if (!fs.existsSync(rawDir)) return [];
  return fs.readdirSync(rawDir).filter((f) => f.endsWith('.json')).sort().map((f) => readJson(path.join(rawDir, f)));
}

function resolveRunDir(arg) {
  if (fs.existsSync(path.join(arg, 'samples.json'))) return arg;
  const viaOut = path.join(DEFAULT_OUT, arg);
  if (fs.existsSync(path.join(viaOut, 'samples.json'))) return viaOut;
  throw new UsageError(`${arg} 에서 samples.json 을 찾지 못했습니다.`);
}

/** raw 응답들로 samples.json 내용을 만든다. */
export function buildSamples(meta, raws) {
  const ok = raws.filter((r) => r.ok);
  return { meta, ...flatten(ok), failures: raws.filter((r) => !r.ok).map((r) => ({ unit: r.unit, level: r.level, status: r.status, error: r.error })) };
}

// ───────── 실행 ─────────
const USAGE = `사용법
  node tools/make-samples.mjs [--label 이름] [--note "쓴 모델"] [--units sys-os,sys-net] [--levels 1,2,3] [--count 1]
                              [--base http://127.0.0.1:18080] [--out 폴더] [--timeout 300] [--yes] [--resume]
  node tools/make-samples.mjs --label 이름 --report-only        서버 호출 없이 저장된 응답으로 보고서만 다시 만들기
  node tools/make-samples.mjs --compare <폴더A> <폴더B>          두 실행 비교 (폴더 또는 samples/ 아래 label)
먼저 다른 창에서 서버를 띄우세요:  .\\tools\\start-local-ai.ps1  (실제 Bedrock 은 -Mode bedrock ...)`;

export async function main(argv) {
  const o = parseArgs(argv);
  if (o.help) { console.log(USAGE); return 0; }

  if (o.compare) {
    const dirs = o.compare.map(resolveRunDir);
    const [a, b] = dirs.map((d) => readJson(path.join(d, 'samples.json')));
    const rev = dirs.map((d) => (fs.existsSync(path.join(d, 'review.csv')) ? parseCsv(fs.readFileSync(path.join(d, 'review.csv'), 'utf8')) : null));
    const file = path.join(o.out, `compare-${a.meta.label}-vs-${b.meta.label}.md`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, renderCompare(a, b, rev[0], rev[1]), 'utf8');
    console.log('비교 보고서:', file);
    return 0;
  }

  const runDir = path.join(o.out, o.label);
  const rawDir = path.join(runDir, 'raw');
  const failures = [];

  if (!o.reportOnly) {
    // 단원 확인 — 서버가 떠 있는지, 단원 id 가 맞는지를 요금이 들기 전에 본다
    const unitInfo = {};
    for (const u of o.units) {
      const r = await http('GET', `${o.base}/api/v1/ai/units/${u}`, null, 15);
      if (!r.ok) {
        console.error(r.status === 404 ? `단원을 찾을 수 없습니다: ${u}` : `${o.base} 에 닿지 않습니다 — ${r.error}\n서버를 먼저 띄우세요: .\\tools\\start-local-ai.ps1`);
        return 1;
      }
      unitInfo[u] = r.json;
    }
    const cellsN = sum(o.units.map((u) => unitInfo[u].concepts.length)) * o.levels.length;
    const questionsN = cellsN * o.count;
    console.log(`\n라벨 ${o.label} · 서버 ${o.base}\n단원 ${o.units.join(', ')} × 단계 ${o.levels.join(', ')} × 칸마다 ${o.count}문항`);
    console.log(`→ 요청 ${o.units.length * o.levels.length}번 · 칸 ${cellsN}개 · 문항 ${questionsN}개`);
    console.log(`→ 모델 호출 최소 약 ${cellsN + questionsN}회(생성 ${cellsN} + 2차 풀이 ${questionsN}). 검사에 걸려 재생성하면 그 칸은 2배까지 늘어납니다.`);
    if (!o.yes && !(await confirm('실제 Bedrock 모드면 이 호출 수만큼 요금이 듭니다(mock 이면 무료). 진행할까요? (y/N) '))) {
      console.log(process.stdin.isTTY ? '취소했습니다.' : '대화형 터미널이 아니라서 확인을 받을 수 없습니다. --yes 를 붙이세요.');
      return 2;
    }

    fs.mkdirSync(rawDir, { recursive: true });
    let consecutive = 0;
    for (const unit of o.units) {
      for (const level of o.levels) {
        const file = path.join(rawDir, rawName(unit, level));
        if (o.resume && fs.existsSync(file) && readJson(file).ok) { console.log(`· ${unit} ${level}단계: 이미 있음(--resume), 건너뜀`); continue; }
        const t0 = Date.now();
        process.stdout.write(`· ${unit} ${level}단계 요청 중… `);
        const r = await http('POST', `${o.base}/api/v1/ai/units/${unit}/questions`, { levels: [level], count: o.count }, o.timeout);
        const ms = Date.now() - t0;
        if (r.ok && r.json?.cells) {
          fs.writeFileSync(file, JSON.stringify({ ok: true, unit, level, count: o.count, at: new Date().toISOString(), ms, response: r.json }, null, 1), 'utf8');
          console.log(`${r.json.passed}/${r.json.requested}문항 통과 (${(ms / 1000).toFixed(1)}초)`);
          consecutive = 0;
        } else {
          fs.writeFileSync(file, JSON.stringify({ ok: false, unit, level, at: new Date().toISOString(), status: r.status, error: r.error }, null, 1), 'utf8');
          console.log(`실패 — ${r.status ? 'HTTP ' + r.status + ' ' : ''}${r.error}`);
          failures.push(`${unit}-${level}`);
          if (++consecutive >= 2) { console.error('연속 2번 실패해서 멈춥니다. 서버 창의 로그와 AWS 설정(권한 · 모델 ID · 리전)을 확인하세요. 받은 것만으로 보고서를 만듭니다.'); break; }
        }
      }
      if (consecutive >= 2) break;
    }
  }

  const idx = (u) => (o.units.includes(u) ? o.units.indexOf(u) : o.units.length);
  const raws = readRaws(rawDir).sort((a, b) => idx(a.unit) - idx(b.unit) || a.unit.localeCompare(b.unit) || a.level - b.level);
  if (!raws.length) { console.error('저장된 응답이 없어 보고서를 만들 수 없습니다.'); return 1; }
  const prev = fs.existsSync(path.join(runDir, 'samples.json')) ? readJson(path.join(runDir, 'samples.json')).meta : null;
  const meta = {
    label: o.label, note: o.note || prev?.note || '', base: o.base, createdAt: prev?.createdAt || stamp(),
    units: [...new Set(raws.map((r) => r.unit))], levels: [...new Set(raws.map((r) => r.level))].sort(), count: raws.find((r) => r.count)?.count ?? o.count,
  };
  const samples = buildSamples(meta, raws);
  fs.writeFileSync(path.join(runDir, 'samples.json'), JSON.stringify(samples, null, 1), 'utf8');
  fs.writeFileSync(path.join(runDir, 'samples.md'), renderReport(samples), 'utf8');
  const csv = path.join(runDir, 'review.csv');
  const keptReview = fs.existsSync(csv);
  if (!keptReview) fs.writeFileSync(csv, renderReviewCsv(samples), 'utf8');   // 사람이 채운 검토표는 덮어쓰지 않는다

  const m = metrics(samples);
  console.log(`\n== ${o.label} == 문항 ${m.passed}/${m.requested} 통과 · 한 번에 통과한 칸 ${m.firstTry}/${m.cells} · 재생성 ${m.retried}칸 · 평균 ${m.avgAttemptMs}ms/시도`);
  if (m.mock) console.log('⚠ 가짜 모델 응답입니다([모의]). 품질 판단에 쓰지 마세요.');
  console.log('보고서:', path.join(runDir, 'samples.md'));
  console.log(keptReview ? '검토표: 기존 review.csv 를 그대로 두었습니다(채워 둔 내용 보호)' : '검토표: ' + csv + '  ← 정답맞음 등을 O/X 로 채우세요');
  return failures.length ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then((code) => process.exit(code), (e) => {
    if (e instanceof UsageError) { console.error(e.message + '\n\n' + USAGE); process.exit(2); }
    console.error(e);
    process.exit(1);
  });
}
