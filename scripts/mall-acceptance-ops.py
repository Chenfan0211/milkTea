#!/usr/bin/env python3
"""Run on the authorized production host; default is a read-only inspection.

Credentials are read in memory from existing deployment configuration, never logged
or saved. Business snapshots exclude customer, address and payment information.
"""
import argparse
import json
import os
import shlex
import subprocess

RUN_CODE = 'QA-MALL-20261103-WXPAY'
BACKUP_DIR = '/opt/wuling/backup/mall-entry-wechat-20261103'


def credentials():
    values = {}
    for path in ['/opt/wuling/deploy/.env', '/opt/wuling/app/secrets.env']:
        if not os.path.isfile(path):
            continue
        with open(path) as source:
            for line in source:
                if '=' not in line or line.lstrip().startswith('#'):
                    continue
                key, value = line.strip().split('=', 1)
                if key not in ['MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD']:
                    continue
                words = shlex.split(value, comments=True)
                values[key] = words[0] if words else ''
    if not values.get('MYSQL_ROOT_PASSWORD'):
        raise RuntimeError('Authorized deployment database credential is missing')
    return values


def mysql(sql, values):
    result = subprocess.run(
        ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + values['MYSQL_ROOT_PASSWORD'],
         'wuling-mysql', 'mysql', '-uroot', '--default-character-set=utf8mb4',
         '--batch', '--raw', '--skip-column-names', 'wuling'],
        input=sql.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Do not include command arguments or deployment configuration in errors.
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace')[:500])
    return result.stdout.decode('utf-8')


def rows(table, condition, values):
    columns = mysql("SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema='wuling' AND table_name='%s' "
                    "ORDER BY ordinal_position" % table, values).splitlines()
    expression = ','.join("'%s',`%s`" % (column, column) for column in columns)
    result = mysql('SELECT JSON_OBJECT(%s) FROM `%s` WHERE %s' %
                   (expression, table, condition), values)
    return [json.loads(line) for line in result.splitlines() if line]


def snapshot(values):
    os.makedirs(BACKUP_DIR, mode=0o700, exist_ok=True)
    path = os.path.join(BACKUP_DIR, 'business-before.json')
    if os.path.exists(path):
        return
    data = {
        'app_config': rows('app_config', "config_key='profile_functions' AND deleted=0", values),
        'mall_category': rows('mall_category', "code LIKE 'QA-MALL-%'", values),
        'mall_product': rows('mall_product', "product_code LIKE 'QA-MALL-%'", values),
        'mall_sku': rows('mall_sku', "product_id IN (SELECT id FROM mall_product "
                        "WHERE product_code LIKE 'QA-MALL-%')", values)
    }
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as output:
        json.dump(data, output, ensure_ascii=True, indent=2)


def inspect(values):
    print(mysql("SELECT 'category',id,code,status FROM mall_category "
                "WHERE code LIKE 'QA-MALL-%%' AND deleted=0;"
                "SELECT 'product',id,product_code,status FROM mall_product "
                "WHERE product_code LIKE 'QA-MALL-%%' AND deleted=0;"
                "SELECT 'sku',s.id,s.sale_price,s.distribution_commission,s.stock,s.locked_stock "
                "FROM mall_sku s JOIN mall_product p ON p.id=s.product_id "
                "WHERE p.product_code='%s' AND s.deleted=0;" % RUN_CODE, values))
    configs = rows('app_config', "config_key='profile_functions' AND deleted=0", values)
    for config in configs:
        functions = config['value']
        if isinstance(functions, str):
            functions = json.loads(functions)
        print(json.dumps({'profile_status': config['status'],
                          'profile_function_ids': [item.get('id') for item in functions]},
                         ensure_ascii=True))


def prepare(values):
    supplier = mysql("SELECT supplier_subject_id FROM mall_product WHERE deleted=0 "
                     "AND product_code='DEMO-MALL-001' AND supplier_subject_id IS NOT NULL LIMIT 1",
                     values).strip()
    if not supplier.isdigit():
        raise RuntimeError('A valid existing supplier is required before preparing test data')
    snapshot(values)
    # Never reset stock or amounts of an existing test product on a second run.
    sql = """
    START TRANSACTION;
    UPDATE mall_product SET status='OFF_SALE'
      WHERE deleted=0 AND product_code LIKE 'QA-MALL-%%' AND product_code<>'%(code)s';
    UPDATE mall_category SET status='DISABLED'
      WHERE deleted=0 AND code LIKE 'QA-MALL-%%' AND code<>'%(code)s';
    INSERT INTO mall_category(code,name,icon_image,sort,status)
      SELECT '%(code)s','微信支付验收专用','/assets/images/3x/mall-category-placeholder.jpg',9999,'ENABLED'
      WHERE NOT EXISTS(SELECT 1 FROM mall_category WHERE code='%(code)s');
    INSERT INTO mall_product(product_code,category_id,name,subtitle,spec_name,cover_image,
      gallery_images,description,supplier_subject_id,status,sort)
      SELECT '%(code)s',c.id,'微信支付验收商品（非实物）','仅限本次验收，完成后下架','规格',
        '/assets/images/3x/mall-category-placeholder.jpg',
        JSON_ARRAY('/assets/images/3x/mall-category-placeholder.jpg'),
        '测试专用，不发实物。真实微信支付验收，单价0.01元，佣金0。',
        (SELECT supplier_subject_id FROM mall_product WHERE deleted=0
          AND product_code='DEMO-MALL-001' AND supplier_subject_id IS NOT NULL LIMIT 1),
        'ON_SALE',9999
      FROM mall_category c WHERE c.code='%(code)s'
        AND NOT EXISTS(SELECT 1 FROM mall_product WHERE product_code='%(code)s');
    INSERT INTO mall_sku(product_id,sku_code,spec_value,image,sale_price,original_price,
      cost_price,platform_commission,distribution_commission,supplier_subject_id,stock,locked_stock,sort)
      SELECT p.id,'%(code)s-01','验收规格','/assets/images/3x/mall-category-placeholder.jpg',
        1,1,0,0,0,p.supplier_subject_id,6,0,0 FROM mall_product p
      WHERE p.product_code='%(code)s' AND p.supplier_subject_id IS NOT NULL
        AND NOT EXISTS(SELECT 1 FROM mall_sku WHERE sku_code='%(code)s-01');
    COMMIT;
    """ % {'code': RUN_CODE}
    mysql(sql, values)
    product = rows('mall_product', "product_code='%s'" % RUN_CODE, values)
    skus = rows('mall_sku', "sku_code='%s-01'" % RUN_CODE, values)
    if len(product) != 1 or not product[0]['supplier_subject_id'] or len(skus) != 1:
        raise RuntimeError('Test product did not pass supplier/SKU preflight')
    if skus[0]['sale_price'] != 1 or skus[0]['distribution_commission'] != 0:
        raise RuntimeError('Test price/commission differs from approved acceptance setup')
    inspect(values)


def cleanup(values):
    snapshot(values)
    mysql("START TRANSACTION; UPDATE mall_product SET status='OFF_SALE' "
          "WHERE product_code='%s' AND deleted=0; UPDATE mall_category SET status='DISABLED' "
          "WHERE code='%s' AND deleted=0; COMMIT;" % (RUN_CODE, RUN_CODE), values)
    inspect(values)


def evict_profile_cache(values):
    detail = json.loads(subprocess.check_output(['docker', 'inspect', 'wuling-server']))[0]
    env = dict(item.split('=', 1) for item in detail['Config']['Env'] if '=' in item)
    database = env.get('REDIS_DB_CACHE', '2') if env.get('REDIS_MODE', 'single') == 'single' else '0'
    if not database.isdigit():
        raise RuntimeError('Invalid configured Redis cache database')
    password = env.get('REDIS_PASSWORD') or values.get('REDIS_PASSWORD')
    command = ['docker', 'exec']
    if password:
        command += ['-e', 'REDISCLI_AUTH=' + password]
    command += ['wuling-redis', 'redis-cli', '-n', database, 'DEL',
                'wuling:cache:app_config:profile_functions']
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # redis-cli can exit 0 after AUTH fails and leave SELECT unapplied (DB0).
    # Only retry anonymously when Redis explicitly says no password is configured.
    if password and b'without any password configured' in result.stderr:
        result = subprocess.run(['docker', 'exec'] + command[4:],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode or result.stderr.strip() or result.stdout.strip() not in [b'0', b'1']:
        raise RuntimeError('Profile cache invalidation failed; configuration requires re-verification')


def enable_entry(values):
    snapshot(values)
    configs = rows('app_config', "config_key='profile_functions' AND deleted=0", values)
    if len(configs) != 1 or configs[0]['status'].upper() != 'ENABLED':
        raise RuntimeError('Expected exactly one enabled profile_functions configuration')
    config = configs[0]
    old = config['value']
    if isinstance(old, str):
        old = json.loads(old)
    updated = [item for item in old if item.get('id') != 'mall']
    updated.append({'id': 'mall', 'label': '线上商城',
                    'icon': '/assets/icons/lucide/shopping-bag.svg'})
    old_hex = json.dumps(old, ensure_ascii=True).encode('utf-8').hex()
    new_hex = json.dumps(updated, ensure_ascii=True).encode('utf-8').hex()
    changed = mysql("UPDATE app_config SET value=CAST(CONVERT(0x%s USING utf8mb4) AS JSON) "
                    "WHERE id=%d AND value=CAST(CONVERT(0x%s USING utf8mb4) AS JSON);"
                    "SELECT ROW_COUNT();" % (new_hex, config['id'], old_hex), values).strip()
    if changed not in ['0', '1']:
        raise RuntimeError('Unexpected profile configuration update result')
    actual = rows('app_config', "config_key='profile_functions' AND deleted=0", values)
    current = actual[0]['value'] if len(actual) == 1 else None
    if isinstance(current, str):
        current = json.loads(current)
    if current != updated:
        raise RuntimeError('Profile configuration changed concurrently; refusing to overwrite it')
    evict_profile_cache(values)
    print('profile_configuration_updated', changed)
    print('profile_configuration_cache_invalidated')
    inspect(values)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['inspect', 'prepare', 'cleanup', 'enable-entry'], default='inspect')
    args = parser.parse_args()
    handlers = {'inspect': inspect, 'prepare': prepare, 'cleanup': cleanup, 'enable-entry': enable_entry}
    handlers[args.action](credentials())
