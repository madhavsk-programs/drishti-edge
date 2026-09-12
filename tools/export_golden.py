"""MIRROR of entire-old-codebase/backend/scripts/export_golden.py.

That directory is gitignored (it is a local reference copy of the previous
implementation), so this copy exists to keep the generator of the committed
golden vectors in version control. It imports `app.*` and therefore only RUNS
from inside the backend tree:

    cd entire-old-codebase/backend
    PYTHONPATH=. ./.venv-vectors/Scripts/python.exe scripts/export_golden.py

Keep the two copies in step. The vectors it writes
(apps/android/app/src/test/resources/golden/) are the cross-language contract
the Kotlin port is tested against.
"""

"""Export golden input/output vectors from the production perception modules.

These files are the cross-language contract: the Kotlin port in
``apps/android/app`` is tested against them so that "same behaviour on the
phone" is a measured claim rather than an assertion (BUILD_PLAN.md task A0,
ARCHITECTURE.md §22 R3).

It imports the *production* modules directly. It must not go through the test
conftest, which constructs the whole FastAPI app and drags in dependencies this
environment deliberately does not have.

    python scripts/export_golden.py

Writes to apps/android/app/src/test/resources/golden/.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from typing import Any

from app.config import Settings
from app.perception.detector import (
    CANONICAL_LABELS,
    DetectionCandidate,
    RawDetection,
    canonicalize_detections,
)
from app.perception.tracking import SessionTracker
from app.risk.rules import ProposedDecision, select_action
from app.risk.scoring import RiskAssessment, risk_level_for_score, score_tracks
from app.risk.state_machine import AlertStateMachine
from app.schemas.walk import (
    ApproachState,
    CorridorChoice,
    CorridorCosts,
    Direction,
    GuidanceAction,
    ProximityBand,
    RiskLevel,
)
from app.perception.tracking import TrackedDetection
from app.spatial.corridor import (
    CorridorAnalysis,
    SpatialTrack,
    bbox_path_overlap,
    corridor_polygons,
    direction_for_anchor,
)
from app.spatial.proximity import (
    RelativeProximity,
    classify_approach,
    estimate_relative_proximity,
)


REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "apps" / "android" / "app" / "src" / "test" / "resources" / "golden"

SETTINGS = Settings()
EPOCH = datetime(2026, 9, 12, 10, 0, 0, tzinfo=timezone.utc)


def det(label: str, conf: float, x1: float, y1: float, x2: float, y2: float) -> dict[str, Any]:
    return {"label": label, "confidence": conf, "x1": x1, "y1": y1, "x2": x2, "y2": y2}


def candidate(d: dict[str, Any]) -> DetectionCandidate:
    return DetectionCandidate(
        label=d["label"],
        confidence=d["confidence"],
        x1=d["x1"],
        y1=d["y1"],
        x2=d["x2"],
        y2=d["y2"],
    )


def dump_candidate(c: DetectionCandidate) -> dict[str, Any]:
    return {
        "label": c.label,
        "confidence": c.confidence,
        "x1": c.x1,
        "y1": c.y1,
        "x2": c.x2,
        "y2": c.y2,
    }


def write(name: str, cases: list[dict[str, Any]]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    path.write_text(json.dumps({"cases": cases}, indent=2), encoding="utf-8")
    print(f"{name}: {len(cases)} cases")


# --------------------------------------------------------------------------
# canonicalization.json
# --------------------------------------------------------------------------


def export_canonicalization() -> None:
    cases: list[dict[str, Any]] = []

    def case(name: str, raw: list[dict[str, Any]], **kwargs: Any) -> None:
        width = kwargs.pop("width", 640)
        height = kwargs.pop("height", 480)
        threshold = kwargs.pop("confidence_threshold", SETTINGS.detector_confidence_threshold)
        allowed = kwargs.pop("allowed", "CANONICAL")
        apply_aliases = kwargs.pop("apply_aliases", True)
        assert not kwargs, kwargs
        result = canonicalize_detections(
            [RawDetection(**item) for item in raw],
            width=width,
            height=height,
            confidence_threshold=threshold,
            allowed_labels=CANONICAL_LABELS if allowed == "CANONICAL" else None,
            apply_aliases=apply_aliases,
        )
        cases.append(
            {
                "name": name,
                "input": {
                    "width": width,
                    "height": height,
                    "confidence_threshold": threshold,
                    "allowed_labels": allowed,
                    "apply_aliases": apply_aliases,
                    "detections": raw,
                },
                "expected": [dump_candidate(item) for item in result],
            }
        )

    case("plain_person", [det("person", 0.9, 64, 48, 320, 480)])
    case("alias_backpack_to_bag", [det("backpack", 0.8, 10, 10, 200, 300)])
    case("alias_handbag_to_bag", [det("handbag", 0.8, 10, 10, 200, 300)])
    case("alias_dining_table_to_desk", [det("dining table", 0.7, 0, 0, 640, 480)])
    case("alias_table_to_desk", [det("table", 0.7, 0, 0, 640, 480)])
    case(
        "allowlist_rejects_unlisted",
        [det("kite", 0.99, 10, 10, 100, 100), det("chair", 0.9, 10, 10, 100, 100)],
    )
    case(
        "full_view_keeps_everything_unaliased",
        [
            det("kite", 0.99, 10, 10, 100, 100),
            det("backpack", 0.8, 10, 10, 200, 300),
            det("dining table", 0.7, 0, 0, 640, 480),
        ],
        allowed="ALL",
        apply_aliases=False,
    )
    case(
        "sub_threshold_rejected",
        [det("person", 0.10, 10, 10, 100, 100), det("person", 0.95, 10, 10, 100, 100)],
        confidence_threshold=0.5,
    )
    case("degenerate_box_zero_width_rejected", [det("person", 0.9, 100, 10, 100, 200)])
    case("degenerate_box_inverted_rejected", [det("person", 0.9, 300, 200, 100, 10)])
    case(
        "clamping_out_of_frame",
        [det("person", 0.9, -100, -50, 900, 700)],
    )
    case("uppercase_label_lowered", [det("PERSON", 0.9, 10, 10, 100, 100)])
    case("empty_input", [])
    case(
        "mixed_batch_order_preserved",
        [
            det("car", 0.85, 400, 200, 600, 400),
            det("kite", 0.99, 10, 10, 100, 100),
            det("chair", 0.6, 50, 100, 150, 300),
            det("person", 0.3, 200, 100, 260, 400),
        ],
        confidence_threshold=0.5,
    )
    case(
        "door_is_canonical_though_not_coco",
        [det("door", 0.9, 100, 50, 300, 470)],
    )

    write("canonicalization.json", cases)


# --------------------------------------------------------------------------
# tracking.json
# --------------------------------------------------------------------------


def export_tracking() -> None:
    cases: list[dict[str, Any]] = []

    def case(name: str, frames: list[dict[str, Any]], **overrides: Any) -> None:
        iou = overrides.pop("iou_threshold", SETTINGS.track_iou_threshold)
        centre = overrides.pop(
            "centre_distance_threshold", SETTINGS.track_centre_distance_threshold
        )
        max_age = overrides.pop("max_age_frames", SETTINGS.track_max_age_frames)
        assert not overrides, overrides
        tracker = SessionTracker(
            iou_threshold=iou,
            centre_distance_threshold=centre,
            max_age_frames=max_age,
        )
        expected: list[list[dict[str, Any]]] = []
        for frame in frames:
            tracked = tracker.update(
                [candidate(item) for item in frame["detections"]],
                frame_id=frame["frame_id"],
                captured_at=EPOCH + timedelta(milliseconds=100 * frame["frame_id"]),
            )
            expected.append(
                [
                    {
                        "track_id": item.track_id,
                        "label": item.detection.label,
                        "approach_rate": item.approach_rate,
                        "area_change": item.area_change,
                        "motion_dx": item.motion_dx,
                        "motion_dy": item.motion_dy,
                    }
                    for item in tracked
                ]
            )
        cases.append(
            {
                "name": name,
                "settings_overrides": {
                    "track_iou_threshold": iou,
                    "track_centre_distance_threshold": centre,
                    "track_max_age_frames": max_age,
                },
                "input": {"frames": frames},
                "expected": expected,
            }
        )

    case(
        "id_persists_across_frames",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
            {"frame_id": 2, "detections": [det("person", 0.9, 0.41, 0.3, 0.61, 0.9)]},
            {"frame_id": 3, "detections": [det("person", 0.9, 0.42, 0.3, 0.62, 0.9)]},
        ],
    )
    case(
        "new_id_when_label_differs",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
            {"frame_id": 2, "detections": [det("chair", 0.9, 0.4, 0.3, 0.6, 0.9)]},
        ],
    )
    case(
        "expiry_after_max_age",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
            {"frame_id": 2, "detections": []},
            {"frame_id": 3, "detections": []},
            {"frame_id": 4, "detections": []},
            {"frame_id": 9, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
        ],
    )
    case(
        "survives_gap_within_max_age",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
            {"frame_id": 2, "detections": []},
            {"frame_id": 3, "detections": [det("person", 0.9, 0.4, 0.3, 0.6, 0.9)]},
        ],
    )
    case(
        "approaching_area_growth",
        [
            {"frame_id": 1, "detections": [det("car", 0.9, 0.40, 0.40, 0.50, 0.50)]},
            {"frame_id": 2, "detections": [det("car", 0.9, 0.38, 0.38, 0.52, 0.52)]},
        ],
    )
    case(
        "receding_area_shrink",
        [
            {"frame_id": 1, "detections": [det("car", 0.9, 0.35, 0.35, 0.55, 0.55)]},
            {"frame_id": 2, "detections": [det("car", 0.9, 0.40, 0.40, 0.50, 0.50)]},
        ],
    )
    case(
        "motion_vector_lateral",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.10, 0.40, 0.20, 0.80)]},
            {"frame_id": 2, "detections": [det("person", 0.9, 0.14, 0.42, 0.24, 0.82)]},
        ],
    )
    case(
        "two_objects_keep_separate_ids",
        [
            {
                "frame_id": 1,
                "detections": [
                    det("person", 0.9, 0.05, 0.40, 0.20, 0.90),
                    det("person", 0.9, 0.70, 0.40, 0.85, 0.90),
                ],
            },
            {
                "frame_id": 2,
                "detections": [
                    det("person", 0.9, 0.06, 0.40, 0.21, 0.90),
                    det("person", 0.9, 0.71, 0.40, 0.86, 0.90),
                ],
            },
        ],
    )
    case(
        "far_jump_creates_new_track",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.02, 0.40, 0.10, 0.60)]},
            {"frame_id": 2, "detections": [det("person", 0.9, 0.80, 0.40, 0.88, 0.60)]},
        ],
    )
    case(
        "centre_distance_association_without_iou",
        [
            {"frame_id": 1, "detections": [det("person", 0.9, 0.40, 0.40, 0.46, 0.50)]},
            {"frame_id": 2, "detections": [det("person", 0.9, 0.47, 0.40, 0.53, 0.50)]},
        ],
    )

    write("tracking.json", cases)


# --------------------------------------------------------------------------
# spatial.json
# --------------------------------------------------------------------------


def export_spatial() -> None:
    cases: list[dict[str, Any]] = []

    def direction_case(name: str, x: float, y: float) -> None:
        cases.append(
            {
                "name": name,
                "kind": "direction_for_anchor",
                "input": {"x": x, "y": y},
                "expected": {"direction": str(direction_for_anchor(x, y, SETTINGS))},
            }
        )

    def overlap_case(name: str, d: dict[str, Any]) -> None:
        cases.append(
            {
                "name": name,
                "kind": "bbox_path_overlap",
                "input": {"detection": d},
                "expected": {"path_overlap": bbox_path_overlap(candidate(d), SETTINGS)},
            }
        )

    def proximity_case(name: str, d: dict[str, Any]) -> None:
        result = estimate_relative_proximity(candidate(d), SETTINGS)
        cases.append(
            {
                "name": name,
                "kind": "proximity",
                "input": {"detection": d},
                "expected": {"score": result.score, "band": str(result.band)},
            }
        )

    direction_case("above_horizon_unknown", 0.5, 0.10)
    direction_case("below_frame_unknown", 0.5, 1.20)
    direction_case("centre_at_bottom", 0.50, 1.00)
    direction_case("left_at_bottom", 0.20, 1.00)
    direction_case("right_at_bottom", 0.80, 1.00)
    direction_case("outside_corridor_left_unknown", 0.02, 1.00)
    direction_case("outside_corridor_right_unknown", 0.98, 1.00)
    direction_case("at_horizon_centre", 0.50, 0.38)
    direction_case("at_horizon_left_edge", 0.43, 0.38)
    direction_case("mid_depth_centre", 0.50, 0.70)
    direction_case("mid_depth_left", 0.35, 0.70)
    direction_case("mid_depth_right", 0.65, 0.70)

    overlap_case("fully_inside_corridor", det("person", 0.9, 0.45, 0.80, 0.55, 0.99))
    overlap_case("fully_outside_corridor", det("person", 0.9, 0.00, 0.05, 0.10, 0.15))
    overlap_case("straddles_corridor_edge", det("person", 0.9, 0.02, 0.85, 0.25, 0.99))
    overlap_case("whole_frame", det("person", 0.9, 0.0, 0.0, 1.0, 1.0))
    overlap_case("tiny_box_in_centre", det("person", 0.9, 0.49, 0.95, 0.51, 0.99))

    proximity_case("far_small_high_box", det("person", 0.9, 0.48, 0.40, 0.52, 0.46))
    proximity_case("medium_mid_box", det("person", 0.9, 0.40, 0.45, 0.55, 0.62))
    proximity_case("near_large_low_box", det("person", 0.9, 0.30, 0.40, 0.70, 0.86))
    proximity_case("immediate_fills_frame", det("person", 0.9, 0.05, 0.10, 0.95, 1.00))
    proximity_case("zero_area", det("person", 0.9, 0.50, 0.50, 0.50, 0.50))

    polygons = corridor_polygons(SETTINGS)
    cases.append(
        {
            "name": "corridor_polygons_default_settings",
            "kind": "corridor_polygons",
            "input": {},
            "expected": {
                str(choice): [[x, y] for x, y in polygon]
                for choice, polygon in polygons.items()
            },
        }
    )

    write("spatial.json", cases)


# --------------------------------------------------------------------------
# risk.json
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class AssessmentSpec:
    label: str
    confidence: float
    path_overlap: float
    proximity_score: float
    proximity_band: ProximityBand
    direction: Direction
    approach_rate: float | None
    area_change: float | None
    track_id: int


def build_assessment(spec: AssessmentSpec) -> RiskAssessment:
    """Assemble a RiskAssessment without running detection or segmentation.

    select_action reads only these fields, so pinning them directly keeps the
    decision cascade's vectors independent of the corridor geometry vectors.
    """
    detection = DetectionCandidate(
        label=spec.label,
        confidence=spec.confidence,
        x1=0.4,
        y1=0.4,
        x2=0.6,
        y2=0.9,
    )
    tracked = TrackedDetection(
        detection=detection,
        track_id=spec.track_id,
        approach_rate=spec.approach_rate,
        area_change=spec.area_change,
        motion_dx=None,
        motion_dy=None,
    )
    spatial = SpatialTrack(
        tracked=tracked,
        proximity=RelativeProximity(
            score=spec.proximity_score, band=spec.proximity_band
        ),
        direction=spec.direction,
        path_overlap=spec.path_overlap,
    )
    class_severity = SETTINGS.risk_class_severities.get(spec.label, 0.5)
    score = min(
        1.0,
        max(
            0.0,
            SETTINGS.risk_weight_path_overlap * spec.path_overlap
            + SETTINGS.risk_weight_proximity * spec.proximity_score
            + SETTINGS.risk_weight_approach * min(1.0, max(0.0, spec.approach_rate or 0.0))
            + SETTINGS.risk_weight_class_severity * class_severity
            + SETTINGS.risk_weight_confidence * spec.confidence,
        ),
    )
    return RiskAssessment(
        spatial=spatial,
        score=score,
        level=risk_level_for_score(score, SETTINGS),
        class_severity=class_severity,
        approach_state=classify_approach(
            spec.area_change, threshold=SETTINGS.approach_change_threshold
        ),
    )


def dump_assessment_spec(spec: AssessmentSpec) -> dict[str, Any]:
    return {
        "label": spec.label,
        "confidence": spec.confidence,
        "path_overlap": spec.path_overlap,
        "proximity_score": spec.proximity_score,
        "proximity_band": str(spec.proximity_band),
        "direction": str(spec.direction),
        "approach_rate": spec.approach_rate,
        "area_change": spec.area_change,
        "track_id": spec.track_id,
    }


def build_corridor(
    assessments: list[RiskAssessment],
    *,
    left: float,
    centre: float,
    right: float,
    walkable: set[CorridorChoice],
    uncertain: set[CorridorChoice],
    wall: tuple[float, float, float] = (0.0, 0.0, 0.0),
    floor: tuple[float, float, float] = (1.0, 1.0, 1.0),
    stairs: tuple[float, float, float] = (0.0, 0.0, 0.0),
    wall_dead_end: bool = False,
) -> CorridorAnalysis:
    return CorridorAnalysis(
        tracks=[assessment.spatial for assessment in assessments],
        costs=CorridorCosts(left_cost=left, centre_cost=centre, right_cost=right),
        preferred=CorridorChoice.NONE,
        walkable_choices=frozenset(walkable),
        uncertain_choices=frozenset(uncertain),
        safe_polygons=[],
        blocked_polygons=[],
        uncertain_polygons=[],
        wall_ratios=CorridorCosts(
            left_cost=wall[0], centre_cost=wall[1], right_cost=wall[2]
        ),
        floor_extents=CorridorCosts(
            left_cost=floor[0], centre_cost=floor[1], right_cost=floor[2]
        ),
        stairs_ratios=CorridorCosts(
            left_cost=stairs[0], centre_cost=stairs[1], right_cost=stairs[2]
        ),
        wall_dead_end=wall_dead_end,
    )


ALL_CHOICES = {CorridorChoice.LEFT, CorridorChoice.CENTRE, CorridorChoice.RIGHT}


def export_risk() -> None:
    cases: list[dict[str, Any]] = []

    def scoring_case(name: str, spec: AssessmentSpec, sensitivity: float = 0.5) -> None:
        assessment = build_assessment(spec)
        scored = score_tracks([assessment.spatial], SETTINGS, risk_sensitivity=sensitivity)[0]
        cases.append(
            {
                "name": name,
                "kind": "score_tracks",
                "input": {
                    "assessment": dump_assessment_spec(spec),
                    "risk_sensitivity": sensitivity,
                },
                "expected": {
                    "score": scored.score,
                    "level": str(scored.level),
                    "class_severity": scored.class_severity,
                    "approach_state": str(scored.approach_state),
                },
            }
        )

    def action_case(
        name: str,
        specs: list[AssessmentSpec],
        *,
        left: float,
        centre: float,
        right: float,
        walkable: set[CorridorChoice] = ALL_CHOICES,
        uncertain: set[CorridorChoice] = frozenset(),
        wall: tuple[float, float, float] = (0.0, 0.0, 0.0),
        floor: tuple[float, float, float] = (1.0, 1.0, 1.0),
        stairs: tuple[float, float, float] = (0.0, 0.0, 0.0),
        wall_dead_end: bool = False,
    ) -> None:
        assessments = [build_assessment(spec) for spec in specs]
        corridor = build_corridor(
            assessments,
            left=left,
            centre=centre,
            right=right,
            walkable=set(walkable),
            uncertain=set(uncertain),
            wall=wall,
            floor=floor,
            stairs=stairs,
            wall_dead_end=wall_dead_end,
        )
        decision = select_action(assessments, corridor, SETTINGS)
        cases.append(
            {
                "name": name,
                "kind": "select_action",
                "input": {
                    "assessments": [dump_assessment_spec(spec) for spec in specs],
                    "corridor": {
                        "left_cost": left,
                        "centre_cost": centre,
                        "right_cost": right,
                        "walkable_choices": sorted(str(c) for c in walkable),
                        "uncertain_choices": sorted(str(c) for c in uncertain),
                        "wall_ratios": list(wall),
                        "floor_extents": list(floor),
                        "stairs_ratios": list(stairs),
                        "wall_dead_end": wall_dead_end,
                    },
                },
                "expected": {
                    "action": str(decision.action),
                    "level": str(decision.level),
                    "reason_code": decision.reason_code,
                    "preferred_corridor": str(decision.preferred_corridor),
                    "evidence_score": decision.evidence_score,
                    "critical_track_ids": sorted(decision.critical_track_ids),
                },
            }
        )

    person_far = AssessmentSpec(
        label="person",
        confidence=0.8,
        path_overlap=0.10,
        proximity_score=0.20,
        proximity_band=ProximityBand.FAR,
        direction=Direction.LEFT,
        approach_rate=None,
        area_change=None,
        track_id=1,
    )
    chair_centre_near = AssessmentSpec(
        label="chair",
        confidence=0.9,
        path_overlap=0.80,
        proximity_score=0.70,
        proximity_band=ProximityBand.NEAR,
        direction=Direction.CENTRE,
        approach_rate=0.10,
        area_change=0.10,
        track_id=2,
    )
    car_approaching = AssessmentSpec(
        label="car",
        confidence=0.95,
        path_overlap=0.90,
        proximity_score=0.85,
        proximity_band=ProximityBand.IMMEDIATE,
        direction=Direction.CENTRE,
        approach_rate=0.30,
        area_change=0.30,
        track_id=3,
    )
    person_centre_immediate = AssessmentSpec(
        label="person",
        confidence=0.9,
        path_overlap=0.90,
        proximity_score=0.90,
        proximity_band=ProximityBand.IMMEDIATE,
        direction=Direction.CENTRE,
        approach_rate=0.02,
        area_change=0.02,
        track_id=4,
    )

    scoring_case("low_risk_person_far", person_far)
    scoring_case("chair_centre_near", chair_centre_near)
    scoring_case("car_approaching_high", car_approaching)
    scoring_case("sensitivity_zero_damps", car_approaching, sensitivity=0.0)
    scoring_case("sensitivity_one_amplifies", chair_centre_near, sensitivity=1.0)
    scoring_case(
        "unknown_label_default_severity",
        AssessmentSpec(
            label="kite",
            confidence=0.9,
            path_overlap=0.5,
            proximity_score=0.5,
            proximity_band=ProximityBand.MEDIUM,
            direction=Direction.CENTRE,
            approach_rate=0.0,
            area_change=0.0,
            track_id=5,
        ),
    )
    scoring_case(
        "approach_state_unknown_when_no_history",
        AssessmentSpec(
            label="person",
            confidence=0.7,
            path_overlap=0.4,
            proximity_score=0.4,
            proximity_band=ProximityBand.MEDIUM,
            direction=Direction.CENTRE,
            approach_rate=None,
            area_change=None,
            track_id=6,
        ),
    )
    scoring_case(
        "approach_state_receding",
        AssessmentSpec(
            label="person",
            confidence=0.7,
            path_overlap=0.4,
            proximity_score=0.4,
            proximity_band=ProximityBand.MEDIUM,
            direction=Direction.CENTRE,
            approach_rate=0.0,
            area_change=-0.20,
            track_id=7,
        ),
    )

    action_case("path_clear_no_detections", [], left=0.0, centre=0.0, right=0.0)
    action_case("low_risk_monitored", [person_far], left=0.05, centre=0.05, right=0.05)
    action_case(
        "critical_approaching_vehicle_centre",
        [car_approaching],
        left=0.1,
        centre=0.9,
        right=0.1,
    )
    action_case(
        "wall_dead_end_stops",
        [person_far],
        left=0.1,
        centre=0.1,
        right=0.1,
        wall=(0.1, 0.6, 0.1),
        floor=(0.1, 0.05, 0.1),
        wall_dead_end=True,
    )
    action_case(
        "stairs_centre_stops",
        [person_far],
        left=0.1,
        centre=0.1,
        right=0.1,
        stairs=(0.0, 0.20, 0.0),
    )
    action_case(
        "all_corridors_blocked_high",
        [chair_centre_near],
        left=0.9,
        centre=0.9,
        right=0.9,
    )
    action_case(
        "all_corridors_blocked_critical_when_immediate",
        [person_centre_immediate],
        left=0.9,
        centre=0.9,
        right=0.9,
    )
    action_case(
        "centre_blocked_move_left",
        [chair_centre_near],
        left=0.05,
        centre=0.9,
        right=0.35,
    )
    action_case(
        "centre_blocked_move_right",
        [chair_centre_near],
        left=0.35,
        centre=0.9,
        right=0.05,
    )
    # §23.2 test 7: obstacle centre, neither side defensible.
    action_case(
        "centre_blocked_neither_side_defensible_pause",
        [chair_centre_near],
        left=0.30,
        centre=0.9,
        right=0.30,
    )
    action_case(
        "centre_blocked_side_not_walkable_pause",
        [chair_centre_near],
        left=0.05,
        centre=0.9,
        right=0.35,
        walkable={CorridorChoice.CENTRE, CorridorChoice.RIGHT},
    )
    action_case(
        "centre_blocked_side_uncertain_pause",
        [chair_centre_near],
        left=0.05,
        centre=0.9,
        right=0.35,
        uncertain={CorridorChoice.LEFT},
    )
    action_case(
        "centre_blocked_side_floor_too_short_pause",
        [chair_centre_near],
        left=0.05,
        centre=0.9,
        right=0.35,
        floor=(0.20, 1.0, 1.0),
    )
    action_case(
        "centre_blocked_side_wall_ratio_pause",
        [chair_centre_near],
        left=0.05,
        centre=0.9,
        right=0.35,
        wall=(0.50, 0.0, 0.0),
    )
    # §23.2 test 8: no usable evidence.
    action_case(
        "centre_surface_uncertain_pause",
        [person_far],
        left=0.05,
        centre=0.05,
        right=0.05,
        uncertain={CorridorChoice.CENTRE},
    )
    # chair_centre_near scores 0.6375 — below risk_warn_enter, so the cascade
    # falls through to CLEAR. Pins that the WATCH band does not speak.
    action_case(
        "watch_band_below_warn_stays_clear",
        [chair_centre_near],
        left=0.05,
        centre=0.05,
        right=0.05,
    )
    # A high-scoring obstacle that is NOT in the centre corridor: reaches the
    # CAUTION branch without tripping centre_blocked.
    action_case(
        "obstacle_nearby_caution",
        [
            AssessmentSpec(
                label="bicycle",
                confidence=0.95,
                path_overlap=0.95,
                proximity_score=0.85,
                proximity_band=ProximityBand.NEAR,
                direction=Direction.LEFT,
                approach_rate=0.50,
                area_change=0.50,
                track_id=8,
            )
        ],
        left=0.05,
        centre=0.05,
        right=0.05,
    )
    # path_overlap below the 0.25 centre gate keeps a NEAR centre object from
    # blocking the corridor on its own.
    action_case(
        "centre_object_below_overlap_gate_not_blocking",
        [
            AssessmentSpec(
                label="person",
                confidence=0.9,
                path_overlap=0.10,
                proximity_score=0.90,
                proximity_band=ProximityBand.IMMEDIATE,
                direction=Direction.CENTRE,
                approach_rate=0.50,
                area_change=0.50,
                track_id=9,
            )
        ],
        left=0.05,
        centre=0.05,
        right=0.05,
    )

    write("risk.json", cases)


# --------------------------------------------------------------------------
# state_machine.json
# --------------------------------------------------------------------------


def export_state_machine() -> None:
    cases: list[dict[str, Any]] = []

    def proposal(
        action: GuidanceAction,
        level: RiskLevel,
        reason: str,
        corridor: CorridorChoice,
        evidence: float = 0.0,
        critical: tuple[int, ...] = (),
    ) -> dict[str, Any]:
        return {
            "action": str(action),
            "level": str(level),
            "reason_code": reason,
            "preferred_corridor": str(corridor),
            "evidence_score": evidence,
            "critical_track_ids": list(critical),
        }

    def case(name: str, steps: list[dict[str, Any]]) -> None:
        machine = AlertStateMachine(SETTINGS)
        expected: list[dict[str, Any]] = []
        for step in steps:
            p = step["proposal"]
            decision = machine.apply(
                ProposedDecision(
                    action=GuidanceAction(p["action"]),
                    level=RiskLevel(p["level"]),
                    reason_code=p["reason_code"],
                    preferred_corridor=CorridorChoice(p["preferred_corridor"]),
                    evidence_score=p["evidence_score"],
                    critical_track_ids=frozenset(p["critical_track_ids"]),
                ),
                now=EPOCH + timedelta(seconds=step["at_seconds"]),
            )
            expected.append(
                {
                    "action": str(decision.action),
                    "level": str(decision.level),
                    "reason_code": decision.reason_code,
                    "preferred_corridor": str(decision.preferred_corridor),
                    "critical_track_ids": sorted(decision.critical_track_ids),
                    "speak": decision.speak,
                }
            )
        cases.append({"name": name, "input": {"steps": steps}, "expected": expected})

    move_left = proposal(
        GuidanceAction.MOVE_LEFT,
        RiskLevel.HIGH,
        "CENTRE_BLOCKED_CLEARER_SIDE",
        CorridorChoice.LEFT,
        0.7,
    )
    move_right = proposal(
        GuidanceAction.MOVE_RIGHT,
        RiskLevel.HIGH,
        "CENTRE_BLOCKED_CLEARER_SIDE",
        CorridorChoice.RIGHT,
        0.7,
    )
    caution = proposal(
        GuidanceAction.CAUTION, RiskLevel.WARN, "OBSTACLE_NEARBY", CorridorChoice.CENTRE, 0.66
    )
    clear = proposal(
        GuidanceAction.CLEAR, RiskLevel.CLEAR, "PATH_CLEAR", CorridorChoice.CENTRE, 0.0
    )
    clear_low_evidence = proposal(
        GuidanceAction.CLEAR, RiskLevel.CLEAR, "LOW_RISK_MONITORED", CorridorChoice.CENTRE, 0.10
    )
    clear_high_evidence = proposal(
        GuidanceAction.CLEAR, RiskLevel.CLEAR, "LOW_RISK_MONITORED", CorridorChoice.CENTRE, 0.55
    )
    stop_critical = proposal(
        GuidanceAction.STOP,
        RiskLevel.CRITICAL,
        "APPROACHING_VEHICLE_CENTRE",
        CorridorChoice.NONE,
        0.95,
        (3,),
    )
    pause = proposal(
        GuidanceAction.PAUSE_UNCLEAR,
        RiskLevel.WARN,
        "CENTRE_BLOCKED_DIRECTION_UNCLEAR",
        CorridorChoice.NONE,
        0.5,
    )

    case(
        "critical_bypasses_persistence_immediately",
        [{"at_seconds": 0.0, "proposal": stop_critical}],
    )
    case(
        "pause_unclear_commits_immediately",
        [{"at_seconds": 0.0, "proposal": pause}],
    )
    case(
        "alert_persistence_two_frames_before_move",
        [
            {"at_seconds": 0.0, "proposal": caution},
            {"at_seconds": 0.2, "proposal": move_left},
            {"at_seconds": 0.4, "proposal": move_left},
        ],
    )
    case(
        "direction_change_pending_emits_pause",
        [
            {"at_seconds": 0.0, "proposal": move_left},
            {"at_seconds": 0.2, "proposal": move_left},
            {"at_seconds": 0.4, "proposal": move_right},
        ],
    )
    case(
        "alert_persistence_pending_stays_quiet",
        [{"at_seconds": 0.0, "proposal": caution}, {"at_seconds": 0.2, "proposal": move_left}],
    )
    case(
        "clear_requires_three_frames_to_decay",
        [
            {"at_seconds": 0.0, "proposal": caution},
            {"at_seconds": 0.2, "proposal": clear_low_evidence},
            {"at_seconds": 0.4, "proposal": clear_low_evidence},
            {"at_seconds": 0.6, "proposal": clear_low_evidence},
        ],
    )
    case(
        "hysteresis_holds_above_warn_exit",
        [
            {"at_seconds": 0.0, "proposal": caution},
            {"at_seconds": 0.2, "proposal": clear_high_evidence},
            {"at_seconds": 0.4, "proposal": clear_high_evidence},
        ],
    )
    case(
        "cooldown_suppresses_repeat_speech",
        [
            {"at_seconds": 0.0, "proposal": caution},
            {"at_seconds": 0.5, "proposal": caution},
            {"at_seconds": 1.0, "proposal": caution},
            {"at_seconds": 4.5, "proposal": caution},
        ],
    )
    case(
        "level_increase_speaks_within_cooldown",
        [
            {"at_seconds": 0.0, "proposal": caution},
            {"at_seconds": 0.5, "proposal": stop_critical},
        ],
    )
    case(
        "clear_from_clear_stays_silent",
        [{"at_seconds": 0.0, "proposal": clear}, {"at_seconds": 0.2, "proposal": clear}],
    )
    case(
        "critical_then_recovery_sequence",
        [
            {"at_seconds": 0.0, "proposal": stop_critical},
            {"at_seconds": 0.3, "proposal": clear_low_evidence},
            {"at_seconds": 0.6, "proposal": clear_low_evidence},
            {"at_seconds": 0.9, "proposal": clear_low_evidence},
            {"at_seconds": 1.2, "proposal": clear_low_evidence},
        ],
    )

    write("state_machine.json", cases)


def main() -> None:
    export_canonicalization()
    export_tracking()
    export_spatial()
    export_risk()
    export_state_machine()
    print(f"\nwrote to {OUT}")


if __name__ == "__main__":
    main()
