from datetime import datetime, timezone

import pytest

from app.models.notification import Channel, Preferences
from app.services.dispatcher import Dispatcher, PreferenceStore


class FrozenClock:
    def __init__(self, at: datetime) -> None:
        self.at = at

    def __call__(self) -> datetime:
        return self.at


@pytest.fixture
def clock() -> FrozenClock:
    # 14:00 UTC == 09:00 Eastern; outside default quiet hours
    return FrozenClock(datetime(2026, 3, 2, 14, 0, tzinfo=timezone.utc))


@pytest.fixture
def store() -> PreferenceStore:
    s = PreferenceStore()
    s.upsert(
        Preferences(
            customer_id="CUST-1",
            email="jane@example.com",
            phone="+12125551234",
            channels=[Channel.EMAIL, Channel.SMS, Channel.PUSH],
            quiet_hours_start=22,
            quiet_hours_end=7,
        )
    )
    s.upsert(Preferences(customer_id="CUST-EMAIL-ONLY", email="bob@example.com"))
    return s


@pytest.fixture
def dispatcher(store: PreferenceStore, clock: FrozenClock) -> Dispatcher:
    return Dispatcher(store, now_fn=clock)
