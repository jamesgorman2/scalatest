# PROTOTYPE - throwaway. Runs run.sh under WSL (distro Ubuntu) on a copy of this folder in the Linux
# file system (~/no-trait-progress), and copies its runs/linux-* back.
# Usage: .\run-wsl.ps1 [-Rows 40]
param([int]$Rows = 40)
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force runs | Out-Null
Remove-Item -Recurse runs\linux-* -ErrorAction SilentlyContinue
$here = '/mnt/' + $PSScriptRoot.Substring(0, 1).ToLower() + $PSScriptRoot.Substring(2).Replace('\', '/')
$sh = @"
set -e
mkdir -p ~/no-trait-progress
cd '$here'
tar --exclude=./out --exclude=./runs -cf - . | (cd ~/no-trait-progress && tar -xf -)
cd ~/no-trait-progress
sed -i 's/\r$//' mill run.sh *.py cases.tsv
chmod +x mill run.sh
./run.sh $Rows
cp -r runs/linux-* '$here/runs/'
"@
$tmp = Join-Path $PSScriptRoot 'runs\wsl-run.sh'
[IO.File]::WriteAllText($tmp, $sh.Replace("`r`n", "`n"))
wsl.exe -d Ubuntu -- bash "$here/runs/wsl-run.sh"
