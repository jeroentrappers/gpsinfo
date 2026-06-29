#!/bin/sh
# Poll the gpsinfo-traffic service's /edgespeeds and write live per-edge
# speeds into Valhalla's memory-mapped traffic.tar. Valhalla (MAP_SHARED)
# picks up the changes for current-time routing.
set -u
: "${TRAFFIC_TAR:=/custom_files/traffic.tar}"
: "${EDGESPEEDS_URL:=http://127.0.0.1:8793/edgespeeds}"
: "${INTERVAL:=45}"

echo "traffic-writer: tar=$TRAFFIC_TAR url=$EDGESPEEDS_URL interval=${INTERVAL}s"
while true; do
  if curl -fsS "$EDGESPEEDS_URL" -o /tmp/edgespeeds.json 2>/dev/null; then
    if [ -s /tmp/edgespeeds.json ]; then
      /usr/local/bin/traffic-updater "$TRAFFIC_TAR" /tmp/edgespeeds.json || echo "traffic-writer: update failed"
    fi
  else
    echo "traffic-writer: fetch failed ($EDGESPEEDS_URL)"
  fi
  sleep "$INTERVAL"
done
