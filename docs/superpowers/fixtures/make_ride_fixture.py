"""Writes a synthetic 1 Hz ride (Colombo) with a stop, a hard brake, a hard acceleration and a GPS spike.
Usage: python docs/superpowers/fixtures/make_ride_fixture.py OUT_DIR [START_EPOCH_MS]"""
import json, sys
out = sys.argv[1]
start = int(sys.argv[2]) if len(sys.argv) > 2 else 1790000000000
kmh = ([0] * 3 + [5, 12, 20, 28, 36, 44, 50] + [50] * 30 + [50, 36, 24] + [24] * 10 + [0] * 30 +
       [4, 18, 30, 40] + [40] * 20 + [40, 120, 40] + [40] * 20 + [70, 80, 84, 84, 70, 55] + [40] * 20 + [9, 20, 30] + [0] * 3)
lat, lon, pts, dist = 6.9271, 79.8612, [], 0.0
for i, v in enumerate(kmh):
    ms = v / 3.6
    lat += ms / 111_000 * 0.8
    lon += ms / 111_000 * 0.6
    dist += ms
    pts.append([lat, lon, start + i * 1000, ms])
moving = sum(1000 for v in kmh if v >= 3)
trip = {"id": str(start), "start": start, "end": start + (len(kmh) - 1) * 1000, "distanceMeters": dist,
        "movingTimeMs": moving, "maxSpeedMs": 84 / 3.6, "points": pts}
open(f"{out}/{start}.json", "w").write(json.dumps(trip))
print(f"{out}/{start}.json")
