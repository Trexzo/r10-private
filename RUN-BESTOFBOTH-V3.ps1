$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'VapeTrainer BestOfBoth V3 - RACEGUARD + post-load features'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$cache = Join-Path $env:LOCALAPPDATA 'VapeTrainer\bestofboth-v3-smart5-r10'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$launchLog = Join-Path $cache 'launcher.log'

function Log([string]$s) {
    $line = ('[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), $s)
    Write-Host $line
    Add-Content -LiteralPath $launchLog -Value $line -Encoding UTF8
}
function Sha([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant() }
function Require-File([string]$name, [string]$sha) {
    $p = Join-Path $root $name
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing required file: $name" }
    if ($sha -and (Sha $p) -ne $sha) { throw "SHA256 mismatch: $name" }
    return $p
}
function Get-MinecraftCandidates {
    $ps = @(Get-Process javaw,java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match 'Minecraft' })
    $rows = @()
    foreach ($p in $ps) {
        $cmd = ''
        try { $cmd = (Get-CimInstance Win32_Process -Filter ("ProcessId=" + $p.Id) -ErrorAction Stop).CommandLine } catch {}
        $isGrim = $cmd -match '(?i)grimclient|offline\.client'
        $isFabric = $cmd -match '(?i)KnotClient|fabric-loader'
        $isNormal = $cmd -match '(?i)\\\.minecraft\\|AppData\\Roaming\\\.minecraft'
        $rows += [pscustomobject]@{ Process=$p; PID=$p.Id; Started=$p.StartTime; Title=$p.MainWindowTitle; CommandLine=$cmd; IsGrim=$isGrim; IsFabric=$isFabric; IsNormal=$isNormal }
    }
    return @($rows)
}
function Select-Minecraft {
    $rows = @(Get-MinecraftCandidates)
    if ($rows.Count -eq 0) { throw 'No Minecraft Java window found. Start Minecraft 1.21.11 first.' }

    $preferred = @($rows | Where-Object { -not $_.IsGrim -and $_.IsFabric -and $_.IsNormal })
    if ($preferred.Count -eq 1) { return $preferred[0] }
    if ($preferred.Count -gt 1) {
        $preferred = @($preferred | Sort-Object Started -Descending)
        Log ('Multiple normal Fabric instances found; choosing newest PID ' + $preferred[0].PID)
        return $preferred[0]
    }

    $nongrim = @($rows | Where-Object { -not $_.IsGrim })
    if ($nongrim.Count -eq 1) { return $nongrim[0] }
    if ($rows.Count -eq 1) { return $rows[0] }

    Write-Host ''
    Write-Host 'Multiple Minecraft windows found:'
    $rows | Select-Object PID,Started,Title,IsGrim,IsFabric,IsNormal | Format-Table -AutoSize | Out-Host
    $pidText = Read-Host 'Enter the PID to use'
    $chosen = $rows | Where-Object { [string]$_.PID -eq $pidText } | Select-Object -First 1
    if ($null -eq $chosen) { throw "PID $pidText was not one of the listed Minecraft processes." }
    return $chosen
}
function Find-AttachJava([object]$row) {
    $candidates = New-Object System.Collections.Generic.List[string]
    try {
        if ($row.Process.Path) {
            $same = Join-Path (Split-Path $row.Process.Path -Parent) 'java.exe'
            if (Test-Path $same) { $candidates.Add($same) }
        }
    } catch {}
    if ($env:JAVA_HOME) {
        $jh = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $jh) { $candidates.Add($jh) }
    }
    try { $j = (Get-Command java.exe -ErrorAction Stop).Source; if ($j) { $candidates.Add($j) } } catch {}
    foreach ($base in @('C:\Program Files\Eclipse Adoptium','C:\Program Files\Java','C:\Program Files\BellSoft')) {
        if (Test-Path $base) {
            Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object {
                $j = Join-Path $_.FullName 'bin\java.exe'; if (Test-Path $j) { $candidates.Add($j) }
            }
        }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        try {
            $mods = @(& $candidate --list-modules 2>$null)
            if ($LASTEXITCODE -eq 0 -and ($mods | Where-Object { $_ -match '^jdk\.attach@' })) { return $candidate }
        } catch {}
    }
    throw 'Could not find a Java installation containing jdk.attach.'
}
function Get-LoadedVapeModules([System.Diagnostics.Process]$p) {
    try { return @($p.Modules | Where-Object { $_.ModuleName -match '(?i)Vape|Trainer' }) } catch { return @() }
}
function Invoke-Agent([string]$label, [string]$java, [int]$targetPid, [string]$loader, [string]$agent, [string]$status, [int]$attempts=5) {
    for ($attempt=1; $attempt -le $attempts; $attempt++) {
        if (Test-Path $status) { Remove-Item -Force $status }
        Log ("$label attach attempt $attempt/$attempts")
        & $java --add-modules jdk.attach -jar $loader ([string]$targetPid) $agent $status
        $exit = $LASTEXITCODE
        if ($exit -eq 0) {
            for ($i=0; $i -lt 40 -and -not (Test-Path $status); $i++) { Start-Sleep -Milliseconds 250 }
            if (Test-Path $status) {
                $text = Get-Content $status -Raw
                Write-Host $text
                Add-Content -LiteralPath $launchLog -Value $text -Encoding UTF8
                if ($text -match 'RESULT=PASS') { Log "$label PASS"; return }
            }
        }
        if ($attempt -lt $attempts) { Start-Sleep -Milliseconds 1500 }
    }
    throw "$label failed after $attempts attempts. See $status and $launchLog"
}

try {
    Write-Host ''
    Write-Host '============================================================'
    Write-Host ' VapeTrainer BestOfBoth V3 SMART5-R10 - ONE CLICK'
    Write-Host ' Proven RACEGUARD1 startup + post-load FeaturePack/FIX4/Smart'
    Write-Host '============================================================'
    Log 'Launcher start'

    $coreInjector = Require-File 'Vape421Injector-core.exe' 'f81db521368226bc7329465af982a9ce8e003a2cbab35f4438cde62baf0472a5'
    $baseDll = Require-File 'VapeTrainerBigPatch1RaceGuard1SafeNative.dll' '693d8d33f0f985ef8f64834b045a907cc3b3114cf79378e89611fc420cde234c'
    [void](Require-File 'VapeTrainer-best-of-both-v1-BIGPATCH1-RACEGUARD1-injection.jar' 'df4eb9e2c8a5ea179869d51012d2a7a36ccf4ea60f2d84d2bd7562f4473736d7')
    $loader = Require-File 'VapeTrainer-LIVE-HOTPATCH-loader.jar' '375d9c95d6ab6387db3804552740425e34c269f72c9b00e16dd36cc065c4147f'
    $featureAgent = Require-File 'VapeTrainer-FEATUREPACK1-HOTPATCH-R4-agent.jar' 'f86b8d2f6061456afff215ccd4a8faf23f7d202edd03c0c5a88d9ad1e6ad06b4'
    $espAgent = Require-File 'VapeTrainer-ESP-FREELOOK-FIX4-agent.jar' 'ee151c158cb9b673b65df9ed5a5eb843a4a30928ca329fc025111197ffc29a39'
    $smartAgent = Require-File 'VapeTrainer-SMART-BLOCKHIT-LIVE-R10-agent.jar' '9825a465005dd183303230568960ecc53b2766b2fa67eebe12129e82e522d768'

    $target = Select-Minecraft
    $targetPid = [int]$target.PID
    Log ("Target Minecraft PID=$targetPid title='$($target.Title)' grim=$($target.IsGrim) fabric=$($target.IsFabric) normal=$($target.IsNormal)")
    if ($target.IsGrim) { Log 'WARNING: selected target looks like grimclient/offline.client because no normal client was uniquely available.' }

    $mods = @(Get-LoadedVapeModules $target.Process)
    $baseLoaded = @($mods | Where-Object { $_.ModuleName -ieq 'VapeTrainerBigPatch1RaceGuard1SafeNative.dll' }).Count -gt 0
    if (-not $baseLoaded) {
        $other = @($mods | Where-Object { $_.ModuleName -match '(?i)Vape.*\.dll|Trainer.*\.dll' })
        if ($other.Count -gt 0) {
            Write-Host ($other | Select-Object ModuleName,FileName | Format-Table -AutoSize | Out-String)
            throw 'A different Vape/VapeTrainer DLL is already mapped. Fresh-start this Minecraft before using the V3 launcher.'
        }
        Log 'Injecting proven RACEGUARD1 base...'
        & $coreInjector ([string]$targetPid) $baseDll
        $injExit = $LASTEXITCODE
        Log ("Core injector exit=$injExit")
        if ($injExit -ne 0) { throw "Core injector failed with exit code $injExit." }
        Start-Sleep -Seconds 5
    } else {
        Log 'RACEGUARD1 base already mapped; skipping duplicate base injection.'
    }

    $java = Find-AttachJava $target
    Log ("Attach Java=$java")

    $featureStatus = Join-Path $cache ("featurepack-r4-$targetPid.txt")
    Invoke-Agent 'FeaturePack R4 (Target Marker + Jump Circles)' $java $targetPid $loader $featureAgent $featureStatus 6

    $espStatus = Join-Path $cache ("esp-fix4-$targetPid.txt")
    Invoke-Agent 'ESP Freelook FIX4' $java $targetPid $loader $espAgent $espStatus 4

    $smartStatus = Join-Path $cache ("smart-blockhit-r10-$targetPid.txt")
    Invoke-Agent 'Smart BlockHit R10 (SMART5 damage epochs + clean V4 identity)' $java $targetPid $loader $smartAgent $smartStatus 4

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host ' [PASS] BestOfBoth V3 stack is live.' -ForegroundColor Green
    Write-Host (" Minecraft PID: $targetPid")
    Write-Host ' BlockHit modes: Manual / Predict / Smart / Auto / Lag'
    Write-Host ' Smart is installed/replaced with SMART5/R10: damage epochs, locked hurtTime scheduling, boundary-driven release, regen diagnostics only, and a clean V4 runtime identity.'
    Write-Host ' Close/reopen the Vape GUI once if the new mode list was already open.'
    Write-Host (" Smart diagnostics: $env:LOCALAPPDATA\VapeTrainer\smart-blockhit.log")
    Write-Host (" Launcher log: $launchLog")
    Write-Host '============================================================' -ForegroundColor Green
    Start-Sleep -Seconds 4
    exit 0
} catch {
    $msg = $_.Exception.Message
    Log ("FATAL: $msg")
    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Red
    Write-Host ' BestOfBoth V3 FAILED' -ForegroundColor Red
    Write-Host $msg -ForegroundColor Red
    Write-Host ("Launcher log: $launchLog")
    Write-Host '============================================================' -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}
