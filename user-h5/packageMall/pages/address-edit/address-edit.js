const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mall = require('../../utils/mall');

const EMPTY_FORM = {
  recipientName: '',
  phone: '',
  provinceCode: '',
  provinceName: '',
  cityCode: '',
  cityName: '',
  districtCode: '',
  districtName: '',
  detailAddress: '',
  tag: '',
  isDefault: false
};

Page({
  data: {
    addressId: 0,
    editing: false,
    loading: false,
    saving: false,
    needLogin: false,
    error: '',
    form: Object.assign({}, EMPTY_FORM),
    region: [],
    regionText: ''
  },

  onLoad(options) {
    const addressId = Number(options && options.id) || 0;
    this.setData({ addressId, editing: Boolean(addressId) });
    if (!auth.isLoggedIn()) {
      this.setData({ needLogin: true });
      return;
    }
    if (addressId) this.loadAddress(addressId);
  },

  loadAddress(addressId) {
    this.setData({ loading: true, error: '' });
    api
      .fetchAddresses()
      .then(result => {
        const addresses = (Array.isArray(result) ? result : []).map(mall.normalizeAddress);
        const address = addresses.find(item => Number(item.id) === Number(addressId));
        if (!address) throw new Error('收货地址不存在');
        this.setData({
          loading: false,
          form: {
            recipientName: address.recipientName || '',
            phone: address.phone || '',
            provinceCode: address.provinceCode || '',
            provinceName: address.provinceName || '',
            cityCode: address.cityCode || '',
            cityName: address.cityName || '',
            districtCode: address.districtCode || '',
            districtName: address.districtName || '',
            detailAddress: address.detailAddress || '',
            tag: address.tag || '',
            isDefault: Boolean(address.isDefault)
          },
          region: [address.provinceName || '', address.cityName || '', address.districtName || ''].filter(Boolean),
          regionText: address.regionText || ''
        });
      })
      .catch(error => {
        this.setData({
          loading: false,
          error: (error && error.message) || '地址加载失败'
        });
      });
  },

  handleInput(event) {
    const field = event.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ [`form.${field}`]: event.detail.value });
  },

  onRegionChange(event) {
    const detail = event.detail || {};
    const names = Array.isArray(detail.value) ? detail.value : [];
    const codes = Array.isArray(detail.code) ? detail.code : [];
    if (names.length < 3 || codes.length < 3) return;
    this.setData({
      region: names,
      regionText: names.join(' '),
      'form.provinceName': names[0],
      'form.provinceCode': codes[0],
      'form.cityName': names[1],
      'form.cityCode': codes[1],
      'form.districtName': names[2],
      'form.districtCode': codes[2]
    });
  },

  toggleDefault() {
    this.setData({ 'form.isDefault': !this.data.form.isDefault });
  },

  validate() {
    const form = this.data.form;
    if (!String(form.recipientName || '').trim()) return '请输入收件人姓名';
    if (!/^1[3-9]\d{9}$/.test(String(form.phone || '').trim())) return '请输入正确的手机号';
    if (!form.provinceCode || !form.cityCode || !form.districtCode) return '请选择省市县';
    if (!String(form.detailAddress || '').trim()) return '请输入详细地址';
    if (String(form.detailAddress || '').trim().length > 255) return '详细地址不能超过 255 个字符';
    return '';
  },

  saveAddress() {
    if (this.data.saving) return;
    const message = this.validate();
    if (message) {
      wx.showToast({ title: message, icon: 'none' });
      return;
    }
    if (!auth.isLoggedIn()) {
      this.goLogin();
      return;
    }
    const form = this.data.form;
    const payload = {
      recipientName: String(form.recipientName || '').trim(),
      phone: String(form.phone || '').trim(),
      provinceCode: form.provinceCode,
      provinceName: form.provinceName,
      cityCode: form.cityCode,
      cityName: form.cityName,
      districtCode: form.districtCode,
      districtName: form.districtName,
      detailAddress: String(form.detailAddress || '').trim(),
      tag: String(form.tag || '').trim(),
      isDefault: Boolean(form.isDefault)
    };
    this.setData({ saving: true });
    const task = this.data.editing ? api.updateAddress(this.data.addressId, payload) : api.createAddress(payload);
    task
      .then(() => {
        this.setData({ saving: false });
        wx.showToast({ title: '地址已保存', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 500);
      })
      .catch(error => {
        this.setData({ saving: false });
        wx.showToast({ title: (error && error.message) || '保存失败', icon: 'none' });
      });
  },

  retry() {
    if (this.data.addressId) this.loadAddress(this.data.addressId);
  },

  goLogin() {
    loginGuard.requireLogin(
      () => {
        this.setData({ needLogin: false });
        if (this.data.addressId) this.loadAddress(this.data.addressId);
      },
      { reason: '登录后可维护收货地址' }
    );
  }
});
