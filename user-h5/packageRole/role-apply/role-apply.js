const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { getRoleDefinitions, hasRole, applyRole } = require('../../utils/roles');
const { getStoreTypes } = require('../../utils/store-types');

const FIELD_CONFIG = {
  store: [
    { id: 'name', label: '姓名', placeholder: '请输入姓名', type: 'text' },
    { id: 'phone', label: '手机号', placeholder: '请输入手机号', type: 'number' },
    { id: 'storeName', label: '门店名称', placeholder: '请输入门店名称', type: 'text' },
    { id: 'storeAddress', label: '门店地址', placeholder: '请输入门店地址', type: 'text' },
    { id: 'storeType', label: '门店类型', placeholder: '请选择门店类型', type: 'select', options: [] }
  ],
  investor: [
    { id: 'name', label: '姓名', placeholder: '请输入姓名', type: 'text' },
    { id: 'phone', label: '手机号', placeholder: '请输入手机号', type: 'number' },
    { id: 'investLocation', label: '意向投资点位', placeholder: '请输入意向投资点位', type: 'text' },
    { id: 'investBudget', label: '投资预算', placeholder: '请输入投资预算（元）', type: 'number' }
  ],
  resource: [
    { id: 'name', label: '姓名', placeholder: '请输入姓名', type: 'text' },
    { id: 'phone', label: '手机号', placeholder: '请输入手机号', type: 'number' },
    { id: 'resourceLocation', label: '资源方所在地', placeholder: '请输入资源方所在地', type: 'text' },
    { id: 'storeType', label: '门店类型', placeholder: '请选择门店类型', type: 'select', options: [] }
  ]
};

function createEmptyForm() {
  return {
    name: '',
    phone: '',
    storeName: '',
    storeAddress: '',
    storeType: '',
    investLocation: '',
    investBudget: '',
    resourceLocation: ''
  };
}

Page(
  withShare({
    data: {
      roles: [],
      selectedRoleId: '',
      fields: [],
      form: createEmptyForm(),
      storeTypeOptions: []
    },
    onLoad() {
      const roles = getRoleDefinitions();
      this.setData({ roles });
      getStoreTypes().then(storeTypeOptions => {
        this.setData({ storeTypeOptions });
      });
    },
    selectRole(event) {
      const { id } = event.currentTarget.dataset;
      if (!id || hasRole(id)) return;
      const fields = (FIELD_CONFIG[id] || []).map(item =>
        item.id === 'storeType' ? Object.assign({}, item, { options: this.data.storeTypeOptions }) : item
      );
      this.setData({
        selectedRoleId: id,
        fields,
        form: createEmptyForm()
      });
    },
    handleInput(event) {
      const { field } = event.currentTarget.dataset;
      this.setData({ [`form.${field}`]: event.detail.value });
    },
    handleSelect(event) {
      const { field } = event.currentTarget.dataset;
      const index = Number(event.detail.value);
      const fieldConfig = this.data.fields.find(item => item.id === field);
      const option = (fieldConfig && fieldConfig.options && fieldConfig.options[index]) || null;
      if (option) this.setData({ [`form.${field}`]: option.name });
    },
    submit() {
      loginGuard.requirePhone(() => this.doSubmit(), { reason: '提交申请需要绑定手机号' });
    },

    doSubmit() {
      const { selectedRoleId, form, fields } = this.data;
      if (!selectedRoleId) {
        wx.showToast({ title: '请先选择经营角色', icon: 'none' });
        return;
      }
      if (hasRole(selectedRoleId)) {
        wx.showToast({ title: '该角色已申请，请勿重复提交', icon: 'none' });
        return;
      }
      const missing = fields.find(item => !String(form[item.id] || '').trim());
      if (missing) {
        wx.showToast({ title: `请填写${missing.label}`, icon: 'none' });
        return;
      }
      // 申请为服务端写操作：提交到后端审核队列，成功后再写本地待审核态
      api
        .applyBusinessRole(selectedRoleId, Object.assign({}, form))
        .then(() => {
          applyRole(selectedRoleId);
          wx.showToast({ title: '已提交，等待运营审核', icon: 'none' });
          setTimeout(() => wx.navigateBack(), 800);
        })
        .catch(error => {
          wx.showToast({ title: (error && error.message) || '提交失败，请稍后重试', icon: 'none' });
        });
    }
  })
);


