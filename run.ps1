$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    $jar = if (Test-Path -LiteralPath (Join-Path $PSScriptRoot "probeloom.jar")) { "probeloom.jar" } else { "test-platform-deploy/target/probeloom.jar" }
    java "-Dprobeloom.home=$PSScriptRoot" -jar $jar @args
    exit $LASTEXITCODE
}
finally { Pop-Location }
