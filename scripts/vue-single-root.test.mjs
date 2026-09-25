/**
 * Vue 单根节点守卫（CI 检查）
 *
 * 背景（2026-09-25 报错）：
 *   运营后台「资源方管理」页弹出全屏红色报错：
 *     [vite-plugin-vue-transition-root-validator] 检测到 Vue Transition 多根节点错误
 *
 * 根因：`src/views/subject/channel/index.vue` 的 <template> 有**两个并列根元素**
 *   （AdminListPage + ChannelStoreDialog），渲染出 Fragment，
 *   而页面切换动画 <Transition> 要求「插槽只渲染一个元素根节点」。
 *
 * 本脚本用**真实 Vue 编译器**判定每个页面的根节点，把这类问题在 CI 拦住。
 *
 * 为什么用编译器而不是「数标签」：
 *   1) 模板内的**顶层注释**同样会被算作一个根节点，使根变成 Fragment
 *      并触发同样的报错（本项目已实测踩到）；
 *   2) 模板里的 v-for 会让编译器 import Fragment，**全文搜 Fragment 会大面积误报**
 *      （写过一版，实测误报 10 个正常页面）。
 *   只有「看 render 函数首个 return 实际创建的是什么」才准确。
 *
 * 用法：node scripts/vue-single-root.test.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { parse, compileTemplate } from '@vue/compiler-sfc';

const ROOT = process.cwd();
const VIEWS = join(ROOT, 'src/views');

/** 递归收集所有 .vue 文件 */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.vue')) out.push(p);
  }
  return out;
}

/**
 * 判定模板是否「只渲染一个元素根节点」。
 *
 * 判据：render 函数首个 return 创建的是否为 Fragment。
 *   根为 Fragment -> _createElementBlock(_Fragment, ...)   ❌
 *   单元素根      -> _createElementBlock("div", ...)        ✅
 */
function checkSingleRoot(file) {
  const source = readFileSync(file, 'utf8');
  const { descriptor, errors } = parse(source, { filename: file });
  if (errors.length) {
    return { ok: false, reason: `解析失败: ${errors[0].message}` };
  }
  if (!descriptor.template) {
    return { ok: true, reason: '无 template' };
  }

  const code = compileTemplate({
    source: descriptor.template.content,
    filename: file,
    id: 'guard'
  }).code;

  // 只看 render 函数体，避免被 import 里的 Fragment 干扰
  const renderIdx = code.indexOf('_sfc_render');
  const renderBody = renderIdx >= 0 ? code.slice(renderIdx) : code;
  const m = renderBody.match(/return\s*\(?\s*_?openBlock\(\)\s*,\s*_?(?:createElementBlock|createBlock)\s*\(\s*_?([\w$"']+)/);
  const root = m ? m[1] : '';
  const fragmentRoot = root === 'Fragment';

  if (fragmentRoot) {
    const ast = descriptor.template.ast;
    const children = ast?.children ?? [];
    const elems = children.filter(n => n.type === 1);
    const nonElems = children.filter(n => n.type !== 1);
    const detail =
      elems.length === 1 && nonElems.length > 0
        ? `模板内有 ${nonElems.length} 个顶层非元素节点（注释/文本）—— 注释请移到 <template> 外面`
        : `模板有 ${elems.length} 个并列根元素，需用一个容器（项目约定 <div class="page-root">）包起来`;
    return { ok: false, reason: detail };
  }
  return { ok: true };
}

const files = walk(VIEWS);

// 自检：必须真的扫描到文件，否则脚本形同虚设
if (files.length < 20) {
  console.error(`✗ 只扫描到 ${files.length} 个 .vue 文件，明显偏少 —— 检查脚本路径是否失效`);
  process.exit(1);
}

const failures = [];
let parsed = 0;
for (const f of files) {
  const res = checkSingleRoot(f);
  if (res.ok && res.reason !== '无 template') parsed++;
  if (!res.ok) failures.push({ file: relative(ROOT, f).replace(/\\/g, '/'), reason: res.reason });
}

// 自检：必须真的解析到模板，否则「全部通过」没有意义
if (parsed < 20) {
  console.error(`✗ 只成功解析到 ${parsed} 个模板，判定逻辑可能已失效（否则会静默全通过）`);
  process.exit(1);
}

if (failures.length) {
  console.error(`✗ 检测到 ${failures.length} 个页面存在「多根节点」问题（会触发 Vue Transition 报错）：\n`);
  for (const f of failures) {
    console.error(`  ${f.file}\n     ${f.reason}\n`);
  }
  console.error('修复方式：在 <template> 最外层包一个容器（项目约定 <div class="page-root">），');
  console.error('        并确保模板内没有顶层注释（注释写在 <template> 之前）。');
  process.exit(1);
}

console.log(`✓ Vue 单根节点检查通过（扫描 ${files.length} 个页面，解析 ${parsed} 个模板，全部为单元素根）`);