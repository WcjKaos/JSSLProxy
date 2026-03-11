# ========================================
# SSL 证书生成脚本 (Windows PowerShell 版本)
# ========================================
# 用于生成自签名证书，供 Java SSL Proxy 测试使用
#
# 使用方法：
#   .\generate-certs.ps1
#
# 前置要求：
#   1. 安装 OpenSSL (推荐使用 Chocolatey: choco install openssl)
#      或从 https://slproweb.com/products/Win32OpenSSL.html 下载
#   2. 确保 Java JDK 已安装（需要 keytool 命令）
#
# 生成的文件：
#   - ca.key, ca.crt         # CA 根证书
#   - server.key, server.crt # 服务端证书
#   - server.p12             # 服务端 PKCS12 密钥库
#   - client.key, client.crt # 客户端证书  
#   - client.p12             # 客户端 PKCS12 证书
#   - truststore.jks         # 信任库 (包含 CA 证书)
# ========================================

# 遇到错误时停止执行
$ErrorActionPreference = "Stop"

# 配置变量（脚本已切换到项目根目录，所以使用相对路径）
$CERT_DIR = "src\main\resources\certificates"
$PASSWORD = "changeit"
$CA_SUBJECT = "/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy CA/CN=SSL Proxy CA"
$SERVER_SUBJECT = "/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy/CN=localhost"
$CLIENT_SUBJECT = "/C=CN/ST=Beijing/L=Beijing/O=SSL Proxy Client/CN=test-client"

# 证书有效期（天）
$DAYS = 3650

Write-Host "========================================"
Write-Host "  SSL 证书生成工具 (Windows 版)"
Write-Host "========================================"
Write-Host ""

# 检查 OpenSSL 是否可用
try {
    $null = Get-Command openssl -ErrorAction Stop
    Write-Host "✓ 检测到 OpenSSL" -ForegroundColor Green
} catch {
    Write-Host "✗ 错误：未找到 OpenSSL 命令" -ForegroundColor Red
    Write-Host ""
    Write-Host "请安装 OpenSSL："
    Write-Host "  方法1: 使用 Chocolatey"
    Write-Host "    choco install openssl"
    Write-Host ""
    Write-Host "  方法2: 从官网下载安装"
    Write-Host "    https://slproweb.com/products/Win32OpenSSL.html"
    Write-Host ""
    exit 1
}

# 查找或创建 OpenSSL 配置文件
$opensslConfigPath = $null
$possiblePaths = @(
    "$env:OPENSSL_CONF",
    "C:\Program Files\OpenSSL-Win64\bin\openssl.cfg",
    "C:\Program Files\OpenSSL\bin\openssl.cfg",
    "C:\OpenSSL-Win64\bin\openssl.cfg",
    "C:\Program Files (x86)\OpenSSL-Win32\bin\openssl.cfg"
)

foreach ($path in $possiblePaths) {
    if ($path -and (Test-Path $path)) {
        $opensslConfigPath = $path
        Write-Host "✓ 找到 OpenSSL 配置文件: $opensslConfigPath" -ForegroundColor Green
        break
    }
}

# 如果找不到配置文件，创建一个临时的
if (-not $opensslConfigPath) {
    Write-Host "! 未找到 OpenSSL 配置文件，创建临时配置..." -ForegroundColor Yellow
    $opensslConfigPath = Join-Path $env:TEMP "openssl_temp.cnf"
    
    $minimalConfig = @"
[ req ]
default_bits        = 2048
distinguished_name  = req_distinguished_name
x509_extensions     = v3_ca
prompt              = no

[ req_distinguished_name ]
C  = CN
ST = Beijing
L  = Beijing
O  = SSL Proxy
CN = localhost

[ v3_ca ]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical,CA:true

[ v3_req ]
basicConstraints = CA:FALSE
keyUsage = nonRepudiation, digitalSignature, keyEncipherment
"@
    Set-Content -Path $opensslConfigPath -Value $minimalConfig -Encoding ASCII
    Write-Host "✓ 临时配置文件已创建" -ForegroundColor Green
}

# 设置环境变量
$env:OPENSSL_CONF = $opensslConfigPath

# 检查 keytool 是否可用
try {
    $null = Get-Command keytool -ErrorAction Stop
    Write-Host "✓ 检测到 keytool (Java)" -ForegroundColor Green
} catch {
    Write-Host "✗ 错误：未找到 keytool 命令" -ForegroundColor Red
    Write-Host ""
    Write-Host "请确保已安装 Java JDK 并添加到 PATH 环境变量"
    Write-Host ""
    exit 1
}

Write-Host ""

# 创建证书目录
Write-Host "创建证书目录..."
New-Item -Path "$CERT_DIR\server" -ItemType Directory -Force | Out-Null
New-Item -Path "$CERT_DIR\trusted" -ItemType Directory -Force | Out-Null

# 切换到证书目录
Push-Location $CERT_DIR

try {
    Write-Host ""
    Write-Host "[1/6] 生成 CA 根证书私钥..." -ForegroundColor Cyan
    openssl genrsa -out ca.key 4096
    if ($LASTEXITCODE -ne 0) { throw "生成 CA 私钥失败" }

    Write-Host ""
    Write-Host "[2/6] 生成 CA 根证书..." -ForegroundColor Cyan
    openssl req -new -x509 -days $DAYS -key ca.key -out ca.crt -subj $CA_SUBJECT
    if ($LASTEXITCODE -ne 0) { throw "生成 CA 证书失败" }

    Write-Host ""
    Write-Host "[3/6] 生成服务器私钥和证书请求..." -ForegroundColor Cyan
    openssl genrsa -out server\server.key 2048
    if ($LASTEXITCODE -ne 0) { throw "生成服务器私钥失败" }
    
    openssl req -new -key server\server.key -out server\server.csr -subj $SERVER_SUBJECT
    if ($LASTEXITCODE -ne 0) { throw "生成服务器 CSR 失败" }

    Write-Host ""
    Write-Host "[4/6] 签发服务器证书..." -ForegroundColor Cyan
    # 创建 SAN 扩展文件
    $sanContent = @"
subjectAltName = DNS:localhost,DNS:*.localhost,IP:127.0.0.1
extendedKeyUsage = serverAuth
"@
    Set-Content -Path "server\san.ext" -Value $sanContent -Encoding ASCII

    openssl x509 -req -in server\server.csr -CA ca.crt -CAkey ca.key `
        -CAcreateserial -out server\server.crt -days $DAYS `
        -extfile server\san.ext
    if ($LASTEXITCODE -ne 0) { throw "签发服务器证书失败" }

    # 生成服务器 PKCS12 密钥库
    openssl pkcs12 -export -in server\server.crt -inkey server\server.key `
        -out server\server.p12 -name "server" `
        -CAfile ca.crt -caname "ca" -chain `
        -password "pass:$PASSWORD"
    if ($LASTEXITCODE -ne 0) { throw "生成服务器 P12 文件失败" }

    Write-Host ""
    Write-Host "[5/6] 生成客户端证书..." -ForegroundColor Cyan
    openssl genrsa -out trusted\client.key 2048
    if ($LASTEXITCODE -ne 0) { throw "生成客户端私钥失败" }
    
    openssl req -new -key trusted\client.key -out trusted\client.csr -subj $CLIENT_SUBJECT
    if ($LASTEXITCODE -ne 0) { throw "生成客户端 CSR 失败" }

    # 创建客户端扩展文件
    $clientExtContent = @"
extendedKeyUsage = clientAuth
"@
    Set-Content -Path "trusted\client.ext" -Value $clientExtContent -Encoding ASCII

    openssl x509 -req -in trusted\client.csr -CA ca.crt -CAkey ca.key `
        -CAcreateserial -out trusted\client.crt -days $DAYS `
        -extfile trusted\client.ext
    if ($LASTEXITCODE -ne 0) { throw "签发客户端证书失败" }

    # 生成客户端 PKCS12 证书
    openssl pkcs12 -export -in trusted\client.crt -inkey trusted\client.key `
        -out trusted\client.p12 -name "client" `
        -CAfile ca.crt -caname "ca" -chain `
        -password "pass:$PASSWORD"
    if ($LASTEXITCODE -ne 0) { throw "生成客户端 P12 文件失败" }
    
    # 为 Windows curl (Schannel) 和 Java 测试生成 PEM 格式的客户端证书
    Write-Host "  生成 PEM 格式客户端证书..." -ForegroundColor DarkGray
    
    # 将私钥转换为 PKCS#8 格式（Java 需要）
    openssl pkcs8 -topk8 -inform PEM -outform PEM -in trusted\client.key `
        -out trusted\client-pkcs8.key -nocrypt
    if ($LASTEXITCODE -ne 0) { throw "转换私钥为 PKCS#8 格式失败" }
    
    # 合并证书和 PKCS#8 私钥到一个 PEM 文件
    Get-Content trusted\client.crt, trusted\client-pkcs8.key | Set-Content trusted\client-full.pem -Encoding ASCII

    Write-Host ""
    Write-Host "[6/6] 生成信任库..." -ForegroundColor Cyan
    # 将 CA 证书导入到 PKCS12 信任库
    # 如果文件已存在，先删除
    if (Test-Path "trusted\truststore.p12") {
        Remove-Item "trusted\truststore.p12" -Force
    }
    
    keytool -import -trustcacerts -alias ca -file ca.crt `
        -keystore trusted\truststore.p12 -storetype PKCS12 -storepass $PASSWORD -noprompt
    if ($LASTEXITCODE -ne 0) { throw "生成信任库失败" }

    Write-Host ""
    Write-Host "✓ 证书生成完成！" -ForegroundColor Green
    
    Write-Host ""
    Write-Host "清理中间文件..." -ForegroundColor Cyan
    
    # 删除中间文件
    $intermediateFiles = @(
        "server\san.ext",
        "server\server.csr",
        "server\server.crt",
        "server\server.key",
        "trusted\client.ext",
        "trusted\client.csr",
        "trusted\client.crt",
        "trusted\client.key",
        "trusted\client-pkcs8.key",
        "ca.key",
        "ca.srl"
    )
    
    foreach ($file in $intermediateFiles) {
        if (Test-Path $file) {
            Remove-Item $file -Force
            Write-Host "  ✓ 已删除: $file" -ForegroundColor DarkGray
        }
    }
    
    Write-Host ""
    Write-Host "最终保留的文件："
    Write-Host "  CA 证书:            $(Resolve-Path 'ca.crt')" -ForegroundColor Yellow
    Write-Host "  服务端密钥库:        $(Resolve-Path 'server\server.p12')" -ForegroundColor Yellow
    Write-Host "  客户端证书 (P12):   $(Resolve-Path 'trusted\client.p12')" -ForegroundColor Yellow
    Write-Host "  客户端证书 (PEM):   $(Resolve-Path 'trusted\client-full.pem')" -ForegroundColor Yellow
    Write-Host "  信任库:             $(Resolve-Path 'trusted\truststore.p12')" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "密码: $PASSWORD" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "下一步："
    Write-Host "1. 更新 application.yml 中的证书路径："
    Write-Host "   ssl.keystore.path: certificates/server/server.p12"
    Write-Host "   ssl.truststore.path: certificates/trusted/truststore.p12"
    Write-Host ""
    Write-Host "2. 设置环境变量 (PowerShell)："
    Write-Host "   `$env:KEYSTORE_PASSWORD = '$PASSWORD'"
    Write-Host "   `$env:TRUSTSTORE_PASSWORD = '$PASSWORD'"
    Write-Host ""
    Write-Host "   或永久设置 (系统环境变量)："
    Write-Host "   [System.Environment]::SetEnvironmentVariable('KEYSTORE_PASSWORD', '$PASSWORD', 'User')"
    Write-Host "   [System.Environment]::SetEnvironmentVariable('TRUSTSTORE_PASSWORD', '$PASSWORD', 'User')"
    Write-Host ""
    Write-Host "3. 启动代理服务器："
    Write-Host "   .\gradlew.bat run"
    Write-Host ""
    Write-Host "4. 使用客户端证书测试 (需要安装 curl)："
    Write-Host "   curl --cert certificates/trusted/client.p12:$PASSWORD ``"
    Write-Host "        --cacert certificates/ca.crt ``"
    Write-Host "        https://localhost:8443"
    Write-Host ""
    Write-Host "========================================"

} catch {
    Write-Host ""
    Write-Host "✗ 错误: $_" -ForegroundColor Red
    Write-Host ""
    exit 1
} finally {
    # 返回原目录
    Pop-Location
}

