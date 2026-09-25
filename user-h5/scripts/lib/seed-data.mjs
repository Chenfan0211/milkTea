// 测试辅助：从 DB migration seed 中解析前端所需的初始数据。
//
// 阶段 C 后业务数据不再存放在 user-h5/data/mock.js，测试改为读取
// server/src/main/resources/db/migration 下的 seed 文件，保证断言
// 仍校验真实数据源。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MIGRATION_DIR = path.resolve(HERE, '..', '..', '..', 'server/src/main/resources/db/migration');

/** 读取某个 migration 文件。 */
export function readSeed(name) {
  return fs.readFileSync(path.join(MIGRATION_DIR, name), 'utf8');
}

/**
 * 从 app_config seed 中提取某个 config_key 的 JSON 值。
 * 以 ('key', '显示名', <json>) 形式定位，避免误匹配 INSERT 列清单里的同名 key。
 */
export function readAppConfig(key, sql) {
  const source = sql || readSeed('V10__app_config.sql');
  const marker = "('" + key + "',";
  const idx = source.indexOf(marker);
  if (idx < 0) return null;
  // marker 之后是 '显示名', 再往后是 JSON 值
  const afterName = source.indexOf(",", source.indexOf("'", idx + marker.length + 1));
  if (afterName < 0) return null;
  return extractJson(source, afterName + 1);
}

/** 从给定位置起解析一个 JSON 数组 / 对象字面量（按括号配对，忽略字符串内的括号）。 */
function extractJson(source, from) {
  const rest = source.slice(from);
  const startMatch = rest.match(/\s*(\[|\{)/);
  if (!startMatch) return null;
  const start = startMatch.index + startMatch[0].length - 1;
  const open = rest[start];
  const close = open === '[' ? ']' : '}';
  let depth = 0;
  let inString = false;
  for (let i = start; i < rest.length; i += 1) {
    const ch = rest[i];
    if (ch === "'" && rest[i - 1] !== '\\') inString = !inString;
    if (inString) continue;
    if (ch === open) depth += 1;
    else if (ch === close) {
      depth -= 1;
      if (depth === 0) return JSON.parse(rest.slice(start, i + 1));
    }
  }
  return null;
}

/** 解析门店列表（biz_subject + store_profile）。 */
export function loadStores() {
  const sql = readSeed('V3__seed_base.sql');
  const STORE_CODES = { 101: 'store-001', 102: 'store-002', 103: 'store-003', 104: 'store-004', 105: 'store-005' };
  const CITY_BY_NAME = { 长沙市: 'changsha', 广州市: 'guangzhou', 深圳市: 'shenzhen' };
  const names = {};
  const subjectInsert = sql.match(/INSERT INTO biz_subject[\s\S]*?;/)[0];
  for (const m of subjectInsert.matchAll(/\((\d+)\s*,\s*'[^']*'\s*,\s*'([^']+)'\s*,\s*'(\w+)'/g)) {
    names[Number(m[1])] = { name: m[2], type: m[3] };
  }
  const profileInsert = sql.match(/INSERT INTO store_profile \([\s\S]*?;/)[0];
  return [...profileInsert.matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*([\d.]+),\s*([\d.]+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*(\d+|NULL),\s*'([^']+)',\s*'([^']*)',\s*'([^']*)',\s*(\d+)\)/g)].map(m => ({
    id: STORE_CODES[Number(m[1])],
    code: STORE_CODES[Number(m[1])],
    name: (names[Number(m[1])] || {}).name || '',
    city: m[2],
    cityCode: CITY_BY_NAME[m[2]] || 'changsha',
    address: m[3],
    phone: m[4],
    latitude: Number(m[5]),
    longitude: Number(m[6]),
    storeType: m[7],
    businessStatus: m[8],
    manager: m[9],
    businessHours: m[11],
    modes: JSON.parse(m[12]),
    promotion: m[13],
    queueCount: Number(m[14]),
    status: 'enabled',
    // store_profile.investor_subject_id -> 业务编号（201 -> INV-1000）
    investorSubjectId: m[10] && m[10] !== 'NULL' ? Number(m[10]) : null,
    investorId: m[10] && m[10] !== 'NULL' ? 'INV-' + (1000 + Number(m[10]) - 201) : ''
  }));
}

/** 解析菜单（product_category + product），结构与 /api/v1/app/menu 一致。 */
export function loadMenu() {
  const sql = readSeed('V4__seed_product.sql');
  const cats = [...sql.match(/INSERT INTO product_category[\s\S]*?;/)[0]
    .matchAll(/\((\d+),\s*(\d+),\s*'([^']+)',\s*'([^']+)',\s*'(TAB|GROUP|CATEGORY)',\s*\d+\)/g)]
    .map(m => ({ id: Number(m[1]), parentId: Number(m[2]), code: m[3], name: m[4], type: m[5] }));

  // V4 的 product INSERT 列顺序：
  //   id, product_id, code, name, category_id, tags, description, price,
  //   original_price, stored_value_price, image, ingredients, allergens,
  //   cup_capacity, tips, on_sale
  // 由于 description / tips 中可能含逗号与引号，这里用「逐值扫描」解析每一行，
  // 避免正则分组错位。
  const valuesBlock = sql.match(/INSERT INTO product \(id, product_id[\s\S]*?VALUES([\s\S]*?);\s*\n/)[1];
  const rows = splitSqlRows(valuesBlock);
  const prods = rows.map(values => {
    const name = values[3];
    return {
      id: values[1],
      name,
      categoryId: Number(values[4]),
      tags: JSON.parse(values[5] || '[]'),
      description: values[6],
      // 后端金额单位为「分」，前端页面按「元」展示
      price: Number(values[7]) / 100,
      originalPrice: Number(values[8]) / 100,
      storedValuePrice: Number(values[9]) / 100,
      image: values[10],
      ingredients: values[11],
      allergens: values[12],
      cupCapacity: values[13],
      tips: JSON.parse(values[14] || '[]'),
      specGroups: []
    };
  });

  // 规格（product_spec）：按 product_id 分组，再按 group_code 聚合为 specGroups
  const specBlock = sql.match(/INSERT INTO product_spec \(id, product_id[\s\S]*?VALUES([\s\S]*?);\s*\n/);
  const specRows = specBlock ? splitSqlRows(specBlock[1]) : [];
  const specGroupsByProduct = {};
  for (const values of specRows) {
    // 列顺序：id, product_id, group_code, group_label, option_code, option_label,
    //         price_delta, selected, icon, sort
    const productId = Number(values[1]);
    const groupCode = values[2];
    const groups = specGroupsByProduct[productId] || (specGroupsByProduct[productId] = []);
    let group = groups.find(g => g.id === groupCode);
    if (!group) {
      group = { id: groupCode, label: values[3], options: [] };
      groups.push(group);
    }
    group.options.push({
      id: values[4],
      label: values[5],
      selected: String(values[7]).trim() === '1',
      priceDelta: Number(values[6]) / 100,
      icon: values[8]
    });
  }
  // V4 的 product_spec.product_id 引用 product.id（数字主键），这里映射为业务 ID
  const productIdByPk = {};
  rows.forEach((values, index) => { productIdByPk[index + 1] = values[1]; });
  for (const prod of prods) {
    const pk = Object.keys(productIdByPk).find(key => productIdByPk[key] === prod.id);
    prod.specGroups = (specGroupsByProduct[Number(pk)] || []).map(g => ({
      id: g.id,
      label: g.label,
      options: g.options.map(o => ({ ...o }))
    }));
  }

  // 分类层级已拍平为单层（V38）：seed 中 TAB/GROUP 仅作历史存在，
  // 运行时菜单结构 = 1 个 tab -> 1 个 group -> 分类列表（对齐 getMenu() 单层实现）。
  const categoryList = cats
    .filter(c => c.type === 'CATEGORY')
    .map(c => ({
      id: c.code,
      label: c.name,
      tag: c.tag,
      products: prods.filter(p => p.categoryId === c.id)
    }));

  return [
    {
      id: 'menu',
      label: '菜单',
      groups: [
        {
          id: 'all',
          label: '全部',
          categories: categoryList
        }
      ]
    }
  ];
}

/**
 * 把 SQL VALUES 块里的每一行 "(...)" 拆为值数组（去掉引号、还原转义）。
 * 仅支持本仓库 seed 用到的字面量：单引号字符串、数字、NULL、JSON 数组。
 */
function splitSqlRows(block) {
  const rows = [];
  let i = 0;
  while (i < block.length) {
    if (block[i] !== '(') { i += 1; continue; }
    const values = [];
    let current = '';
    let inString = false;
    i += 1;
    for (; i < block.length; i += 1) {
      const ch = block[i];
      if (inString) {
        if (ch === "'" && block[i + 1] === "'") { current += "'"; i += 1; continue; }
        if (ch === "'") { inString = false; continue; }
        current += ch;
        continue;
      }
      if (ch === "'") { inString = true; continue; }
      if (ch === ',') { values.push(normalizeValue(current)); current = ''; continue; }
      if (ch === ')') { values.push(normalizeValue(current)); i += 1; break; }
      current += ch;
    }
    rows.push(values);
  }
  return rows;
}

/** SQL 字面量 -> JS 值（仅处理 NULL 与纯数字，其余原样返回）。 */
function normalizeValue(raw) {
  const value = String(raw).trim();
  if (value === 'NULL') return null;
  if (/^-?\d+(\.\d+)?$/.test(value)) return Number(value) === 0 && value === '0' ? '0' : value;
  return value;
}

/** 解析会员等级（member_level）。 */
export function loadMemberLevels() {
  const sql = readSeed('V3__seed_base.sql');
  const block = sql.match(/INSERT INTO member_level[\s\S]*?;/);
  if (!block) return [];
  return [...block[0].matchAll(/\('(Lv\d+)', '([^']+)', (\d+), '([^']+)', '([\s\S]*?)', (\d+)\)/g)].map(m => ({
    level: m[1],
    name: m[2],
    amountTarget: Math.round(Number(m[3]) / 100),
    discount: m[4],
    benefits: JSON.parse(m[5]),
    sort: Number(m[6])
  }));
}

