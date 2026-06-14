# gpsinfo-extract — region extractor. Bundles the go-pmtiles CLI it shells out to.
# ⚠️ Untested scaffold — review before running on the box.

FROM golang:1.23-alpine AS build
WORKDIR /src
COPY main.go .
RUN go mod init gpsinfo-extract && go build -o /gpsinfo-extract .

# Grab the official pmtiles CLI binary.
FROM alpine:3.20 AS pmtiles
ARG PMTILES_VERSION=1.30.3
ARG TARGETARCH=amd64
RUN apk add --no-cache curl tar \
 && curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz" \
    | tar -xz -C /usr/local/bin pmtiles

FROM alpine:3.20
COPY --from=build /gpsinfo-extract /usr/local/bin/gpsinfo-extract
COPY --from=pmtiles /usr/local/bin/pmtiles /usr/local/bin/pmtiles
ENV PLANET=/data/planet.pmtiles CACHE=/cache ADDR=:8081
VOLUME ["/data", "/cache"]
EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/gpsinfo-extract"]
