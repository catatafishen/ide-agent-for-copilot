#!/usr/bin/env pwsh
# Quick plugin build and install helper

Write-Host "`n🔨 Building plugin..." -ForegroundColor Cyan

$ideaHome = Get-Content "C:\Users\developer\AppData\Local\JetBrains\IntelliJIdea2025.3\.home"
$env:JAVA_HOME = "$ideaHome\jbr"

.\gradlew.bat --no-daemon :plugin-core:buildPlugin

if ($LASTEXITCODE -eq 0) {
    $zipPath = Resolve-Path "plugin-core\build\distributions\plugin-core-0.1.0-SNAPSHOT.zip"
    
    Write-Host "`n✅ Build successful!" -ForegroundColor Green
    Write-Host "`n📦 Plugin ZIP created:" -ForegroundColor Yellow
    Write-Host "   $zipPath" -ForegroundColor White
    
    Write-Host "`n📋 To install:" -ForegroundColor Cyan
    Write-Host "   1. Open IntelliJ Settings (Ctrl+Alt+S)" -ForegroundColor White
    Write-Host "   2. Go to: Plugins" -ForegroundColor White
    Write-Host "   3. Click gear icon (⚙️) → Install Plugin from Disk..." -ForegroundColor White
    Write-Host "   4. Select the ZIP file above" -ForegroundColor White
    Write-Host "   5. Click OK and restart IntelliJ`n" -ForegroundColor White
    
    # Option to copy path to clipboard
    $copy = Read-Host "Copy path to clipboard? (y/n)"
    if ($copy -eq 'y') {
        Set-Clipboard -Value $zipPath.Path
        Write-Host "✓ Path copied to clipboard!" -ForegroundColor Green
    }
} else {
    Write-Host "`n❌ Build failed - check errors above" -ForegroundColor Red
    exit 1
}
