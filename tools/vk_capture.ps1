# Captures the dev Minecraft window each time DevWorld (devvk) writes a new pose name to run/vk-stage.txt.
# PrintWindow with PW_RENDERFULLCONTENT reads the window itself (works for Vulkan swapchains, even if covered).
# Usage: powershell -ExecutionPolicy Bypass -File tools/vk_capture.ps1 <run dir> <out dir> <seconds>
param([string]$RunDir, [string]$OutDir, [int]$Seconds = 900)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class W {
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  public struct RECT { public int L, T, R, B; }
  public struct POINT { public int X, Y; }
}
"@
New-Item -ItemType Directory -Force $OutDir | Out-Null
$stage = Join-Path $RunDir "vk-stage.txt"
Remove-Item $stage -ErrorAction SilentlyContinue
$last = ""
$end = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $end) {
  Start-Sleep -Milliseconds 150
  if (-not (Test-Path $stage)) { continue }
  $name = (Get-Content $stage -Raw).Trim()
  if ($name -eq "" -or $name -eq $last) { continue }
  $last = $name
  $p = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
  if (-not $p) { Write-Output "no window for $name"; continue }
  $h = $p.MainWindowHandle
  $wr = New-Object W+RECT; [W]::GetWindowRect($h, [ref]$wr) | Out-Null
  $cr = New-Object W+RECT; [W]::GetClientRect($h, [ref]$cr) | Out-Null
  $pt = New-Object W+POINT; [W]::ClientToScreen($h, [ref]$pt) | Out-Null
  $ww = $wr.R - $wr.L; $wh = $wr.B - $wr.T
  $bmp = New-Object System.Drawing.Bitmap $ww, $wh
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $dc = $g.GetHdc(); [W]::PrintWindow($h, $dc, 2) | Out-Null; $g.ReleaseHdc($dc); $g.Dispose()
  # keep only the client area (no title bar / borders)
  $cx = $pt.X - $wr.L; $cy = $pt.Y - $wr.T
  $crop = $bmp.Clone((New-Object System.Drawing.Rectangle $cx, $cy, $cr.R, $cr.B), $bmp.PixelFormat)
  $crop.Save((Join-Path $OutDir ("vk_" + $name + ".png")), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose(); $crop.Dispose()
  Write-Output "captured $name"
}
