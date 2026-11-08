# PROTOTYPE - throwaway. Runs run.sh under WSL (distro Ubuntu) on a copy of this folder in the Linux
# file system (~/nested-ticker), copies its runs/linux-* back, and analyses each case on Windows.
# Usage: .\run-wsl.ps1 -Python <python.exe with pyte installed> [-Repeats 3]
param([Parameter(Mandatory = $true)][string]$Python, [int]$Repeats = 3)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item runs\linux-* -ErrorAction SilentlyContinue
$here = '/mnt/' + $PSScriptRoot.Substring(0, 1).ToLower() + $PSScriptRoot.Substring(2).Replace('\', '/')
$sh = @"
set -e
mkdir -p ~/nested-ticker
cd '$here'
tar --exclude=./out --exclude=./runs -cf - . | (cd ~/nested-ticker && tar -xf -)
cd ~/nested-ticker
sed -i 's/\r$//' mill run.sh *.py
chmod +x mill run.sh
./run.sh $Repeats
cp runs/linux-* '$here/runs/'
"@
$tmp = Join-Path $PSScriptRoot 'runs\wsl-run.sh'
[IO.File]::WriteAllText($tmp, $sh.Replace("`r`n", "`n"))
wsl.exe -d Ubuntu -- bash "$here/runs/wsl-run.sh"
Get-ChildItem runs\linux-*.chunks.jsonl | ForEach-Object {
  & $Python analyse.py $_.Name.Replace('.chunks.jsonl', '')
}
