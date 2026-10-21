import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import {
  classifyDiet,
  extractData,
  findGroup,
  javaUrlEncode,
  main,
  safeHeaderSummary,
  signGet
} from './tongue-report-inspect.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = path.join(root, 'user-service/src/test/resources/tongue/report-real-heat.json');

// ---- 1) URL 编码与 GET 签名：与 Java TongueSignerTest 的输入保持一致 ----
assert.equal(javaUrlEncode('R 1/中文'), 'R%201%2F%E4%B8%AD%E6%96%87');
const signed = signGet({
  reportId: 'R 1/中文',
  appKey: 'app-key',
  appSecret: 'test-secret',
  timestamp: '1700000000000',
  nonce: 'abc123'
});
assert.equal(signed.contentMd5, 'A2B4737B3B65F94854BCDF3E2E8D109E');
assert.equal(signed.sign, '8EE43B4BA7A0BF3A7DB03DBCB6AA1F33DF76C469DA754A49430EEA7D367EAC72');
const safe = safeHeaderSummary(signed);
assert.equal(safe.appSecret, undefined, '日志摘要不得包含 appSecret');
assert.ok(!JSON.stringify(safe).includes('test-secret'), '日志摘要不得泄露 secret');

// ---- 2) 完整第三方响应 / 裸 data 节点都能提取 ----
const bareData = { physique: '湿热质', children: [{ id: '2', title: '饮食', children: [] }] };
assert.deepEqual(extractData(bareData), bareData);
const envelope = { status: 1, message: 'ok', data: bareData };
assert.deepEqual(extractData(envelope), bareData);

// ---- 3) 真实 fixture：饮食只有推荐商品，没有饮食方式 ----
const realRaw = JSON.parse(fs.readFileSync(fixture, 'utf8'));
const realDiet = findGroup(realRaw, '饮食');
assert.ok(realDiet, '真实 fixture 必须有饮食分组');
const realClassify = classifyDiet(realDiet, null);
assert.equal(realClassify.rows.length, 1);
assert.equal(realClassify.rows[0].title, '推荐商品');
assert.equal(realClassify.rows[0].kind, 'products');
assert.equal(realClassify.verdict, 'third-party-missing', '真实 fixture 应判定第三方未返回饮食方式');

// ---- 4) 四种解析对比场景 ----
const dietWith = child => ({ id: '2', title: '饮食', children: [child] });
const dietWayContent = {
  id: '2.1',
  title: '饮食方式',
  content: '<p>宜食冬瓜、白萝卜</p>'
};
const dietWayEmpty = { id: '2.2', title: '饮食方式', children: [] };

const parsedSection = classifyDiet(dietWith(dietWayContent), {
  products: [],
  sections: [{ id: '2.1', title: '饮食方式', block: 'TEXT' }]
});
assert.equal(parsedSection.rows[0].kind, 'parsed-section');
assert.equal(parsedSection.verdict, 'consistent');

const parserDropped = classifyDiet(dietWith(dietWayContent), { products: [], sections: [] });
assert.equal(parserDropped.rows[0].kind, 'parser-dropped');
assert.equal(parserDropped.verdict, 'parser-dropped');

const thirdPartyEmpty = classifyDiet(dietWith(dietWayEmpty), { products: [], sections: [] });
assert.equal(thirdPartyEmpty.rows[0].kind, 'third-party-empty');
assert.equal(thirdPartyEmpty.verdict, 'third-party-empty');

const productOnly = classifyDiet(
  dietWith({ id: '2.6', title: '推荐商品', children: [{ id: '2.6.1', title: '滇绿' }] }),
  { products: [{ goodsName: '滇绿', matched: true }], sections: [] }
);
assert.equal(productOnly.rows[0].kind, 'products');
assert.ok(productOnly.rows[0].detail.includes('1 件'));

// ---- 5) 离线 main：能跑通并落盘原始 JSON，且不打印 secret ----
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tongue-inspect-test-'));
const logs = [];
const originalLog = console.log;
console.log = (...args) => logs.push(args.join(' '));
let exitCode;
try {
  exitCode = await main(['--raw-file', fixture, '--out', tempDir]);
} finally {
  console.log = originalLog;
}
assert.equal(exitCode, 0, '离线诊断应正常结束');
assert.ok(
  logs.some(line => line.includes('第三方未返回饮食方式')),
  '输出应包含判定结论'
);
const files = fs.readdirSync(tempDir);
assert.equal(files.length, 1, '离线运行应只写一个原始 JSON 文件');
const saved = fs.readFileSync(path.join(tempDir, files[0]), 'utf8');
assert.ok(saved.includes('推荐商品'), '保存的原始 JSON 应包含饮食子节点');
assert.ok(!saved.includes('test-secret'), '保存文件不得包含 secret');

console.log('tongue-report-inspect.test.mjs: passed');
