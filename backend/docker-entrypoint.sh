#!/bin/sh
# Unraid creates appdata folders as root; hand /data to PUID:PGID (Unraid's nobody:users by
# default) and run the server as that user, not root.
set -e
mkdir -p /data
chown -R "$PUID:$PGID" /data
exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups "$@"
