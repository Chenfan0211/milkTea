export interface SplitSnapshotLike {
  orderType?: string | null;
  platformAmount?: number | null;
  supplierAmount?: number | null;
  leaderAmount?: number | null;
  storeAmount?: number | null;
  channelAmount?: number | null;
  investorAmount?: number | null;
}

export interface SplitParty {
  key: 'platform' | 'supplier' | 'leader' | 'store' | 'channel' | 'investor';
  label: string;
  amount: number;
}

export interface SplitComposition {
  isMall: boolean;
  orderTypeLabel: string;
  currentParties: SplitParty[];
  legacyParties: SplitParty[];
  isLegacyMall: boolean;
}

const definitions: Record<SplitParty['key'], string> = {
  platform: '平台',
  supplier: '供应商',
  leader: '团长',
  store: '门店',
  channel: '资源方',
  investor: '投资人'
};

function party(key: SplitParty['key'], amount?: number | null): SplitParty {
  return { key, label: definitions[key], amount: Number(amount) || 0 };
}

export function splitComposition(row: SplitSnapshotLike): SplitComposition {
  const orderType = String(row.orderType || '').toUpperCase();
  const isMall = orderType === 'MALL';
  const all = {
    platform: party('platform', row.platformAmount),
    supplier: party('supplier', row.supplierAmount),
    leader: party('leader', row.leaderAmount),
    store: party('store', row.storeAmount),
    channel: party('channel', row.channelAmount),
    investor: party('investor', row.investorAmount)
  };

  if (isMall) {
    const legacyParties = [all.store, all.channel, all.investor].filter(item => item.amount !== 0);
    return {
      isMall: true,
      orderTypeLabel: '商城订单',
      currentParties: [all.platform, all.supplier, all.leader],
      legacyParties,
      isLegacyMall: legacyParties.length > 0
    };
  }

  return {
    isMall: false,
    orderTypeLabel: orderType === 'STORE' ? '门店订单' : '未标记订单',
    currentParties: [all.platform, all.supplier, all.store, all.channel, all.investor, all.leader],
    legacyParties: [],
    isLegacyMall: false
  };
}
