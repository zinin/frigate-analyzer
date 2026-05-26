---
paths: "modules/core/**/loadbalancer/**,modules/core/**/service/Detect*,modules/core/**/service/*Visualization*,modules/core/**/service/*Filter*,modules/core/**/service/Detection*,modules/core/**/service/VideoExport*,modules/core/**/service/StatusService*"
---

# Detection Server Management

## Load Balancer Architecture

| Component | Location | Purpose |
|-----------|----------|---------|
| DetectServerLoadBalancer | `core/loadbalancer/` | Main coordinator, priority-based load balancing |
| DetectServerRegistry | `core/loadbalancer/` | Thread-safe registry of server states |
| ServerHealthMonitor | `core/loadbalancer/` | Health checks (configurable interval/timeout) |
| ServerSelectionStrategy | `core/loadbalancer/` | Priority-based selection considering load |
| ServerState | `core/loadbalancer/` | Server state data class |
| AcquiredServer | `core/loadbalancer/` | Acquired server handle with release |
| RequestType | `core/loadbalancer/` | Enum: FRAME, FRAME_EXTRACTION, VISUALIZE |

## Request Types

| Type | Purpose |
|------|---------|
| FRAME | Single frame detection |
| FRAME_EXTRACTION | Extract frames from video on server |
| VISUALIZE | Draw bounding boxes on frame (remote) |

Each server has configurable capacity per request type.

## Detection Services

| Service | Location | Purpose |
|---------|----------|---------|
| DetectService | `core/service/` | WebClient calls to detection servers |
| DetectionPostProcessor | `core/service/` | Orchestrates detect + filter + retry |
| DetectionFilterService | `core/service/` | Filters by allowed object classes |

### DetectService

- WebClient calls to detection servers
- Automatic retry with configurable timeout per request type
- Marks server dead on failure, retries with different server
- Sends images as multipart/form-data to `/detect`

### DetectionPostProcessor

- Orchestrates detection flow: detect → filter → handle errors
- Retries on `DetectTimeoutException`
- Catches `CancellationException` for graceful shutdown

## Visualization Services

| Service | Location | Purpose |
|---------|----------|---------|
| FrameVisualizationService | `core/service/` | Orchestrates frame visualization (remote or local) |
| LocalVisualizationService | `core/service/` | Draws bounding boxes locally using Java2D |
| VideoVisualizationService | `core/service/` | Submits video-annotation jobs to vision-api-server, polls status until completion/cancel |

### LocalVisualizationService

- Pure Java2D rendering without external server
- Configurable: line width, font scaling, quality, label padding
- Auto-scales fonts based on image height relative to reference height

## Video Export & Status

| Service | Location | Purpose |
|---------|----------|---------|
| VideoExportServiceImpl | `core/service/` | Merges Frigate recordings into a single video for `/export` and QuickExport (modes: ORIGINAL / ANNOTATED) |
| StatusService | `core/service/` | Aggregates recordings/cameras/detect-servers state for `/actuator` and admin endpoints; consumes optional `SignalLossMonitorTask` via `ObjectProvider` |

## Configuration

Detection servers in `application.yaml` under `application.detect-servers`:
- Each server: frame-requests, frames-extract-requests, visualize-requests
- Each request type: simultaneous-count (capacity), priority (lower = preferred)

Detection filtering in `application.detection-filter`:
- `enabled` - on/off
- `allowed-classes` - list (person, car, dog, etc.)

See `.claude/rules/configuration.md` for all environment variables.
