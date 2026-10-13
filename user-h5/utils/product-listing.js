const api = require('./api');

// 菜单内存镜像。
//
// 阶段 C 后菜单来自 /api/v1/app/menu：页面先 await refreshMenuFromRemote()，
// 之后同步函数（getListedMenuTabs 等）读镜像。未拉取到时返回空数组，
// 页面按「无菜单」处理，不再回退本地假数据。
let menuCatalog = [];

// 菜单镜像的最近一次同步状态，供页面区分「首次加载失败」与「已有镜像的刷新失败」。
// 前者必须让用户知道（否则页面会一直空着且无从重试），后者保留旧镜像即可。
let menuSyncState = { loaded: false, error: null, updatedAt: 0 };

/**
 * 用远端数据刷新菜单镜像（页面每次展示时调用）。
 *
 * 约定：
 *   · 带门店查询且成功返回空数组 -> 清空镜像。这是「本店无任何可售商品」的
 *     权威信号（后端已按 product_store 过滤），必须如实呈现空菜单，供点单页
 *     展示「已售罄」空态。若保留旧镜像，会出现「选了没配选品的门店却显示
 *     上一次门店/全量的商品」。
 *   · 不带门店且返回空 -> 保留旧镜像（未选门店时的瞬时故障兜底）。
 *   · 请求失败 -> 保留旧镜像，但把错误记进 menuSyncState，让页面能区分
 *     「首次就没拿到」和「只是这次刷新失败」，前者需要提示用户重试。
 */
function refreshMenuFromRemote(storeSubjectId) {
  return api
    .fetchMenu(storeSubjectId)
    .then(list => {
      const arr = Array.isArray(list) ? list : [];
      if (arr.length) {
        menuCatalog = arr;
      } else if (storeSubjectId != null) {
        // 带门店查询且成功返回空数组 = 该门店没有任何可售商品，
        // 必须清空镜像，否则残留上一门店（或全量）菜单，
        // 导致「门店页显示有货、提交支付才报错」的错乱。
        menuCatalog = [];
      }
      // storeSubjectId == null 且返回空：保留旧镜像（瞬时故障兜底）。
      menuSyncState = { loaded: true, error: null, updatedAt: Date.now() };
      return menuCatalog;
    })
    .catch(error => {
      menuSyncState = {
        loaded: menuCatalog.length > 0,
        error: error || new Error('菜单加载失败'),
        updatedAt: menuSyncState.updatedAt
      };
      return menuCatalog;
    });
}

/** 最近一次菜单同步状态（只读），页面据此决定是否需要提示「加载失败」。 */
function getMenuSyncState() {
  return menuSyncState;
}
/** 直接注入菜单镜像（仅供测试使用）。 */
function setMenuCatalogForTest(list) {
  menuCatalog = Array.isArray(list) ? list : [];
  return menuCatalog;
}

/** 当前菜单镜像（只读） */
function getMenuCatalog() {
  return menuCatalog;
}

// 拍平菜单得到全量商品，附带所属 tab / 分组 / 分类。供购物车可售判断与测试使用。
function flattenProducts(menus) {
  const list = [];
  (menus || []).forEach(tab => {
    (tab.groups || []).forEach(group => {
      (group.categories || []).forEach(category => {
        (category.products || []).forEach(product => {
          list.push({
            id: product.id,
            name: product.name,
            price: product.price,
            originalPrice: product.originalPrice,
            image: product.image,
            badgeIcon: product.badgeIcon,
            tabId: tab.id,
            tabLabel: tab.label,
            groupId: group.id,
            groupLabel: group.label,
            categoryId: category.id,
            categoryLabel: category.label,
            product
          });
        });
      });
    });
  });
  return list;
}

// 当前菜单镜像里的全量商品（后端已按门店过滤）。
function getAllProducts() {
  return flattenProducts(menuCatalog);
}

// 消费端读取：当前门店的菜单。
//
// 菜单镜像由后端按门店选品过滤（GET /api/v1/app/menu?storeSubjectId=），
// 前端不再维护本地上架状态，也不再「可售为 0 时回落全量菜单」——
// 后者会让「未配置选品的门店」错误地显示平台全量商品。
// 门店无选品时 menuCatalog 为空，由点单页按「已售罄」空态展示。
function getListedMenuTabs(storeId) {
  // storeId 仅用于调用方语义标注；过滤已在后端完成，这里直接返回镜像。
  return menuCatalog;
}

/**
 * 判定某门店是否「已售罄」。
 *
 * 语义：已选中门店，且后端按该门店查询菜单成功返回了空（该店无任何
 * 可售商品）。此时菜单镜像为空、且不是「首次加载失败」（无 error），
 * 点单页据此显示「已售罄」空态，而不是残留旧菜单。
 */
function isStoreSoldOut(storeId) {
  if (!storeId) return false;
  return menuCatalog.length === 0 && menuSyncState.loaded && !menuSyncState.error;
}

/**
 * 合并全部一级页签为单一菜单（点单页不展示顶部页签）。
 *
 * 产品口径：原「招牌主打」等其余 TAB 的分组，与第一个 TAB 的分组同级展示。
 * 安全性：group / category 的 id 均取自数据库全局唯一的 code，合并后不会冲突
 * （已用真实分类树 8 条数据校验：recommend/leaf/featured-signature 与
 *  herbal/traditional/featured-season 均无重复）。
 *
 * 注意：合并只做「分组扁平化」，不改变分组内商品与分类的从属关系，
 * 因此左侧栏仍按「分组标签 + 其下分类」渲染，选中态逻辑无需调整。
 *
 * 契约：恒返回带 groups 数组的对象（无菜单时为 { groups: [] }），
 * 消费端无需再各自判空，避免 menu.groups[0] 触发 undefined 访问。
 */
function getMergedMenuTab(storeId) {
  // 门店售罄（有菜单但该店可售为 0）时不回落全量，
  // 返回空 groups，交由点单页按「已售罄」空态展示。
  if (isStoreSoldOut(storeId)) return { groups: [] };
  const tabs = getListedMenuTabs(storeId);
  if (!tabs.length) return { groups: [] };
  const first = tabs[0];
  return Object.assign({}, first, {
    groups: tabs.reduce((acc, tab) => acc.concat(tab.groups || []), [])
  });
}

// 商品在当前门店是否可售 = 是否存在于后端返回的本店菜单镜像中。
//
// 平台级上下架（product.on_sale）与门店级选品（product_store）都由后端过滤，
// 前端不再持有独立的本地上下架状态，避免两套数据源不一致。
function isProductListed(storeId, productId) {
  if (!productId) return false;
  return flattenProducts(menuCatalog).some(item => item.id === productId);
}

module.exports = {
  refreshMenuFromRemote,
  getMenuSyncState,
  setMenuCatalogForTest,
  getMenuCatalog,
  getAllProducts,
  getListedMenuTabs,
  getMergedMenuTab,
  isStoreSoldOut,
  isProductListed
};
