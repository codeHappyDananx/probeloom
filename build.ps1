param([string]$LocalRepository = (Join-Path $PSScriptRoot ".build/m2"))
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    Push-Location test-platform-ui
    try {
        pnpm install --frozen-lockfile --store-dir ../.cache/pnpm-store --registry=https://registry.npmjs.org
        if ($LASTEXITCODE) { throw "Frontend dependency installation failed" }
        pnpm build
        if ($LASTEXITCODE) { throw "Frontend build failed" }
    } finally { Pop-Location }
    mvn -B -s .mvn/public-settings.xml -gs .mvn/public-settings.xml "-Dmaven.repo.local=$LocalRepository" clean package
    if ($LASTEXITCODE) { throw "Maven build failed" }
} finally { Pop-Location }
