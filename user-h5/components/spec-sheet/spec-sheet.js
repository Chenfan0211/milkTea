function roundMoney(value, digits = 2) {
  const factor = Math.pow(10, digits)
  return Math.round(value * factor) / factor
}

function cloneSpecGroups(groups = []) {
  return groups.map(group => ({
    ...group,
    options: group.options.map(option => ({ ...option }))
  }))
}

function buildState(product = {}) {
  const detail = product.specDetail || {}
  const specGroups = cloneSpecGroups(detail.specGroups)
  const selectedOptions = []
  const selectedLabels = []

  for (const group of specGroups) {
    const selected = group.options.find(option => option.selected)
    if (selected) {
      selectedOptions.push({ groupId: group.id, ...selected })
      selectedLabels.push(selected.label)
    }
  }

  const addOnTotal = selectedOptions.reduce((sum, option) => sum + (option.priceDelta || 0), 0)
  const startPrice = detail.startPrice || product.price || 0
  const discountRate = detail.discountRate || 1
  const memberPrice = roundMoney(startPrice * discountRate + addOnTotal)
  const originalPrice = roundMoney(startPrice + addOnTotal)
  const specText = selectedLabels.length > 2
    ? `[${selectedLabels.slice(0, 2).join(',')}],${selectedLabels.slice(2).join(',')}`
    : selectedLabels.join(',')

  return {
    expanded: false,
    quantity: 1,
    favorite: false,
    showTasteTip: true,
    specGroups,
    selectedOptions,
    memberPrice,
    originalPrice,
    specText
  }
}

Component({
  properties: {
    visible: { type: Boolean, value: false },
    product: { type: Object, value: {} }
  },
  data: {
    state: buildState({})
  },
  observers: {
    'visible, product'(visible, product) {
      if (visible) this.setData({ state: buildState(product) })
    }
  },
  methods: {
    noop() {},
    handleClose() {
      this.triggerEvent('close')
    },
    handleShare() {
      wx.showToast({ title: '分享暂未接入', icon: 'none' })
    },
    handleScroll() {
      if (this.data.state.showTasteTip) {
        this.setData({ 'state.showTasteTip': false })
      }
    },
    toggleExpand() {
      this.setData({ 'state.expanded': !this.data.state.expanded })
    },
    selectOption(event) {
      const { groupId, optionId } = event.currentTarget.dataset
      const specGroups = this.data.state.specGroups.map(group => {
        if (group.id !== groupId) return group
        return {
          ...group,
          options: group.options.map(option => ({
            ...option,
            selected: option.id === optionId
          }))
        }
      })
      const nextState = buildState({
        ...this.properties.product,
        specDetail: {
          ...this.properties.product.specDetail,
          specGroups
        }
      })
      this.setData({
        state: {
          ...nextState,
          expanded: this.data.state.expanded,
          quantity: this.data.state.quantity,
          favorite: this.data.state.favorite,
          showTasteTip: false
        }
      })
    },
    increaseQuantity() {
      this.setData({ 'state.quantity': this.data.state.quantity + 1 })
    },
    decreaseQuantity() {
      if (this.data.state.quantity <= 1) return
      this.setData({ 'state.quantity': this.data.state.quantity - 1 })
    },
    toggleFavorite() {
      const favorite = !this.data.state.favorite
      this.setData({ 'state.favorite': favorite })
      this.triggerEvent('favorite', { favorite })
    },
    handleBuy() {
      this.triggerEvent('buy', this.buildOrderPayload())
    },
    handleAddCart() {
      this.triggerEvent('addcart', this.buildOrderPayload())
    },
    buildOrderPayload() {
      return {
        product: this.properties.product,
        selectedOptions: this.data.state.selectedOptions,
        quantity: this.data.state.quantity,
        unitPrice: this.data.state.memberPrice,
        originalPrice: this.data.state.originalPrice,
        specText: this.data.state.specText
      }
    }
  }
})