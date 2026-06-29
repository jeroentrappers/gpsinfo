# gpsinfo traffic.tar writer.
#
# Compiles the updater INSIDE the exact gis-ops Valhalla image we run, so the
# TrafficSpeed / TrafficTileHeader layout matches the live router byte-for-byte
# (header-only; no linking against libvalhalla). Ships a slim runtime that
# loops: fetch /edgespeeds → write the memory-mapped traffic.tar.
ARG VALHALLA_IMAGE=ghcr.io/gis-ops/docker-valhalla/valhalla:latest

FROM ${VALHALLA_IMAGE} AS build
USER root
RUN apt-get update && apt-get install -y --no-install-recommends g++ libboost-dev \
    && rm -rf /var/lib/apt/lists/*
COPY updater.cc /tmp/updater.cc
RUN g++ -O2 -std=c++17 -I/usr/local/include /tmp/updater.cc -o /usr/local/bin/traffic-updater

# Runtime glibc must be >= the build image's (gis-ops is Ubuntu); match it.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates libstdc++6 && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/local/bin/traffic-updater /usr/local/bin/traffic-updater
COPY run.sh /usr/local/bin/run.sh
RUN chmod +x /usr/local/bin/run.sh
ENTRYPOINT ["/usr/local/bin/run.sh"]
