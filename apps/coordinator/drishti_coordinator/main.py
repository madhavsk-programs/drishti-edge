from time import time

from fastapi import FastAPI, Header, HTTPException, status

from .models import HealthResponse, IngestAck, LivePerson, TelemetryEnvelope
from .settings import Settings
from .state import MonitorState


settings = Settings()
state = MonitorState(settings)
app = FastAPI(title="DRISHTI Coordinator", version="0.1.0")


@app.get("/api/v1/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        server_time_ms=int(time() * 1000),
        live_participant_id=settings.live_participant_id,
        has_received_telemetry=state.has_received_telemetry,
    )


@app.post(
    "/api/v1/monitor/telemetry",
    response_model=IngestAck,
    status_code=status.HTTP_202_ACCEPTED,
)
def ingest(
    envelope: TelemetryEnvelope,
    x_drishti_key: str | None = Header(default=None),
) -> IngestAck:
    require_access(x_drishti_key)
    try:
        received_at = state.ingest(envelope)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return IngestAck(server_time_ms=received_at)


@app.get("/api/v1/monitor/live-person", response_model=LivePerson)
def live_person(x_drishti_key: str | None = Header(default=None)) -> LivePerson:
    require_access(x_drishti_key)
    return state.live_person()


def require_access(key: str | None) -> None:
    if key != settings.access_token:
        raise HTTPException(status_code=401, detail="invalid monitor access key")
