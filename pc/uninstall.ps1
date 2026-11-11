# Removes ImgSampah Bridge. ComfyUI, your models, workflows and images are not touched.
# Run in PowerShell:  irm https://raw.githubusercontent.com/semputs/imgsampahsorter/main/pc/uninstall.ps1 | iex

$ErrorActionPreference = 'SilentlyContinue'
$dir = Join-Path $env:LOCALAPPDATA 'ImgSampahBridge'

Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
    Where-Object { ($_.CommandLine -like '*ImgSampahBridge*') -or (($_.CommandLine -like '*main.py*') -and ($_.CommandLine -like '*--listen 127.0.0.1*')) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Remove-Item (Join-Path ([Environment]::GetFolderPath('Startup')) 'ImgSampah Bridge.vbs') -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'ComfyUI (browser).url') -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'ComfyUI on-off.url') -Force
Remove-Item $dir -Recurse -Force

$rule = 'ImgSampah Bridge (Tailscale only)'
if (Get-NetFirewallRule -DisplayName $rule) {
    Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "-NoProfile -Command Remove-NetFirewallRule -DisplayName '$rule'"
}
Write-Host "ImgSampah Bridge removed. You can use the Comfy Desktop app again." -ForegroundColor Green
