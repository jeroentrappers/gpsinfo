# Multi-stage build for the GPSinfo traffic-aggregation service.
# Mirrors the deploy/tiles/extract Go tool packaging so it slots into the
# same Ansible + Docker + nginx pattern as Valhalla and the tile server.
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /traffic .

FROM gcr.io/distroless/static-debian12
COPY --from=build /traffic /traffic
EXPOSE 8080
# Loopback in production; the host nginx fronts it with TLS + the ?key= gate.
# -addr is in CMD so compose can override it (e.g. host networking).
ENTRYPOINT ["/traffic"]
CMD ["-addr", ":8080"]
