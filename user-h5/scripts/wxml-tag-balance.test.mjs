import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * WXML 标签配对校验。
 *
 * 背景（真实故障）：手写 / 重写 WXML 时容易漏掉一个 `</view>`，
 * 微信编译期只报「expect end-tag 'scroll-view', near 'view'」，
 * 不指出具体缺失位置。这里做静态配对检查，把问题提前到 `npm run check`。
 *
 * 只做「标签是否成对」这一件事，不解析属性 / 不校验 wx: 指令语义。
 */

const VOID_TAGS = new Set(['image', 'input', 'import', 'include', 'wxs', 'icon', 'progress', 'switch']);
// 自闭合写法 <tag ... /> 也视为无内容，不需配对

function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) return walk(target);
    return [target];
  });
}

function checkWxml(file) {
  const source = fs.readFileSync(file, 'utf8');
  const relative = path.relative(root, file);

  // 去掉注释，避免注释里的 <> 干扰
  const cleaned = source.replace(/<!--[\s\S]*?-->/g, '');

  const tagPattern = /<\/?([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
  const stack = [];
  const errors = [];
  let match;

  while ((match = tagPattern.exec(cleaned)) !== null) {
    const raw = match[0];
    const tag = match[1];
    const selfClosing = match[3] === '/';
    const isClosing = raw.startsWith('</');

    if (isClosing) {
      const top = stack.pop();
      if (!top) {
        errors.push(`${relative}: 出现多余的结束标签 </${tag}>`);
        continue;
      }
      if (top.tag !== tag) {
        errors.push(
          `${relative}: 结束标签不匹配，期望 </${top.tag}>（第 ${top.line} 行开始），实际 </${tag}>`
        );
      }
      continue;
    }

    if (selfClosing || VOID_TAGS.has(tag)) continue;

    const line = cleaned.slice(0, match.index).split('\n').length;
    stack.push({ tag, line });
  }

  for (const left of stack) {
    errors.push(`${relative}: 缺少结束标签 </${left.tag}>（第 ${left.line} 行开始）`);
  }

  return errors;
}

const wxmlFiles = [
  ...walk(path.join(root, 'pages')),
  ...walk(path.join(root, 'components')),
  ...walk(path.join(root, 'custom-tab-bar'))
].filter(file => file.endsWith('.wxml'));

assert.ok(wxmlFiles.length > 0, '必须存在待校验的 WXML 文件');

const allErrors = wxmlFiles.flatMap(checkWxml);

assert.deepEqual(
  allErrors,
  [],
  `WXML 存在标签未闭合 / 不匹配问题（微信编译期会直接报错）:\n${allErrors.join('\n')}`
);

console.log(`WXML 标签配对校验通过（${wxmlFiles.length} 个文件）`);

/**
 * 全屏弹层组件的显隐约定（Skyline 踩坑防护）。
 *
 * 背景（真实故障）：`login-sheet` 这类组件根节点是 `position: fixed` 全屏层。
 * 若只在**组件内部**用 `wx:if` 控制显隐，页面侧仍会渲染该自定义组件的宿主节点
 * （Skyline + glass-easel 下宿主节点保留占位），导致整页点击被透明层吃掉
 * ——表现为「页面所有图标点了没反应」，且没有任何报错，极难排查。
 *
 * 因此约定：全屏弹层组件必须由**页面侧 `wx:if`** 控制是否渲染，
 * 组件自身不得接收 visible 之类的显隐开关。
 */
const FULLSCREEN_SHEET_COMPONENTS = ['login-sheet'];

for (const component of FULLSCREEN_SHEET_COMPONENTS) {
  const componentWxmlPath = path.join(root, 'components', component, `${component}.wxml`);
  if (!fs.existsSync(componentWxmlPath)) continue;

  const componentWxml = fs.readFileSync(componentWxmlPath, 'utf8');
  const componentJs = fs.readFileSync(path.join(root, 'components', component, `${component}.js`), 'utf8');

  assert.ok(
    !/^\s*<view\s+wx:if=/.test(componentWxml.trimStart()),
    `${component} 组件根节点不得用 wx:if 控制显隐（Skyline 下宿主节点会拦截整页点击）`
  );
  assert.ok(
    !/visible\s*:\s*\{\s*type:\s*Boolean/.test(componentJs),
    `${component} 不得接收 visible 显隐开关，应由页面侧 wx:if 控制渲染`
  );

  // 使用方必须用页面侧 wx:if 包裹
  const users = [
    ...walk(path.join(root, 'pages'))
  ].filter(file => file.endsWith('.wxml'));
  let usedCount = 0;
  for (const file of users) {
    const source = fs.readFileSync(file, 'utf8');
    const pattern = new RegExp(`<${component}[\\s>]`, 'g');
    if (!pattern.test(source)) continue;
    usedCount += 1;
    const relative = path.relative(root, file);
    // 匹配该组件标签的前一段内容，确认同一标签上带 wx:if
    const tagMatch = source.match(new RegExp(`<${component}[\\s\\S]*?/>`));
    assert.ok(
      tagMatch && /wx:if=/.test(tagMatch[0]),
      `${relative} 使用 ${component} 时必须加 wx:if 控制渲染（否则会拦截整页点击）`
    );
  }
  assert.ok(usedCount > 0, `${component} 应至少在一个页面中被使用`);
}

console.log(`全屏弹层组件渲染约定校验通过（${FULLSCREEN_SHEET_COMPONENTS.join(', ')}）`);

/**
 * 组件配置不得在 json 与 js 双份声明同一 option。
 *
 * 背景（真实故障）：navigation-bar 同时在 navigation-bar.json 和
 * navigation-bar.js 的 options 里声明 styleIsolation: 'apply-shared'。
 * 在 Skyline + lazyCodeLoading: requiredComponents（按需注入）下，
 * 框架合并两份配置时产生冲突，导致 appservice.js 内部调用
 * String.prototype.startsWith 拿到 undefined 而抛
 * 「TypeError: String.prototype.startsWith called on null or undefined」，
 * 页面（如 points-detail）直接白屏 / 脚本加载失败。
 *
 * 约定：同一 option 只在一处声明，优先 json（配置更直观）。
 */
const OPTION_KEYS = ['styleIsolation', 'multipleSlots', 'virtualHost', 'addGlobalClass', 'pureDataPattern'];
const componentDirs = fs
  .readdirSync(path.join(root, 'components'), { withFileTypes: true })
  .filter(entry => entry.isDirectory())
  .map(entry => path.join(root, 'components', entry.name));

const optionConflicts = [];
for (const dir of componentDirs) {
  const name = path.basename(dir);
  const jsonPath = path.join(dir, `${name}.json`);
  const jsPath = path.join(dir, `${name}.js`);
  if (!fs.existsSync(jsonPath) || !fs.existsSync(jsPath)) continue;
  const json = fs.readFileSync(jsonPath, 'utf8');
  // 去掉 JS 注释后再比对，避免注释里提到 option 名导致误报
  const js = fs
    .readFileSync(jsPath, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '');
  for (const key of OPTION_KEYS) {
    if (json.includes(key) && js.includes(key)) {
      optionConflicts.push(`components/${name}: ${key} 在 .json 与 .js 中重复声明`);
    }
  }
}

assert.deepEqual(
  optionConflicts,
  [],
  `组件配置不得在 json 与 js 双份声明（Skyline 按需注入下会导致框架解析崩溃）:\n${optionConflicts.join('\n')}`
);

console.log(`组件配置单一来源校验通过（${componentDirs.length} 个组件）`);