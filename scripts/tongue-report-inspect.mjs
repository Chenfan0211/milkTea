#!/usr/bin/env node
/**
 * 舌诊第三方「饮食」原始数据诊断脚本（只读）。
 *
 * 用途：
 *  1. 在线：按生产 TongueSigner 的规则调第三方 getReport，保存原始 data；
 *  2. 离线：直接读取数据库 tongue_report.report_json 导出文件；
 *  3. 可选对比我们 App 的解析结果，判断「第三方没给」还是「我们解析丢了」。
 *
 * 不会修改数据库、不会调用检测上传、不会写入生产接口。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

const DEFAULT_TONGUE_BASE_URL = 'https://linkhealth.tcmbrain.com/hzsg';
const DEFAULT_APP_BASE_URL = 'https://api.wulingshiguang.top';
const DEFAULT_APP_KEY = 'wlsg';
const DEFAULT_OUT_DIR = path.resolve(process.cwd(), '.tmp/tongue-inspect');
const DIET_KEYWORD = '饮食';
const PRODUCT_KEYWORD = '推荐商品';

export function javaUrlEncode(value) {
  // Java URLEncoder.encode(value, UTF_8).replace("+", "%20") 的等价实现：
  // encodeURIComponent 与 Java 的差异仅在 !'()~ 和空格，这里补齐。
  return encodeURIComponent(String(value == null ? '' : value)).replace(
    /[!'()~]/g,
    char => '%' + char.charCodeAt(0).toString(16).toUpperCase()
  );
}

export function md5Hex(value) {
  return crypto.createHash('md5').update(value).digest('hex').toUpperCase();
}

export function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex').toUpperCase();
}

export function signGet(options) {
  const reportId = String(options.reportId || '');
  const appKey = String(options.appKey || DEFAULT_APP_KEY);
  const appSecret = String(options.appSecret || '');
  const timestamp = String(options.timestamp || Date.now());
  const nonce = String(options.nonce || crypto.randomUUID().replace(/-/g, ''));
  const contentMd5 = md5Hex('reportId=' + javaUrlEncode(reportId));
  const source =
    'appKey=' +
    appKey +
    '&timestamp=' +
    timestamp +
    '&nonce=' +
    nonce +
    '&contentMd5=' +
    contentMd5 +
    '&appSecret=' +
    appSecret;
  return { appKey, appSecret, timestamp, nonce, contentMd5, sign: sha256Hex(source) };
}

export function safeHeaderSummary(signed) {
  return {
    appKey: signed.appKey,
    timestamp: signed.timestamp,
    nonce: signed.nonce,
    contentMd5: signed.contentMd5,
    sign: signed.sign
  };
}

export function parseArgs(argv) {
  const args = {};
  const list = argv || [];
  for (let index = 0; index < list.length; index += 1) {
    const token = list[index];
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const next = list[index + 1];
    if (next === undefined || next.startsWith('--')) {
      args[key] = true;
    } else {
      args[key] = next;
      index += 1;
    }
  }
  return args;
}

export function extractData(raw) {
  if (!raw || typeof raw !== 'object') return raw;
  if (raw.data && typeof raw.data === 'object') return raw.data;
  return raw;
}

export function rootNodes(data) {
  if (!data || typeof data !== 'object') return [];
  // App 详情响应是 reportGroups；第三方原始 data 是 children/sections。
  if (Array.isArray(data.reportGroups)) return data.reportGroups;
  if (Array.isArray(data.children)) return data.children;
  if (Array.isArray(data.sections)) return data.sections;
  return [];
}

export function findGroup(data, keyword = DIET_KEYWORD) {
  return rootNodes(data).find(node => String((node && (node.title || node.name)) || '').includes(keyword)) || null;
}

function nodeChildren(node) {
  if (!node || typeof node !== 'object') return [];
  if (Array.isArray(node.children)) return node.children;
  if (Array.isArray(node.sections)) return node.sections;
  return [];
}

function nodeDescriptions(node) {
  return node && Array.isArray(node.descriptions) ? node.descriptions : [];
}

function nodeHasAnalysis(node) {
  if (!node) return false;
  if (node.analysis) return true;
  const props = node.props;
  if (!props || typeof props !== 'object') return false;
  return Boolean(
    (Array.isArray(props.factor) && props.factor.length) || (Array.isArray(props.feature) && props.feature.length)
  );
}

export function summarizeNode(node) {
  const content = node && node.content !== undefined && node.content !== null ? String(node.content) : '';
  const descriptions = nodeDescriptions(node);
  const children = nodeChildren(node);
  return {
    id: node && node.id !== undefined ? String(node.id) : '',
    title: String((node && (node.title || node.name)) || ''),
    contentLength: content.length,
    hasContent: content.trim().length > 0,
    descriptions: descriptions.length,
    mediaKind: node && node.media ? String(node.media.kind || 'MEDIA') : '',
    children: children.length,
    analysis: nodeHasAnalysis(node),
    keys: node && typeof node === 'object' ? Object.keys(node) : []
  };
}

export function nodeHasRawContent(node) {
  if (!node || typeof node !== 'object') return false;
  const descriptions = nodeDescriptions(node);
  const children = nodeChildren(node);
  return Boolean(
    (node.content !== undefined && node.content !== null && String(node.content).trim()) ||
    descriptions.length ||
    node.media ||
    nodeHasAnalysis(node) ||
    children.length
  );
}

export function walkNodes(node, depth = 0, maxDepth = 3) {
  const summary = summarizeNode(node);
  const children = depth >= maxDepth ? [] : nodeChildren(node).map(child => walkNodes(child, depth + 1, maxDepth));
  return { summary, children };
}

export function classifyDiet(rawDiet, parsedDiet) {
  const rawChildren = nodeChildren(rawDiet);
  const parsedSections = parsedDiet && Array.isArray(parsedDiet.sections) ? parsedDiet.sections : [];
  const parsedProducts = parsedDiet && Array.isArray(parsedDiet.products) ? parsedDiet.products : [];
  const hasParsed = Boolean(parsedDiet);
  const sectionIds = new Set(parsedSections.map(item => String(item.id)));
  const sectionTitles = new Set(parsedSections.map(item => String(item.title)));

  const rows = rawChildren.map(node => {
    const summary = summarizeNode(node);
    if (summary.title.includes(PRODUCT_KEYWORD)) {
      return Object.assign({}, summary, {
        kind: 'products',
        detail: hasParsed ? `已解析为商品 ${parsedProducts.length} 件` : '第三方商品节点'
      });
    }
    if (hasParsed && (sectionIds.has(summary.id) || sectionTitles.has(summary.title))) {
      return Object.assign({}, summary, { kind: 'parsed-section', detail: '已解析为 section' });
    }
    if (hasParsed && nodeHasRawContent(node)) {
      return Object.assign({}, summary, { kind: 'parser-dropped', detail: '原始有内容但解析结果缺失' });
    }
    if (!hasParsed) {
      return Object.assign({}, summary, { kind: 'uncompared', detail: '未提供 App 解析结果' });
    }
    return Object.assign({}, summary, { kind: 'third-party-empty', detail: '原始为空节点' });
  });

  const dietWay = rows.find(row => row.title.includes('饮食方式')) || null;
  let verdict = 'consistent';
  if (rows.some(row => row.kind === 'parser-dropped')) {
    verdict = 'parser-dropped';
  } else if (!dietWay) {
    verdict = 'third-party-missing';
  } else if (dietWay.kind === 'third-party-empty') {
    verdict = 'third-party-empty';
  } else if (!hasParsed) {
    verdict = 'uncompared';
  }
  return { rows, dietWay, verdict, hasParsed };
}

export function verdictText(verdict) {
  switch (verdict) {
    case 'parser-dropped':
      return '疑似被我们解析丢弃：原始有内容，但 App 解析结果里没有对应 section';
    case 'third-party-missing':
      return '第三方未返回饮食方式';
    case 'third-party-empty':
      return '第三方返回了饮食方式，但节点为空（无正文/描述/媒体/子节点）';
    case 'parsed-section':
      return '已解析为 section';
    case 'uncompared':
      return '仅拿到原始数据，未提供 App 解析结果，无法判断是否被解析丢弃';
    default:
      return '原始与解析结果一致';
  }
}

export async function fetchThirdPartyReport(options) {
  const reportId = String(options.reportId || '');
  const baseUrl = String(options.baseUrl || DEFAULT_TONGUE_BASE_URL).replace(/\/$/, '');
  const signed = signGet({
    reportId,
    appKey: options.appKey || DEFAULT_APP_KEY,
    appSecret: options.appSecret
  });
  const url = baseUrl + '/api/tongueimage/getReport?reportId=' + encodeURIComponent(reportId);
  const response = await (options.fetchImpl || fetch)(url, {
    method: 'GET',
    headers: {
      appKey: signed.appKey,
      appSecret: signed.appSecret,
      timestamp: signed.timestamp,
      nonce: signed.nonce,
      contentMd5: signed.contentMd5,
      sign: signed.sign
    }
  });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error('第三方返回不是 JSON（HTTP ' + response.status + '）：' + text.slice(0, 200));
  }
  return { httpStatus: response.status, body, data: extractData(body), signed };
}

export async function fetchAppReport(options) {
  const appBaseUrl = String(options.appBaseUrl || DEFAULT_APP_BASE_URL).replace(/\/$/, '');
  const url = appBaseUrl + '/api/v1/app/tongue/reports/' + encodeURIComponent(options.appReportId);
  const response = await (options.fetchImpl || fetch)(url, {
    method: 'GET',
    headers: {
      Authorization: 'Bearer ' + String(options.token || ''),
      'content-type': 'application/json'
    }
  });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error('App 接口返回不是 JSON（HTTP ' + response.status + '）：' + text.slice(0, 200));
  }
  if (body && body.code !== 0 && body.code !== 200) {
    throw new Error('App 接口返回失败：' + (body.message || body.code));
  }
  return extractData(body);
}

function safeFilePart(value) {
  return String(value || 'raw').replace(/[^A-Za-z0-9._-]/g, '_');
}

export function writeRawFile(outDir, name, data) {
  const dir = path.resolve(outDir || DEFAULT_OUT_DIR);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, safeFilePart(name) + '-raw.json');
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8');
  return file;
}

function printUsage() {
  console.log(`舌诊第三方「饮食」原始数据诊断脚本

在线调用第三方：
  node scripts/tongue-report-inspect.mjs --report-id <第三方reportId> [--app-report-id <我们记录id> --token <小程序token>]

离线分析数据库 report_json：
  node scripts/tongue-report-inspect.mjs --raw-file <report_json导出文件> [--parsed-file <App接口响应JSON>]

参数：
  --report-id <id>        第三方 reportId（在线必填）
  --raw-file <path>       离线原始 JSON（第三方 data 节点或完整响应）
  --app-report-id <id>    我们 App 的报告记录 id（在线对比可选）
  --token <token>         小程序登录 token（在线对比可选）
  --parsed-file <path>    App 接口响应 JSON（离线对比可选）
  --base-url <url>        第三方地址，默认 ${DEFAULT_TONGUE_BASE_URL}
  --app-base-url <url>    我们 App 地址，默认 ${DEFAULT_APP_BASE_URL}
  --out <dir>             原始 JSON 输出目录，默认 .tmp/tongue-inspect

环境变量：
  TONGUE_APP_KEY          默认 ${DEFAULT_APP_KEY}
  TONGUE_APP_SECRET       在线调用必填，只从环境变量读取
  TONGUE_BASE_URL         可覆盖第三方地址
  TONGUE_APP_BASE_URL     可覆盖 App 地址`);
}

function printTree(node, depth = 0, maxDepth = 3) {
  const summary = summarizeNode(node);
  const prefix = '  '.repeat(depth);
  const parts = [
    `${prefix}- ${summary.title || '(无标题)'}`,
    `id=${summary.id || '-'}`,
    `content=${summary.hasContent ? summary.contentLength + '字符' : '无'}`,
    `descriptions=${summary.descriptions}`,
    `media=${summary.mediaKind || '无'}`,
    `children=${summary.children}`,
    summary.analysis ? 'analysis=有' : ''
  ].filter(Boolean);
  console.log(parts.join(' | '));
  if (depth < maxDepth) {
    nodeChildren(node).forEach(child => printTree(child, depth + 1, maxDepth));
  }
}

function printClassification(result) {
  console.log('\n饮食直接子节点对比：');
  if (!result.rows.length) {
    console.log('  (饮食节点没有直接子节点)');
  }
  result.rows.forEach(row => {
    console.log(`  - ${row.title || '(无标题)'} | id=${row.id || '-'} | ${row.kind} | ${row.detail}`);
  });
  console.log('\n判定：' + verdictText(result.verdict));
}

export async function main(argv = process.argv.slice(2), deps = {}) {
  const args = parseArgs(argv);
  if (args.help) {
    printUsage();
    return 0;
  }
  const outDir = args.out || deps.outDir || DEFAULT_OUT_DIR;
  const baseUrl = args['base-url'] || process.env.TONGUE_BASE_URL || DEFAULT_TONGUE_BASE_URL;
  const appBaseUrl = args['app-base-url'] || process.env.TONGUE_APP_BASE_URL || DEFAULT_APP_BASE_URL;
  const appKey = process.env.TONGUE_APP_KEY || DEFAULT_APP_KEY;
  const appSecret = process.env.TONGUE_APP_SECRET || '';

  let rawData = null;
  let rawName = '';
  if (args['raw-file']) {
    const rawPath = path.resolve(String(args['raw-file']));
    const raw = JSON.parse(fs.readFileSync(rawPath, 'utf8'));
    rawData = extractData(raw);
    rawName = path.basename(rawPath, path.extname(rawPath));
    console.log('离线原始文件：' + rawPath);
  } else if (args['report-id']) {
    if (!appSecret) {
      throw new Error('在线调用需要环境变量 TONGUE_APP_SECRET；或改用 --raw-file 离线分析');
    }
    const result = await fetchThirdPartyReport({
      reportId: String(args['report-id']),
      baseUrl,
      appKey,
      appSecret,
      fetchImpl: deps.fetchImpl
    });
    rawData = result.data;
    rawName = String(args['report-id']);
    console.log(
      '第三方 HTTP=' +
        result.httpStatus +
        ' | status=' +
        String(result.body && result.body.status) +
        ' | message=' +
        String(result.body && result.body.message)
    );
    console.log('签名头（不含 secret）：' + JSON.stringify(safeHeaderSummary(result.signed)));
  } else {
    throw new Error('必须提供 --report-id（在线）或 --raw-file（离线）');
  }

  const rawFile = writeRawFile(outDir, rawName, rawData);
  console.log('原始 data 已保存：' + rawFile);
  console.log(
    '顶层分组：' +
      rootNodes(rawData)
        .map(node => String(node.title || node.name || ''))
        .join(' / ')
  );

  const rawDiet = findGroup(rawData, DIET_KEYWORD);
  if (!rawDiet) {
    throw new Error('第三方原始数据里没有找到饮食分组');
  }
  console.log('\n第三方饮食节点原始结构：');
  printTree(rawDiet);

  let parsedDiet = null;
  if (args['parsed-file']) {
    const parsedData = extractData(JSON.parse(fs.readFileSync(path.resolve(String(args['parsed-file'])), 'utf8')));
    parsedDiet = findGroup(parsedData, DIET_KEYWORD);
  } else if (args['app-report-id'] && args.token) {
    const appData = await fetchAppReport({
      appReportId: String(args['app-report-id']),
      appBaseUrl,
      token: String(args.token),
      fetchImpl: deps.fetchImpl
    });
    parsedDiet = findGroup(appData, DIET_KEYWORD);
  }

  if (parsedDiet) {
    const products = Array.isArray(parsedDiet.products) ? parsedDiet.products : [];
    const sections = Array.isArray(parsedDiet.sections) ? parsedDiet.sections : [];
    console.log('\nApp 解析结果：');
    console.log(
      '  products=' +
        products.length +
        '：' +
        products.map(item => `${item.goodsName || item.name}(${item.matched ? '已匹配' : '未匹配'})`).join(', ')
    );
    console.log(
      '  sections=' + sections.length + '：' + sections.map(item => `${item.title}[${item.block || '-'}]`).join(', ')
    );
  } else {
    console.log('\n未提供 App 解析结果（--app-report-id/--token 或 --parsed-file），只输出第三方原始结构。');
  }

  const classification = classifyDiet(rawDiet, parsedDiet);
  printClassification(classification);
  return classification.verdict === 'parser-dropped' ? 2 : 0;
}

const isDirectRun = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (isDirectRun) {
  main()
    .then(code => {
      process.exitCode = code;
    })
    .catch(error => {
      console.error('[error] ' + (error && (error.message || error)));
      process.exitCode = 1;
    });
}
