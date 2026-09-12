# DRISHTI Coordinator

Small local-LAN service for one real walking phone and DRISHTI Monitor. It
receives structured facts only: activity, battery, GPS, and the on-device
safety verdict. It never receives camera frames and is never consulted by the
walking safety loop.

## Run

```powershell
cd apps/coordinator
Copy-Item .env.example .env
# Put the real participant phone number in the ignored .env file.
# Generate one access key and put the same value in both Android projects'
# ignored local.properties as drishti.monitorToken.
python -m venv .venv
.venv/Scripts/pip install -e ".[test]"
.venv/Scripts/python -m uvicorn drishti_coordinator.main:app --host 0.0.0.0 --port 8000
```

On the current Wi-Fi, configure both Android apps to use
`http://172.26.252.170:8000`. The address changes when the laptop joins a
different network; run `ipconfig` and update the app setting/build property.

## Endpoints

- `GET /api/v1/health`
- `POST /api/v1/monitor/telemetry` — walking phone, every two seconds; access key required
- `GET /api/v1/monitor/live-person` — Monitor polling feed; access key required

State is deliberately in memory for this one-live-user demo. Restarting the
service clears the session event history; the hardcoded Monitor participants
and hazards remain in the Monitor app.
