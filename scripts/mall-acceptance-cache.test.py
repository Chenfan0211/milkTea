"""Regression: Redis AUTH warnings must not silently invalidate the wrong DB."""
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mall_ops', Path(__file__).with_name('mall-acceptance-ops.py'))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class ProfileCacheEvictionTest(unittest.TestCase):
    def setUp(self):
        self.detail = json.dumps([{'Config': {'Env': ['REDIS_MODE=single', 'REDIS_DB_CACHE=2',
                                                    'REDIS_PASSWORD=placeholder-only']}}]).encode()

    def result(self, output=b'1\n', error=b'', code=0):
        return subprocess.CompletedProcess([], code, stdout=output, stderr=error)

    def test_authenticated_server_uses_only_the_requested_cache_key(self):
        with patch.object(ops.subprocess, 'check_output', return_value=self.detail):
            with patch.object(ops.subprocess, 'run', return_value=self.result()) as run:
                ops.evict_profile_cache({})
        args = run.call_args.args[0]
        self.assertEqual(args[-4:], ['-n', '2', 'DEL', 'wuling:cache:app_config:profile_functions'])
        self.assertEqual(run.call_count, 1)

    def test_no_server_password_retries_without_auth_and_keeps_database_two(self):
        warning = b'AUTH failed: ERR AUTH <password> called without any password configured for the default user.'
        with patch.object(ops.subprocess, 'check_output', return_value=self.detail):
            with patch.object(ops.subprocess, 'run', side_effect=[self.result(b'0\n', warning), self.result()]) as run:
                ops.evict_profile_cache({})
        self.assertEqual(run.call_count, 2, 'AUTH failure may leave redis-cli in DB0; repeat the exact command')
        args = run.call_args.args[0]
        self.assertNotIn('-e', args)
        self.assertEqual(args[args.index('-n') + 1], '2')
        self.assertEqual(args[-1], 'wuling:cache:app_config:profile_functions')

    def test_wrong_password_never_falls_back_to_anonymous_or_claims_success(self):
        with patch.object(ops.subprocess, 'check_output', return_value=self.detail):
            with patch.object(ops.subprocess, 'run', return_value=self.result(b'0\n', b'AUTH failed: WRONGPASS')) as run:
                with self.assertRaisesRegex(RuntimeError, 'invalidation failed'):
                    ops.evict_profile_cache({})
        self.assertEqual(run.call_count, 1)

    def test_failed_retry_does_not_claim_success(self):
        warning = b'AUTH failed: ERR AUTH <password> called without any password configured for the default user.'
        with patch.object(ops.subprocess, 'check_output', return_value=self.detail):
            with patch.object(ops.subprocess, 'run', side_effect=[self.result(b'0\n', warning), self.result(b'', b'connection failed', 1)]):
                with self.assertRaisesRegex(RuntimeError, 'invalidation failed'):
                    ops.evict_profile_cache({})


if __name__ == '__main__':
    unittest.main()
