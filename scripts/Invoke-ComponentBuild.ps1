[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Component
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$registryPath = Join-Path $repoRoot "catalog/components.json"
$registry = Get-Content -LiteralPath $registryPath -Raw | ConvertFrom-Json
$componentEntry = @($registry.components | Where-Object { $_.id -ceq $Component })

if ($componentEntry.Count -ne 1) {
    throw "Unknown component ID '$Component'. Use a category-qualified ID from catalog/components.json."
}

$entry = $componentEntry[0]
if ($entry.kind -ne "plugin") {
    python (Join-Path $repoRoot "scripts/deploy.py") validate --component $Component
    if ($LASTEXITCODE -ne 0) {
        throw "Validation failed for $Component."
    }
    exit 0
}

$javaVersionText = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersionText -notmatch 'version "21(?:\.|")') {
    throw "Plugin builds require JDK 21 to run the pinned Gradle 8.14.5/JOSM Gradle plugin. JOSM runtime target is Java 25; JDK 21 class files run on Java 25."
}

$componentPath = Join-Path $repoRoot ($entry.path.Replace("/", [System.IO.Path]::DirectorySeparatorChar))
if (-not (Test-Path -LiteralPath $componentPath -PathType Container)) {
    throw "Component directory does not exist: $($entry.path)"
}

Push-Location $componentPath
try {
    $gradleArguments = @("--no-daemon") + @($entry.build.tasks)
    if ($IsWindows) {
        & .\gradlew.bat @gradleArguments
    } else {
        & ./gradlew @gradleArguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed for $Component with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

python (Join-Path $repoRoot "scripts/deploy.py") validate --component $Component --skip-josm-parser
if ($LASTEXITCODE -ne 0) {
    throw "Plugin artifact validation failed for $Component."
}
