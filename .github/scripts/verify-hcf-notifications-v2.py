#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
source = root if (root / "AndroidManifest.xml").is_file() else root / "source code"
manifest = (source / "AndroidManifest.xml").read_text(encoding="utf-8")
engine = (source / "src" / "com" / "harleytg" / "forum" / "HcfNotifications.java").read_text(encoding="utf-8")

for forbidden in (
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    'android:foregroundServiceType="specialUse"',
    "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
):
    if forbidden in manifest:
        raise SystemExit(f"Play-sensitive foreground-service declaration remains: {forbidden}")

if "JobScheduler" not in engine or "NotificationSyncJobService" not in engine:
    raise SystemExit("Scheduled notification fallback is missing")
if "scheduled jobs + one-shot sync" not in engine:
    raise SystemExit("Play-safe notification sync mode marker is missing")
if "context.startForegroundService(intent)" in engine:
    raise SystemExit("Foreground-service startup call remains active")

if "DistributionMode" in engine or "legacy foreground live sync" in engine.lower():
    raise SystemExit("Legacy notification distribution logic remains active")

if (source / "AndroidManifest.legacy.xml").exists():
    raise SystemExit("Legacy Android manifest must not exist")

print("HCF notification verification passed: JobScheduler + one-shot sync only; no special-use FGS or legacy manifest.")
