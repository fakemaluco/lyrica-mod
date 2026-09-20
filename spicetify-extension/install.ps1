param(
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$extensionRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $extensionRoot
try {
    if (-not $SkipInstall) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    }
    npm run typecheck
    if ($LASTEXITCODE -ne 0) { throw "TypeScript typecheck failed" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "Extension build failed" }

    $spicetify = Get-Command spicetify -ErrorAction Stop
    $configPath = ((& $spicetify.Source -c) | Out-String).Trim()
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "Spicetify config was not found at $configPath"
    }
    $spicetifyRoot = Split-Path -Parent $configPath
    $extensions = Join-Path $spicetifyRoot "Extensions"
    New-Item -ItemType Directory -Force -Path $extensions | Out-Null
    Copy-Item -Force -LiteralPath (Join-Path $extensionRoot "dist\fandroid.js") `
        -Destination (Join-Path $extensions "fandroid.js")

    $before = Get-Content -Raw -LiteralPath $configPath
    $match = [regex]::Match($before, '(?m)^extensions\s*=\s*(.*)$')
    $enabled = if ($match.Success) {
        @($match.Groups[1].Value.Split('|') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    } else { @() }
    if ($enabled -notcontains 'fandroid.js') { $enabled += 'fandroid.js' }
    & $spicetify.Source config extensions ($enabled -join '|')
    if ($LASTEXITCODE -ne 0) { throw "spicetify config failed" }
    $config = Get-Content -Raw -LiteralPath $configPath
    if ($config -notmatch '(?m)^extensions\s*=.*\bfandroid\.js\b') {
        throw "fandroid.js was copied, but config-xpui.ini did not enable it"
    }

    & $spicetify.Source apply
    if ($LASTEXITCODE -ne 0) { throw "spicetify apply failed" }
    Write-Host "Relay 0.4.1 installed and applied. Restart Spotify if the phone icon is not visible."
} finally {
    Pop-Location
}
