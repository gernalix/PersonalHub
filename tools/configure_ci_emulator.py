"""Scale only the disposable GitHub CI AVD, preserving its logical viewport."""
import os
from pathlib import Path


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("CI display preparation is restricted to GitHub Actions")
    config = Path(os.environ["ANDROID_AVD_HOME"]) / "test.avd" / "config.ini"
    updates = {
        "hw.lcd.width": "720", "hw.lcd.height": "1600", "hw.lcd.density": "280",
        "skin.name": "720x1600", "skin.path": "_no_skin", "skin.dynamic": "yes",
    }
    lines = [line for line in config.read_text().splitlines()
             if line.split("=", 1)[0].strip() not in updates]
    config.write_text("\n".join(lines + [f"{key}={value}" for key, value in updates.items()]) + "\n")
    print("CI display prepared: 720x1600 at 280dpi; original logical viewport preserved")


if __name__ == "__main__":
    main()
