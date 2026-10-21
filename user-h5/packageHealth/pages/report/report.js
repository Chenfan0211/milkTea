const api = require('../../../utils/api');
const recommendationCart = require('../../../utils/recommendation-cart-state');
const { formatDateTime } = require('../../../utils/date-format');
const {
  buildTabs,
  buildTree,
  filterRenderableBlocks,
  injectProducts,
  profileSummaryOf,
  toBlock
} = require('../../../utils/tongue-report-model');

const POLL_INTERVAL = 3000;
const MAX_POLLS = 5;

/**
 * 详情页：检查记录 + 报告详情五 Tab（参考图 5-9）。
 *
 * 仍然保留「报告未就绪自动轮询」：参考图没有刷新按钮，
 * 但首次检测后第三方报告通常需要几秒才生成，缺少轮询会卡在空报告。
 */
Page({
  data: {
    id: '',
    loading: true,
    error: '',
    report: null,
    profileSummary: '',
    statusNote: '',
    tabs: [],
    activeTabIndex: 0,
    blocks: [],
    activeMediaKey: '',
    audioPlaying: false,
    cartItems: [],
    cartCount: 0,
    cartTotal: 0,
    cartVisible: false
  },

  onShow() {
    this.syncRecommendationCart();
  },

  syncRecommendationCart() {
    if (!this.reportId) {
      this.setData({ cartItems: [], cartCount: 0, cartTotal: 0, cartVisible: false });
      return;
    }
    recommendationCart.consumeSettledItems(this.reportId);
    const cartItems = recommendationCart.getItems(this.reportId);
    const summary = recommendationCart.summarizeItems(cartItems);
    this.setData({
      cartItems,
      cartCount: summary.count,
      cartTotal: summary.total,
      cartVisible: this.data.cartVisible && cartItems.length > 0
    });
  },

  handleCartOpen() {
    if (!this.data.cartCount) return;
    this.setData({ cartVisible: true });
  },

  closeRecommendationCart() {
    this.setData({ cartVisible: false });
  },

  handleCartChange(event) {
    recommendationCart.setItems(this.reportId, (event && event.detail && event.detail.items) || []);
    this.syncRecommendationCart();
  },

  handleCartCheckout(event) {
    const eventItems = event && event.detail && event.detail.items;
    const selectedItems = (
      Array.isArray(eventItems) && eventItems.length
        ? eventItems
        : (this.data.cartItems || []).filter(item => item && item.selected)
    ).filter(Boolean);
    if (!selectedItems.length) {
      wx.showToast({ title: '请选择商品', icon: 'none' });
      return;
    }
    const items = selectedItems.map(item => ({
      productId: item.productId || item.id,
      name: item.name,
      image: item.image,
      selectedOptionIds: item.selectedOptionIds || [],
      quantity: item.quantity || 1,
      priceMode: item.priceMode || '',
      cartItemId: item.id || ''
    }));
    getApp().globalData.pendingRecommendationAdd = {
      reportId: this.reportId,
      intent: 'cartCheckout',
      cartScope: 'recommendation',
      items
    };
    this.setData({ cartVisible: false });
    wx.navigateTo({ url: '/pages/recommendation-stores/recommendation-stores' });
  },

  handleCartInfo() {
    wx.showToast({ title: '优惠说明暂未接入', icon: 'none' });
  },

  onLoad(options) {
    this.reportId = String((options && options.id) || '');
    this.pollCount = 0;
    this.pills = {};
    if (!this.reportId) {
      this.setData({ loading: false, error: '报告编号缺失' });
      return;
    }
    this.load();
  },

  onUnload() {
    this.stopPolling();
    this.stopMedia(false);
  },

  load() {
    this.setData({ loading: true, error: '' });
    api
      .fetchTongueReport(this.reportId)
      .then(report => this.applyReport(report))
      .catch(error => {
        this.setData({ loading: false, error: (error && error.message) || '报告加载失败' });
      });
  },

  applyReport(report) {
    const status = (report && report.reportStatus) || 'PENDING';
    const displayReport = Object.assign({}, report || {}, {
      createdAt: formatDateTime((report && report.createdAt) || '')
    });
    const tabs = buildTabs(report, this.data.activeMediaKey, this.pills, this.data.tabs);
    const activeTabIndex = Math.min(this.data.activeTabIndex, Math.max(tabs.length - 1, 0));
    this.setData({
      loading: false,
      error: '',
      report: displayReport,
      profileSummary: profileSummaryOf(report),
      tabs,
      activeTabIndex,
      blocks: tabs.length ? tabs[activeTabIndex].blocks : [],
      statusNote:
        status === 'READY'
          ? ''
          : status === 'FAILED'
            ? '报告生成失败，可回到体质健康管理重新检测'
            : '报告生成中，页面会自动刷新'
    });
    if (status === 'PENDING') this.startPolling();
    else this.stopPolling();
  },

  switchTab(event) {
    const index = Number((event.currentTarget.dataset || {}).index);
    if (Number.isNaN(index) || !this.data.tabs[index]) return;
    this.stopMedia();
    this.setData({ activeTabIndex: index, blocks: this.data.tabs[index].blocks });
  },

  /**
   * 一键加购：把当前饮食区块的全部已匹配商城商品交给点单页，
   * 由点单页完成定位、门店可售排序与购物车写入。
   */
  handleAddAllProducts(event) {
    const blockId = String((event.currentTarget.dataset || {}).block || '');
    const block = (this.data.blocks || []).find(item => item.id === blockId);
    const items = ((block && block.products) || [])
      .filter(product => product && product.productId)
      .map(product => ({
        productId: product.productId,
        name: product.name,
        image: product.image,
        reason: product.reason
      }));
    if (!items.length) {
      wx.showToast({ title: '暂无可加购商品', icon: 'none' });
      return;
    }
    getApp().globalData.pendingRecommendationAdd = {
      reportId: this.reportId,
      items
    };
    wx.navigateTo({ url: '/pages/recommendation-stores/recommendation-stores' });
  },

  /** 点击商品卡进入消费端商品详情。 */
  openProductDetail(event) {
    const productId = String((event.currentTarget.dataset || {}).id || '');
    if (!productId) {
      wx.showToast({ title: '商品信息缺失', icon: 'none' });
      return;
    }
    wx.navigateTo({
      url:
        '/pages/product-detail/product-detail?productId=' +
        encodeURIComponent(productId) +
        '&reportId=' +
        encodeURIComponent(this.reportId)
    });
  },

  switchPill(event) {
    const dataset = event.currentTarget.dataset || {};
    const blockId = String(dataset.block || '');
    const pill = String(dataset.pill || '');
    const tab = this.data.tabs[this.data.activeTabIndex];
    if (!tab || !blockId || !pill) return;
    const key = tab.key;
    this.pills[key] = Object.assign({}, this.pills[key], { [blockId]: pill });
    this.stopMedia();
    this.refreshBlocks();
  },

  /** 用当前 pill 选择重建当前 Tab 的块（轮询刷新后也要保留用户选择）。 */
  refreshBlocks() {
    const tab = this.data.tabs[this.data.activeTabIndex];
    if (!tab) return;
    const pills = this.pills[tab.key] || {};
    const blocks = buildTree(tab.sections || [], '').map(node => toBlock(node, '', pills[node.id]));
    const merged = injectProducts(blocks, tab.products, tab.key === 'diet');
    this.setData({ blocks: filterRenderableBlocks(merged) });
  },

  handleMediaTap(event) {
    const dataset = event.currentTarget.dataset || {};
    const key = String(dataset.key || '');
    const kind = String(dataset.kind || '');
    const source = String(dataset.source || '');
    if (!key || !source) {
      wx.showToast({ title: '该内容暂不可播放', icon: 'none' });
      return;
    }
    if (this.data.activeMediaKey === key) {
      if (kind === 'AUDIO' && this.audioContext) {
        if (this.data.audioPlaying) this.audioContext.pause();
        else this.audioContext.play();
        return;
      }
      this.stopMedia();
      return;
    }
    this.stopMedia();
    if (kind === 'AUDIO') {
      this.startAudio(key, source);
      return;
    }
    this.setData({ activeMediaKey: key, audioPlaying: false });
  },

  startAudio(key, source) {
    if (typeof wx === 'undefined' || !wx.createInnerAudioContext) {
      wx.showToast({ title: '当前环境不支持音频播放', icon: 'none' });
      return;
    }
    const audio = wx.createInnerAudioContext();
    audio.src = source;
    audio.autoplay = true;
    audio.onPlay(() => this.setData({ audioPlaying: true }));
    audio.onPause(() => this.setData({ audioPlaying: false }));
    audio.onEnded(() => this.setData({ audioPlaying: false }));
    audio.onError(() => {
      this.setData({ audioPlaying: false });
      wx.showToast({ title: '音频播放失败', icon: 'none' });
    });
    this.audioContext = audio;
    this.setData({ activeMediaKey: key, audioPlaying: true });
  },

  stopMedia(updateData = true) {
    if (this.audioContext) {
      try {
        this.audioContext.stop();
        this.audioContext.destroy();
      } catch (error) {
        // 音频上下文可能已被系统回收。
      }
      this.audioContext = null;
    }
    if (updateData !== false) {
      this.setData({ activeMediaKey: '', audioPlaying: false });
    }
  },

  startPolling() {
    this.stopPolling();
    if (this.pollCount >= MAX_POLLS) return;
    this.pollTimer = setTimeout(() => {
      this.pollCount += 1;
      api
        .refreshTongueReport(this.reportId)
        .then(report => this.applyReport(report))
        .catch(() => {
          if (this.pollCount < MAX_POLLS) this.startPolling();
        });
    }, POLL_INTERVAL);
  },

  stopPolling() {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }
});
