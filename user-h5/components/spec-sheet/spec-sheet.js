const { buildSpecState } = require('../../utils/spec-sheet');

Component({
  properties: {
    visible: { type: Boolean, value: false },
    product: { type: Object, value: {} },
    mode: { type: String, value: 'add' },
    initialQuantity: { type: Number, value: 1 },
    initialSelectedOptionIds: { type: Array, value: [] }
  },
  data: {
    state: buildSpecState({})
  },
  observers: {
    'visible, product, mode, initialQuantity, initialSelectedOptionIds'(
      visible,
      product,
      mode,
      initialQuantity,
      initialSelectedOptionIds
    ) {
      if (visible) {
        this.setData({
          state: buildSpecState(product, { mode, initialQuantity, initialSelectedOptionIds })
        });
      }
    }
  },
  methods: {
    noop() {},
    handleClose() {
      this.triggerEvent('close');
    },
    handleShare() {
      wx.showToast({ title: '分享暂未接入', icon: 'none' });
    },
    handleScroll() {
      if (this.data.state.showTasteTip) {
        this.setData({ 'state.showTasteTip': false });
      }
    },
    toggleExpand() {
      this.setData({ 'state.expanded': !this.data.state.expanded });
    },
    selectOption(event) {
      const { groupId, optionId } = event.currentTarget.dataset;
      const specGroups = this.data.state.specGroups.map(group => {
        if (group.id !== groupId) return group;
        return {
          ...group,
          options: group.options.map(option => ({
            ...option,
            selected: option.id === optionId
          }))
        };
      });
      const nextState = buildSpecState(
        {
          ...this.properties.product,
          specDetail: {
            ...this.properties.product.specDetail,
            specGroups
          }
        },
        {
          mode: this.properties.mode,
          initialQuantity: this.data.state.quantity
        }
      );
      this.setData({
        state: {
          ...nextState,
          expanded: this.data.state.expanded,
          quantity: this.data.state.quantity,
          favorite: this.data.state.favorite,
          showTasteTip: false
        }
      });
    },
    increaseQuantity() {
      this.setData({ 'state.quantity': this.data.state.quantity + 1 });
    },
    decreaseQuantity() {
      if (this.data.state.quantity <= 1) return;
      this.setData({ 'state.quantity': this.data.state.quantity - 1 });
    },
    toggleFavorite() {
      const favorite = !this.data.state.favorite;
      this.setData({ 'state.favorite': favorite });
      this.triggerEvent('favorite', { favorite });
    },
    handleBuy() {
      this.triggerEvent('buy', this.buildOrderPayload());
    },
    handleAddCart() {
      this.triggerEvent('addcart', this.buildOrderPayload());
    },
    handleUpdateCart() {
      this.triggerEvent('updatecart', this.buildOrderPayload());
    },
    buildOrderPayload() {
      return {
        product: this.properties.product,
        selectedOptions: this.data.state.selectedOptions,
        quantity: this.data.state.quantity,
        unitPrice: this.data.state.memberPrice,
        storedValuePrice: this.data.state.storedValuePrice,
        originalPrice: this.data.state.originalPrice,
        specText: this.data.state.specText
      };
    }
  }
});
