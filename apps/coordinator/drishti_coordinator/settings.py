from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="DRISHTI_",
        env_file=Path(__file__).resolve().parents[1] / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    live_participant_id: str = "live-arun"
    live_name: str = "Arun Kumar"
    live_phone: str = ""
    live_area: str = "The Hive, OMR, Chennai"
    access_token: str = "configure-me"
