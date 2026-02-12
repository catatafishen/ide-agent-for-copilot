# Automated Sidecar Testing Script
# Tests the sidecar JSON-RPC endpoints

$ErrorActionPreference = "Stop"
$FIXED_PORT = 9876  # Fixed port to avoid firewall prompts

Write-Host "🚀 Starting Sidecar Tests..." -ForegroundColor Cyan

# Start sidecar on fixed port
Write-Host "`n📡 Starting sidecar on port $FIXED_PORT..."
$sidecarPath = ".\bin\copilot-sidecar.exe"
if (-not (Test-Path $sidecarPath)) {
    Write-Host "❌ Sidecar binary not found at $sidecarPath" -ForegroundColor Red
    exit 1
}

$sidecarProcess = Start-Process -FilePath $sidecarPath -ArgumentList "--port", "$FIXED_PORT", "--mock" -PassThru -NoNewWindow
Start-Sleep -Seconds 2

try {
    $baseUrl = "http://localhost:$FIXED_PORT"
    
    # Test 1: Health Check
    Write-Host "`n✅ Test 1: Health Check"
    $health = Invoke-RestMethod -Uri "$baseUrl/health" -Method GET
    if ($health.status -eq "ok") {
        Write-Host "   ✓ Health check passed" -ForegroundColor Green
    } else {
        throw "Health check failed"
    }
    
    # Test 2: List Models
    Write-Host "`n✅ Test 2: List Models"
    $body = '{"jsonrpc":"2.0","id":1,"method":"models.list","params":{}}'
    $modelsResp = Invoke-RestMethod -Method POST -Uri "$baseUrl/rpc" -ContentType "application/json" -Body $body
    $models = $modelsResp.result.models
    if ($models.Count -eq 3) {
        Write-Host "   ✓ Returned 3 models (mock mode)" -ForegroundColor Green
        foreach ($model in $models) {
            Write-Host "     - $($model.name)" -ForegroundColor Gray
        }
    } else {
        throw "Expected 5 models, got $($models.Count)"
    }
    
    # Test 3: Create Session
    Write-Host "`n✅ Test 3: Create Session"
    $body = '{"jsonrpc":"2.0","id":2,"method":"session.create","params":{}}'
    $sessionResp = Invoke-RestMethod -Method POST -Uri "$baseUrl/rpc" -ContentType "application/json" -Body $body
    $sessionId = $sessionResp.result.sessionId
    if ($sessionId) {
        Write-Host "   ✓ Session created: $sessionId" -ForegroundColor Green
    } else {
        throw "Session creation failed"
    }
    
    # Test 4: Send Message
    Write-Host "`n✅ Test 4: Send Message"
    $body = @{
        jsonrpc = "2.0"
        id = 3
        method = "session.send"
        params = @{
            sessionId = $sessionId
            prompt = "Hello, test!"
            model = "gpt-4o"
        }
    } | ConvertTo-Json
    $sendResp = Invoke-RestMethod -Method POST -Uri "$baseUrl/rpc" -ContentType "application/json" -Body $body
    if ($sendResp.result.messageId) {
        Write-Host "   ✓ Message sent: $($sendResp.result.messageId)" -ForegroundColor Green
        Write-Host "     Stream URL: $($sendResp.result.streamUrl)" -ForegroundColor Gray
    } else {
        throw "Message send failed"
    }
    
    # Test 5: Close Session
    Write-Host "`n✅ Test 5: Close Session"
    $body = @{
        jsonrpc = "2.0"
        id = 4
        method = "session.close"
        params = @{
            sessionId = $sessionId
        }
    } | ConvertTo-Json
    $closeResp = Invoke-RestMethod -Method POST -Uri "$baseUrl/rpc" -ContentType "application/json" -Body $body
    if ($closeResp.result.closed -eq $true) {
        Write-Host "   ✓ Session closed" -ForegroundColor Green
    } else {
        throw "Session close failed"
    }
    
    Write-Host "`n🎉 All tests passed!" -ForegroundColor Green
    exit 0
    
} catch {
    Write-Host "`n❌ Test failed: $_" -ForegroundColor Red
    exit 1
} finally {
    # Cleanup
    Write-Host "`n🧹 Cleaning up..."
    if ($sidecarProcess -and !$sidecarProcess.HasExited) {
        Stop-Process -Id $sidecarProcess.Id -Force
        Write-Host "   Stopped sidecar process" -ForegroundColor Gray
    }
}
