param(
    [string]$ComposeFile = "docker-compose.test.yml"
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[smoke] $Message"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required for the PowerShell smoke test."
}

Write-Step "running Docker smoke test"
docker compose -f $ComposeFile run --rm test-runner bash ./scripts/smoke-test.sh
if ($LASTEXITCODE -ne 0) {
    throw "Docker smoke test failed with exit code $LASTEXITCODE"
}

Write-Step "cleaning test compose services"
docker compose -f $ComposeFile down --remove-orphans
if ($LASTEXITCODE -ne 0) {
    throw "docker compose cleanup failed with exit code $LASTEXITCODE"
}

Write-Step "PASS: PowerShell smoke test completed"
