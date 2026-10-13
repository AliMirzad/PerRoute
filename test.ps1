$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jdk21 = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
if ($null -ne $Jdk21) {
    $env:JAVA_HOME = $Jdk21.FullName
    $env:Path = (Join-Path $Jdk21.FullName 'bin') + ';' + $env:Path
}
$env:GRADLE_OPTS = '-Dkotlin.compiler.execution.strategy=in-process'
& (Join-Path $ProjectRoot 'gradlew.bat') --no-daemon runAllTests
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
