"""Execute the actual mapper SELECT against read-only MySQL 8 CTE fixtures.

Requires an explicitly named authorized SSH host with the existing acceptance
operator helper. No real business table is queried: the CTE shadows its name.
Deployment credentials remain in memory on that host and are never returned.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ssh-host', required=True)
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.@:-]+', args.ssh_host):
    raise SystemExit('Invalid SSH host')

root = Path(__file__).resolve().parent.parent
source = (root / 'trade-service/src/main/java/com/wuling/trade/mapper/MallAfterSaleMapper.java').read_text(encoding='utf-8')
match = re.search(r'@Select\(([^@]*?)\)\s*int selectSumRefundedQuantity\(', source, re.S)
if not match:
    raise SystemExit('Quantity mapper annotation not found')
sql = ''.join(json.loads('"' + part + '"') for part in re.findall(r'"((?:\\.|[^"\\])*)"', match.group(1)))
if not sql.lstrip().lower().startswith('select ') or ';' in sql:
    raise SystemExit('Only the mapper read-only SELECT is allowed')

fixtures = [
    (10001, 0, 'SUCCESS', [{'orderItemId': 84, 'quantity': 1}, {'orderItemId': 85, 'quantity': 4}]),
    (10001, 0, 'REFUNDING', [{'orderItemId': 84, 'quantity': 2}]),
    (10001, 0, 'APPROVED', [{'orderItemId': 84, 'quantity': 3}]),
    (10001, 0, 'FAILED', [{'orderItemId': 84, 'quantity': 100}]),
    (10001, 0, 'PENDING', [{'orderItemId': 84, 'quantity': 100}]),
    (10001, 1, 'SUCCESS', [{'orderItemId': 84, 'quantity': 100}]),
    (10002, 0, 'SUCCESS', [{'orderItemId': 84, 'quantity': 7}]),
]
rows = []
for order_id, deleted, status, items in fixtures:
    payload = json.dumps(items, separators=(',', ':')).encode('utf-8').hex()
    rows.append("SELECT %d order_id,%d deleted,'%s' status,CONVERT(0x%s USING utf8mb4) items" %
                (order_id, deleted, status, payload))
cte = 'WITH mall_after_sale AS (' + ' UNION ALL '.join(rows) + ') '
cases = [(10001, 84, 6), (10001, 85, 4), (10002, 84, 7), (10001, 999, 0)]
queries = [cte + sql.replace('#{orderId}', str(order)).replace('#{orderItemId}', str(item))
           for order, item, _ in cases]

remote = """
import json,runpy
ops=runpy.run_path('/opt/wuling/scripts/mall-acceptance-ops-20261103.py')
credentials=ops['credentials']()
queries=json.loads(%r)
expected=json.loads(%r)
for index,(query,want) in enumerate(zip(queries,expected),1):
    actual=int(ops['mysql'](query,credentials).strip())
    if actual!=want: raise SystemExit('Fixture %%d: expected %%d, actual %%d'%%(index,want,actual))
    print('Fixture %%d PASS: quantity=%%d'%%(index,actual))
""" % (json.dumps(queries), json.dumps([case[2] for case in cases]))
result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                         args.ssh_host, 'python3 -'], input=remote.encode('utf-8'))
raise SystemExit(result.returncode)
