// 生成商品/菜单 seed SQL（V4），数据源：user-h5/data/mock.js
const fs = require('fs');
const path = require('path');
const mock = require('../../user-h5/data/mock.js');

function esc(v) {
  if (v === null || v === undefined) return 'NULL';
  return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'";
}

function json(v) {
  return esc(JSON.stringify(v));
}

function money(v) {
  return Math.round((Number(v) || 0) * 100);
}

const categories = [];
let categoryId = 0;
const products = [];
let productId = 0;
const specs = [];
let specId = 0;
let optionSort = 0;
const productStores = [];
let productStoreId = 0;

const STORE_SUBJECT_IDS = [101, 102, 103, 104, 105];

for (const tab of mock.menuTabs || []) {
  categoryId += 1;
  const tabId = categoryId;
  categories.push({ id: tabId, parentId: 0, code: tab.id, name: tab.label, type: 'TAB', sort: 0 });
  for (const group of tab.groups || []) {
    categoryId += 1;
    const groupId = categoryId;
    categories.push({ id: groupId, parentId: tabId, code: group.id, name: group.label, type: 'GROUP', sort: 0 });
    for (const category of group.categories || []) {
      categoryId += 1;
      const catId = categoryId;
      categories.push({ id: catId, parentId: groupId, code: category.id, name: category.label, type: 'CATEGORY', sort: 0 });
      for (const p of category.products || []) {
        productId += 1;
        const pid = productId;
        products.push({
          id: pid,
          productId: p.id,
          code: p.id,
          name: p.name,
          categoryId: catId,
          tags: p.tags || [],
          description: p.description || null,
          price: money(p.price),
          originalPrice: money(p.originalPrice),
          storedValuePrice: money(p.storedValuePrice),
          image: p.image || null,
          ingredients: (p.specDetail && p.specDetail.ingredients) || null,
          allergens: (p.specDetail && p.specDetail.allergens) || null,
          cupCapacity: (p.specDetail && p.specDetail.cupCapacity) || null,
          tips: (p.specDetail && p.specDetail.tips) || [],
          onSale: 1
        });
        for (const g of (p.specDetail && p.specDetail.specGroups) || []) {
          for (const o of g.options || []) {
            specId += 1;
            specs.push({
              id: specId,
              productId: pid,
              groupCode: g.id,
              groupLabel: g.label,
              optionCode: o.id,
              optionLabel: o.label,
              priceDelta: money(o.priceDelta),
              selected: o.selected ? 1 : 0,
              icon: o.icon || null,
              sort: optionSort++
            });
          }
        }
        for (const storeId of STORE_SUBJECT_IDS) {
          productStoreId += 1;
          productStores.push({ id: productStoreId, productId: pid, storeSubjectId: storeId });
        }
      }
    }
  }
}

const lines = [];
lines.push('-- 商品菜单 seed（由 server/scripts/gen-product-seed.cjs 自动生成，数据源 user-h5/data/mock.js）');
lines.push('');
lines.push('INSERT INTO product_category (id, parent_id, code, name, type, sort) VALUES');
lines.push(categories.map(c => `(${c.id}, ${c.parentId}, ${esc(c.code)}, ${esc(c.name)}, ${esc(c.type)}, ${c.sort})`).join(',\n') + ';');
lines.push('');
lines.push('INSERT INTO product (id, product_id, code, name, category_id, tags, description, price, original_price, stored_value_price, image, ingredients, allergens, cup_capacity, tips, on_sale) VALUES');
lines.push(products.map(p => `(${p.id}, ${esc(p.productId)}, ${esc(p.code)}, ${esc(p.name)}, ${p.categoryId}, ${json(p.tags)}, ${esc(p.description)}, ${p.price}, ${p.originalPrice}, ${p.storedValuePrice}, ${esc(p.image)}, ${esc(p.ingredients)}, ${esc(p.allergens)}, ${esc(p.cupCapacity)}, ${json(p.tips)}, ${p.onSale})`).join(',\n') + ';');
lines.push('');
lines.push('INSERT INTO product_spec (id, product_id, group_code, group_label, option_code, option_label, price_delta, selected, icon, sort) VALUES');
lines.push(specs.map(s => `(${s.id}, ${s.productId}, ${esc(s.groupCode)}, ${esc(s.groupLabel)}, ${esc(s.optionCode)}, ${esc(s.optionLabel)}, ${s.priceDelta}, ${s.selected}, ${esc(s.icon)}, ${s.sort})`).join(',\n') + ';');
lines.push('');
lines.push('INSERT INTO product_store (id, product_id, store_subject_id) VALUES');
lines.push(productStores.map(ps => `(${ps.id}, ${ps.productId}, ${ps.storeSubjectId})`).join(',\n') + ';');
lines.push('');

const out = path.join(__dirname, '../src/main/resources/db/migration/V4__seed_product.sql');
fs.writeFileSync(out, lines.join('\n'), 'utf8');
console.log(`tabs/group/category rows: ${categories.length}`);
console.log(`products: ${products.length}`);
console.log(`specs: ${specs.length}`);
console.log(`product_store: ${productStores.length}`);
console.log('written ->', out);
