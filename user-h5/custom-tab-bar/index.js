const api = require('../utils/api');
const tabBarConfig = require('../utils/tab-bar-config');

Component({
  data: {
    selected: 0,
    hidden: false,
    ready: false,
    list: []
  },
  lifetimes: {
    attached() {
      this.syncFeaturePolicy();
    }
  },
  pageLifetimes: {
    show() {
      this.syncFeaturePolicy();
    }
  },
  methods: {
    syncFeaturePolicy() {
      return tabBarConfig.ready(api.fetchConfig).then(() => {
        const list = tabBarConfig.getTabBarItems();
        if (JSON.stringify(list) !== JSON.stringify(this.data.list) || !this.data.ready) {
          this.setData({ list, ready: true });
        }
      });
    }
  }
});
