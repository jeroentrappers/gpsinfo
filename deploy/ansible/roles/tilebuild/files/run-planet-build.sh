#!/usr/bin/env bash
# Throttled Planetiler build for the shared box, run DIRECTLY via Java (not
# Docker — Docker can't bind-mount the sshfs Storage Box, it hangs). A normal
# process writes to the sshfs mount fine (~87 MB/s), so:
#   - sources (PBF) + output  -> Storage Box (CWD = $STORAGE_BUILD_DIR)
#   - random-IO temp          -> local NVMe (--tmpdir=$LOCAL_TMP)
# nice/ionice keep CPU+IO polite next to the live sites; a disk watchdog aborts
# if local free drops too low; on success the pmtiles is copied to local disk
# for fast serving.
#
# env: JAVA JAR AREA THREADS XMX STORAGE_BUILD_DIR LOCAL_TMP DATA_DIR WATCHDOG_MIN_FREE_KB
set -euo pipefail

mkdir -p "$LOCAL_TMP" "$STORAGE_BUILD_DIR"
cd "$STORAGE_BUILD_DIR"     # sources download to ./data/sources here (Storage Box)

# --- disk watchdog (protects the box) ---
(
  while sleep 60; do
    pgrep -f planetiler.jar >/dev/null || exit 0          # build finished
    free=$(df -P "$LOCAL_TMP" | awk 'NR==2{print $4}')
    if [ "${free:-0}" -lt "$WATCHDOG_MIN_FREE_KB" ]; then
      echo "[watchdog] local free ${free}KB < ${WATCHDOG_MIN_FREE_KB}KB — aborting to protect /"
      pkill -f planetiler.jar || true
      exit 1
    fi
  done
) &
WATCH=$!

nice -n 15 ionice -c2 -n7 \
  "$JAVA" -Xmx"$XMX" -jar "$JAR" \
  --area="$AREA" --download \
  --output="$STORAGE_BUILD_DIR/planet.pmtiles" \
  --tmpdir="$LOCAL_TMP" \
  --nodemap-storage=mmap --storage=mmap --threads="$THREADS"

kill "$WATCH" 2>/dev/null || true

# Publish: copy from the Storage Box to local NVMe so tiles serve fast.
cp "$STORAGE_BUILD_DIR/planet.pmtiles" "$DATA_DIR/planet.pmtiles"
chmod 644 "$DATA_DIR/planet.pmtiles"
rm -rf "${LOCAL_TMP:?}/"* 2>/dev/null || true
echo "[done] planet.pmtiles published to $DATA_DIR"
