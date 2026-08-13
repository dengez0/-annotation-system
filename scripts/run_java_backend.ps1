param(
    [ValidateSet('Preview', 'Production')]
    [string]$Mode = 'Preview'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$port = if ($Mode -eq 'Production') { 18083 } else { 18084 }
$workerPort = 18085
$jarPath = Join-Path $root 'backend-java\target\simplelabel-java-1.0.0-SNAPSHOT.jar'
$workerScript = Join-Path $root 'yolo-worker\worker.py'

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
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Java JAR not found. Run build_java.bat first."
}
if (-not (Test-Path -LiteralPath $workerScript -PathType Leaf)) {
    throw "YOLO worker not found: $workerScript"
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
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$env:SIMPLELABEL_ROOT = $root
$env:SIMPLELABEL_PORT = [string]$port
$env:SIMPLELABEL_DATA_DIR = Join-Path $root 'data'
$env:SIMPLELABEL_MODELS_DIR = Join-Path $root 'models'
$env:SIMPLELABEL_YOLO_WORKER_PORT = [string]$workerPort
$env:SIMPLELABEL_YOLO_WORKER_URL = "http://127.0.0.1:$workerPort"
$env:SIMPLELABEL_YOLO_WORKER_TOKEN = 'local-simplelabel-worker'

$worker = $null
$exitCode = 1
try {
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
}

exit $exitCode
