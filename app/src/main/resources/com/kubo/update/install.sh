#!/bin/sh
# All paths are positional arguments. No eval, shell-generated command strings or recursive deletion.
kind=$1
previous_process=$2
target=$3
staged=$4
backup=$5
launcher=$6
package=$7
ready=$8
result=$9
old_moved=0
new_moved=0

failed() {
    trap - EXIT
    if [ "$old_moved" = 1 ]; then
        if [ "$new_moved" = 1 ] && [ -d "$target" ]; then mv -- "$target" "$staged"; fi
        if [ ! -e "$target" ]; then mv -- "$backup" "$target"; fi
    fi
    printf '%s\n' 'failed: Update could not finish. Check install.log in ~/.kubo/updates.' > "$result"
    if ! kill -0 "$previous_process" 2>/dev/null && [ -x "$launcher" ]; then
        nohup "$launcher" >/dev/null 2>&1 &
    fi
    exit 1
}
trap failed EXIT
set -e
case "$kind" in
    portable-tar)
        case "$target" in /*) ;; *) exit 1 ;; esac
        parent=$(dirname -- "$target")
        staging_root=$(dirname -- "$staged")
        [ "$target" != / ] && [ "$parent" != / ]
        [ "$(dirname -- "$backup")" = "$parent" ]
        [ "$(dirname -- "$staging_root")" = "$parent" ]
        case "$(basename -- "$staging_root")" in .kubo-update-*) ;; *) exit 1 ;; esac
        [ ! -e "$backup" ] && [ -f "$target/lib/app/Kubo.cfg" ] && [ -x "$staged/bin/Kubo" ]
        ;;
    deb) [ -x /usr/bin/pkexec ] && [ -x /usr/bin/apt-get ] ;;
    rpm) [ -x /usr/bin/pkexec ] ;;
    *) exit 1 ;;
esac
printf '%s\n' ready > "$ready"
attempt=0
while kill -0 "$previous_process" 2>/dev/null; do
    [ "$attempt" -lt 120 ]
    sleep 1
    attempt=$((attempt + 1))
done
case "$kind" in
    portable-tar)
        mv -- "$target" "$backup"
        old_moved=1
        mv -- "$staged" "$target"
        new_moved=1
        ;;
    deb) /usr/bin/pkexec /usr/bin/apt-get install --yes -- "$package" ;;
    rpm)
        if [ -x /usr/bin/dnf ]; then /usr/bin/pkexec /usr/bin/dnf install --assumeyes -- "$package"
        elif [ -x /usr/bin/zypper ]; then /usr/bin/pkexec /usr/bin/zypper --non-interactive install -- "$package"
        else /usr/bin/pkexec /usr/bin/rpm -U -- "$package"
        fi
        ;;
esac
printf '%s\n' ok > "$result"
trap - EXIT
if [ -x "$launcher" ]; then nohup "$launcher" >/dev/null 2>&1 & fi
