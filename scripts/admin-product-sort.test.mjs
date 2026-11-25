/**
 * 后台商品排序能力守卫。
 *
 * 背景：列表是 remote 分页，naive-ui 的本地 sorter 只会排「当前这一页」，
 * 用户点「按创建时间倒序」看到的仍是全表最旧那一页，属于静默错误结果。
 * 因此表头排序必须走远端：列声明 sorter: true + config.remoteSort = true，
 * 由 AdminListPage 把 sortBy / sortOrder 传给后端白名单。
 *
 * 同时锁定：
 *   1. 两端的商品页都提供「排序号」列与表单字段（否则运营无法填）；
 *   2. 排序号在小程序侧真正生效（后端菜单按 sort 排序，由 Java 测试覆盖）；
 *   3. 未开启 remoteSort 的页面不得残留 sorter（避免只排当前页）。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relativePath) {
  return readFileSync(join(root, relativePath), 'utf8');
}

const listPage = read('src/views/_shared/AdminListPage.vue');
const types = read('src/views/_shared/types.ts');
const mallPage = read('src/views/mall/product/index.vue');
const storePage = read('src/views/product/list/index.vue');
const mallApi = read('src/service/api/mall.ts');

// 一、AdminListPage 必须支持远端排序
assert.match(types, /remoteSort\?: boolean/, 'AdminListConfig 必须声明 remoteSort 开关');
assert.match(types, /sortBy\?: string/, 'loadData 入参必须支持 sortBy');
assert.match(types, /sortOrder\?: 'asc' \| 'desc'/, 'loadData 入参必须支持 sortOrder');
assert.match(listPage, /@update:sorter="onSorterChange"/, '表格必须监听 sorter 变化');
assert.match(listPage, /sortBy: sorter\.value\?\.columnKey/, '必须把 sorter 列名透传给 loadData');
assert.match(
  listPage,
  /sortOrder: sorter\.value \? \(sorter\.value\.order === 'descend' \? 'desc' : 'asc'\) : undefined/,
  '必须把 sorter 方向转成 asc / desc'
);
assert.match(listPage, /if \(!props\.config\.remoteSort\) return;/, '未开启远端排序时不得改动请求');
assert.match(
  listPage,
  /!props\.config\.remoteSort && col\.sorter/,
  '未开启远端排序的页面必须移除 sorter，避免只排当前页'
);
assert.match(listPage, /sorter\.value = null;/, '重置必须一并清空排序状态');

// 二、商城商品页
assert.match(mallPage, /remoteSort: true/, '商城商品页必须开启远端排序');
assert.match(mallPage, /key: 'sort'[\s\S]{0,120}sorter: true/, '商城商品页必须有可排序的「排序号」列');
assert.match(mallPage, /label="排序号"/, '商城商品表单必须有排序号输入');
assert.match(mallPage, /sort: Math\.max\(0, Math\.min\(9999, Number\(form\.sort\) \|\| 0\)\)/, '排序号必须夹在 0-9999');
assert.match(mallPage, /sortBy,\n\s+sortOrder/, '商城商品列表必须把排序参数传给接口');
assert.match(mallApi, /sortBy\?: string;/, 'MallProductQuery 必须声明 sortBy');
assert.match(mallApi, /sort\?: number;/, 'MallProductPayload 必须声明 sort');

// 三、门店点单商品页
assert.match(storePage, /remoteSort: true/, '点单商品页必须开启远端排序');
assert.match(storePage, /key: 'sort'[\s\S]{0,120}sorter: true/, '点单商品页必须有可排序的「排序号」列');
assert.match(storePage, /key: 'sort',\n\s+label: '排序号'/, '点单商品表单必须有排序号字段');
assert.match(storePage, /sortBy,\n\s+sortOrder/, '点单商品列表必须把排序参数传给接口');
assert.match(storePage, /sort: Number\(row\.sort\) \|\| 0/, '编辑回填必须带出排序号');

console.log('商品排序能力检查通过');
