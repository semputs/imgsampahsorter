# Logs which programs use the graphics card (engine % and dedicated memory) every 5 seconds.
# Started hidden by the ImgSampah Bridge when it finds gpu_monitor_request.json.
param([int]$Minutes = 30)
$ErrorActionPreference = 'SilentlyContinue'
$dir = Join-Path $env:LOCALAPPDATA 'ImgSampahBridge'
$out = Join-Path $dir 'gpu_monitor.csv'
'time,pid,process,kind,value' | Set-Content $out -Encoding UTF8
$end = (Get-Date).AddMinutes($Minutes)
$names = @{}
function NameOf($p) {
    if (-not $names.ContainsKey($p)) { $pr = Get-Process -Id $p; $names[$p] = if ($pr) { $pr.ProcessName } else { '?' } }
    return $names[$p]
}
while ((Get-Date) -lt $end) {
    $t = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $lines = @()
    $eng = (Get-Counter '\GPU Engine(*)\Utilization Percentage').CounterSamples
    if (-not $eng) { $lines += "$t,0,counter-error,engine,0" }
    $sum = @{}
    foreach ($x in $eng) {
        if ($x.CookedValue -le 0.5) { continue }
        if ($x.InstanceName -match 'pid_(\d+).*engtype_(\w+)') {
            $k = "$($matches[1])|$($matches[2])"
            $sum[$k] = [double]$sum[$k] + $x.CookedValue
        }
    }
    foreach ($k in $sum.Keys) { $p, $e = $k -split '\|'; $lines += "$t,$p,$(NameOf ([int]$p)),engine_$e,$([math]::Round($sum[$k],1))" }
    $mem = (Get-Counter '\GPU Process Memory(*)\Dedicated Usage').CounterSamples
    $msum = @{}
    foreach ($x in $mem) {
        if ($x.InstanceName -match 'pid_(\d+)') { $msum[$matches[1]] = [double]$msum[$matches[1]] + $x.CookedValue }
    }
    foreach ($p in $msum.Keys) { if ($msum[$p] -gt 50MB) { $lines += "$t,$p,$(NameOf ([int]$p)),vram_MB,$([math]::Round($msum[$p]/1MB))" } }
    $ad = (Get-Counter '\GPU Adapter Memory(*)\Dedicated Usage').CounterSamples | Measure-Object -Property CookedValue -Maximum
    if ($ad) { $lines += "$t,0,_total,vram_total_MB,$([math]::Round($ad.Maximum/1MB))" }
    $lines | Add-Content $out -Encoding UTF8
    Start-Sleep -Seconds 4
}
"$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'),0,_done,done,0" | Add-Content $out -Encoding UTF8
