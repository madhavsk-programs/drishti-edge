from drishti_coordinator.models import Activity, LocationFix, ObstacleFact, TelemetryEnvelope
from drishti_coordinator.settings import Settings
from drishti_coordinator.state import MonitorState, OBSTACLE_COOLDOWN_MS


def telemetry(*, session="s1", obstacle=None):
    return TelemetryEnvelope(
        participant_id="live-arun",
        session_id=session,
        sent_at_ms=1,
        activity=Activity.WALKING,
        battery_percent=73,
        location=LocationFix(
            latitude=12.941,
            longitude=80.236,
            accuracy_m=4.0,
            observed_at_ms=1,
        ),
        obstacle=obstacle,
    )


def test_latest_location_and_private_identity_are_returned():
    now = 10_000
    state = MonitorState(
        Settings(live_phone="+91 00000 00000", live_area="The Hive, OMR, Chennai"),
        clock_ms=lambda: now,
    )
    state.ingest(telemetry())

    person = state.live_person()
    assert person.name == "Arun Kumar"
    assert person.phone_number == "+91 00000 00000"
    assert person.latitude == 12.941
    assert person.last_update_ms == now
    assert person.events[0].kind == "WALK_STARTED"


def test_obstacle_events_are_deduplicated_for_thirty_seconds():
    now = 10_000
    state = MonitorState(Settings(), clock_ms=lambda: now)
    obstacle = ObstacleFact(
        action="STOP",
        risk_level="HIGH",
        reason_code="OBSTACLE_NEARBY",
        label="chair",
        direction="CENTRE",
    )
    state.ingest(telemetry(obstacle=obstacle))
    state.ingest(telemetry(obstacle=obstacle))
    assert [event.kind for event in state.live_person().events].count("OBSTACLE_DETECTED") == 1

    now += OBSTACLE_COOLDOWN_MS
    state.ingest(telemetry(obstacle=obstacle))
    assert [event.kind for event in state.live_person().events].count("OBSTACLE_DETECTED") == 2


def test_unknown_participant_is_rejected():
    state = MonitorState(Settings(), clock_ms=lambda: 1)
    bad = telemetry().model_copy(update={"participant_id": "someone-else"})
    try:
        state.ingest(bad)
    except ValueError as exc:
        assert "unknown participant" in str(exc)
    else:
        raise AssertionError("unknown participant was accepted")
