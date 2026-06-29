// gpsinfo traffic.tar updater.
//
// Pokes live per-edge speeds into Valhalla's memory-mapped traffic overlay
// (traffic.tar) so routing + ETAs become traffic-aware. Reads an edge→speed
// list (the gpsinfo-traffic service's /edgespeeds JSON) and writes a
// TrafficSpeed record for each listed directed edge; edges not listed are
// cleared (no live data).
//
// Header-only against Valhalla (uses just the exact TrafficSpeed /
// TrafficTileHeader layout from traffictile.h — compiled inside the same
// gis-ops image, so the structs match the running router).
//
// Valhalla 3.5.x traffic extract = a TAR whose first member "index.bin" is an
// array of tile_index_entry{offset,tile_id,size}; each tile's data lives at
// (tar_base + offset) as TrafficTileHeader + directed_edge_count TrafficSpeed
// records. A directed edge's GraphId value = tile.tile_id | (index << 25),
// matching /trace_attributes edge.id.

#include <valhalla/baldr/traffictile.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

using valhalla::baldr::TrafficSpeed;
using valhalla::baldr::TrafficTileHeader;

// Mirrors graphreader.cc's private index entry (16 bytes).
struct tile_index_entry {
  uint64_t offset;  // byte offset from the start of the tar to the tile data
  uint32_t tile_id; // level + tileindex (unused here; we read the tile header)
  uint32_t size;    // tile size in bytes
};

static uint64_t parse_octal(const char* p, int n) {
  uint64_t v = 0;
  for (int i = 0; i < n; i++) {
    char c = p[i];
    if (c < '0' || c > '7') break;
    v = (v << 3) + (uint64_t)(c - '0');
  }
  return v;
}

// Minimal scan for {"edges":[{"edge":N,"speed":S},...]} — no JSON dep.
static void load_speeds(const char* path, std::unordered_map<uint64_t, int>& out) {
  FILE* f = fopen(path, "rb");
  if (!f) { fprintf(stderr, "updater: cannot open %s\n", path); return; }
  fseek(f, 0, SEEK_END);
  long sz = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (sz <= 0) { fclose(f); return; }
  std::string s;
  s.resize((size_t)sz);
  if (fread(&s[0], 1, (size_t)sz, f) != (size_t)sz) { fclose(f); return; }
  fclose(f);
  size_t i = 0;
  while (true) {
    size_t e = s.find("\"edge\"", i);
    if (e == std::string::npos) break;
    e = s.find(':', e);
    if (e == std::string::npos) break;
    uint64_t edge = strtoull(s.c_str() + e + 1, nullptr, 10);
    size_t sp = s.find("\"speed\"", e);
    if (sp == std::string::npos) break;
    sp = s.find(':', sp);
    if (sp == std::string::npos) break;
    int speed = (int)strtol(s.c_str() + sp + 1, nullptr, 10);
    out[edge] = speed;
    i = sp + 1;
  }
}

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <traffic.tar> <edgespeeds.json>\n", argv[0]);
    return 2;
  }
  std::unordered_map<uint64_t, int> speeds;
  load_speeds(argv[2], speeds);

  int fd = open(argv[1], O_RDWR);
  if (fd < 0) { perror("updater: open traffic.tar"); return 1; }
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size <= 0) { perror("updater: fstat"); close(fd); return 1; }
  const size_t fsize = (size_t)st.st_size;
  void* data = mmap(nullptr, fsize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (data == MAP_FAILED) { perror("updater: mmap"); close(fd); return 1; }
  char* base = (char*)data;

  // Find the index.bin tar member.
  const tile_index_entry* entries = nullptr;
  size_t entry_count = 0;
  {
    size_t pos = 0;
    const size_t HDR = 512;
    while (pos + HDR <= fsize) {
      char* h = base + pos;
      bool zero = true;
      for (size_t k = 0; k < HDR; k++) {
        if (h[k]) { zero = false; break; }
      }
      if (zero) break;
      char name[101];
      memcpy(name, h, 100);
      name[100] = 0;
      uint64_t msize = parse_octal(h + 124, 11);
      if (strcmp(name, "index.bin") == 0) {
        entries = reinterpret_cast<const tile_index_entry*>(base + pos + HDR);
        entry_count = (size_t)msize / sizeof(tile_index_entry);
        break;
      }
      pos += HDR + ((msize + 511) / 512) * 512;
    }
  }
  if (!entries) {
    fprintf(stderr, "updater: index.bin not found in %s\n", argv[1]);
    munmap(data, fsize);
    close(fd);
    return 1;
  }

  long tiles = 0, updated = 0, closed = 0;
  for (size_t e = 0; e < entry_count; e++) {
    uint64_t off = entries[e].offset;
    uint32_t tsize = entries[e].size;
    if (off + sizeof(TrafficTileHeader) > fsize) continue;
    char* tdata = base + off;
    auto* th = reinterpret_cast<TrafficTileHeader*>(tdata);
    auto* sp = reinterpret_cast<TrafficSpeed*>(tdata + sizeof(TrafficTileHeader));
    uint32_t n = th->directed_edge_count;
    if (sizeof(TrafficTileHeader) + (size_t)n * sizeof(TrafficSpeed) > tsize) continue;
    uint64_t tile_base = th->tile_id;
    for (uint32_t idx = 0; idx < n; idx++) {
      uint64_t ev = tile_base | ((uint64_t)idx << 25);
      TrafficSpeed ts; // default: all zero → breakpoint1==0 → no live data
      auto it = speeds.find(ev);
      if (it != speeds.end()) {
        int kmh = it->second;
        if (kmh < 0) kmh = 0;
        if (kmh > 250) kmh = 250;
        uint32_t enc = (uint32_t)(kmh >> 1); // 2 km/h resolution
        ts.overall_encoded_speed = enc;
        ts.encoded_speed1 = enc;
        ts.breakpoint1 = 255; // speed applies to the whole edge
        updated++;
        if (kmh == 0) closed++;
      }
      sp[idx] = ts;
    }
    tiles++;
  }
  msync(data, fsize, MS_SYNC);
  munmap(data, fsize);
  close(fd);
  fprintf(stderr, "updater: tiles=%ld edges_set=%ld closed=%ld (of %zu requested)\n",
          tiles, updated, closed, speeds.size());
  return 0;
}
