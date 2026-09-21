const { withShare } = require('../../utils/share');
const { getServiceFaqs, getServiceInfo, getServiceStores } = require('../../data/service');

Page(
  withShare({
    data: {
      info: {},
      serviceHours: '',
      onlineNote: '',
      faqs: [],
      storeList: [],
      expandedFaqId: ''
    },
    onLoad() {
      const info = getServiceInfo();
      this.setData({
        info,
        serviceHours: info.serviceHours,
        onlineNote: info.onlineNote,
        faqs: getServiceFaqs(),
        storeList: getServiceStores()
      });
    },
    // 常见问题手风琴：再次点击同一项收起
    toggleFaq(event) {
      const id = event.currentTarget.dataset.id;
      this.setData({ expandedFaqId: this.data.expandedFaqId === id ? '' : id });
    },
    // 拨号：号码为空或无效时不唤起，避免系统报错
    callPhone(event) {
      const phone = String(event.currentTarget.dataset.phone || '').trim();
      if (!phone) {
        wx.showToast({ title: '暂无联系电话', icon: 'none' });
        return;
      }
      wx.makePhoneCall({
        phoneNumber: phone,
        fail() {
          // 用户取消拨号属正常操作，不提示错误
        }
      });
    }
  })
);