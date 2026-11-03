// make-samples.mjs 검증 — 가짜 HTTP 서버로 돌리므로 JVM · AWS · 요금이 필요 없다.
//   node tools/test-make-samples.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { CIRCLED, REVIEW_COLUMNS, UsageError, lengthRatio, main, metrics, parseArgs, parseCsv, renderCompare, renderReport, renderReviewCsv, tallyReview } from './make-samples.mjs';

try { process.stdin.isTTY = false; } catch { /* 터미널에서 직접 돌려도 확인 질문에서 멈추지 않게 */ }
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'make-samples-'));
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const csvEsc = (v) => (/[",\n]/.test(v) ? '"' + String(v).replace(/"/g, '""') + '"' : v);
const writeCsv = (file, rows) => { const head = Object.keys(rows[0]); fs.writeFileSync(file, '﻿' + [head, ...rows.map((r) => head.map((h) => r[h]))].map((r) => r.map(csvEsc).join(',')).join('\r\n') + '\r\n', 'utf8'); };

// ───────── 가짜 서버 ─────────
const UNITS = { 'sys-os': '운영체제 기초 활용', 'sys-net': '네트워크 기초 활용' };
const CONCEPTS = [{ id: 'c1', name: '개념하나' }, { id: 'c2', name: '개념둘' }, { id: 'c3', name: '개념셋' }];

function question(lv, { mock = false, longAnswer = false, code = null, issue = null } = {}) {
  const base = mock ? '[모의] ' : '';
  const o = [`${base}보기 가나다라마바사`, `${base}보기 아자차카타파하`, `${base}보기 하나둘셋넷다섯`, `${base}보기 일이삼사오육칠`];
  if (longAnswer) o[1] += ' 그리고 아주아주 길게 덧붙인 설명이 이어집니다 이어집니다';
  return {
    c: 'c1', lv, concepts: ['개념하나'], steps: ['풀이 첫째 단계입니다', '풀이 둘째 단계입니다'], q: `${base}다음 중 옳은 것은? "쉼표, 따옴표" 포함\n둘째 줄`, code,
    o, a: 1, distractors: { 0: '오개념 가', 2: '오개념 다', 3: '오개념 라' }, x: '해설입니다', ev: '근거입니다', shuffleMode: 'random', shuffleNote: null,
    verify: { verified: true, stage: lv, stageOk: true, issue, note: null },
  };
}

function response(unit, level, opts = {}) {
  return {
    exam: '정보처리산업기사', unit: { id: unit, name: UNITS[unit], concepts: CONCEPTS }, requested: 3, passed: 3,
    cells: CONCEPTS.map((c, i) => ({
      conceptId: c.id, conceptName: c.name, level,
      result: {
        status: opts.retryCell === i ? 'pass-after-retry' : 'pass', requested: 1, questions: [question(level, opts)],
        attempts: opts.retryCell === i ? [{ n: 1, ms: 100, errors: ['정답 보기가 가장 깁니다(1.4배)'] }, { n: 2, ms: 120, errors: [] }] : [{ n: 1, ms: 80, errors: [] }],
        skipped: [], exampleIds: [`C${level}-a`, `C${level}-b`],
      },
    })),
  };
}

const server = { posts: [], failUnit: null, opts: {} };
const srv = http.createServer((req, res) => {
  const m = req.url.match(/^\/api\/v1\/ai\/units\/([\w-]+)(\/questions)?$/);
  const send = (code, body) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(body)); };
  if (!m || !UNITS[m[1]]) return send(404, { error: '단원을 찾을 수 없습니다: ' + (m ? m[1] : req.url) });
  if (req.method === 'GET') return send(200, { id: m[1], name: UNITS[m[1]], concepts: CONCEPTS });
  let body = '';
  req.on('data', (d) => (body += d));
  req.on('end', () => {
    const r = JSON.parse(body);
    server.posts.push({ unit: m[1], ...r });
    if (server.failUnit === m[1]) return send(502, { error: 'AI 호출에 실패했습니다. 잠시 뒤 다시 시도해 주세요.' });
    send(200, response(m[1], r.levels[0], server.opts));
  });
});
await new Promise((r) => srv.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${srv.address().port}`;

// main 의 출력을 모아서 돌려준다
async function run(argv) {
  const lines = [];
  const orig = [console.log, console.error];
  console.log = (...a) => lines.push(a.join(' '));
  console.error = (...a) => lines.push(a.join(' '));
  try {
    return { code: await main(argv), out: lines.join('\n') };
  } catch (e) {
    if (e instanceof UsageError) return { code: 2, out: lines.concat(e.message).join('\n') };   // 실제 실행에서는 맨 아래 래퍼가 하는 일
    throw e;
  } finally { [console.log, console.error] = orig; }
}
const common = (label) => ['--base', BASE, '--out', tmp, '--label', label];

// ───────── 1) 인자 ─────────
{
  const d = parseArgs([]);
  assert.deepEqual(d.units, ['sys-os', 'sys-net']);
  assert.deepEqual(d.levels, [1, 2, 3]);
  assert.equal(d.count, 1);
  assert.match(d.label, /^run-\d{8}-\d{4}$/);
  assert.deepEqual(parseArgs(['--levels', '3,1,3']).levels, [1, 3]);
  // 출력 폴더 이름이 되는 --label 은 폴더 밖으로 못 나가게 (.. · / · \ · 점으로 시작)
  for (const bad of [['--units', 'SYS OS'], ['--levels', '4'], ['--levels', '0'], ['--count', '0'], ['--count', '4'], ['--count', 'x'], ['--timeout', '0'],
    ['--label', 'a/b'], ['--label', 'a\\b'], ['--label', '..'], ['--label', '.'], ['--label', '.hidden'], ['--nope'], ['--note']]) {
    assert.throws(() => parseArgs(bad), UsageError, bad.join(' '));
  }
  assert.equal(parseArgs(['--label', 'haiku-sonnet_2.1']).label, 'haiku-sonnet_2.1');
  assert.equal(parseArgs(['--label', '모델A']).label, '모델A');
  assert.throws(() => parseArgs(['--compare', 'a']), UsageError);
  assert.deepEqual(parseArgs(['--compare', 'a', 'b']).compare, ['a', 'b']);
  // 요금이 붙는 서버를 실수로 부르지 않게
  assert.throws(() => parseArgs(['--base', 'http://example.com:8080']), /이 PC 가 아닙니다/);
  assert.throws(() => parseArgs(['--base', 'http://10.0.0.5:18080']), /이 PC 가 아닙니다/);
  assert.equal(parseArgs(['--base', 'http://example.com:8080', '--allow-remote']).base, 'http://example.com:8080');
  assert.equal(parseArgs(['--base', 'http://localhost:18080/']).base, 'http://localhost:18080');
  console.log('OK 1: 인자 검사 · 원격 서버 차단');
}

// ───────── 2) 확인 절차 — 대화형이 아니면 --yes 없이는 호출하지 않는다 ─────────
{
  const r = await run(common('no-yes'));
  assert.equal(r.code, 2);
  assert.match(r.out, /--yes/);
  assert.equal(server.posts.length, 0, '확인 전에는 POST 가 없다');
  assert.ok(!fs.existsSync(path.join(tmp, 'no-yes', 'samples.md')));
  assert.match(r.out, /요청 6번 · 칸 18개 · 문항 18개/);
  assert.match(r.out, /최소 약 36회/);
  console.log('OK 2: 확인 없이 호출하지 않고, 호출 규모를 먼저 보여 준다');
}

// ───────── 3) 정상 실행 ─────────
const dirA = path.join(tmp, 'A');
{
  const r = await run([...common('A'), '--note', '모델A', '--yes']);
  assert.equal(r.code, 0, r.out);
  assert.equal(server.posts.length, 6);
  assert.deepEqual(server.posts[0], { unit: 'sys-os', levels: [1], count: 1 });
  assert.deepEqual(server.posts.map((p) => p.unit + p.levels[0]), ['sys-os1', 'sys-os2', 'sys-os3', 'sys-net1', 'sys-net2', 'sys-net3']);
  for (const f of ['samples.json', 'samples.md', 'review.csv']) assert.ok(fs.existsSync(path.join(dirA, f)), f);
  assert.equal(fs.readdirSync(path.join(dirA, 'raw')).length, 6);
  const md = fs.readFileSync(path.join(dirA, 'samples.md'), 'utf8');
  assert.match(md, /^# 샘플 보고서 — A/);
  assert.match(md, /메모: 모델A/);
  assert.ok(md.indexOf('운영체제 기초 활용') < md.indexOf('네트워크 기초 활용'), '단원은 --units 순서(운영체제가 먼저)');
  assert.ok(!md.includes('가짜 모델 응답입니다'), '실제 응답에는 가짜 모델 경고가 없다');
  assert.match(md, /pass-after-retry|pass/);
  assert.match(md, /✅ \*\*정답\*\*/);
  assert.equal((md.match(/✅/g) || []).length, 18, '정답 표시는 문항마다 하나');
  assert.match(md, /- ② .*✅/, '정답은 a=1 → ②');
  assert.match(md, /\*\*오답이 노린 오개념\*\*\n- ① 오개념 가\n- ③ 오개념 다\n- ④ 오개념 라/);
  const s = JSON.parse(fs.readFileSync(path.join(dirA, 'samples.json'), 'utf8'));
  assert.equal(s.items.length, 18);
  assert.equal(s.items[0].id, 'sys-os.c1.lv1.1');
  assert.deepEqual(s.meta.units, ['sys-os', 'sys-net']);
  console.log('OK 3: 6번 요청 → raw 6개 · samples.json · samples.md · review.csv');
}

// ───────── 4) 검토표 — CSV 왕복, 채워 둔 검토표는 덮어쓰지 않는다 ─────────
{
  const csvPath = path.join(dirA, 'review.csv');
  const raw = fs.readFileSync(csvPath);
  assert.deepEqual([...raw.subarray(0, 3)], [0xef, 0xbb, 0xbf], 'Excel 에서 한글이 깨지지 않게 BOM');
  const rows = parseCsv(raw.toString('utf8'));
  assert.equal(rows.length, 18);
  assert.deepEqual(Object.keys(rows[0]), ['id', '단원', '개념', '단계', '상태', '지문(앞부분)', ...REVIEW_COLUMNS, '메모']);
  assert.match(rows[0]['지문(앞부분)'], /쉼표, 따옴표/, '쉼표 · 따옴표가 든 칸도 한 칸으로');
  // 사람이 채운 척: 앞 10개 O, 다음 4개 X, 나머지는 비움 + 메모에 쉼표 · 줄바꿈
  writeCsv(csvPath, rows.map((r, i) => ({ ...r, 정답맞음: i < 10 ? 'O' : i < 14 ? 'x' : '', 메모: i === 0 ? '쉼표, 있고\n줄바꿈도' : '' })));
  const before = sha(csvPath);
  const t = tallyReview(parseCsv(fs.readFileSync(csvPath, 'utf8')));
  assert.deepEqual(t['정답맞음'], { yes: 10, no: 4, total: 18 });
  assert.deepEqual(t['범위안'], { yes: 0, no: 0, total: 18 });
  assert.equal(parseCsv(fs.readFileSync(csvPath, 'utf8'))[0].메모, '쉼표, 있고\n줄바꿈도');

  const r = await run([...common('A'), '--report-only']);
  assert.equal(r.code, 0, r.out);
  assert.equal(sha(csvPath), before, '다시 만들어도 채운 검토표는 그대로');
  assert.match(r.out, /기존 review\.csv 를 그대로/);
  assert.equal(server.posts.length, 6, '--report-only 는 서버를 부르지 않는다');
  console.log('OK 4: CSV 왕복 · 채운 검토표 보호 · --report-only 는 호출 없음');
}

// ───────── 5) 이어받기 ─────────
{
  const r = await run([...common('A'), '--resume', '--yes']);
  assert.equal(r.code, 0, r.out);
  assert.equal(server.posts.length, 6, '이미 받은 6개는 다시 부르지 않는다');
  assert.equal((r.out.match(/이미 있음/g) || []).length, 6);
  fs.rmSync(path.join(dirA, 'raw', 'sys-net-lv2.json'));
  const r2 = await run([...common('A'), '--resume', '--yes']);
  assert.equal(r2.code, 0, r2.out);
  assert.equal(server.posts.length, 7, '지워진 하나만 다시 받는다');
  assert.deepEqual(server.posts.at(-1), { unit: 'sys-net', levels: [2], count: 1 });
  console.log('OK 5: --resume 은 받은 칸을 건너뛰고 빠진 것만 받는다');
}

// ───────── 6) 실패 — 연속 2번이면 멈추고, 받은 것만으로 보고서를 만든다 ─────────
{
  const before = server.posts.length;
  server.failUnit = 'sys-net';
  const r = await run([...common('F'), '--yes']);
  server.failUnit = null;
  assert.equal(r.code, 1);
  const calls = server.posts.slice(before).map((p) => p.unit + p.levels[0]);
  assert.deepEqual(calls, ['sys-os1', 'sys-os2', 'sys-os3', 'sys-net1', 'sys-net2'], '연속 2번 실패하면 sys-net 3단계는 부르지 않는다');
  assert.match(r.out, /연속 2번 실패/);
  const md = fs.readFileSync(path.join(tmp, 'F', 'samples.md'), 'utf8');
  assert.match(md, /\*\*실패한 요청\*\*/);
  assert.match(md, /sys-net 1단계: HTTP 502/);
  assert.equal(JSON.parse(fs.readFileSync(path.join(tmp, 'F', 'samples.json'), 'utf8')).items.length, 9, '성공한 운영체제 9문항은 보고서에 있다');
  // 실패한 칸은 --resume 때 다시 시도한다
  const b2 = server.posts.length;
  const r2 = await run([...common('F'), '--resume', '--yes']);
  assert.equal(r2.code, 0, r2.out);
  assert.deepEqual(server.posts.slice(b2).map((p) => p.unit + p.levels[0]), ['sys-net1', 'sys-net2', 'sys-net3']);
  console.log('OK 6: 연속 실패에서 멈춤 · 부분 보고서 · 실패한 칸만 재시도');
}

// ───────── 7) 서버가 없거나 단원이 틀렸을 때 — 호출 전에 알려 준다 ─────────
{
  const dead = http.createServer(); await new Promise((r) => dead.listen(0, '127.0.0.1', r));
  const deadPort = dead.address().port; await new Promise((r) => dead.close(r));
  const before = server.posts.length;
  const r1 = await run(['--base', `http://127.0.0.1:${deadPort}`, '--out', tmp, '--label', 'dead', '--yes']);
  assert.equal(r1.code, 1);
  assert.match(r1.out, /닿지 않습니다/);
  assert.match(r1.out, /start-local-ai\.ps1/);
  const r2 = await run([...common('bad-unit'), '--units', 'sys-os,nope', '--yes']);
  assert.equal(r2.code, 1);
  assert.match(r2.out, /단원을 찾을 수 없습니다: nope/);
  assert.equal(server.posts.length, before, '단원 확인 단계에서 끝나 요금이 드는 호출은 없다');
  console.log('OK 7: 서버 없음 · 단원 틀림은 호출 전에 알려 줌');
}

// ───────── 8) 지표 ─────────
{
  const q = question(1, { longAnswer: true });
  const ratio = lengthRatio(q);
  assert.ok(ratio > 4 && ratio < 4.5, 'ratio ' + ratio);   // 정답 42자 ÷ 나머지 10자
  assert.equal(lengthRatio(question(1)), 1);
  const s = JSON.parse(fs.readFileSync(path.join(dirA, 'samples.json'), 'utf8'));
  const m = metrics(s);
  assert.equal(m.passed, 18); assert.equal(m.requested, 18); assert.equal(m.firstTry, 18); assert.equal(m.retried, 0);
  assert.equal(m.answerLongest, 0); assert.equal(m.withCode, 0); assert.equal(m.verifyRan, 18); assert.equal(m.mock, false);
  server.opts = { retryCell: 1, longAnswer: true, code: 'SELECT 1;\n```', issue: '보기 둘이 비슷함' };
  const r = await run([...common('B'), '--units', 'sys-os', '--note', '모델B', '--yes']);
  server.opts = {};
  assert.equal(r.code, 0, r.out);
  const sb = JSON.parse(fs.readFileSync(path.join(tmp, 'B', 'samples.json'), 'utf8'));
  const mb = metrics(sb);
  assert.equal(mb.cells, 9); assert.equal(mb.retried, 3); assert.equal(mb.firstTry, 6);
  assert.equal(mb.answerLongest, 9); assert.equal(mb.ratioOver125, 9); assert.equal(mb.withCode, 9); assert.equal(mb.verifyIssue, 9);
  assert.deepEqual(mb.topErrors, [['정답 보기가 가장 깁니다(1.4배)', 3]]);
  const md = fs.readFileSync(path.join(tmp, 'B', 'samples.md'), 'utf8');
  assert.match(md, /~~~~\nSELECT 1;\n```\n~~~~/, '코드에 ``` 가 있으면 ~~~~ 울타리');
  assert.match(md, /지적: 보기 둘이 비슷함/);
  assert.match(md, /재생성을 부른 검사 결과/);
  console.log('OK 8: 길이비 · 정답이 가장 긴 보기 · 재생성 · 2차 풀이 지적 · 코드 울타리');
}

// ───────── 9) 가짜 모델 경고 ─────────
{
  server.opts = { mock: true };
  const r = await run([...common('M'), '--units', 'sys-os', '--levels', '1', '--yes']);
  server.opts = {};
  assert.equal(r.code, 0, r.out);
  assert.match(fs.readFileSync(path.join(tmp, 'M', 'samples.md'), 'utf8'), /가짜 모델 응답입니다/);
  assert.match(r.out, /가짜 모델 응답입니다/);
  console.log('OK 9: [모의] 응답은 보고서와 콘솔에 가짜라고 표시');
}

// ───────── 10) 비교 ─────────
{
  // B 에도 검토표를 채운다: 정답맞음 O 4 / X 5
  const csvB = path.join(tmp, 'B', 'review.csv');
  writeCsv(csvB, parseCsv(fs.readFileSync(csvB, 'utf8')).map((r, i) => ({ ...r, 정답맞음: i < 4 ? 'O' : 'X' })));
  const r = await run(['--out', tmp, '--compare', 'no-such-run-A', 'no-such-run-B']);
  assert.equal(r.code, 2);
  assert.match(r.out, /samples\.json 을 찾지 못했습니다/);
  const r2 = await run(['--out', tmp, '--compare', path.join(tmp, 'A'), path.join(tmp, 'B')]);
  assert.equal(r2.code, 0, r2.out);
  const md = fs.readFileSync(path.join(tmp, 'compare-A-vs-B.md'), 'utf8');
  assert.match(md, /# 모델 비교 — A vs B/);
  assert.match(md, /\| 한 번에 통과한 칸 \| 18\/18 \(100%\) \| 6\/9 \(67%\) \|/);
  assert.match(md, /\| 정답이 가장 긴 보기 \| 0\/18 \(0%\) \| 9\/9 \(100%\) \|/);
  assert.match(md, /\| 정답맞음 \| 10 \/ 14 \/ 18 \(71%\) \| 4 \/ 9 \/ 9 \(44%\) \|/);
  assert.match(md, /같은 요청을 보냈지만 예시 고르기가 무작위/);
  assert.match(md, /\| 네트워크 기초 활용 \| 개념하나 \| 1 \| pass \(1\/1\) \| - \|/, 'B 에 없는 칸은 -');
  // 검토표가 없으면 안내
  fs.rmSync(path.join(tmp, 'B', 'review.csv'));
  const r3 = await run(['--out', tmp, '--compare', path.join(tmp, 'A'), path.join(tmp, 'B')]);
  assert.equal(r3.code, 0);
  assert.match(fs.readFileSync(path.join(tmp, 'compare-A-vs-B.md'), 'utf8'), /검토표를 읽지 못했습니다/);
  console.log('OK 10: 두 실행 비교 — 자동 지표 · 칸별 상태 · 사람 검토 정확도');
}

// ───────── 11) 보고서 형식 ─────────
{
  const s = JSON.parse(fs.readFileSync(path.join(dirA, 'samples.json'), 'utf8'));
  assert.equal(CIRCLED.length, 4);
  const md = renderReport(s);
  assert.equal((md.match(/^### /gm) || []).length, 18);
  assert.equal((md.match(/\*\*사람 검토\*\*/g) || []).length, 18);
  assert.equal(renderReviewCsv(s).split('\r\n').length, 20, '머리줄 + 18줄 + 끝 빈 줄');
  assert.ok(!/undefined|NaN|\[object/.test(md), '값이 비어 undefined 가 찍히지 않는다');
  assert.ok(renderCompare(s, s, null, null).includes('검토표를 읽지 못했습니다'));
  console.log('OK 11: 보고서 형식 — 문항 18 · 검토 칸 18 · undefined 없음');
}

srv.close();
fs.rmSync(tmp, { recursive: true, force: true });
console.log('\nmake-samples 테스트 통과');
