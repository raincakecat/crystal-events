param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$serverDir = Join-Path $PSScriptRoot 'server'
$eulaPath = Join-Path $serverDir 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath) -or -not (Select-String -LiteralPath $eulaPath -Pattern '^eula=true$' -Quiet)) {
    throw 'Minecraft EULA is not accepted. Review it yourself and set integration/server/eula.txt to eula=true if you agree. This script does not accept it.'
}
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
$plugin = Join-Path $PSScriptRoot '..\target\crystal-pvp-events-1.0.0.jar'
$smoke = Join-Path $PSScriptRoot 'target\crystal-pvp-smoke-1.0.0.jar'
if (-not (Test-Path -LiteralPath $plugin) -or -not (Test-Path -LiteralPath $smoke)) { throw 'Run build.ps1 first.' }
$paper = Join-Path $serverDir 'paper-1.21.11-132.jar'
if ((Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash -ne '5FFEF465EEEB5F2A3C23A24419D97C51AFD7DBB4923FF42DF9A3F58BBA1CCFBA') { throw 'Paper JAR checksum mismatch.' }
New-Item -ItemType Directory -Force (Join-Path $serverDir 'plugins') | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $serverDir 'plugins\CrystalPvPEvents.jar')
Copy-Item -LiteralPath $smoke -Destination (Join-Path $serverDir 'plugins\CrystalPvPSmoke.jar')
Push-Location $serverDir
try {
    $startedUtc = [DateTime]::UtcNow
    & $java -Xms512M -Xmx2G '-Dterminal.jline=false' '-Dterminal.ansi=false' '-Dcpvp.integration=true' -jar $paper --nogui
    if ($LASTEXITCODE -ne 0) { throw 'Paper process failed; inspect server/logs/latest.log.' }
    $report = Join-Path $serverDir 'plugins\CrystalPvPSmoke\smoke-report.txt'
    if (-not (Test-Path -LiteralPath $report)) { throw 'No smoke report; inspect server/logs/latest.log.' }
    if ((Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $startedUtc) { throw 'Only an old smoke report exists; this run did not complete.' }
    Get-Content -LiteralPath $report
    if (-not (Select-String -LiteralPath $report -Pattern '^Overall: PASS$' -Quiet)) { throw 'Paper smoke test failed.' }
} finally { Pop-Location }
