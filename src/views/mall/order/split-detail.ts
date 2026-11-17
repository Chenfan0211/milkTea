import type { MallOrder, MallOrderSplit } from '@/service/api/mall';

export interface SplitPart {
  original: number | null;
  refunded: number | null;
  net: number | null;
}

function amount(value: number | null | undefined): number | null {
  return value == null || !Number.isFinite(Number(value)) ? null : Number(value);
}

function part(
  split: MallOrderSplit | null | undefined,
  originalKey: keyof MallOrderSplit,
  refundedKey: keyof MallOrderSplit,
  netKey: keyof MallOrderSplit,
  hasRefund: boolean
): SplitPart {
  const original = amount(split?.[originalKey] as number | null | undefined);
  return {
    original,
    refunded: amount(split?.[refundedKey] as number | null | undefined) ?? (hasRefund ? null : 0),
    net: amount(split?.[netKey] as number | null | undefined) ?? (hasRefund ? null : original)
  };
}

export function describeMallSplit(row: MallOrder) {
  const attribution =
    row.leaderAttributionType === 'SELF'
      ? '本人团长价，本单团长佣金 0'
      : row.leaderAttributionType === 'OTHER'
        ? '归属团长'
        : row.leaderAttributionType === 'PLATFORM'
          ? '未绑定团长，佣金归平台'
          : '历史归属未记录';
  const state = row.split?.snapshotNo
    ? '已分账'
    : row.status === 'CANCELED'
      ? '未产生分账'
      : row.status === 'COMPLETED' || row.status === 'REFUNDED'
        ? '暂无分账快照，请核对账务'
        : '待分账';
  const hasRefund = Boolean(row.refundStatus && row.refundStatus !== 'NONE') || row.status === 'REFUNDED';
  const platform = part(row.split, 'platformShare', 'refundPlatform', 'netPlatform', hasRefund);
  const supplier = part(row.split, 'costTotal', 'refundSupplier', 'netSupplier', hasRefund);
  const leader = part(row.split, 'leaderShare', 'refundLeader', 'netLeader', hasRefund);
  const legacy =
    (amount(row.split?.storeShare) ?? 0) +
    (amount(row.split?.channelShare) ?? 0) +
    (amount(row.split?.investorShare) ?? 0);
  const totalCheck =
    row.split?.snapshotNo &&
    platform.original != null &&
    supplier.original != null &&
    leader.original != null &&
    row.paidAmount != null
      ? platform.original + supplier.original + leader.original + legacy === Number(row.paidAmount)
      : null;
  return {
    attribution,
    state,
    leaderUserId: row.leaderUserId == null ? '—' : String(row.leaderUserId),
    leaderSubjectId: row.leaderSubjectId == null ? '—' : String(row.leaderSubjectId),
    platform,
    supplier,
    leader,
    legacy,
    totalCheck
  };
}
