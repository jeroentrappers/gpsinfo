#!/usr/bin/env bash
# Throttled Planetiler build for the shared box. PBF download + output live on
# the Storage Box (sequential, network-friendly); the random-IO temp (node map,
# feature store) stays on local NVMe. A disk watchdog aborts the build if local
# free space drops too low, so a mis-estimated temp footprint can never fill /
# and take down the co-hosted live sites. On success the finished pmtiles is
# copied to local disk for fast serving.
#
# env: PLANETILER_IMAGE AREA THREADS XMX STORAGE_BUILD_DIR LOCAL_TMP DATA_DIR WATCHDOG_MIN_FREE_KB
set -euo pipefail

docker rm -f gpsinfo-planetiler 2>/dev/null || true
mkdir -p "$LOCAL_TMP" "$STORAGE_BUILD_DIR"

# --- disk watchdog (protects the box) ---
(
  while sleep 60; do
    docker ps -q -f name=gpsinfo-planetiler | grep -q . || exit 0   # build finished
    free=$(df -P "$LOCAL_TMP" | awk 'NR==2{print $4}')
    if [ "${free:-0}" -lt "$WATCHDOG_MIN_FREE_KB" ]; then
      echo "[watchdog] local free ${free}KB < ${WATCHDOG_MIN_FREE_KB}KB — aborting build to protect /"
      docker stop gpsinfo-planetiler || true
      exit 1
    fi
  done
) &
WATCH=$!

docker run --rm --name gpsinfo-planetiler \
  --cpus="$THREADS" --blkio-weight=100 \
  -e JAVA_TOOL_OPTIONS="-Xmx$XMX" \
  -v "$STORAGE_BUILD_DIR":/data \
  -v "$LOCAL_TMP":/tmp/pt \
  "$PLANETILER_IMAGE" \
  --area="$AREA" --download \
  --output=/data/planet.pmtiles \
  --tmpdir=/tmp/pt \
  --nodemap-storage=mmap --storage=mmap --threads="$THREADS"

kill "$WATCH" 2>/dev/null || true

# Publish: copy from the Storage Box to local NVMe so tiles serve fast.
cp "$STORAGE_BUILD_DIR/planet.pmtiles" "$DATA_DIR/planet.pmtiles"
chmod 644 "$DATA_DIR/planet.pmtiles"
# Free the local temp now that we're done.
rm -rf "${LOCAL_TMP:?}/"* 2>/dev/null || true
echo "[done] planet.pmtiles published to $DATA_DIR"
