Component({
  properties: {
    store: { type: Object, value: {} },
    selected: { type: Boolean, value: false },
    favoriteInteractive: { type: Boolean, value: false }
  },
  methods: {
    noop() {},
    handleSelect() {
      this.triggerEvent('select', { store: this.data.store });
    },
    handlePhone() {
      this.triggerEvent('phone', { store: this.data.store });
    },
    handleNavigate() {
      this.triggerEvent('navigate', { store: this.data.store });
    },
    handleFavorite() {
      if (!this.data.favoriteInteractive) return;
      this.triggerEvent('favorite', { store: this.data.store });
    }
  }
});
