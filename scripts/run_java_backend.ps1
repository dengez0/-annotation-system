param(
    [ValidateSet('Preview', 'Production')]
    [string]$Mode = 'Preview'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$port = if ($Mode -eq 'Production') { 18083 } else { 18084 }
$workerPort = 18085
$modelDetectionPort = if ($Mode -eq 'Production') { 8000 } else { 8001 }
$jarPath = Join-Path $root 'backend-java\target\simplelabel-java-1.0.0-SNAPSHOT.jar'
$workerScript = Join-Path $root 'yolo-worker\worker.py'
$modelDetectionScript = Join-Path $root 'model-detection-python\main.py'

function Test-ListeningPort([int]$Port) {
    try {
        return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
    } catch {
        $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        return [bool]($listeners | Where-Object Port -eq $Port)
    }
}

if (Test-ListeningPort $port) {
    if ($Mode -eq 'Production') {
        throw "Port $port is already in use. Stop the old Python server normally before starting Java. This script never terminates an existing service."
    }
    throw "Preview port $port is already in use. Close the previous Java preview window first."
}
if (Test-ListeningPort $workerPort) {
    throw "YOLO worker port $workerPort is already in use. Close the previous Java server window first."
}
if (Test-ListeningPort $modelDetectionPort) {
    throw "Model detection port $modelDetectionPort is already in use."
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Java JAR not found. Run build_java.bat first."
}
if (-not (Test-Path -LiteralPath $workerScript -PathType Leaf)) {
    throw "YOLO worker not found: $workerScript"
}
if (-not (Test-Path -LiteralPath $modelDetectionScript -PathType Leaf)) {
    throw "PT_ONNX model detection service not found: $modelDetectionScript"
}

$javaHome = Get-ChildItem -LiteralPath (Join-Path $root '.tools\jdk') -Directory | Select-Object -First 1
if (-not $javaHome) { throw 'Java 21 was not found under .tools\jdk.' }
$javaExecutable = Join-Path $javaHome.FullName 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw "Java executable not found: $javaExecutable" }

$venvPython = Join-Path $root '.venv\Scripts\python.exe'
if (Test-Path -LiteralPath $venvPython -PathType Leaf) {
    $pythonExecutable = $venvPython
} else {
    $pythonInstallRoot = Join-Path $env:LOCALAPPDATA 'Programs\Python'
    $installedPython = Get-ChildItem -LiteralPath $pythonInstallRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'python.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ($installedPython) {
        $pythonExecutable = $installedPython
    } else {
        $pythonExecutable = (Get-Command python.exe -ErrorAction Stop).Source
    }
}

$logs = Join-Path $root 'logs'
$admin = Join-Path $root 'admin'
$processed = Join-Path $root 'processed'
$backups = Join-Path $root 'backups'
New-Item -ItemType Directory -Force -Path $logs, $admin, $processed, $backups | Out-Null
$env:SIMPLELABEL_ROOT = $root
$env:SIMPLELABEL_PORT = [string]$port
$env:SIMPLELABEL_DATA_DIR = Join-Path $root 'data'
$env:SIMPLELABEL_MODELS_DIR = Join-Path $root 'models'
$env:SIMPLELABEL_LOGS_DIR = $logs
$env:SIMPLELABEL_ADMIN_DIR = $admin
$env:SIMPLELABEL_PROCESSED_DIR = $processed
$env:SIMPLELABEL_BACKUPS_DIR = $backups
$env:SIMPLELABEL_STATIC_DIR = Join-Path $root 'static'
$env:SIMPLELABEL_TIME_ZONE = 'Asia/Shanghai'
$env:SIMPLELABEL_YOLO_WORKER_PORT = [string]$workerPort
$env:SIMPLELABEL_YOLO_WORKER_URL = "http://127.0.0.1:$workerPort"
$env:SIMPLELABEL_YOLO_WORKER_TOKEN = 'local-simplelabel-worker'
$env:SIMPLELABEL_MODEL_DETECTION_PORT = [string]$modelDetectionPort
$env:SIMPLELABEL_MODEL_DETECTION_BIND = '0.0.0.0'

$worker = $null
$modelDetection = $null
$exitCode = 1
try {
    Write-Host "Starting PT_ONNX model detection service on 0.0.0.0:$modelDetectionPort..."
    $modelDetectionStart = [System.Diagnostics.ProcessStartInfo]::new()
    $modelDetectionStart.FileName = $pythonExecutable
    $modelDetectionStart.Arguments = '-u "{0}"' -f $modelDetectionScript
    $modelDetectionStart.WorkingDirectory = (Split-Path $modelDetectionScript)
    $modelDetectionStart.UseShellExecute = $false
    $modelDetectionStart.CreateNoWindow = $true
    $modelDetection = [System.Diagnostics.Process]::Start($modelDetectionStart)
    $detectorReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($modelDetection.HasExited) { break }
        try { $models = Invoke-RestMethod -Uri "http://127.0.0.1:$modelDetectionPort/api/models" -TimeoutSec 2; if ($null -ne $models) { $detectorReady = $true; break } } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $detectorReady) { throw "PT_ONNX model detection service failed to start" }

    Write-Host "Starting local YOLO worker on 127.0.0.1:$workerPort..."
    $workerStart = [System.Diagnostics.ProcessStartInfo]::new()
    $workerStart.FileName = $pythonExecutable
    $workerStart.Arguments = '-u "{0}"' -f $workerScript
    $workerStart.WorkingDirectory = $root
    $workerStart.UseShellExecute = $false
    $workerStart.CreateNoWindow = $true
    $worker = [System.Diagnostics.Process]::Start($workerStart)

    $healthy = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($worker.HasExited) { break }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$workerPort/internal/health" -TimeoutSec 2
            if ($health.status -eq 'ok') { $healthy = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) {
        $workerState = if ($worker.HasExited) { "exit code $($worker.ExitCode)" } else { 'health check timeout' }
        throw "YOLO worker failed to start: $workerState"
    }

    Write-Host ''
    Write-Host "SimpleLabel Java $Mode is running"
    Write-Host "Local URL: http://127.0.0.1:$port"
    if ($Mode -eq 'Preview') {
        Write-Host 'Preview uses port 18084 and does not occupy the current Python production port 18083.'
    }
    Write-Host 'Press Ctrl+C to stop both the Java backend and this YOLO worker.'
    Write-Host ''

    & $javaExecutable -jar $jarPath
    $exitCode = $LASTEXITCODE
} finally {
    if ($worker -and -not $worker.HasExited) {
        Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue
        $worker.WaitForExit(5000) | Out-Null
    }
    if ($modelDetection -and -not $modelDetection.HasExited) {
        Stop-Process -Id $modelDetection.Id -Force -ErrorAction SilentlyContinue
        $modelDetection.WaitForExit(5000) | Out-Null
    }
}

exit $exitCode
