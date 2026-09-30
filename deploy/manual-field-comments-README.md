# 线上数据库字段注释补齐 · 执行手册

## 一、执行前准备

1. 确认能 SSH 登录服务器（已配好免密公钥）。
2. 上传脚本到服务器：

   ```bash
   scp deploy/manual-field-comments.sql root@<服务器IP>:/opt/wuling/
   ```

3. **执行前备份**（只读安全，强烈建议）：

   ```bash
   # 登录服务器后执行，备份整库结构（含注释）到文件
   docker exec wuling-mysql mysqldump -uroot -p --no-data wuling > /opt/wuling/schema_before_v62.sql
   ```

## 二、执行

```bash
# 登录服务器，进入 MySQL 执行脚本
docker exec -i wuling-mysql mysql -uroot -p wuling < /opt/wuling/manual-field-comments.sql
```

- 脚本共 66 条 ALTER（每表一条）、覆盖 635 个字段。
- MySQL 8.0 仅追加 COMMENT 走 INSTANT 算法：不锁表、不重建、几乎瞬时完成。
- 幂等：重复执行会重设为相同注释，可放心重跑。

## 三、执行后验证

```sql
-- 随机抽几张表，确认注释已生效
SHOW FULL COLUMNS FROM orders;
SHOW FULL COLUMNS FROM app_user;
SHOW FULL COLUMNS FROM product;

-- 统计：已带注释的字段数（应接近总字段数）
SELECT COUNT(*) AS commented_cols
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='wuling' AND COLUMN_COMMENT <> '';

-- 统计总字段数
SELECT COUNT(*) AS total_cols
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='wuling';
```

## 四、回滚方案（如需要）

因为只是给字段追加注释，不改字段类型/默认值/数据，回滚即把注释清空：

```bash
# 方式1：用备份恢复结构（会回退到执行前的表结构）
docker exec -i wuling-mysql mysql -uroot -p wuling < /opt/wuling/schema_before_v62.sql

# 方式2：仅清空本次新增的注释（保留其他历史注释）
# 见下方生成的 rollback 脚本（可选，一般不需要）
```

## 五、注意事项

- 本次只改字段 COMMENT，不碰任何索引、主键、外键、数据。
- gift_card_refund 表是 V50 动态 SQL 建的，本次已正确跳过其已注释字段。
- 执行期间业务可正常读写（INSTANT 算法无锁），但建议选低峰期执行更稳妥。
- Flyway 的 V62\_\_add_column_comments.sql 仍保留在迁移目录，供新环境部署时自动执行。
  线上手动执行后，若之后还要走 Flyway 部署，V62 会被 Flyway 再执行一遍；
  因为是幂等的 MODIFY COMMENT，重复执行无害。

> 建议：手动执行后，若之后还要走 Flyway 部署，可让 Flyway 再执行一遍（幂等无害），
> 或在线上 flyway_schema_history 表中手动补一条 V62 记录以保持一致。
