# ImgSampah Bridge installer.
# Run in PowerShell:  irm https://raw.githubusercontent.com/semputs/imgsampahsorter/main/pc/install.ps1 | iex
# Safe to run again to update.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }

Say "`n=== ImgSampah Bridge setup ===`n" Cyan

# 1. Find Comfy Desktop's ComfyUI install
$base = Join-Path $env:LOCALAPPDATA 'Comfy-Desktop'
$installsDir = Join-Path $base 'ComfyUI-Installs'
if (-not (Test-Path $installsDir)) { throw "Couldn't find Comfy Desktop at $installsDir" }
$installs = @(Get-ChildItem $installsDir -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'ComfyUI\.venv\Scripts\python.exe') })
if ($installs.Count -eq 0) { throw "No ComfyUI install with a Python environment found in $installsDir" }
$inst = $installs | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$comfy = Join-Path $inst.FullName 'ComfyUI'
$py = Join-Path $comfy '.venv\Scripts\python.exe'
$shared = Join-Path $base 'ComfyUI-Shared'
Say "ComfyUI install : $comfy"
Say "Images folder   : $shared\output"

# GPU the Desktop app uses
$cuda = $null
$deskLog = Join-Path $inst.FullName 'logs\comfyui.log'
if (Test-Path $deskLog) {
    $m = Select-String -Path $deskLog -Pattern 'Set cuda device to: (\d+)' | Select-Object -Last 1
    if ($m) { $cuda = [int]$m.Matches[0].Groups[1].Value }
}

# 2. Stop Comfy Desktop, any ComfyUI from this install, and an older bridge
Say "`nStopping Comfy Desktop / old bridge if running..."
Get-Process -Name 'Comfy Desktop' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
$dir = Join-Path $env:LOCALAPPDATA 'ImgSampahBridge'
Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -like "$($inst.FullName)*" -or $_.CommandLine -like '*ImgSampahBridge*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

# 3. Files
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$raw = 'https://raw.githubusercontent.com/semputs/imgsampahsorter/main/pc'
Say "Downloading the bridge..."
Invoke-WebRequest "$raw/bridge.py" -OutFile (Join-Path $dir 'bridge.py') -UseBasicParsing

$paths = [ordered]@{
    python      = $py
    comfy_dir   = $comfy
    shared_dir  = $shared
    cuda_device = $cuda
}
$paths | ConvertTo-Json | Set-Content -Path (Join-Path $dir 'paths.json') -Encoding UTF8

# Hidden launcher (window style 0 = no window, no taskbar icon)
$bridge = Join-Path $dir 'bridge.py'
$vbs = @'
Set sh = CreateObject("WScript.Shell")
sh.Run """__PY__"" ""__BRIDGE__""", 0, False
'@
$vbs = $vbs.Replace('__PY__', $py).Replace('__BRIDGE__', $bridge)
$launcher = Join-Path $dir 'start-hidden.vbs'
Set-Content -Path $launcher -Value $vbs -Encoding ASCII

# Start automatically at login
$startup = [Environment]::GetFolderPath('Startup')
Copy-Item $launcher (Join-Path $startup 'ImgSampah Bridge.vbs') -Force
Say "Set to start automatically when you log in."

# Desktop shortcut to ComfyUI in the browser (replaces the Comfy Desktop window)
$desk = [Environment]::GetFolderPath('Desktop')
Set-Content -Path (Join-Path $desk 'ComfyUI (browser).url') -Value "[InternetShortcut]`r`nURL=http://127.0.0.1:8188/" -Encoding ASCII

# 4. Windows Firewall: allow port 8190 from Tailscale addresses only (needs one admin prompt)
$rule = 'ImgSampah Bridge (Tailscale only)'
if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
    Say "`nWindows will ask for admin permission once, to allow your phone in through the firewall (Tailscale only)." Yellow
    $cmd = "New-NetFirewallRule -DisplayName '$rule' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8190 -RemoteAddress 100.64.0.0/10 -Profile Any | Out-Null"
    try {
        Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "-NoProfile -Command $cmd"
    } catch {
        Say "Firewall permission was declined. If Windows later asks whether Python may use the network, choose Allow." Yellow
    }
}

# 5. Start it now
Say "`nStarting ComfyUI in the background..."
Start-Process wscript.exe -ArgumentList "`"$launcher`""

$info = $null
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try { $info = Invoke-RestMethod 'http://127.0.0.1:8190/isb/local/info' -TimeoutSec 5; break } catch {}
}
if (-not $info) { throw "The bridge didn't start. Look in $dir\bridge.log" }

$addr = $info.address
if (-not $addr) { $addr = '(open Tailscale on this PC and log in, then run this again)' }

Say "`n=========================================" Green
Say " Done! Pair your phone in the app:" Green
Say "   ImgSampah Sorter -> ComfyUI" Green
Say ""
Say "   Address : $addr" White
Say "   Code    : $($info.code)" White
Say "=========================================`n" Green
Say "ComfyUI takes a minute to load. On this PC, open it with the"
Say "'ComfyUI (browser)' shortcut on your desktop (http://127.0.0.1:8188)."
Say "Don't open the Comfy Desktop app any more - it would clash."
Say "Show this code again any time: http://127.0.0.1:8190/isb/local/pair"
