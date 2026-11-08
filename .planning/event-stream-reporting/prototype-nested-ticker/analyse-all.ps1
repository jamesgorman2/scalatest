# PROTOTYPE - throwaway. Re-runs analyse.py over every recorded case in runs\ and rebuilds runs\summary.txt.
# Usage: .\analyse-all.ps1 -Python <python.exe with pyte installed>
param([Parameter(Mandatory = $true)][string]$Python)
Set-Location $PSScriptRoot
Remove-Item runs\summary.txt -ErrorAction SilentlyContinue
foreach ($p in @('conpty-*', 'mintty-*', 'linux-*')) {
  Get-ChildItem "runs\$p.chunks.jsonl" | Sort-Object Name | ForEach-Object {
    & $Python analyse.py $_.Name.Replace('.chunks.jsonl', '') | Out-Null
  }
}
