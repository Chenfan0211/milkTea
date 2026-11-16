import importlib.util
import pathlib
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mall_ops', pathlib.Path(__file__).with_name('mall-acceptance-ops.py'))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class AcceptanceOperationsTest(unittest.TestCase):
    def test_inspection_uses_only_selects_and_preserves_qa_prefix(self):
        with patch.object(ops, 'mysql', return_value='') as sql, patch.object(ops, 'rows', return_value=[]):
            with redirect_stdout(StringIO()):
                ops.inspect({})
        query = sql.call_args.args[0]
        self.assertIn("LIKE 'QA-MALL-%'", query)
        self.assertNotIn('UPDATE ', query)
        self.assertNotIn('INSERT ', query)

    def test_missing_supplier_does_not_change_or_snapshot_anything(self):
        with patch.object(ops, 'mysql', return_value='') as sql, patch.object(ops, 'snapshot') as snapshot:
            with self.assertRaisesRegex(RuntimeError, 'supplier'):
                ops.prepare({})
        self.assertEqual(sql.call_count, 1)
        snapshot.assert_not_called()

    def test_concurrent_configuration_change_does_not_invalidate_cache_or_claim_success(self):
        before = {'id': 1, 'status': 'enabled', 'value': [{'id': 'service'}]}
        concurrent = {'id': 1, 'status': 'enabled', 'value': [{'id': 'cooperation'}]}
        with patch.object(ops, 'snapshot'), patch.object(ops, 'rows', side_effect=[[before], [concurrent]]):
            with patch.object(ops, 'mysql', return_value='0'), patch.object(ops, 'evict_profile_cache') as evict:
                with self.assertRaisesRegex(RuntimeError, 'concurrently'):
                    ops.enable_entry({})
        evict.assert_not_called()


if __name__ == '__main__':
    unittest.main()
