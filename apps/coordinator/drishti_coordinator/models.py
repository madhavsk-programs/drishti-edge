from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


class Activity(StrEnum):
    WALKING = "WALKING"
    READING_TEXT = "READING_TEXT"
    FINDING_OBJECT = "FINDING_OBJECT"
    ASKING_SCENE = "ASKING_SCENE"
    RESTING = "RESTING"
    NOT_WALKING = "NOT_WALKING"


class LocationFix(BaseModel):
    model_config = ConfigDict(extra="forbid")

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)
    observed_at_ms: int = Field(ge=0)


class ObstacleFact(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: str = Field(min_length=1, max_length=40)
    risk_level: str = Field(min_length=1, max_length=20)
    reason_code: str = Field(min_length=1, max_length=100)
    label: str | None = Field(default=None, max_length=100)
    direction: str | None = Field(default=None, max_length=20)


class TelemetryEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "1.0.0"
    participant_id: str = Field(min_length=1, max_length=80)
    session_id: str = Field(min_length=1, max_length=120)
    sent_at_ms: int = Field(ge=0)
    activity: Activity
    battery_percent: int | None = Field(default=None, ge=0, le=100)
    location: LocationFix | None = None
    obstacle: ObstacleFact | None = None


class SafetyEvent(BaseModel):
    id: str
    kind: str
    at_ms: int
    detail: str
    location_label: str | None = None


class LivePerson(BaseModel):
    id: str
    name: str
    phone_number: str
    emergency_contact_name: str
    emergency_contact_number: str
    area: str
    activity: Activity
    activity_detail: str
    last_update_ms: int
    battery_percent: int | None = None
    latitude: float | None = None
    longitude: float | None = None
    location_accuracy_m: float | None = None
    location_observed_at_ms: int | None = None
    events: list[SafetyEvent]


class IngestAck(BaseModel):
    accepted: bool = True
    server_time_ms: int


class HealthResponse(BaseModel):
    status: str = "OK"
    service: str = "drishti-coordinator"
    version: str = "0.1.0"
    server_time_ms: int
    live_participant_id: str
    has_received_telemetry: bool

