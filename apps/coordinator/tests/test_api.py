from fastapi.testclient import TestClient

from drishti_coordinator.main import app, settings


client = TestClient(app)


def test_health_is_available_without_exposing_the_participant():
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert "phone" not in response.text.lower()


def test_live_person_requires_the_shared_local_key():
    assert client.get("/api/v1/monitor/live-person").status_code == 401
    response = client.get(
        "/api/v1/monitor/live-person",
        headers={"X-Drishti-Key": settings.access_token},
    )
    assert response.status_code == 200

