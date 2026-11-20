# Source snapshot without the local portal database.
# portal/data/*.json is about 48 MB and is not part of the app.
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$dest = Join-Path $root "olympus-source.zip"
if (Test-Path $dest) { Remove-Item $dest -Force }
$zip = [System.IO.Compression.ZipFile]::Open($dest, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $root -Recurse -File | ForEach-Object {
        $full = $_.FullName
        $rel = $full.Substring($root.Length).TrimStart('\', '/')
        $slash = $rel -replace '\\', '/'
        if ($slash -match '(^|/)portal/data/.*\.json$') { return }
        if ($slash -match '(^|/)(build|\.gradle)(/|$)') { return }
        if ($slash -match '\.(apk|aab)$') { return }
        if ($slash -eq 'olympus-source.zip') { return }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $full, $slash) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Output $dest
