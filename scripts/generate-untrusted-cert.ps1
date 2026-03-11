# ========================================
# 生成不受信任的客户端证书 (用于测试)
# ========================================
# 此脚本生成一个由不同 CA 签发的客户端证书
# 用于测试服务器拒绝未授权证书的场景
#
# 使用方法：
#   .\generate-untrusted-cert.ps1
#
# 生成的文件：
#   - untrusted-ca.crt       # 不受信任的 CA 证书
#   - untrusted-client.p12   # 由该 CA 签发的客户端证书
# ========================================

$ErrorActionPreference = "Stop"

# 配置变量
$CERT_DIR = "src\main\resources\certificates\untrusted"
$PASSWORD = "changeit"
$UNTRUSTED_CA_SUBJECT = "/C=CN/ST=Shanghai/L=Shanghai/O=Untrusted CA/CN=Untrusted CA"
$UNTRUSTED_CLIENT_SUBJECT = "/C=CN/ST=Shanghai/L=Shanghai/O=Untrusted Client/CN=untrusted-client"
$DAYS = 365

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  生成不受信任的客户端证书" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "用途：测试服务器拒绝未授权证书" -ForegroundColor Yellow
Write-Host ""

# 检查 OpenSSL
try {
    $null = Get-Command openssl -ErrorAction Stop
    Write-Host "✓ 检测到 OpenSSL" -ForegroundColor Green
} catch {
    Write-Host "✗ 错误：未找到 OpenSSL" -ForegroundColor Red
    exit 1
}

# 查找或创建 OpenSSL 配置
$opensslConfigPath = $null
$possiblePaths = @(
    "$env:OPENSSL_CONF",
    "C:\Program Files\OpenSSL-Win64\bin\openssl.cfg",
    "C:\Program Files\OpenSSL\bin\openssl.cfg",
    "C:\OpenSSL-Win64\bin\openssl.cfg"
)

foreach ($path in $possiblePaths) {
    if ($path -and (Test-Path $path)) {
        $opensslConfigPath = $path
        break
    }
}

if (-not $opensslConfigPath) {
    $opensslConfigPath = Join-Path $env:TEMP "openssl_temp.cnf"
    $minimalConfig = @"
[ req ]
default_bits        = 2048
distinguished_name  = req_distinguished_name
x509_extensions     = v3_ca
prompt              = no

[ req_distinguished_name ]
C  = CN
ST = Shanghai
L  = Shanghai
O  = Untrusted CA
CN = Untrusted CA

[ v3_ca ]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical,CA:true

[ v3_req ]
basicConstraints = CA:FALSE
keyUsage = nonRepudiation, digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
"@
    Set-Content -Path $opensslConfigPath -Value $minimalConfig -Encoding ASCII
}

$env:OPENSSL_CONF = $opensslConfigPath

Write-Host ""

# 创建目录
Write-Host "创建证书目录..." -ForegroundColor Cyan
New-Item -Path $CERT_DIR -ItemType Directory -Force | Out-Null

Push-Location $CERT_DIR

try {
    Write-Host ""
    Write-Host "[1/4] 生成不受信任的 CA 私钥..." -ForegroundColor Cyan
    openssl genrsa -out untrusted-ca.key 4096 2>$null
    if ($LASTEXITCODE -ne 0) { throw "生成 CA 私钥失败" }

    Write-Host "[2/4] 生成不受信任的 CA 证书..." -ForegroundColor Cyan
    openssl req -new -x509 -days $DAYS -key untrusted-ca.key -out untrusted-ca.crt -subj $UNTRUSTED_CA_SUBJECT
    if ($LASTEXITCODE -ne 0) { throw "生成 CA 证书失败" }

    Write-Host "[3/4] 生成不受信任的客户端私钥和 CSR..." -ForegroundColor Cyan
    openssl genrsa -out untrusted-client.key 2048 2>$null
    if ($LASTEXITCODE -ne 0) { throw "生成客户端私钥失败" }
    
    openssl req -new -key untrusted-client.key -out untrusted-client.csr -subj $UNTRUSTED_CLIENT_SUBJECT
    if ($LASTEXITCODE -ne 0) { throw "生成客户端 CSR 失败" }

    # 创建扩展文件
    $extContent = @"
extendedKeyUsage = clientAuth
"@
    Set-Content -Path "client.ext" -Value $extContent -Encoding ASCII

    Write-Host "[4/4] 签发不受信任的客户端证书..." -ForegroundColor Cyan
    openssl x509 -req -in untrusted-client.csr -CA untrusted-ca.crt -CAkey untrusted-ca.key `
        -CAcreateserial -out untrusted-client.crt -days $DAYS `
        -extfile client.ext
    if ($LASTEXITCODE -ne 0) { throw "签发客户端证书失败" }

    # 生成 PKCS12 证书
    openssl pkcs12 -export -in untrusted-client.crt -inkey untrusted-client.key `
        -out untrusted-client.p12 -name "untrusted-client" `
        -CAfile untrusted-ca.crt -caname "untrusted-ca" -chain `
        -password "pass:$PASSWORD"
    if ($LASTEXITCODE -ne 0) { throw "生成 P12 文件失败" }
    
    # 生成 PEM 格式（用于 curl）
    Get-Content untrusted-client.crt, untrusted-client.key | Set-Content untrusted-client-full.pem -Encoding ASCII

    Write-Host ""
    Write-Host "✓ 证书生成完成！" -ForegroundColor Green
    
    Write-Host ""
    Write-Host "清理中间文件..." -ForegroundColor Cyan
    
    $intermediateFiles = @(
        "untrusted-ca.key",
        "untrusted-ca.srl",
        "untrusted-client.key",
        "untrusted-client.csr",
        "untrusted-client.crt",
        "client.ext"
    )
    
    foreach ($file in $intermediateFiles) {
        if (Test-Path $file) {
            Remove-Item $file -Force
            Write-Host "  ✓ 已删除: $file" -ForegroundColor DarkGray
        }
    }
    
    Write-Host ""
    Write-Host "最终保留的文件：" -ForegroundColor Yellow
    Write-Host "  不受信任的 CA:     $(Resolve-Path 'untrusted-ca.crt')" -ForegroundColor Yellow
    Write-Host "  不受信任的客户端:   $(Resolve-Path 'untrusted-client.p12')" -ForegroundColor Yellow
    Write-Host "  不受信任的客户端(PEM): $(Resolve-Path 'untrusted-client-full.pem')" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "密码: $PASSWORD" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "用途说明：" -ForegroundColor Green
    Write-Host "  此证书由独立的 CA 签发，不在服务器的 truststore 中"
    Write-Host "  用于测试服务器拒绝未授权证书的场景"
    Write-Host ""
    Write-Host "测试命令：" -ForegroundColor Green
    Write-Host "  # 使用 curl 测试（预期：连接被拒绝）"
    Write-Host "  curl.exe --cert src/main/resources/certificates/untrusted/untrusted-client-full.pem ``"
    Write-Host "           --cacert src/main/resources/certificates/ca.crt ``"
    Write-Host "           https://localhost:18443"
    Write-Host ""
    Write-Host "  # 使用 Java 测试"
    Write-Host "  .\gradlew.bat test --tests SimpleClientTest.testWithUntrustedCert"
    Write-Host ""
    Write-Host "========================================"

} catch {
    Write-Host ""
    Write-Host "✗ 错误: $_" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}

