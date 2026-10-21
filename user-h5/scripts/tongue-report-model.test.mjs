import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const model = require(path.join(root, 'utils/tongue-report-model.js'));
const report = JSON.parse(fs.readFileSync(path.join(root, 'scripts/fixtures/tongue-report-response.json'), 'utf8'));

const TAB_LABELS = ['概况', '饮食', '穴位', '情志', '运动'];

function mediaBlocks(kind, blocks) {
  const found = [];
  const walk = list =>
    (list || []).forEach(block => {
      if (block.media && block.media.kind === kind) found.push(block);
      walk(block.subBlocks);
    });
  walk(blocks);
  return found;
}

// ---- 1) 真实报文（线上旧接口，无 tabKey）：五个固定 Tab 仍要正确归组 ----
const tabs = model.buildTabs(report, '', {}, []);
assert.deepEqual(
  tabs.map(tab => tab.label),
  TAB_LABELS,
  '真实报文必须归入五个固定 Tab'
);
assert.equal(tabs.length, 5, '真实报文没有未知分组，不应产生额外 Tab');
assert.equal(tabs[0].key, 'overview');
assert.equal(tabs[1].key, 'diet');

// ---- 2) 饮食 Tab：第三方只有推荐商品，也必须渲染出内容（历史故障：整片空白）----
const diet = tabs[1];
const productBlocks = diet.blocks.filter(block => block.isProducts);
assert.equal(productBlocks.length, 0, '全部未命中商城商品时不得渲染养生茶饮（含标题与文字卡）');

// ---- 2.1) 命中商城商品时按参考图渲染卡片：图 + 名称 + 标签 + 适宜体质 + 价格 ----
const hitTabs = model.buildTabs(
  {
    reportGroups: [
      {
        id: '2',
        title: '饮食',
        tabKey: 'diet',
        sections: [],
        products: [
          {
            goodsId: 'vendor-1',
            goodsName: '宫廷熟普',
            reason: '【推荐理由】 温养脾胃',
            matched: true,
            productId: 'tea-gongting-shupu',
            image: '/assets/images/3x/tea-gongting-shupu.jpg',
            price: 1,
            originalPrice: 1,
            tags: ['醇滑质感', '经典传承'],
            suitableConstitutions: ['痰湿质', '阳虚质', '气虚质', '血瘀质']
          }
        ]
      }
    ]
  },
  '',
  {},
  []
);

const hitBlock = hitTabs.find(tab => tab.key === 'diet').blocks.filter(block => block.isProducts)[0];
assert.ok(hitBlock, '命中商城商品时必须有养生茶饮商品区');
assert.equal(hitBlock.title, '养生饮茶');
assert.equal(hitBlock.products.length, 1);
const hitCard = hitBlock.products[0];
assert.equal(hitCard.name, '宫廷熟普');
assert.equal(hitCard.price, '¥0.01');
assert.equal(hitCard.image, '/assets/images/3x/tea-gongting-shupu.jpg');
assert.deepEqual(hitCard.tags, ['醇滑质感', '经典传承']);
assert.deepEqual(hitCard.constitutions, ['痰湿质', '阳虚质', '气虚质', '血瘀质'], '卡片必须带适宜体质');
assert.equal(hitCard.productId, 'tea-gongting-shupu', '卡片必须保留商城商品编号，供详情与一键加购使用');
assert.equal(hitCard.reason, '温养脾胃', '卡片必须展示清洗后的推荐理由，并去掉【推荐理由】前缀');

// ---- 2.2) 头部资料只认接口值，不回落到本地缓存 ----
assert.equal(model.profileSummaryOf({ sex: 'male', age: 23 }), '男/23岁');
assert.equal(model.profileSummaryOf({ reportStatus: 'READY' }), '', '缺 sex/age 时不得回落本地资料');

// ---- 2.3) 饮食分区：有内容则显示、无内容则隐藏 ----
assert.equal(typeof model.blockHasRenderableContent, 'function', '模型必须导出可渲染判断');
assert.equal(typeof model.filterRenderableBlocks, 'function', '模型必须导出空分区过滤器');
assert.equal(model.blockHasRenderableContent({ title: '空标题' }), false, '只有标题不算内容');
assert.equal(model.blockHasRenderableContent({ isProducts: true, products: [] }), false, '空商品块必须隐藏');
assert.equal(model.blockHasRenderableContent({ isProducts: true, products: [{}] }), true, '有商品必须显示');
assert.equal(
  model.blockHasRenderableContent({ hasContent: true, content: '<p><br></p>' }),
  false,
  '空白 HTML 必须隐藏'
);

const emptyDietTabs = model.buildTabs(
  {
    reportGroups: [
      {
        id: '2',
        title: '饮食',
        tabKey: 'diet',
        products: [],
        sections: [
          {
            id: '2.1',
            level: 1,
            parentId: '2',
            title: '饮食方式',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.2',
            level: 1,
            parentId: '2',
            title: '四季节气进补',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.3',
            level: 1,
            parentId: '2',
            title: '一周食谱',
            block: 'TEXT',
            content: '<p><br></p>',
            descriptions: [],
            media: null,
            analysis: null
          }
        ]
      }
    ]
  },
  '',
  {},
  []
);
assert.equal(
  emptyDietTabs.find(tab => tab.key === 'diet').blocks.length,
  0,
  '只有标题/空白 HTML 的饮食分区必须全部隐藏'
);

const presentDietTabs = model.buildTabs(
  {
    reportGroups: [
      {
        id: '2',
        title: '饮食',
        tabKey: 'diet',
        products: [],
        sections: [
          {
            id: '2.1',
            level: 1,
            parentId: '2',
            title: '饮食方式',
            block: 'DESCRIPTIONS',
            content: null,
            descriptions: [{ title: '宜', content: '冬瓜、白萝卜' }],
            media: null,
            analysis: null
          },
          {
            id: '2.2',
            level: 1,
            parentId: '2',
            title: '四季节气进补',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.2.1',
            level: 2,
            parentId: '2.2',
            title: '小满',
            block: 'DESCRIPTIONS',
            content: null,
            descriptions: [{ title: '进补原则', content: '清热利湿' }],
            media: null,
            analysis: null
          },
          {
            id: '2.3',
            level: 1,
            parentId: '2',
            title: '一周食谱',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.3.1',
            level: 2,
            parentId: '2.3',
            title: '周一',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.3.2',
            level: 2,
            parentId: '2.3',
            title: '周二',
            block: 'DESCRIPTIONS',
            content: null,
            descriptions: [{ title: '早餐', content: '燕麦粥' }],
            media: null,
            analysis: null
          }
        ]
      }
    ]
  },
  '',
  {},
  []
);
const presentDiet = presentDietTabs.find(tab => tab.key === 'diet');
const presentTitles = presentDiet.blocks.map(item => item.title);
assert.ok(presentTitles.includes('饮食方式'), '饮食方式有正文时必须显示');
assert.ok(presentTitles.includes('四季节气进补'), '四季节气进补有节气内容时必须显示');
assert.ok(
  presentDiet.blocks.find(item => item.title === '四季节气进补').subBlocks.some(sub => sub.title === '小满'),
  '节气子块必须带出'
);
const weekBlock = presentDiet.blocks.find(item => item.title === '一周食谱');
assert.ok(weekBlock && weekBlock.hasPillContent, '任一周X有内容时一周食谱必须保留');
assert.ok(weekBlock.pills.includes('周一') && weekBlock.pills.includes('周二'), '一周食谱必须保留周X pills');

const missingDietTabs = model.buildTabs(
  {
    reportGroups: [{ id: '2', title: '饮食', tabKey: 'diet', products: [], sections: [] }]
  },
  '',
  {},
  []
);
assert.equal(
  missingDietTabs.find(tab => tab.key === 'diet').blocks.length,
  0,
  '没有返回饮食方式/四季节气进补时不得出现空块'
);

const productWithEmptyAnchor = model.buildTabs(
  {
    reportGroups: [
      {
        id: '2',
        title: '饮食',
        tabKey: 'diet',
        products: [{ goodsName: '宫廷熟普', matched: true, productId: 'tea-x', price: 100, media: {} }],
        sections: [
          {
            id: '2.1',
            level: 1,
            parentId: '2',
            title: '饮食方式',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          }
        ]
      }
    ]
  },
  '',
  {},
  []
);
const productOnlyDiet = productWithEmptyAnchor.find(tab => tab.key === 'diet');
assert.ok(
  productOnlyDiet.blocks.some(item => item.isProducts && item.title === '养生饮茶'),
  '空饮食方式被过滤后商品块必须保留'
);
assert.equal(productOnlyDiet.blocks[0].title, '养生饮茶', '锚点被过滤时商品块必须放最前');

// ---- 3) 概况 Tab：分析指标 + 描述纯文本 ----
const overview = tabs[0];
const analysis = overview.blocks.find(block => block.isAnalysis);
assert.ok(analysis, '概况必须包含舌象分析块');
assert.equal(analysis.factors.length, 5, '舌象特征 5 项');
assert.equal(analysis.features.length, 5, '舌象指数 5 项');

const health = overview.blocks.find(block => block.title === '健康特点');
assert.ok(health && health.hasDescriptions, '健康特点必须是描述块');
for (const item of health.descriptions) {
  assert.ok(item.text.length > 0);
  assert.ok(!item.text.includes('<'), `描述不得残留标签: ${item.text}`);
  assert.ok(!item.text.includes('&nbsp;'), `描述不得残留实体: ${item.text}`);
}

const regulation = overview.blocks.find(block => block.title === '中医调理');
assert.ok(regulation && regulation.hasContent, '中医调理正文必须保留给 rich-text');
assert.ok(regulation.content.includes('<p>'), '正文 HTML 由后端白名单清洗，前端模型原样透传');

// ---- 4) 媒体类型与数量（真实报文）----
assert.equal(mediaBlocks('AUDIO', tabs[3].blocks).length, 2, '情志 Tab 必须有两个音频条目');
assert.equal(mediaBlocks('VIDEO', tabs[2].blocks).length, 2, '穴位 Tab 必须有两个穴位视频');
assert.equal(mediaBlocks('VIDEO', tabs[4].blocks).length, 3, '运动 Tab 必须有三个健身功法视频');

// ---- 5) 后端补齐 tabKey/parentId 后的层级与 pill ----
const nested = model.buildTabs(
  {
    reportGroups: [
      {
        id: '3',
        title: '穴位',
        tabKey: 'acupoint',
        products: [],
        sections: [
          {
            id: '3.2',
            level: 1,
            parentId: '3',
            title: '穴位操作',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '3.2.1',
            level: 2,
            parentId: '3.2',
            title: '阴陵泉穴',
            block: 'MEDIA_DETAIL',
            content: null,
            descriptions: [{ title: '【定位】', content: '小腿内侧' }],
            media: { kind: 'VIDEO', thumbnail: 'a.jpg', source: 'a.mp4' },
            analysis: null
          },
          {
            id: '3.2.2',
            level: 2,
            parentId: '3.2',
            title: '三阴交穴',
            block: 'MEDIA_DETAIL',
            content: null,
            descriptions: [{ title: '【手法】', content: '指揉' }],
            media: { kind: 'VIDEO', thumbnail: 'b.jpg', source: 'b.mp4' },
            analysis: null
          }
        ]
      },
      {
        id: '2',
        title: '饮食',
        tabKey: 'diet',
        products: [],
        sections: [
          {
            id: '2.1',
            level: 1,
            parentId: '2',
            title: '一周食谱',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.1.1',
            level: 2,
            parentId: '2.1',
            title: '周一',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.1.1.1',
            level: 3,
            parentId: '2.1.1',
            title: '早餐',
            block: 'DESCRIPTIONS',
            content: null,
            descriptions: [{ title: '【组成】', content: '小米粥' }],
            media: null,
            analysis: null
          },
          {
            id: '2.1.2',
            level: 2,
            parentId: '2.1',
            title: '周二',
            block: 'TITLE_ONLY',
            content: null,
            descriptions: [],
            media: null,
            analysis: null
          },
          {
            id: '2.1.2.1',
            level: 3,
            parentId: '2.1.2',
            title: '早餐',
            block: 'DESCRIPTIONS',
            content: null,
            descriptions: [{ title: '【组成】', content: '燕麦粥' }],
            media: null,
            analysis: null
          }
        ]
      },
      {
        id: '9',
        title: '未知分组',
        tabKey: 'other',
        products: [],
        sections: [
          {
            id: '9.1',
            level: 1,
            parentId: '9',
            title: '补充建议',
            block: 'TEXT',
            content: '<p>多喝水</p>',
            descriptions: [],
            media: null,
            analysis: null
          }
        ]
      }
    ]
  },
  '',
  {},
  []
);

const acupointGroup = nested.find(tab => tab.key === 'acupoint').blocks.find(block => block.title === '穴位操作');
assert.ok(acupointGroup, '穴位操作必须是顶层块');
assert.equal(acupointGroup.subBlocks.length, 2, '穴位操作必须挂两个穴位子块');
assert.equal(acupointGroup.subBlocks[0].descriptions[0].title, '【定位】');
assert.equal(acupointGroup.subBlocks[0].media.kind, 'VIDEO');

const weekPlan = nested.find(tab => tab.key === 'diet').blocks.find(block => block.title === '一周食谱');
assert.deepEqual(weekPlan.pills, ['周一', '周二'], '一周食谱必须按周X 生成 pill');
assert.equal(weekPlan.activePill, '周一');
assert.ok(weekPlan.subBlocks.length >= 1, '当前 pill 必须带出对应食谱子块');

assert.equal(nested.length, 6, '未知分组必须追加为额外 Tab，内容不丢');
assert.equal(nested[5].label, '未知分组');

// ---- 6) 标题兜底：旧接口即使不发 tabKey 也要能归位 ----
assert.equal(model.tabKeyOfTitle('饮食调理'), 'diet');
assert.equal(model.tabKeyOfTitle('经络穴位'), 'acupoint');
assert.equal(model.tabKeyOfTitle('情志与心理'), 'emotion');
assert.equal(model.tabKeyOfTitle('运动健身'), 'sport');
assert.equal(model.tabKeyOfTitle('其他建议'), 'other');
assert.equal(model.tabKeyOfTitle('饮食调理', 'other'), 'diet', '显式 other 时仍按标题兜底');
assert.equal(model.tabKeyOfTitle('饮食', 'diet'), 'diet', '后端下发的键优先');

console.log('tongue-report-model.test.mjs: passed');
