# PROTOTYPE - throwaway. What a Windows program sees inside MinTTY. Run as:
#   mintty -w hide -s 160,40 --log probe.log -e /usr/bin/bash -lc "[MSYS=disable_pcon] bash mintty-probe/probe.sh"
echo "MSYS=$MSYS"
[ -t 1 ] && echo "bash stdout is a tty: $(tty)" || echo "bash stdout not a tty"
powershell -NoProfile -c "'powershell IsOutputRedirected=' + [Console]::IsOutputRedirected + ' IsInputRedirected=' + [Console]::IsInputRedirected + ' WindowWidth=' + [Console]::WindowWidth"
java "$(dirname "$0")/Probe.java"
