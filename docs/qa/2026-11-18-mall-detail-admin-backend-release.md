# 商城详情长图、轮播初始化与运营后台生产发布

发布于 2026-11-18 14:46（北京时间）。本次更新 `server`、`product-service` 和运营后台静态站；微信小程序客户端未上传或发布。

## 发布产物

- 新镜像：`wuling/server:20261118-mall-detail-144110`、`wuling/product-service:20261118-mall-detail-144110`。
- 原镜像：`wuling/server:20261117-subject-code-170226`、`wuling/product-service:20261109-mall-qa`。
- `server` JAR SHA256：`1a2a62a8c73e2586c2684ca0d7d9a89a8b980051a4543edc1260b680b0d63a1a`。
- `product-service` JAR SHA256：`c30447233281f58a796cc5c589887092c85d2bb01a304ba9cbb4b2c9237e2941`。
- 本地、上传文件及镜像内 `/app/app.jar` 的 SHA256 均一致。
- 运营后台包：`web-https-20261118-144640.tar.gz`，本地 SHA256 为 `589bdf9d13ffebc456fb949f23d75f96464384ffd9e4873ac267f96bea67d8d1`。

## 备份与迁移

- 后端备份目录：`/opt/wuling/backup/20261118-mall-detail-144110/`，包含旧 JAR、容器与镜像元数据、部署文件及发布前全库备份。
- 全库备份：`/opt/wuling/backup/20261118-mall-detail-144110/wuling-before.sql.gz`，SHA256 为 `a7c086010478f71513cada6f04b9fb3708b3baaca891b6ff4c6893a6bdd17379`，gzip 校验通过。
- 运营后台原站：`/opt/wuling/web.old-20261118-144640`；归档：`/opt/wuling/backup/web-pre-20261118-144640.tar.gz`。
- Flyway V108、V109、V110 均执行成功，失败迁移 0 项。
- V109 新增 `mall_product.detail_images`；V110 写入 3 条商城轮播初始化数据。

## 验证结果

- 两个新服务容器及其余应用容器均为 `healthy`，公网 `/actuator/health` 返回 `UP`。
- `/api/v1/app/config/mall` 返回 3 条轮播；首条关联 `DEMO-MALL-005`，商品 ID 为 10，另外两条为纯展示。
- `/api/v1/app/mall/products` 正常返回；`DEMO-MALL-005` 详情包含 `detailImages` 字段。
- 未登录访问后台商城商品接口仍返回 401，网关鉴权正常。
- 运营后台首页、`/mall/product`、`/mall/banner` 均返回 200；本地与服务器 `index.html` SHA256 一致。
- Nginx 配置检查通过。

## 回滚

应用回滚：

```bash
cd /opt/wuling/build
IMAGE_TAG=20261117-subject-code-170226 docker compose -f docker-compose.prod.yml \
  --project-name wuling up -d --no-build server
IMAGE_TAG=20261109-mall-qa docker compose -f docker-compose.prod.yml \
  --project-name wuling up -d --no-build product-service
```

运营后台回滚：

```bash
mv /opt/wuling/web /opt/wuling/web.failed-20261118-144640
mv /opt/wuling/web.old-20261118-144640 /opt/wuling/web
systemctl reload nginx
```

数据库已执行 V108–V110。应用回退时保留新增列、轮播表及初始化数据；如需数据库恢复，使用发布前全库备份并按停写流程操作。
