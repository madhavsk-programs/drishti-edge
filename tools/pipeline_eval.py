"""Run the SHIPPING perception stack over still images, off-device.

This is the bench that makes Walk Mode's perception arguable instead of
anecdotal. It loads the same two ONNX graphs the phone loads, reproduces the
Kotlin pipeline's arithmetic step for step — letterbox, YOLO head decode, NMS,
label canonicalization, corridor geometry, surface ratios, floor extent, and the
risk cascade — and prints what the phone would have decided, plus the evidence
it decided on.

    python tools/pipeline_eval.py IMAGE_OR_DIR [--render OUTDIR] [--json OUT.json]

Any divergence from `apps/android/.../spatial` and `.../risk` is a bug HERE, not
a licence to tune. When a threshold moves in Kotlin it moves here too.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[1]
MODELS = REPO / "models" / "staging"

# ---------------------------------------------------------------------------
# PipelineSettings.kt — keep these in lockstep with the Kotlin defaults.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Settings:
    detector_confidence_threshold: float = 0.45   # Find / landmark view
    risk_confidence_threshold: float = 0.35       # safety view
    proximity_area_weight: float = 0.55
    proximity_area_scale: float = 0.50
    proximity_far_threshold: float = 0.35
    proximity_medium_threshold: float = 0.55
    proximity_near_threshold: float = 0.78

    corridor_horizon_y: float = 0.38
    corridor_top_half_width: float = 0.08
    corridor_bottom_half_width: float = 0.42
    corridor_clear_margin: float = 0.10

    wall_centre_ratio_threshold: float = 0.35
    wall_side_ratio_threshold: float = 0.20
    surface_cost_road_weight: float = 0.0
    surface_cost_unknown_weight: float = 0.10
    freespace_dead_end_max: float = 0.12
    freespace_blocked_max: float = 0.20
    freespace_side_open_min: float = 0.30
    direction_min_free_extent: float = 0.35
    stairs_centre_ratio_threshold: float = 0.08

    risk_watch_enter: float = 0.25
    risk_warn_enter: float = 0.65
    risk_high_enter: float = 0.80

    risk_weight_path_overlap: float = 0.30
    risk_weight_proximity: float = 0.25
    risk_weight_approach: float = 0.20
    risk_weight_class_severity: float = 0.15
    risk_weight_confidence: float = 0.10

    risk_centre_block_threshold: float = 0.40
    risk_side_block_threshold: float = 0.55
    decision_margin: float = 0.15


MIN_WALKABLE_RATIO = 0.25
MIN_BLOCKING_SURFACE_RATIO = 0.20
MIN_COLUMN_SPAN_RATIO = 0.80

CONFIDENCE_FLOOR = 0.35
IOU_THRESHOLD = 0.45
PAD_VALUE = 114.0 / 255.0

CLASS_SEVERITIES = {
    "person": 0.55, "chair": 0.75, "bag": 0.45, "desk": 0.80, "bicycle": 0.80,
    "motorcycle": 1.0, "car": 0.95, "bus": 1.0, "truck": 1.0, "train": 1.0,
    "dog": 0.60, "fire hydrant": 0.70, "stop sign": 0.70, "parking meter": 0.70,
    "traffic light": 0.70, "bench": 0.65, "door": 0.80, "suitcase": 0.65,
    "umbrella": 0.55, "potted plant": 0.70, "couch": 0.75, "bed": 0.80,
    "tv": 0.45, "refrigerator": 0.85, "sink": 0.65, "toilet": 0.75,
    "laptop": 0.45, "bottle": 0.35, "cup": 0.30, "bowl": 0.30, "vase": 0.45,
    "book": 0.25, "keyboard": 0.25, "skateboard": 0.55, "sports ball": 0.35,
    "microwave": 0.60, "oven": 0.70, "toaster": 0.45,
}
CANONICAL_LABELS = set(CLASS_SEVERITIES)
LABEL_ALIASES = {
    "backpack": "bag", "handbag": "bag", "dining table": "desk", "table": "desk",
}

# Surfaces.kt
WALKABLE_TOKENS = {
    "floor", "flooring", "rug", "carpet", "path", "sidewalk", "pavement",
    "grass", "earth", "field", "land", "dirt track", "sand",
}
ROAD_TOKENS = {"road", "route"}
NON_WALKABLE_TOKENS = {
    "wall", "building", "ceiling", "door", "screen door", "windowpane",
    "column", "pillar", "railing", "bannister", "fence", "pole",
    "house", "skyscraper", "tower", "hovel", "tent", "bridge", "booth",
    "curtain", "blind", "mirror", "painting", "poster", "bulletin board",
    "signboard", "trade name", "screen", "stage", "grandstand",
    "cabinet", "wardrobe", "table", "desk", "coffee table", "pool table",
    "chair", "armchair", "swivel chair", "seat", "stool", "ottoman",
    "bench", "sofa", "shelf", "bookcase", "chest of drawers", "buffet",
    "counter", "countertop", "kitchen island", "bar", "case", "box",
    "basket", "barrel", "bed", "cradle", "fireplace", "radiator",
    "refrigerator", "stove", "oven", "microwave", "dishwasher", "washer",
    "sink", "toilet", "bathtub", "shower", "computer", "monitor",
    "crt screen", "television receiver", "arcade machine", "conveyer belt",
    "person", "rider", "animal", "plant", "tree", "palm", "flower",
    "sculpture", "fountain", "ashcan", "streetlight", "traffic light",
    "pier", "swimming pool", "rock",
    "car", "truck", "bus", "train", "motorcycle", "minibike", "bicycle",
    "van", "boat", "ship", "airplane", "tank",
    "water", "river", "sea", "lake", "waterfall",
    "stairs", "stairway", "step", "escalator",
}
HAZARD_TOKENS = {"stairs", "stairway", "step", "escalator"}
WALL_TOKENS = {"wall"}

WALKABLE, ROAD, NON_WALKABLE, UNKNOWN = 0, 1, 2, 3
KIND_NAME = {WALKABLE: "WALKABLE", ROAD: "ROAD", NON_WALKABLE: "NON_WALKABLE", UNKNOWN: "UNKNOWN"}


def label_tokens(label: str) -> set[str]:
    return {p.strip().lower() for p in label.split(",") if p.strip()}


def kind_for(label: str) -> int:
    t = label_tokens(label)
    if t & NON_WALKABLE_TOKENS:
        return NON_WALKABLE
    if t & WALKABLE_TOKENS:
        return WALKABLE
    if t & ROAD_TOKENS:
        return ROAD
    return UNKNOWN


# ---------------------------------------------------------------------------
# Letterbox.kt
# ---------------------------------------------------------------------------


class Letterbox:
    def __init__(self, sw: int, sh: int, tw: int, th: int):
        self.sw, self.sh, self.tw, self.th = sw, sh, tw, th
        self.scale = min(tw / sw, th / sh)
        self.scaled_w = max(1, round(sw * self.scale))
        self.scaled_h = max(1, round(sh * self.scale))
        self.pad_x = (tw - self.scaled_w) // 2
        self.pad_y = (th - self.scaled_h) // 2

    def to_oriented(self, tx: float, ty: float) -> tuple[float, float]:
        return (
            min(1.0, max(0.0, (tx - self.pad_x) / self.scaled_w)),
            min(1.0, max(0.0, (ty - self.pad_y) / self.scaled_h)),
        )

    def from_oriented(self, nx: float, ny: float) -> tuple[float, float]:
        return nx * self.scaled_w + self.pad_x, ny * self.scaled_h + self.pad_y

    def fill(self, rgb: np.ndarray, pad_value: float) -> np.ndarray:
        """RGB uint8 HxWx3 -> float32 target_h x target_w x 3 in [0,1]."""
        out = np.full((self.th, self.tw, 3), pad_value, dtype=np.float32)
        resized = np.asarray(
            Image.fromarray(rgb).resize((self.scaled_w, self.scaled_h), Image.BILINEAR),
            dtype=np.float32,
        ) / 255.0
        out[self.pad_y:self.pad_y + self.scaled_h, self.pad_x:self.pad_x + self.scaled_w] = resized
        return out


# ---------------------------------------------------------------------------
# Detector
# ---------------------------------------------------------------------------


@dataclass
class Detection:
    label: str
    confidence: float
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def area(self) -> float:
        return max(1e-6, (self.x2 - self.x1) * (self.y2 - self.y1))

    def polygon(self):
        return [(self.x1, self.y1), (self.x2, self.y1), (self.x2, self.y2), (self.x1, self.y2)]


def parse_names(session) -> dict[int, str]:
    raw = session.get_modelmeta().custom_metadata_map.get("names")
    if not raw:
        return {}
    try:
        import ast

        return {int(k): str(v).lower() for k, v in ast.literal_eval(raw).items()}
    except Exception:
        return {}


def nms(boxes: list[tuple[float, float, float, float, float, int]], iou_threshold: float):
    boxes = sorted(boxes, key=lambda b: b[4], reverse=True)
    kept = []
    for box in boxes:
        drop = False
        for k in kept:
            if k[5] != box[5]:
                continue
            ix1, iy1 = max(box[0], k[0]), max(box[1], k[1])
            ix2, iy2 = min(box[2], k[2]), min(box[3], k[3])
            iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
            inter = iw * ih
            if inter <= 0:
                continue
            a = (box[2] - box[0]) * (box[3] - box[1])
            b = (k[2] - k[0]) * (k[3] - k[1])
            if inter / (a + b - inter) >= iou_threshold:
                drop = True
                break
        if not drop:
            kept.append(box)
    return kept


class Detector:
    def __init__(self, model: Path, settings: Settings):
        self.session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
        self.settings = settings
        self.input_name = self.session.get_inputs()[0].name
        shape = self.session.get_inputs()[0].shape
        self.nhwc = len(shape) == 4 and shape[3] == 3
        self.h = int(shape[1] if self.nhwc else shape[2])
        self.w = int(shape[2] if self.nhwc else shape[3])
        self.names = parse_names(self.session)

    def detect(self, rgb: np.ndarray, conf_floor: float = CONFIDENCE_FLOOR):
        sh, sw = rgb.shape[:2]
        lb = Letterbox(sw, sh, self.w, self.h)
        tensor = lb.fill(rgb, PAD_VALUE)
        tensor = tensor[None] if self.nhwc else tensor.transpose(2, 0, 1)[None]
        out = self.session.run(None, {self.input_name: np.ascontiguousarray(tensor)})[0]

        # [1, 4 + classes, anchors], channel-major, no NMS in the export.
        pred = out[0]
        boxes_xywh = pred[:4]
        scores = pred[4:]
        best_cls = scores.argmax(axis=0)
        best_score = scores.max(axis=0)
        keep = best_score >= conf_floor
        raw = []
        for i in np.nonzero(keep)[0]:
            cx, cy, bw, bh = boxes_xywh[:, i]
            raw.append((
                float(cx - bw / 2), float(cy - bh / 2),
                float(cx + bw / 2), float(cy + bh / 2),
                float(best_score[i]), int(best_cls[i]),
            ))
        kept = nms(raw, IOU_THRESHOLD)

        everything, risk_view = [], []
        for x1, y1, x2, y2, score, cls in kept:
            nx1, ny1 = lb.to_oriented(x1, y1)
            nx2, ny2 = lb.to_oriented(x2, y2)
            if nx1 >= nx2 or ny1 >= ny2:
                continue
            native = self.names.get(cls, f"class_{cls}")
            everything.append(Detection(native, score, nx1, ny1, nx2, ny2))
            aliased = LABEL_ALIASES.get(native, native)
            if aliased in CANONICAL_LABELS and score >= self.settings.risk_confidence_threshold:
                risk_view.append(Detection(aliased, score, nx1, ny1, nx2, ny2))
        return risk_view, everything


# ---------------------------------------------------------------------------
# Segmenter
# ---------------------------------------------------------------------------


class Segmenter:
    def __init__(self, model: Path, labels: dict[int, str]):
        self.session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        shape = self.session.get_inputs()[0].shape
        self.h, self.w = int(shape[2]), int(shape[3])
        self.labels = labels
        self.kind_by_id = {i: kind_for(v) for i, v in labels.items()}
        self.hazard_ids = {i for i, v in labels.items() if label_tokens(v) & HAZARD_TOKENS}
        self.wall_ids = {i for i, v in labels.items() if label_tokens(v) & WALL_TOKENS}

    def segment(self, rgb: np.ndarray):
        sh, sw = rgb.shape[:2]
        lb = Letterbox(sw, sh, self.w, self.h)
        tensor = lb.fill(rgb, 0.0).transpose(2, 0, 1)[None]
        logits = self.session.run(None, {self.input_name: np.ascontiguousarray(tensor)})[0]
        class_id = logits[0].argmax(axis=0).astype(np.int32)
        kind = np.vectorize(lambda c: self.kind_by_id.get(int(c), UNKNOWN))(class_id).astype(np.int32)
        hazard = np.isin(class_id, list(self.hazard_ids))
        wall = np.isin(class_id, list(self.wall_ids))
        return class_id, kind, hazard, wall


# ---------------------------------------------------------------------------
# Corridor.kt geometry
# ---------------------------------------------------------------------------

LEFT, CENTRE, RIGHT = "LEFT", "CENTRE", "RIGHT"
ORDER = (LEFT, CENTRE, RIGHT)


def corridor_polygons(s: Settings) -> dict[str, list[tuple[float, float]]]:
    tl, tr = 0.5 - s.corridor_top_half_width, 0.5 + s.corridor_top_half_width
    bl, br = 0.5 - s.corridor_bottom_half_width, 0.5 + s.corridor_bottom_half_width

    def pt(f: float, top: bool):
        lo, hi = (tl, tr) if top else (bl, br)
        return (lo + (hi - lo) * f, s.corridor_horizon_y if top else 1.0)

    t, tt = 1 / 3, 2 / 3
    return {
        LEFT: [pt(0, True), pt(t, True), pt(t, False), pt(0, False)],
        CENTRE: [pt(t, True), pt(tt, True), pt(tt, False), pt(t, False)],
        RIGHT: [pt(tt, True), pt(1, True), pt(1, False), pt(tt, False)],
    }


def full_corridor(s: Settings):
    return [
        (0.5 - s.corridor_top_half_width, s.corridor_horizon_y),
        (0.5 + s.corridor_top_half_width, s.corridor_horizon_y),
        (0.5 + s.corridor_bottom_half_width, 1.0),
        (0.5 - s.corridor_bottom_half_width, 1.0),
    ]


def signed_area(poly):
    return sum(
        poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
        for i in range(len(poly))
    ) / 2


def polygon_area(poly):
    return abs(signed_area(poly))


def intersection_area(subject, clip):
    out = list(subject)
    if signed_area(clip) < 0:
        clip = clip[::-1]
    for i in range(len(clip)):
        if not out:
            return 0.0
        a, b = clip[i], clip[(i + 1) % len(clip)]
        inp, out = out, []

        def inside(p):
            return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0

        def cross(p, q):
            r1, r2 = q[0] - p[0], q[1] - p[1]
            s1, s2 = b[0] - a[0], b[1] - a[1]
            d = r1 * s2 - r2 * s1
            if abs(d) < 1e-12:
                return None
            t = ((a[0] - p[0]) * s2 - (a[1] - p[1]) * s1) / d
            return (p[0] + t * r1, p[1] + t * r2)

        for j in range(len(inp)):
            cur, prev = inp[j], inp[j - 1]
            if inside(cur):
                if not inside(prev):
                    p = cross(prev, cur)
                    if p:
                        out.append(p)
                out.append(cur)
            elif inside(prev):
                p = cross(prev, cur)
                if p:
                    out.append(p)
    return polygon_area(out) if len(out) >= 3 else 0.0


def obstruction(det: Detection, region) -> float:
    inter = intersection_area(det.polygon(), region)
    if inter <= 0:
        return 0.0
    return min(1.0, max(inter / det.area, inter / max(1e-6, polygon_area(region))))


def direction_for_anchor(x: float, y: float, s: Settings) -> str:
    if y < s.corridor_horizon_y or y > 1.0:
        return "UNKNOWN"
    progress = (y - s.corridor_horizon_y) / (1.0 - s.corridor_horizon_y)
    half = s.corridor_top_half_width + progress * (
        s.corridor_bottom_half_width - s.corridor_top_half_width
    )
    lo, hi = 0.5 - half, 0.5 + half
    if x < lo or x > hi:
        return "UNKNOWN"
    f = (x - lo) / (hi - lo)
    return LEFT if f < 1 / 3 else (CENTRE if f < 2 / 3 else RIGHT)


def relative_proximity(det: Detection, s: Settings):
    signal = min(1.0, math.sqrt(max(0.0, det.area)) / s.proximity_area_scale)
    score = min(1.0, max(0.0, s.proximity_area_weight * signal
                         + (1 - s.proximity_area_weight) * det.y2))
    if score < s.proximity_far_threshold:
        band = "FAR"
    elif score < s.proximity_medium_threshold:
        band = "MEDIUM"
    elif score < s.proximity_near_threshold:
        band = "NEAR"
    else:
        band = "IMMEDIATE"
    return score, band


# ---------------------------------------------------------------------------
# SurfaceEvidenceBuilder.kt
# ---------------------------------------------------------------------------


def rasterize(poly, w, h) -> np.ndarray:
    if signed_area(poly) < 0:
        poly = poly[::-1]
    ys, xs = np.mgrid[0:h, 0:w]
    px, py = xs + 0.5, ys + 0.5
    mask = np.ones((h, w), dtype=bool)
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        mask &= ((b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0])) >= 0
    return mask


def floor_extent(mask: np.ndarray, kind: np.ndarray, min_span_ratio: float) -> float:
    h, w = mask.shape
    extents = []
    spans, tops, bots = np.zeros(w, int), np.full(w, -1), np.full(w, -1)
    for x in range(w):
        rows = np.nonzero(mask[:, x])[0]
        if rows.size == 0:
            continue
        tops[x], bots[x] = rows[0], rows[-1]
        spans[x] = rows[-1] - rows[0] + 1
    tallest = spans.max() if spans.size else 0
    if tallest <= 0:
        return 0.0
    minimum = max(1, int(tallest * min_span_ratio))
    for x in range(w):
        if spans[x] < minimum:
            continue
        run = 0
        for y in range(bots[x], tops[x] - 1, -1):
            if not mask[y, x] or kind[y, x] != WALKABLE:
                break
            run += 1
        extents.append(run / spans[x])
    if not extents:
        return 0.0
    extents.sort()
    m = len(extents) // 2
    return (extents[m - 1] + extents[m]) / 2 if len(extents) % 2 == 0 else extents[m]


SURFACE_WITNESS_LABELS = {"laptop", "keyboard", "microwave", "oven", "toaster"}
SURFACE_WITNESS_MIN_CONFIDENCE = 0.50


def occlusion_mask(occluders, kind, lb: Letterbox, sx, sy, s: Settings):
    """Pixels under a risk-view detection: SurfaceEvidenceBuilder.occlusionMask."""
    if not occluders:
        return None
    h, w = kind.shape
    mask = np.zeros((h, w), dtype=bool)
    for d in occluders:
        tx1, ty1 = lb.from_oriented(d.x1, d.y1)
        tx2, ty2 = lb.from_oriented(d.x2, d.y2)
        x1 = int(np.clip(tx1 * sx, 0, w - 1)); x2 = int(np.clip(tx2 * sx, 0, w - 1))
        y1 = int(np.clip(ty1 * sy, 0, h - 1)); y2 = int(np.clip(ty2 * sy, 0, h - 1))
        witness = (d.label in SURFACE_WITNESS_LABELS
                   and d.confidence >= SURFACE_WITNESS_MIN_CONFIDENCE
                   and relative_proximity(d, s)[1] != "FAR")
        bottom = h - 1 if witness else y2
        mask[y1:bottom + 1, x1:x2 + 1] = True
    return mask


def surface_evidence(kind, hazard, wall, lb: Letterbox, s: Settings, min_span_ratio: float,
                     occluders=None):
    h, w = kind.shape
    sx, sy = w / lb.tw, h / lb.th
    occluded = occlusion_mask(occluders or [], kind, lb, sx, sy, s)
    if occluded is not None:
        kind = np.where(occluded & (kind == WALKABLE), UNKNOWN, kind)
    ev = {k: {} for k in ("walkable", "road", "non_walkable", "unknown", "wall", "stairs", "floor_extent")}
    for choice, poly in corridor_polygons(s).items():
        mapped = [(x * sx, y * sy) for x, y in (lb.from_oriented(nx, ny) for nx, ny in poly)]
        mask = rasterize(mapped, w, h)
        total = max(1, int(mask.sum()))
        ev["walkable"][choice] = float((mask & (kind == WALKABLE)).sum()) / total
        ev["road"][choice] = float((mask & (kind == ROAD)).sum()) / total
        ev["non_walkable"][choice] = float((mask & (kind == NON_WALKABLE)).sum()) / total
        ev["unknown"][choice] = float((mask & (kind == UNKNOWN)).sum()) / total
        ev["wall"][choice] = float((mask & wall).sum()) / total
        ev["stairs"][choice] = float((mask & hazard).sum()) / total
        ev["floor_extent"][choice] = floor_extent(mask, kind, min_span_ratio)
    return ev


# ---------------------------------------------------------------------------
# analyzeCorridors + scoreTracks + selectAction
# ---------------------------------------------------------------------------


@dataclass
class Track:
    detection: Detection
    proximity: float
    band: str
    direction: str
    path_overlap: float
    score: float = 0.0
    level: str = "CLEAR"


@dataclass
class Analysis:
    tracks: list[Track] = field(default_factory=list)
    costs: dict = field(default_factory=dict)
    walkable: set = field(default_factory=set)
    uncertain: set = field(default_factory=set)
    wall_ratios: dict = field(default_factory=dict)
    floor_extents: dict = field(default_factory=dict)
    stairs_ratios: dict = field(default_factory=dict)
    wall_dead_end: bool = False
    has_surfaces: bool = False
    surfaces: dict | None = None


def analyze(detections: list[Detection], s: Settings, surfaces=None) -> Analysis:
    polys = corridor_polygons(s)
    costs = {c: 0.0 for c in ORDER}
    tracks = []
    for det in detections:
        prox, band = relative_proximity(det, s)
        tracks.append(Track(
            detection=det, proximity=prox, band=band,
            direction=direction_for_anchor((det.x1 + det.x2) / 2, det.y2, s),
            path_overlap=obstruction(det, full_corridor(s)),
        ))
        for c in ORDER:
            ov = obstruction(det, polys[c])
            contribution = min(1.0, ov * (0.35 + 0.65 * prox) * det.confidence)
            costs[c] = 1.0 - (1.0 - costs[c]) * (1.0 - contribution)

    wall = {c: 0.0 for c in ORDER}
    floor = {c: 0.0 for c in ORDER}
    stairs = {c: 0.0 for c in ORDER}
    if surfaces is not None:
        for c in ORDER:
            surface_cost = min(1.0, surfaces["non_walkable"][c]
                               + s.surface_cost_road_weight * surfaces["road"][c]
                               + s.surface_cost_unknown_weight * surfaces["unknown"][c])
            costs[c] = 1.0 - (1.0 - costs[c]) * (1.0 - surface_cost)
            wall[c] = surfaces["wall"][c]
            stairs[c] = surfaces["stairs"][c]
            floor[c] = surfaces["floor_extent"][c]

    dead_end = (floor[CENTRE] <= s.freespace_dead_end_max
                and floor[LEFT] < s.freespace_side_open_min
                and floor[RIGHT] < s.freespace_side_open_min
                and wall[CENTRE] >= s.wall_centre_ratio_threshold)
    walkable = set() if surfaces is None else {
        c for c in ORDER if surfaces["walkable"][c] >= MIN_WALKABLE_RATIO
    }
    uncertain = {
        c for c in ORDER
        if surfaces is None or (surfaces["walkable"][c] < MIN_WALKABLE_RATIO
                                and surfaces["non_walkable"][c] < MIN_BLOCKING_SURFACE_RATIO)
    }
    return Analysis(tracks, costs, walkable, uncertain, wall, floor, stairs,
                    dead_end, surfaces is not None, surfaces)


def score_tracks(analysis: Analysis, s: Settings, sensitivity: float = 0.5):
    factor = 0.75 + 0.5 * sensitivity
    for t in analysis.tracks:
        severity = CLASS_SEVERITIES.get(t.detection.label, 0.5)
        t.score = min(1.0, max(0.0, (
            s.risk_weight_path_overlap * t.path_overlap
            + s.risk_weight_proximity * t.proximity
            + s.risk_weight_class_severity * severity
            + s.risk_weight_confidence * t.detection.confidence
        ) * factor))
        t.level = ("HIGH" if t.score >= s.risk_high_enter else
                   "WARN" if t.score >= s.risk_warn_enter else
                   "WATCH" if t.score >= s.risk_watch_enter else "CLEAR")
    return analysis.tracks


def select_action(analysis: Analysis, s: Settings):
    a = analysis
    if a.wall_dead_end:
        return "STOP", "HIGH", "WALL_OR_DEAD_END_AHEAD"
    if a.stairs_ratios[CENTRE] >= s.stairs_centre_ratio_threshold:
        return "STOP", "HIGH", "STAIRS_OR_LEVEL_CHANGE_AHEAD"

    def no_floor(c):
        return a.has_surfaces and a.floor_extents[c] <= s.freespace_blocked_max

    centre_tracks = [t for t in a.tracks if t.direction == CENTRE and t.path_overlap >= 0.25]
    centre_blocked = (a.costs[CENTRE] >= s.risk_centre_block_threshold or no_floor(CENTRE)
                      or any(t.level in ("WARN", "HIGH") and t.band in ("NEAR", "IMMEDIATE")
                             for t in centre_tracks))
    side_gate = (s.risk_side_block_threshold if a.has_surfaces
                 else s.risk_centre_block_threshold)
    left_blocked = a.costs[LEFT] >= side_gate or no_floor(LEFT)
    right_blocked = a.costs[RIGHT] >= side_gate or no_floor(RIGHT)
    immediate = any(t.band == "IMMEDIATE" for t in centre_tracks)

    if centre_blocked and left_blocked and right_blocked:
        return "STOP", ("CRITICAL" if immediate else "HIGH"), "ALL_CORRIDORS_BLOCKED"
    if centre_blocked:
        left, right = a.costs[LEFT], a.costs[RIGHT]
        preferred = (LEFT if left + s.decision_margin < right else
                     RIGHT if right + s.decision_margin < left else None)
        if (preferred and preferred in a.walkable and preferred not in a.uncertain
                and a.floor_extents[preferred] >= s.direction_min_free_extent
                and a.wall_ratios[preferred] < s.wall_side_ratio_threshold):
            return f"MOVE_{preferred}", "HIGH", "CENTRE_BLOCKED_CLEARER_SIDE"
        return "PAUSE_UNCLEAR", "WARN", "CENTRE_BLOCKED_DIRECTION_UNCLEAR"
    if CENTRE in a.uncertain:
        return "PAUSE_UNCLEAR", "WARN", "CENTRE_SURFACE_UNCERTAIN"
    top = max(a.tracks, key=lambda t: t.score, default=None)
    if top and top.level in ("WARN", "HIGH"):
        return "CAUTION", "WARN", "OBSTACLE_NEARBY"
    return "CLEAR", (top.level if top else "CLEAR"), ("LOW_RISK_MONITORED" if top else "PATH_CLEAR")


# ---------------------------------------------------------------------------
# SessionTracker.kt — only the parts a still-image bench needs: association and
# COASTING, which is the whole reason this bench has a sequence mode.
# ---------------------------------------------------------------------------


class Tracker:
    def __init__(self, iou_threshold=0.20, centre_distance=0.12, max_age=3, coast_frames=3):
        self.iou_threshold = iou_threshold
        self.centre_distance = centre_distance
        self.max_age = max_age
        self.coast_frames = coast_frames
        self.tracks: dict[int, dict] = {}
        self.next_id = 1

    @staticmethod
    def _iou(a: Detection, b: Detection) -> float:
        w = max(0.0, min(a.x2, b.x2) - max(a.x1, b.x1))
        h = max(0.0, min(a.y2, b.y2) - max(a.y1, b.y1))
        inter = w * h
        union = a.area + b.area - inter
        return inter / union if union > 0 else 0.0

    @staticmethod
    def _centre(d: Detection):
        return ((d.x1 + d.x2) / 2, (d.y1 + d.y2) / 2)

    def _distance(self, a: Detection, b: Detection) -> float:
        ca, cb = self._centre(a), self._centre(b)
        return math.hypot(ca[0] - cb[0], ca[1] - cb[1])

    def update(self, detections: list[Detection], frame_id: int):
        for tid in [t for t, v in self.tracks.items()
                    if frame_id - v["last_seen"] > self.max_age]:
            del self.tracks[tid]

        available = set(self.tracks)
        out: list[tuple[Detection, int, int]] = []
        for det in detections:
            best, best_score = None, -1.0
            for tid in available:
                tr = self.tracks[tid]
                if tr["label"] != det.label:
                    continue
                overlap = self._iou(tr["det"], det)
                distance = self._distance(tr["det"], det)
                if overlap < self.iou_threshold and distance > self.centre_distance:
                    continue
                score = overlap + max(0.0, 1 - distance / self.centre_distance) * 0.1
                if score > best_score:
                    best, best_score = tid, score
            if best is None:
                tid = self.next_id
                self.next_id += 1
                self.tracks[tid] = {"label": det.label, "det": det, "last_seen": frame_id}
            else:
                available.discard(best)
                tid = best
                self.tracks[tid]["det"] = det
                self.tracks[tid]["last_seen"] = frame_id
            out.append((det, tid, 0))

        for tid in available:
            tr = self.tracks[tid]
            age = frame_id - tr["last_seen"]
            if not 1 <= age <= self.coast_frames:
                continue
            decay = 1.0 - age / (self.coast_frames + 1)
            faded = Detection(tr["det"].label, tr["det"].confidence * decay,
                              tr["det"].x1, tr["det"].y1, tr["det"].x2, tr["det"].y2)
            out.append((faded, tid, age))
        return out


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

PALETTE = {
    WALKABLE: (40, 200, 90), ROAD: (230, 180, 40),
    NON_WALKABLE: (220, 50, 50), UNKNOWN: (110, 110, 130),
}


def load_labels() -> dict[int, str]:
    raw = json.loads((MODELS / "ade20k_config.json").read_text())
    raw = raw.get("id2label", raw)
    return {int(k): v for k, v in raw.items()}


def render(path: Path, rgb, class_id, kind, analysis, decision, labels, out_dir: Path, s: Settings):
    h, w = rgb.shape[:2]
    seg = Image.fromarray(
        np.take(np.array([PALETTE[k] for k in range(4)], dtype=np.uint8), kind, axis=0)
    ).resize((w, h), Image.NEAREST)
    canvas = Image.blend(Image.fromarray(rgb), seg, 0.55).convert("RGB")
    draw = ImageDraw.Draw(canvas, "RGBA")
    for c, poly in corridor_polygons(s).items():
        pts = [(x * w, y * h) for x, y in poly]
        draw.polygon(pts, outline=(255, 255, 255, 255), width=3)
    for t in analysis.tracks:
        d = t.detection
        draw.rectangle([d.x1 * w, d.y1 * h, d.x2 * w, d.y2 * h], outline=(255, 230, 0), width=4)
        draw.text((d.x1 * w + 4, d.y1 * h + 4), f"{d.label} {d.confidence:.2f}", fill=(255, 255, 0))
    draw.rectangle([0, 0, w, 74], fill=(0, 0, 0, 190))
    draw.text((10, 8), f"{decision[0]} / {decision[1]} / {decision[2]}", fill=(255, 255, 255))
    draw.text((10, 28), "cost L/C/R " + "/".join(f"{analysis.costs[c]:.2f}" for c in ORDER)
              + "   floor " + "/".join(f"{analysis.floor_extents[c]:.2f}" for c in ORDER),
              fill=(255, 255, 255))
    top = sorted(((int((class_id == i).sum()), i) for i in np.unique(class_id)), reverse=True)[:6]
    draw.text((10, 48), "seg: " + ", ".join(
        f"{labels.get(i, i)}({KIND_NAME[kind_for(labels.get(i, ''))][:3]}) {n * 100 // class_id.size}%"
        for n, i in top), fill=(200, 255, 200))
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{path.stem}_eval.png"
    canvas.save(target)
    return target


def centre_crop(rgb: np.ndarray, aspect: float | None) -> np.ndarray:
    """Emulate the analysis frame's aspect (CameraX gives ~3:4 portrait)."""
    if not aspect:
        return rgb
    h, w = rgb.shape[:2]
    if abs(w / h - aspect) < 1e-3:
        return rgb
    if w / h > aspect:
        cw = max(1, min(w, round(h * aspect)))
        x = (w - cw) // 2
        return rgb[:, x:x + cw]
    ch = max(1, min(h, round(w / aspect)))
    y = (h - ch) // 2
    return rgb[y:y + ch, :]


def evaluate(path: Path, detector: Detector, segmenter: Segmenter, labels, s: Settings,
             min_span_ratio: float = MIN_COLUMN_SPAN_RATIO, crop_aspect: float | None = None,
             fuse: bool = True, tracker=None, frame_id: int = 1):
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.uint8)
    rgb = centre_crop(rgb, crop_aspect)
    risk_view, everything = detector.detect(rgb)
    if tracker is not None:
        tracked = tracker.update(risk_view, frame_id)
        risk_view = [d for d, _tid, _age in tracked]
    class_id, kind, hazard, wall = segmenter.segment(rgb)
    lb = Letterbox(rgb.shape[1], rgb.shape[0], segmenter.w, segmenter.h)
    ev = surface_evidence(kind, hazard, wall, lb, s, min_span_ratio,
                          occluders=risk_view if fuse else [])
    analysis = analyze(risk_view, s, ev)
    score_tracks(analysis, s)
    decision = select_action(analysis, s)
    return rgb, class_id, kind, everything, analysis, decision, ev


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", type=Path, help="image file or directory")
    ap.add_argument("--render", type=Path, help="write annotated PNGs here")
    ap.add_argument("--json", type=Path, help="write the full evidence as JSON")
    ap.add_argument("--detector", type=Path, default=MODELS / "yolo11n_fp32_nchw.onnx")
    ap.add_argument("--segmenter", type=Path, default=MODELS / "segformer_float.onnx")
    ap.add_argument("--conf", type=float, default=None,
                    help="override riskConfidenceThreshold (the safety gate)")
    ap.add_argument("--crop-aspect", type=float, default=None,
                    help="centre-crop to this width/height first (0.75 = the 3:4 analysis frame)")
    ap.add_argument("--no-fuse", action="store_true",
                    help="disable the detection-vetoes-floor fusion")
    ap.add_argument("--sequence", action="store_true",
                    help="treat the inputs as consecutive frames and run a tracker over them")
    ap.add_argument("--coast-frames", type=int, default=0,
                    help="sequence mode: frames an unmatched track keeps reporting (0 = Python)")
    args = ap.parse_args()

    files = sorted(p for p in ([args.target] if args.target.is_file()
                               else args.target.rglob("*"))
                   if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not files:
        print(f"no images under {args.target}", file=sys.stderr)
        return 1

    s = Settings() if args.conf is None else Settings(risk_confidence_threshold=args.conf)
    labels = load_labels()
    detector = Detector(args.detector, s)
    segmenter = Segmenter(args.segmenter, labels)
    print(f"detector {args.detector.name} {detector.w}x{detector.h} "
          f"{'NHWC' if detector.nhwc else 'NCHW'}, {len(detector.names)} classes")
    print(f"segmenter {args.segmenter.name} {segmenter.w}x{segmenter.h}, {len(labels)} classes\n")

    tracker = Tracker(coast_frames=args.coast_frames) if args.sequence else None
    report = []
    for index, path in enumerate(files):
        rgb, class_id, kind, everything, analysis, decision, ev = evaluate(
            path, detector, segmenter, labels, s,
            crop_aspect=args.crop_aspect, fuse=not args.no_fuse, tracker=tracker,
            frame_id=index + 1)
        print("=" * 78)
        print(path.name, f"{rgb.shape[1]}x{rgb.shape[0]}")
        print(f"  DECISION   {decision[0]} / {decision[1]} / {decision[2]}")
        print("  cost       " + "  ".join(f"{c} {analysis.costs[c]:.3f}" for c in ORDER))
        print("  walkable%  " + "  ".join(f"{c} {ev['walkable'][c]:.3f}" for c in ORDER))
        print("  nonwalk%   " + "  ".join(f"{c} {ev['non_walkable'][c]:.3f}" for c in ORDER))
        print("  unknown%   " + "  ".join(f"{c} {ev['unknown'][c]:.3f}" for c in ORDER))
        print("  floorExt   " + "  ".join(f"{c} {ev['floor_extent'][c]:.3f}" for c in ORDER))
        counts = sorted(((int((class_id == i).sum()), i) for i in np.unique(class_id)), reverse=True)
        print("  seg top    " + ", ".join(
            f"{labels.get(i, i)}[{KIND_NAME[kind_for(labels.get(i, ''))]}] "
            f"{n * 100 / class_id.size:.0f}%" for n, i in counts[:6]))
        print(f"  detections ({len(everything)} native, {len(analysis.tracks)} in risk view)")
        for d in sorted(everything, key=lambda d: -d.confidence)[:10]:
            print(f"    {d.label:16} {d.confidence:.2f}  "
                  f"[{d.x1:.2f},{d.y1:.2f},{d.x2:.2f},{d.y2:.2f}]")
        for t in analysis.tracks:
            print(f"    -> {t.detection.label:14} score {t.score:.3f} {t.level:5} "
                  f"{t.band:9} dir {t.direction:7} overlap {t.path_overlap:.3f}")
        if args.render:
            print(f"  rendered   {render(path, rgb, class_id, kind, analysis, decision, labels, args.render, s)}")
        report.append({
            "file": path.name, "decision": decision,
            "costs": analysis.costs, "evidence": ev,
            "detections": [{"label": d.label, "confidence": d.confidence,
                            "bbox": [d.x1, d.y1, d.x2, d.y2]} for d in everything],
        })

    if args.json:
        args.json.write_text(json.dumps(report, indent=2))
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
