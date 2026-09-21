const { calcMemberPrice, getUserLevel } = require('./pricing');

function roundMoney(value, digits = 2) {
  const factor = Math.pow(10, digits);
  return Math.round(value * factor) / factor;
}

function cloneSpecGroups(groups = []) {
  return groups.map(group => ({
    ...group,
    options: group.options.map(option => ({ ...option }))
  }));
}

function buildSpecState(product = {}, options = {}) {
  const detail = product.specDetail || {};
  const specGroups = cloneSpecGroups(detail.specGroups);
  const selectedIds = options.initialSelectedOptionIds || [];
  const selectedOptions = [];
  const selectedLabels = [];

  for (const group of specGroups) {
    const selected =
      group.options.find(option => selectedIds.includes(option.id)) || group.options.find(option => option.selected);

    group.options = group.options.map(option =>
      Object.assign({}, option, {
        selected: Boolean(selected && selected.id === option.id)
      })
    );

    if (selected) {
      selectedOptions.push({ groupId: group.id, ...selected });
      selectedLabels.push(selected.label);
    }
  }

  const addOnTotal = selectedOptions.reduce((sum, option) => sum + (option.priceDelta || 0), 0);
  const listPrice = detail.originalBasePrice || detail.startPrice || product.originalPrice || product.price || 0;
  const level = getUserLevel();
  const memberBasePrice = calcMemberPrice(listPrice);
  const memberPrice = roundMoney(memberBasePrice + addOnTotal);
  const originalPrice = roundMoney(listPrice + addOnTotal);
  const storedValueBase = detail.storedValuePrice || product.storedValuePrice || listPrice;
  const storedValueDiscount = roundMoney(listPrice - storedValueBase);
  const storedValuePrice = roundMoney(memberPrice - storedValueDiscount);
  const specText = selectedLabels.join(',');

  return {
    expanded: false,
    quantity: Math.max(1, Number(options.initialQuantity) || 1),
    favorite: false,
    showTasteTip: options.mode !== 'edit',
    specGroups,
    selectedOptions,
    memberPrice,
    originalPrice,
    storedValuePrice,
    specText
  };
}

module.exports = {
  buildSpecState,
  roundMoney
};
