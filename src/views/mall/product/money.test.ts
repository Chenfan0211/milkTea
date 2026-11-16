import assert from 'node:assert/strict';
import test from 'node:test';
import { fenToYuan, yuanToFen } from './money';

test('yuan and fen conversion keeps two-decimal monetary precision', () => {
  assert.equal(yuanToFen(10), 1000);
  assert.equal(yuanToFen(0.1 + 0.2), 30);
  assert.equal(yuanToFen('12.34'), 1234);
  assert.equal(fenToYuan(1000), 10);
  assert.equal(fenToYuan(1234), 12.34);
});
