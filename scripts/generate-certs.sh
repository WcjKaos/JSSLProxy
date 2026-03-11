#!/bin/bash

# ========================================
# SSL 证书生成脚本
# ========================================
# 用于生成自签名证书，供 Java SSL Proxy 测试使用
#
# 使用方法：
#   ./generate-certs.sh
#
# 生成的文件：
#   - ca.key, ca.crt         # CA 根证书
#   - server.key, server.crt # 服务端证书
#   - server.p12             # 服务端 PKCS12 密钥库
#   - client.key, client.crt # 客户端证书  
#   - client.p12             # 客户端 PKCS12 证书
#   - truststore.jks         # 信任库 (包含 CA 证书)
# ========================================

set -e

# 配置变量
CERT_DIR="../src/main/resources/certificates"
PASSWORD="changeit"
CA_SUBJECT="/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy CA/CN=SSL Proxy CA"
SERVER_SUBJECT="/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy/CN=localhost"
CLIENT_SUBJECT="/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy Client/CN=test-client"

# 证书有效期（天）
DAYS=3650

echo "========================================"
echo "  SSL 证书生成工具"
echo "========================================"
echo ""

# 创建证书目录
mkdir -p "$CERT_DIR/server"
mkdir -p "$CERT_DIR/trusted"
cd "$CERT_DIR"

echo "[1/6] 生成 CA 根证书私钥..."
openssl genrsa -out ca.key 4096

echo "[2/6] 生成 CA 根证书..."
openssl req -new -x509 -days $DAYS -key ca.key -out ca.crt \
    -subj "$CA_SUBJECT"

echo "[3/6] 生成服务器私钥和证书请求..."
openssl genrsa -out server/server.key 2048
openssl req -new -key server/server.key -out server/server.csr \
    -subj "$SERVER_SUBJECT"

echo "[4/6] 签发服务器证书..."
# 创建 SAN 扩展文件
cat > server/san.ext << EOF
subjectAltName = DNS:localhost,DNS:*.localhost,IP:127.0.0.1
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in server/server.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out server/server.crt -days $DAYS \
    -extfile server/san.ext

# 生成服务器 PKCS12 密钥库
openssl pkcs12 -export -in server/server.crt -inkey server/server.key \
    -out server/server.p12 -name "server" \
    -CAfile ca.crt -caname "ca" -chain \
    -password pass:$PASSWORD

echo "[5/6] 生成客户端证书..."
openssl genrsa -out trusted/client.key 2048
openssl req -new -key trusted/client.key -out trusted/client.csr \
    -subj "$CLIENT_SUBJECT"

# 创建客户端扩展文件
cat > trusted/client.ext << EOF
extendedKeyUsage = clientAuth
EOF

openssl x509 -req -in trusted/client.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out trusted/client.crt -days $DAYS \
    -extfile trusted/client.ext

# 生成客户端 PKCS12 证书
openssl pkcs12 -export -in trusted/client.crt -inkey trusted/client.key \
    -out trusted/client.p12 -name "client" \
    -CAfile ca.crt -caname "ca" -chain \
    -password pass:$PASSWORD

# 为 curl 和 Java 测试生成 PEM 格式的客户端证书
# 将私钥转换为 PKCS#8 格式（Java 需要）
openssl pkcs8 -topk8 -inform PEM -outform PEM -in trusted/client.key \
    -out trusted/client-pkcs8.key -nocrypt

# 合并证书和 PKCS#8 私钥
cat trusted/client.crt trusted/client-pkcs8.key > trusted/client-full.pem

echo "[6/6] 生成信任库..."
# 将 CA 证书导入到 PKCS12 信任库
keytool -import -trustcacerts -alias ca -file ca.crt \
    -keystore trusted/truststore.p12 -storetype PKCS12 -storepass $PASSWORD -noprompt

echo ""
echo "✓ 证书生成完成！"

echo ""
echo "清理中间文件..."

# 删除中间文件
rm -f server/san.ext
rm -f server/server.csr
rm -f server/server.crt
rm -f server/server.key
rm -f trusted/client.ext
rm -f trusted/client.csr
rm -f trusted/client.crt
rm -f trusted/client.key
rm -f trusted/client-pkcs8.key
rm -f ca.key
rm -f ca.srl

echo "  ✓ 已删除中间文件"

echo ""
echo "最终保留的文件："
echo "  CA 证书:            $CERT_DIR/ca.crt"
echo "  服务端密钥库:        $CERT_DIR/server/server.p12"
echo "  客户端证书 (P12):   $CERT_DIR/trusted/client.p12"
echo "  客户端证书 (PEM):   $CERT_DIR/trusted/client-full.pem"
echo "  信任库:             $CERT_DIR/trusted/truststore.p12"
echo ""
echo "密码: $PASSWORD"
echo ""
echo "下一步："
echo "1. 更新 application.yml 中的证书路径："
echo "   ssl.keystore.path: certificates/server/server.p12"
echo "   ssl.truststore.path: certificates/trusted/truststore.p12"
echo ""
echo "2. 设置环境变量："
echo "   export KEYSTORE_PASSWORD=$PASSWORD"
echo "   export TRUSTSTORE_PASSWORD=$PASSWORD"
echo ""
echo "3. 启动代理服务器"
echo ""
echo "4. 使用客户端证书测试："
echo "   curl --cert certificates/trusted/client.p12:$PASSWORD \\"
echo "        --cacert certificates/ca.crt \\"
echo "        https://localhost:8443"
echo ""
echo "========================================"

