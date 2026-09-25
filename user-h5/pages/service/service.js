const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { resolveStoreCatalog } = require('../../utils/store');

Page(
  withShare({
    data: {
      info: {},
      serviceHours: '',
      onlineNote: '',
      faqs: [],
      storeList: [],
      expandedFaqId: '',
      loaded: false,
      loadFailed: false
    },
    /**
     * 客服信息与常见问题全部来自后台配置
     * （app_config.service_info / service_faqs，运营可在
     * 「系统审计 - 运营配置」自助修改，改完即时生效）。
     *
     * 修复说明：原实现内置一份本地硬编码的客服信息与 FAQ 作为兜底，
     * 与后台配置重复维护 —— 运营改了后台，页面仍可能显示旧文案。
     * 现改为「接口未返回即视为无数据」，由页面给出加载失败提示。
     */
    onLoad() {
      this.syncStoreList();
      Promise.all([api.fetchConfig('service_info'), api.fetchConfig('service_faqs')])
        .then(([info, faqs]) => {
          this.setData({ loaded: true, loadFailed: false });
          this.applyServiceInfo(
            info && typeof info === 'object' ? info : {},
            Array.isArray(faqs) ? faqs : []
          );
        })
        .catch(() => {
          // 不再回退本地假数据：明确告知用户，避免展示与后台不一致的文案
          this.setData({ loaded: true, loadFailed: true });
          this.applyServiceInfo({}, []);
          wx.showToast({ title: '客服信息加载失败，请稍后重试', icon: 'none' });
        });
    },
    /** 门店联系电话取自门店接口的本地镜像（非假数据） */
    syncStoreList() {
      const catalog = resolveStoreCatalog();
      const storeList = (catalog.stores || []).map(store => ({
        id: store.id,
        name: store.name,
        phone: store.phone,
        businessHours: store.businessHours
      }));
      this.setData({ storeList });
    },
    applyServiceInfo(info, faqs) {
      this.setData({
        info,
        serviceHours: info.serviceHours || '',
        onlineNote: info.onlineNote || '',
        faqs
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
