import assert from 'node:assert/strict';
import test from 'node:test';
import { splitComposition } from './split-parties';

test('mall snapshot exposes only platform, supplier and leader as current parties', () => {
  const result = splitComposition({
    orderType: 'MALL',
    platformAmount: 1200,
    supplierAmount: 800,
    leaderAmount: 200,
    storeAmount: 0,
    channelAmount: 0,
    investorAmount: 0
  });

  assert.deepEqual(
    result.currentParties.map(party => party.key),
    ['platform', 'supplier', 'leader']
  );
  assert.equal(result.isLegacyMall, false);
});

test('store snapshot keeps store, channel and investor parties', () => {
  const result = splitComposition({
    orderType: 'STORE',
    platformAmount: 500,
    supplierAmount: 200,
    leaderAmount: 0,
    storeAmount: 200,
    channelAmount: 50,
    investorAmount: 50
  });

  assert.deepEqual(
    result.currentParties.map(party => party.key),
    ['platform', 'supplier', 'store', 'channel', 'investor', 'leader']
  );
});

test('historical mall investor settlement is separated from the current three parties', () => {
  const result = splitComposition({
    orderType: 'MALL',
    platformAmount: 1200,
    supplierAmount: 800,
    leaderAmount: 200,
    storeAmount: 0,
    channelAmount: 0,
    investorAmount: 300
  });

  assert.equal(result.isLegacyMall, true);
  assert.deepEqual(
    result.legacyParties.map(party => party.key),
    ['investor']
  );
  assert.equal(
    result.currentParties.some(party => party.key === 'investor'),
    false
  );
});
