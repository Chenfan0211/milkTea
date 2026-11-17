import assert from 'node:assert/strict';
import test from 'node:test';
import { describeMallSplit } from './split-detail';
import type { MallOrder } from '@/service/api/mall';

function order(overrides: Partial<MallOrder>): MallOrder {
  return { id: 1, orderNo: 'MO1', orderType: 'MALL', status: 'COMPLETED', userId: 10, paidAmount: 1300, ...overrides };
}

test('leader buyer keeps own ID and gets no commission', () => {
  const view = describeMallSplit(
    order({
      userId: 10,
      leaderUserId: 10,
      leaderSubjectId: 41,
      leaderAttributionType: 'SELF',
      split: { snapshotNo: 'SN1', platformShare: 600, costTotal: 400, leaderShare: 0 }
    })
  );
  assert.equal(view.attribution, '本人团长价，本单团长佣金 0');
  assert.equal(view.leaderUserId, '10');
  assert.equal(view.leaderSubjectId, '41');
  assert.equal(view.state, '已分账');
  assert.equal(view.leader.original, 0);
});

test('other leader displays original, refund and net from ledger summary', () => {
  const view = describeMallSplit(
    order({
      leaderUserId: 20,
      leaderSubjectId: 42,
      leaderAttributionType: 'OTHER',
      split: {
        snapshotNo: 'SN2',
        platformShare: 700,
        costTotal: 500,
        leaderShare: 100,
        refundPlatform: 300,
        refundSupplier: 200,
        refundLeader: 50,
        netPlatform: 400,
        netSupplier: 300,
        netLeader: 50
      }
    })
  );
  assert.equal(view.attribution, '归属团长');
  assert.equal(view.leaderUserId, '20');
  assert.deepEqual(view.leader, { original: 100, refunded: 50, net: 50 });
  assert.deepEqual(view.platform, { original: 700, refunded: 300, net: 400 });
  assert.deepEqual(view.supplier, { original: 500, refunded: 200, net: 300 });
});

test('unbound and historical orders do not invent a leader ID', () => {
  const unbound = describeMallSplit(
    order({ leaderAttributionType: 'PLATFORM', leaderUserId: null, leaderSubjectId: null })
  );
  assert.equal(unbound.attribution, '未绑定团长，佣金归平台');
  assert.equal(unbound.leaderUserId, '—');
  const historical = describeMallSplit(
    order({ leaderAttributionType: null, leaderUserId: null, leaderSubjectId: null })
  );
  assert.equal(historical.attribution, '历史归属未记录');
});

test('pending and missing snapshot states are distinct', () => {
  assert.equal(describeMallSplit(order({ status: 'PAID', split: null })).state, '待分账');
  assert.equal(describeMallSplit(order({ status: 'CANCELED', split: null })).state, '未产生分账');
  assert.equal(describeMallSplit(order({ status: 'COMPLETED', split: null })).state, '暂无分账快照，请核对账务');
});

test('historical mall snapshot includes legacy parties in conservation check', () => {
  const view = describeMallSplit(
    order({
      paidAmount: 1300,
      split: {
        snapshotNo: 'SN3',
        platformShare: 600,
        costTotal: 400,
        leaderShare: 100,
        storeShare: 100,
        channelShare: 50,
        investorShare: 50
      }
    })
  );
  assert.equal(view.totalCheck, true);
  assert.equal(view.legacy, 200);
});
