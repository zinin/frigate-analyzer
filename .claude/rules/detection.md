---
paths: "modules/core/**/loadbalancer/**,modules/core/**/service/Detect*,modules/core/**/service/*Visualization*,modules/core/**/service/*Filter*"
---

# Detection Server Management

## Load Balancer Architecture

| Component | Location | Purpose |
|-----------|----------|---------|
| DetectServerLoadBalancer | `core/loadbalancer/` | Main coordinator, priority-based load balancing |
| DetectServerRegistry | `core/loadbalancer/` | Thread-safe registry of server states |
| ServerHealthMonitor | `core/loadbalancer/` | Health checks every 30s |
| ServerSelectionStrategy | `core/loadbalancer/` | Priority-based selection considering load |

## Request Types

| Type | Purpose |
|------|---------|
| FRAME | Single frame detection |
| FRAME_EXTRACTION | Extract frames from video |
| VISUALIZE | Draw bounding boxes on frame |

Each server has configurable capacity per request type.

## DetectService

Location: `core/service/DetectService.kt`

- WebClient calls to detection servers
- Automatic retry with 60s timeout
- Marks server dead on failure, retries with different server
- Sends images as multipart/form-data to `/detect`

## Post-Processing

| Service | Purpose |
|---------|---------|
| DetectionFilterService | Filters by allowed object classes |
| FrameVisualizationService | Draws bounding boxes on frames |

## Configuration

Detection servers in `application.yaml` under `application.detect-servers`:
- Each server: frame-requests, frames-extract-requests, visualize-requests
- Each request type: simultaneous-count (capacity), priority (lower = preferred)
- Override at runtime via system properties (see `start.sh`)

Detection filtering in `application.detection-filter`:
- `enabled` - on/off
- `allowed-classes` - list (person, car, dog, etc.)
