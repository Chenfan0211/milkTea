const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { getCurrentBusinessRole, getBoundStore, getCurrentSubjectId } = require('../../utils/roles');
const api = require('../../utils/api');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

/**
 * 商品详情（只读）。
 *
 * 数据来源：GET /api/v1/app/workbench/store/{id}/products/{productId}
 * 该接口在服务端校验调用者确实经营该门店（越权返回 403）。
 *
 * 设计取舍：本页只做展示，**不提供上下架操作** —— 上下架统一在选品列表页完成
 * （列表页已支持单条与批量）。原先依赖本地菜单镜像的同步查表已废弃，
 * 因此不再需要页面自行预热 menuCatalog。
 */
Page(
  withShare({
    data: {
      ready: false,
      loading: false,
      title: '商品详情',
      product: null,
      loadError: ''
    },
    onLoad(options) {
      this.productId = (options && options.id) || '';
      // 先确保角色已同步（冷启动时 getBoundStore 依赖 subjectId）
      ensureBusinessRole().then(() => this.syncProduct());
    },
    syncProduct() {
      const role = getCurrentBusinessRole();
      const boundStore = role ? getBoundStore(role.id) : null;
      const subjectId = getCurrentSubjectId();
      if (!boundStore || subjectId == null) {
        this.setData({ ready: false });
        wx.showToast({ title: '仅门店角色可查看商品详情', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      if (!this.productId) {
        this.setData({ ready: false, loadError: '缺少商品参数' });
        return;
      }
      this.storeId = subjectId;
      this.setData({ loading: true, loadError: '' });
      api
        .fetchStoreProductDetail(subjectId, this.productId)
        .then(product => {
          if (!product) {
            this.setData({ ready: false, loading: false, loadError: '' });
            return;
          }
          this.setData({
            ready: true,
            loading: false,
            title: product.name || '商品详情',
            product: this.normalizeProduct(product)
          });
        })
        .catch(error => {
          const message = (error && error.message) || '商品加载失败，请稍后重试';
          this.setData({ ready: false, loading: false, loadError: message });
        });
    },
    /**
     * 后端字段归一化：
     * - 价格由「分」转「元」文本（页面只读展示，不再参与计算）；
     * - 规格组缺省为空数组，避免 wxml 访问 undefined.length。
     */
    normalizeProduct(raw) {
      const product = Object.assign({}, raw);
      product.priceText = this.formatPrice(product.price);
      product.originalPriceText = this.formatPrice(product.originalPrice);
      // 规格加价后端仍为「分」，转为元文本展示
      product.specGroups = (Array.isArray(product.specGroups) ? product.specGroups : []).map(group =>
        Object.assign({}, group, {
          options: (group.options || []).map(option =>
            Object.assign({}, option, { priceDeltaText: this.formatPrice(option.priceDelta) })
          )
        })
      );
      product.tags = Array.isArray(product.tags) ? product.tags : [];
      product.tips = Array.isArray(product.tips) ? product.tips : [];
      return product;
    },
    /** 价格（分 -> 元，保留 2 位） */
    formatPrice(fen) {
      if (fen === null || fen === undefined || fen === '') return '';
      const value = Number(fen);
      if (!Number.isFinite(value)) return '';
      return (value / 100).toFixed(2);
    },
    /** 重试（加载失败时展示） */
    retryLoad() {
      this.syncProduct();
    },
    copyProductId() {
      const id = this.data.product && this.data.product.id;
      if (!id) return;
      wx.setClipboardData({
        data: id,
        success: () => wx.showToast({ title: '商品编号已复制', icon: 'none' }),
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
      });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    }
  })
);
