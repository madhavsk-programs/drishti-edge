from __future__ import annotations

from collections import deque
from threading import Lock
from time import time
from uuid import uuid4

from .models import Activity, LivePerson, SafetyEvent, TelemetryEnvelope
from .settings import Settings


OBSTACLE_COOLDOWN_MS = 30_000


class MonitorState:
    """Thread-safe latest-value store; telemetry is observational, never control."""

    def __init__(self, settings: Settings, clock_ms=None):
        self.settings = settings
        self._clock_ms = clock_ms or (lambda: int(time() * 1000))
        self._lock = Lock()
        self._latest: TelemetryEnvelope | None = None
        self._received_at_ms = 0
        self._events: deque[SafetyEvent] = deque(maxlen=50)
        self._last_obstacle_signature: str | None = None
        self._last_obstacle_at_ms = 0

    @property
    def has_received_telemetry(self) -> bool:
        with self._lock:
            return self._latest is not None

    def ingest(self, envelope: TelemetryEnvelope) -> int:
        if envelope.participant_id != self.settings.live_participant_id:
            raise ValueError("unknown participant_id")

        now = self._clock_ms()
        with self._lock:
            previous = self._latest
            if previous is None or previous.session_id != envelope.session_id:
                self._append_event("WALK_STARTED", now, "Started Walk Mode")
            elif (
                envelope.activity == Activity.NOT_WALKING
                and previous.activity != Activity.NOT_WALKING
            ):
                self._append_event("WALK_ENDED", now, "Ended Walk Mode")

            obstacle = envelope.obstacle
            if obstacle is not None:
                signature = ":".join(
                    [
                        obstacle.action,
                        obstacle.reason_code,
                        obstacle.label or "path hazard",
                        obstacle.direction or "UNKNOWN",
                    ]
                )
                if (
                    signature != self._last_obstacle_signature
                    or now - self._last_obstacle_at_ms >= OBSTACLE_COOLDOWN_MS
                ):
                    label = obstacle.label or "Path hazard"
                    direction = (obstacle.direction or "unknown").lower()
                    detail = f"{label} detected {direction}; guidance: {obstacle.action.lower().replace('_', ' ')}"
                    self._append_event("OBSTACLE_DETECTED", now, detail)
                    self._last_obstacle_signature = signature
                    self._last_obstacle_at_ms = now

            self._latest = envelope
            self._received_at_ms = now
        return now

    def live_person(self) -> LivePerson:
        with self._lock:
            latest = self._latest
            received_at = self._received_at_ms
            events = list(self._events)

        location = latest.location if latest else None
        obstacle = latest.obstacle if latest else None
        if latest is None:
            detail = "Waiting for the walking phone"
            activity = Activity.NOT_WALKING
        elif latest.activity == Activity.NOT_WALKING:
            detail = "Walk Mode is not active"
            activity = latest.activity
        elif obstacle is not None:
            label = obstacle.label or "Path hazard"
            detail = f"{label} detected — {obstacle.action.lower().replace('_', ' ')}"
            activity = latest.activity
        else:
            detail = f"Walking at {self.settings.live_area}; path is clear"
            activity = latest.activity

        return LivePerson(
            id=self.settings.live_participant_id,
            name=self.settings.live_name,
            phone_number=self.settings.live_phone,
            emergency_contact_name="Not provided",
            emergency_contact_number="",
            area=self.settings.live_area,
            activity=activity,
            activity_detail=detail,
            last_update_ms=received_at,
            battery_percent=latest.battery_percent if latest else None,
            latitude=location.latitude if location else None,
            longitude=location.longitude if location else None,
            location_accuracy_m=location.accuracy_m if location else None,
            location_observed_at_ms=location.observed_at_ms if location else None,
            events=events,
        )

    def _append_event(self, kind: str, at_ms: int, detail: str) -> None:
        self._events.appendleft(
            SafetyEvent(
                id=f"live-{uuid4().hex}",
                kind=kind,
                at_ms=at_ms,
                detail=detail,
                location_label=self.settings.live_area,
            )
        )

