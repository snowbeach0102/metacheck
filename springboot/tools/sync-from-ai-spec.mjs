#!/usr/bin/env node
// 사양서(ai관련 폴더) → 스프링 프로젝트 동기화.
//   1) src/main/resources/prompts/*.txt   프롬프트 고정 조각 (사양서 aispec-pages.js 에서 뽑음)
//   2) src/main/resources/prompts/examples.json   예시 은행 (few-shot)
//   3) src/test/resources/golden/cells.json       Node 조립기가 만든 프롬프트 4칸 (PromptAssemblerTest 가 글자 단위로 비교)
//   4) src/test/resources/golden/parity.json      Node 러너(run-bedrock.mjs)의 gate · 섞기 · 유사도 판정 (NodeParityTest 가 비교)
//
// 쓰는 때: 사양서나 Node 러너를 고쳐서 Java 쪽과 어긋났을 때. 자세한 절차는 springboot/CLAUDE.md 의 "사양서와 맞추기".
//   node tools/sync-from-ai-spec.mjs [--ai <ai관련 폴더>] [--out <쓸 프로젝트 폴더>]
//   --ai  기본값 ../../mine_metacheck/ai관련  (두 저장소를 C:\ 아래 나란히 둔 경우)
//   --out 기본값 이 프로젝트(springboot). 다른 폴더를 주면 거기에만 쓴다 — 결과를 미리 비교해 보고 싶을 때
// 먼저 ai관련/소스 에서 `node assemble-bedrock.js bedrock_out3` 로 prompts12.json 을 다시 만들고 ../bedrock_12칸/ 으로 복사해 둘 것.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const project = path.resolve(here, '..');
const argv = process.argv.slice(2);
const opt = (name, fallback) => (argv.includes(name) ? path.resolve(argv[argv.indexOf(name) + 1]) : fallback);
const ai = opt('--ai', path.resolve(project, '../../mine_metacheck/ai관련'));
const out = opt('--out', project);

const specFile = path.join(ai, '소스', 'aispec-pages.js');
const bankFile = path.join(ai, '소스', 'bank', 'examples.json');
const cellsFile = path.join(ai, 'bedrock_12칸', 'prompts12.json');
const runnerFile = path.join(ai, 'bedrock_12칸', 'run-bedrock.mjs');
for (const f of [specFile, bankFile, cellsFile, runnerFile]) if (!fs.existsSync(f)) { console.error('없는 파일:', f, '\n--ai 로 ai관련 폴더를 알려 주세요.'); process.exit(1); }

const write = (rel, text) => { const p = path.join(out, rel); fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, text, 'utf8'); console.log('쓰기', rel, text.length + '자'); };

// ───── 1) 프롬프트 조각 — assemble-bedrock.js 와 같은 방식으로 사양서의 템플릿 문자열을 꺼낸다
const src = fs.readFileSync(specFile, 'utf8');
const grab = (name) => { const m = src.match(new RegExp('const ' + name + ' = `([\\s\\S]*?)`;')); if (!m) throw new Error('사양서에서 못 찾음: ' + name); return m[1]; };
const tpl = (name) => eval('`' + grab(name) + '`').trim(); // 템플릿 리터럴의 \\" 같은 이스케이프를 풀어서 가져옴 (신뢰하는 팀 파일)
const pieces = { sysPrompt: 'system.txt', spU: 'persona-university.txt', spC: 'persona-cert.txt', lv1: 'level-1.txt', lv2: 'level-2.txt', lv3: 'level-3.txt', outFormat: 'output-format.txt' };
for (const [k, f] of Object.entries(pieces)) write(`src/main/resources/prompts/${f}`, tpl(k) + '\n');

// ───── 2) 예시 은행
write('src/main/resources/prompts/examples.json', fs.readFileSync(bankFile, 'utf8'));

// ───── 3) 프롬프트 골든 — 12칸 중 대학생 기초 · 대학생 심화 · 수험생 실전 · 수험생 심화
const order = ['A1', 'B1', 'A2', 'B2', 'A3', 'B3', 'C1', 'D1', 'C2', 'D2', 'C3', 'D3'];   // assemble-bedrock.js 의 칸 순서 = 예시를 돌리는 seed
const pick = ['A1', 'B3', 'C2', 'D3'];
const data = JSON.parse(fs.readFileSync(cellsFile, 'utf8'));
const cells = data.cells.filter((c) => pick.includes(c.id)).map((c) => {
  const cm = /\[개념\] (.+?) — (.+?)   \(개념 id: (c\d+)\)/.exec(c.user);
  const rm = /\[관련 개념\] (.+?) — (.+)/.exec(c.user);
  if (!cm || !rm) throw new Error(c.id + ': user 글에서 개념을 못 읽음');
  return {
    id: c.id, seed: order.indexOf(c.id), persona: c.persona, level: c.lv, subject: c.subject, topic: c.topic, examName: c.persona === 'CERT_EXAM' ? c.exam : null,
    concept: { id: cm[3], name: cm[1], desc: cm[2] }, related: [{ name: rm[1], desc: rm[2] }],
    exampleIds: c.exampleIds, system: c.system, user: c.user,
  };
});
write('src/test/resources/golden/cells.json', JSON.stringify(cells, null, 2));

// ───── 4) 판정 골든 — 예시 은행 문항 6개를 14가지로 망가뜨린 변형에 Node 러너가 내린 판정
const { gate1, gate2, similar, detectFixedOrder, itemText } = await import(pathToFileURL(runnerFile).href);
const bank = JSON.parse(fs.readFileSync(bankFile, 'utf8')).entries;
const pickIds = [];
for (const persona of ['UNIVERSITY', 'CERT_EXAM']) for (const lv of [1, 2, 3]) pickIds.push(bank.find((e) => e.persona === persona && e.lv === lv).id);
const base = pickIds.map((id) => ({ id, item: bank.find((e) => e.id === id).item }));
const MUT = {
  none: (it) => it,
  threeSentences: (it) => ({ ...it, q: it.q + ' 추가 문장이다. 또 다른 문장이다.' }),
  extraConcept: (it) => ({ ...it, concepts: [...it.concepts, '추가 개념'] }),
  noSteps: (it) => ({ ...it, steps: [] }),
  longAnswer: (it) => { const o = [...it.o]; o[it.a] = o[it.a] + ' 그리고 이 보기는 일부러 매우 길고 자세하게 늘려 쓴 것이다 그것도 아주 많이'; return { ...it, o }; },
  forbiddenOption: (it) => { const o = [...it.o]; o[(it.a + 1) % 4] = '위의 모두 옳다'; return { ...it, o }; },
  circled: (it) => ({ ...it, x: it.x + ' ②가 맞다' }),
  shuffleStr: (it) => ({ ...it, shuffle: 'yes' }),
  aOut: (it) => ({ ...it, a: 5 }),
  dropDistractor: (it) => { const d = { ...it.distractors }; delete d[Object.keys(d)[0]]; return { ...it, distractors: d }; },
  dupOption: (it) => { const o = [...it.o]; o[(it.a + 1) % 4] = o[(it.a + 2) % 4]; return { ...it, o }; },
  threeOptions: (it) => ({ ...it, o: it.o.slice(0, 3) }),
  missingEv: (it) => { const c = { ...it }; delete c.ev; return c; },
  conceptsNotList: (it) => ({ ...it, concepts: '하나' }),
};
const gates = [];
for (const b of base) for (const [name, f] of Object.entries(MUT)) {
  const item = f(b.item);
  const g1 = gate1(item);
  gates.push({ id: b.id, mutation: name, item, gate1: g1, gate2: [1, 2, 3].map((lv) => (g1.length ? null : gate2(item, lv).errors)) });
}
const fixedInputs = [
  ...base.map((b) => b.item.o),
  ['ㄱ, ㄷ', 'ㄱ', 'ㄴ, ㄷ', 'ㄱ, ㄴ'], ['ㄱ', 'ㄴ', 'ㄷ', 'ㄹ'], ['ㄴ·ㄷ', 'ㄱ·ㄴ', 'ㄱ', 'ㄷ'],
  ['0.5', '2', '1.5', '0.8'], ['1,200원', '-30원', '450원', '15원'], ['10', '9', '100', '1'],
  ['탄력성 0.5, 탄력적', '탄력성 2, 탄력적', '탄력성 0.8, 비탄력적', '탄력성 1.5, 탄력적'],
  ['1, 3', '2, 4', '1, 2', '3, 4'], ['−5', '3', '+7', '0'], ['3.5%', '12%', '7.25%', '-1%'],
  ['제3원칙: 공개', '제1원칙: 책임', '제2원칙: 동의', '제4원칙: 최소'], ['1단계 수집', '3단계 분석', '2단계 정제', '4단계 보고'], ['제2법칙', '제1법칙', '제3법칙', '제4법칙'],
  ['수요 증가', '수요 감소', '공급 증가', '공급 감소'], ['스택', '큐', '덱', '힙'], ['a'], [],
];
const fixed = fixedInputs.map((options) => { const r = detectFixedOrder(options); return { options, result: r ? { kind: r.kind, order: r.order } : null }; });
const texts = base.map((b) => itemText(b.item));
const sims = [];
for (let i = 0; i < texts.length; i++) for (let j = i; j < texts.length; j++) sims.push({ a: texts[i], b: texts[j], value: similar(texts[i], texts[j]) });
sims.push({ a: texts[0], b: texts[0].replace('것은', '것이'), value: similar(texts[0], texts[0].replace('것은', '것이')) });
sims.push({ a: '', b: texts[0], value: similar('', texts[0]) });
write('src/test/resources/golden/parity.json', JSON.stringify({ gates, fixed, sims }, null, 1));

console.log(`\n완료 → ${out}\n다음: gradlew test --tests "com.example.metacheck.question.*"  (JAVA_HOME 지정 필요, springboot/CLAUDE.md 참고)`);
