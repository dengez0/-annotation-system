[CmdletBinding()]
param(
    [string]$OutputDirectory = "dist"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot $OutputDirectory
$releaseId = 'simplelabel-docker-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$stagingRoot = Join-Path ([IO.Path]::GetTempPath()) $releaseId
$releaseRoot = Join-Path $stagingRoot 'simplelabel'

try {
    & (Join-Path $projectRoot 'build_java.bat')
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed.' }

    New-Item -ItemType Directory -Force -Path $releaseRoot, $outputRoot | Out-Null
    $items = @(
        'backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar',
        '.dockerignore',
        '.gitattributes',
        'deploy/docker/Dockerfile',
        'deploy/docker/entrypoint.sh',
        'deploy/docker/compose.yml',
        'deploy/docker/compose.test.yml',
        'deploy/docker/.env.example',
        'deploy/docker/.env.test.example',
        'deploy/docker/deploy.sh',
        'deploy/docker/prepare_test_image.sh',
        'deploy/docker/test_stack.sh',
        'deploy/docker/generate_admin_device_tokens.py',
        'deploy/docker/configure_admin_token.py',
        'deploy/docker/verify.sh',
        'deploy/docker/verify_test.sh',
        'deploy/docker/README.md',
        'deploy/ubuntu/requirements-yolo-worker.txt',
        'deploy/ubuntu/validate_runtime_env.sh',
        'deploy/ubuntu/wait_for_worker.sh',
        'services/__init__.py',
        'services/yolo_backend.py',
        'services/yolo_legacy_loader.py',
        'services/yolo_result_parser.py',
        'static',
        'yolo-worker',
        'model-detection-python',
        'yolov5'
    )

    foreach ($item in $items) {
        $source = Join-Path $projectRoot $item
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing release item: $item" }
        $destination = Join-Path $releaseRoot $item
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
    }

    $runtime = Join-Path $releaseRoot 'runtime'
    foreach ($name in @('data', 'models', 'logs', 'admin', 'processed', 'backups')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $runtime $name) | Out-Null
    }
    $testRuntime = Join-Path $releaseRoot 'runtime-test'
    foreach ($name in @('data', 'models', 'logs', 'admin', 'processed', 'backups')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $testRuntime $name) | Out-Null
    }

    $checksumLines = Get-ChildItem -LiteralPath $releaseRoot -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($releaseRoot.Length + 1).Replace('\', '/')
        "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
    }
    [IO.File]::WriteAllText(
        (Join-Path $releaseRoot 'SHA256SUMS'),
        (($checksumLines -join "`n") + "`n"),
        [Text.Encoding]::ASCII)

    $archive = Join-Path $outputRoot ($releaseId + '.tar.gz')
    & tar.exe -C $stagingRoot -czf $archive simplelabel
    if ($LASTEXITCODE -ne 0) { throw 'tar archive creation failed.' }
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(
        ($archive + '.sha256'),
        "$archiveHash  $([IO.Path]::GetFileName($archive))`n",
        [Text.Encoding]::ASCII)

    Write-Host "Built: $archive"
    Write-Host "SHA256: $archiveHash"
} finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}
