#!/bin/bash
# =============================================================
# 五零时光 · 微信支付 APIv3 证书落盘脚本
#
# 用途：把商户 API 证书与微信支付公钥放到 /opt/wuling/app/certs/，
#       权限收紧为 600，属主与容器运行用户（uid 1000）一致。
#
# 用法（在部署机上，证书文件已上传到 /tmp/wxpay/ 为例）：
#   ./wechat-pay-certs.sh /tmp/wxpay/apiclient_key.pem /tmp/wxpay/pub_key.pem
#
# 前置：需先下载好两个文件（微信支付商户平台 → 账户中心 → API 安全）：
#   - apiclient_key.pem  商户 API 证书私钥（请求签名用）
#   - pub_key.pem        微信支付公钥（回调验签用，公钥模式下必需）
#
# 说明：apiclient_cert.pem（商户证书公钥部分）在本项目的「公钥验签模式」下
#       不会被代码读取，可不放；私钥 + 微信支付公钥即可满足请求签名与回调验签。
# =============================================================
set -euo pipefail

CERT_DIR=/opt/wuling/app/certs

log()  { echo -e "\033[1;32m[certs]\033[0m $*"; }
err()  { echo -e "\033[1;31m[error]\033[0m $*" >&2; }

[ $# -ge 2 ] || { err "用法: $0 <apiclient_key.pem> <pub_key.pem>"; exit 1; }
PRIVATE_KEY="$1"
PUBLIC_KEY="$2"

[ -f "$PRIVATE_KEY" ] || { err "私钥不存在: $PRIVATE_KEY"; exit 1; }
[ -f "$PUBLIC_KEY" ]  || { err "公钥不存在: $PUBLIC_KEY"; exit 1; }

log "创建证书目录 $CERT_DIR"
mkdir -p "$CERT_DIR"

log "落盘证书"
install -m 600 -o 1000 -g 1000 "$PRIVATE_KEY" "$CERT_DIR/apiclient_key.pem"
install -m 600 -o 1000 -g 1000 "$PUBLIC_KEY"  "$CERT_DIR/pub_key.pem"

log "校验结果"
ls -l "$CERT_DIR"

# 关键校验：容器以 uid 1000 运行，必须能读到证书；权限过宽则告警
for f in apiclient_key.pem pub_key.pem; do
  p="$CERT_DIR/$f"
  perms=$(stat -c '%a' "$p")
  owner=$(stat -c '%u:%g' "$p")
  if [ "$perms" != "600" ]; then
    err "$f 权限异常（$perms，期望 600）"
  fi
  if [ "$owner" != "1000:1000" ]; then
    err "$f 属主异常（$owner，期望 1000:1000）"
  fi
done

log "完成。证书已就位：$CERT_DIR"
log "提醒：当前 PAY_CHANNEL=mock，证书暂不会被读取；备案通过切 wxpay 后生效。"
