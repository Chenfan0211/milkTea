const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getBoundStore } = require('../../utils/roles');
const { getProductDetail, setListed } = require('../../utils/product-listing');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '商品详情',
      product: null
    },
    onLoad(options) {
      this.productId = (options && options.id) || '';
      this.syncProduct();
    },
    syncProduct() {
      const role = getCurrentBusinessRole();
      const boundStore = role ? getBoundStore(role.id) : null;
      if (!boundStore) {
        this.setData({ ready: false });
        wx.showToast({ title: '仅门店角色可查看商品详情', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.storeId = boundStore.id;
      const product = getProductDetail(boundStore.id, this.productId);
      if (!product) {
        this.setData({ ready: false });
        return;
      }
      this.setData({ ready: true, title: product.name, product });
    },
    // 详情页与列表页共用同一份上下架状态。
    toggleListing() {
      const product = this.data.product;
      if (!product) return;
      const next = !product.listed;
      setListed(this.storeId, product.id, next);
      this.setData({ product: Object.assign({}, product, { listed: next }) });
      wx.showToast({ title: next ? '已上架' : '已下架', icon: 'none' });
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