param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$maven = Join-Path $PSScriptRoot 'tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
Push-Location $PSScriptRoot
try {
    & $maven -B -ntp clean install
    if ($LASTEXITCODE -ne 0) { throw 'Plugin build or tests failed.' }
    & $maven -B -ntp -f integration/pom.xml package
    if ($LASTEXITCODE -ne 0) { throw 'Paper smoke runner build failed.' }
    New-Item -ItemType Directory -Force jar | Out-Null
    Copy-Item -LiteralPath target\crystal-pvp-events-1.0.0.jar -Destination jar\CrystalPvPEvents-1.0.0.jar
    Copy-Item -LiteralPath target\crystal-pvp-events-1.0.0-sources.jar -Destination jar\CrystalPvPEvents-1.0.0-sources.jar
    Get-FileHash jar\*.jar -Algorithm SHA256
} finally { Pop-Location }
