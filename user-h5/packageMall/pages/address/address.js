const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mall = require('../../utils/mall');

Page({
  data: {
    mode: 'manage',
    selectedId: 0,
    addresses: [],
    loading: true,
    needLogin: false,
    error: '',
    actionBusy: false
  },

  onLoad(options) {
    this.setData({
      mode: options && options.mode === 'select' ? 'select' : 'manage',
      selectedId: Number(options && options.selectedId) || 0
    });
  },

  onShow() {
    this.loadAddresses();
  },

  loadAddresses() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, addresses: [], error: '' });
      return;
    }
    this.setData({ loading: true, needLogin: false, error: '' });
    api
      .fetchAddresses()
      .then(result => {
        const addresses = (Array.isArray(result) ? result : []).map(mall.normalizeAddress).sort((left, right) => {
          if (left.isDefault !== right.isDefault) return left.isDefault ? -1 : 1;
          return Number(right.id) - Number(left.id);
        });
        this.setData({ addresses, loading: false });
      })
      .catch(error => {
        this.setData({
          loading: false,
          error: (error && error.message) || '地址加载失败'
        });
      });
  },

  selectAddress(event) {
    if (this.data.mode !== 'select') {
      this.editAddress(event);
      return;
    }
    const id = Number(event.currentTarget.dataset.id);
    const address = this.data.addresses.find(item => Number(item.id) === id);
    if (!address) return;
    const channel = typeof this.getOpenerEventChannel === 'function' ? this.getOpenerEventChannel() : null;
    if (channel && channel.emit) channel.emit('selectAddress', address);
    wx.navigateBack();
  },

  addAddress() {
    wx.navigateTo({ url: '/packageMall/pages/address-edit/address-edit' });
  },

  editAddress(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!id) return;
    wx.navigateTo({ url: `/packageMall/pages/address-edit/address-edit?id=${id}` });
  },

  setDefault(event) {
    const id = Number(event.currentTarget.dataset.id);
    const address = this.data.addresses.find(item => Number(item.id) === id);
    if (!id || !address || address.isDefault || this.data.actionBusy) return;
    this.setData({ actionBusy: true });
    api
      .setDefaultAddress(id)
      .then(() => {
        this.setData({ actionBusy: false });
        this.loadAddresses();
        wx.showToast({ title: '已设为默认地址', icon: 'none' });
      })
      .catch(error => {
        this.setData({ actionBusy: false });
        wx.showToast({ title: (error && error.message) || '设置失败', icon: 'none' });
      });
  },

  removeAddress(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!id || this.data.actionBusy) return;
    wx.showModal({
      title: '删除地址',
      content: '确定删除该收货地址吗？',
      success: ({ confirm }) => {
        if (!confirm) return;
        this.setData({ actionBusy: true });
        api
          .deleteAddress(id)
          .then(() => {
            this.setData({ actionBusy: false });
            this.loadAddresses();
            wx.showToast({ title: '地址已删除', icon: 'none' });
          })
          .catch(error => {
            this.setData({ actionBusy: false });
            wx.showToast({ title: (error && error.message) || '删除失败', icon: 'none' });
          });
      }
    });
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadAddresses(), { reason: '登录后管理收货地址' });
  },

  retry() {
    this.loadAddresses();
  }
});
