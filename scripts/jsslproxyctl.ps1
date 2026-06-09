$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$jar = if ($env:JSSLPROXY_JAR) { $env:JSSLPROXY_JAR } else { Join-Path $root "build\libs\JSSLProxy-1.0-SNAPSHOT.jar" }

if (-not (Test-Path $jar)) {
    Push-Location $root
    try {
        .\gradlew.bat --no-daemon jar | Out-Null
    } finally {
        Pop-Location
    }
}

java -jar $jar ctl @args
exit $LASTEXITCODE
