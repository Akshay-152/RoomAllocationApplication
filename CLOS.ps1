$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
$javaProjectRoot = Join-Path $projectRoot 'java project'
$notificationEntry = Join-Path $projectRoot 'notification\src\server.js'
$javaProjectPattern = [regex]::Escape($javaProjectRoot) + '(?:\\|/|["\s])'
$failed = $false

$processes = Get-CimInstance Win32_Process | Where-Object {
    $commandLine = $_.CommandLine
    if (-not $commandLine) {
        return $false
    }

    if ($_.Name -in @('java.exe', 'javaw.exe')) {
        return $commandLine -match '(?i)room-allocation-system|com\.exam\.allocation|spring-boot:run' `
            -and $commandLine -match "(?i)$javaProjectPattern"
    }

    return $_.Name -ieq 'node.exe' `
        -and $commandLine.IndexOf($notificationEntry, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

if (-not $processes) {
    Write-Host 'No identifiable Room Allocation server process is running.'
    exit 0
}

foreach ($process in $processes) {
    Write-Host "Stopping project process $($process.Name), PID $($process.ProcessId)..."
    try {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    catch {
        Write-Error "Could not stop PID $($process.ProcessId): $($_.Exception.Message)"
        $failed = $true
    }
}

if ($failed) {
    exit 1
}

Write-Host 'Project server processes stopped.'
