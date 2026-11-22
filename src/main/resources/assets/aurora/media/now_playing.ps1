# Paranoia Client - Now Playing
# Lit le media en cours de Windows (Spotify, navigateur, Deezer...) via les controles de transport
# systeme, et ecrit une ligne JSON toutes les 500 ms. Commandes lues sur l'entree standard :
# toggle, next, prev. Ne lit ni n'envoie rien d'autre.
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing

$asTaskOp = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$type) {
    $task = $asTaskOp.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(3000)
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStream', [type[]]@([Windows.Storage.Streams.IRandomAccessStream]))
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pending = $stdin.ReadLineAsync()
$lastKey = ''
$art = ''

while ($true) {
    if ($pending.IsCompleted) {
        $cmd = $pending.Result
        if ($null -eq $cmd) { break }
        $s = $mgr.GetCurrentSession()
        if ($s) {
            switch ($cmd.Trim()) {
                'toggle' { $null = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
                'next' { $null = Await ($s.TrySkipNextAsync()) ([bool]) }
                'prev' { $null = Await ($s.TrySkipPreviousAsync()) ([bool]) }
            }
        }
        $pending = $stdin.ReadLineAsync()
    }

    $out = @{ ok = $false }
    $s = $mgr.GetCurrentSession()
    if ($s) {
        $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
        $t = $s.GetTimelineProperties()
        $info = $s.GetPlaybackInfo()
        if ($p -and $p.Title) {
            $key = $p.Title + '|' + $p.Artist
            if ($key -ne $lastKey) {
                $lastKey = $key
                if ($art -and (Test-Path $art)) { Remove-Item $art }
                $art = ''
                if ($p.Thumbnail) {
                    try {
                        $ras = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                        # appel par reflexion : le runtime convertit l objet COM en flux .NET
                        $net = $asStream.Invoke($null, @($ras))
                        $img = [System.Drawing.Image]::FromStream($net)
                        $file = Join-Path $env:TEMP ('aurora_np_' + [Math]::Abs($key.GetHashCode()) + '.png')
                        $bmp = New-Object System.Drawing.Bitmap 128, 128
                        $gr = [System.Drawing.Graphics]::FromImage($bmp)
                        $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                        $gr.DrawImage($img, 0, 0, 128, 128)
                        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
                        $gr.Dispose(); $bmp.Dispose(); $img.Dispose(); $net.Dispose()
                        $art = $file
                    } catch { $art = '' }
                }
            }
            $out = @{
                ok      = $true
                title   = $p.Title
                artist  = $p.Artist
                playing = ($info.PlaybackStatus -eq 4)
                pos     = $t.Position.TotalSeconds
                dur     = ($t.EndTime - $t.StartTime).TotalSeconds
                updated = $t.LastUpdatedTime.ToUnixTimeMilliseconds()
                app     = $s.SourceAppUserModelId
                art     = $art
            }
        }
    }
    [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 500
}
