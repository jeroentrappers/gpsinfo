#!/usr/bin/env bash
# Staged, throttled Planetiler builds, run DIRECTLY via Java (not Docker —
# Docker can't bind-mount the CIFS Storage Box). Builds each area in $AREAS in
# order and swaps its output into the served file as soon as it finishes, so
# coverage grows progressively (e.g. belgium -> europe -> planet): the home
# region is live in minutes while the planet bakes overnight.
#
#   - sources (PBF) + per-area output -> Storage Box (CWD = $STORAGE_BUILD_DIR)
#   - random-IO temp                  -> local NVMe (--tmpdir=$LOCAL_TMP), wiped between stages
# nice/ionice keep CPU+IO polite; a disk watchdog aborts if local free drops too
# low. After each stage the area's pmtiles is copied to $DATA_DIR for fast serving.
#
# env: JAVA JAR AREAS THREADS XMX STORAGE_BUILD_DIR LOCAL_TMP DATA_DIR WATCHDOG_MIN_FREE_KB
set -uo pipefail

mkdir -p "$LOCAL_TMP" "$STORAGE_BUILD_DIR"
cd "$STORAGE_BUILD_DIR"     # sources download to ./data/sources here (Storage Box)

# --- disk watchdog: runs for the whole pipeline, killed at the end. Keys on a
#     sentinel (not pgrep) so the brief gaps between stages don't stop it. ---
FLAG="$LOCAL_TMP/.building"
: > "$FLAG"
(
  while [ -f "$FLAG" ]; do
    sleep 60
    free=$(df -P "$LOCAL_TMP" | awk 'NR==2{print $4}')
    if [ "${free:-0}" -lt "$WATCHDOG_MIN_FREE_KB" ]; then
      echo "[watchdog] local free ${free}KB < ${WATCHDOG_MIN_FREE_KB}KB — aborting to protect /"
      pkill -f planetiler.jar || true
      rm -f "$FLAG"
      exit 1
    fi
  done
) &
WATCH=$!

for AREA in $AREAS; do
  echo "[stage] building ${AREA} ..."
  out="$STORAGE_BUILD_DIR/${AREA}.pmtiles"
  if nice -n 15 ionice -c2 -n7 \
      "$JAVA" -Xmx"$XMX" -jar "$JAR" \
      --area="$AREA" --download \
      --output="$out" \
      --tmpdir="$LOCAL_TMP" \
      --nodemap-storage=mmap --storage=mmap --threads="$THREADS"
  then
    # Publish: copy to local NVMe so tiles serve fast. cp to a temp then mv so
    # the swap into the served path is atomic.
    cp "$out" "$DATA_DIR/planet.pmtiles.new"
    chmod 644 "$DATA_DIR/planet.pmtiles.new"
    mv "$DATA_DIR/planet.pmtiles.new" "$DATA_DIR/planet.pmtiles"
    echo "[stage] ${AREA} is LIVE ($(du -h "$out" | cut -f1))"
  else
    echo "[stage] ${AREA} build FAILED — keeping previous coverage, stopping pipeline"
    break
  fi
  rm -rf "${LOCAL_TMP:?}/"* 2>/dev/null || true   # free temp for the next stage
done

rm -f "$FLAG"
kill "$WATCH" 2>/dev/null || true
echo "[done] staged build pipeline finished"
