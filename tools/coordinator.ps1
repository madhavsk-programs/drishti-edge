$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot 'apps\coordinator\.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $python)) {
    throw 'Coordinator environment is missing. Follow apps/coordinator/README.md first.'
}

Push-Location (Join-Path $projectRoot 'apps\coordinator')
try {
    & $python -m uvicorn drishti_coordinator.main:app --host 0.0.0.0 --port 8000
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
