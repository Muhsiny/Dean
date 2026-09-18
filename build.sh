#!/bin/sh
set -eu
mkdir -p assets resources
BASE='https://sayeh-news.un-beha.org'
curl -fsSL "$BASE/assets/index-BusRIYNS.js" -o assets/index-BusRIYNS.js
curl -fsSL "$BASE/assets/index-CaJsiD4p.css" -o assets/index-CaJsiD4p.css
curl -fsSL "$BASE/resources/sayeh-news-logo.png" -o resources/sayeh-news-logo.png
curl -fsSL "$BASE/manifest.webmanifest" -o manifest.webmanifest
curl -fsSL "$BASE/sw.js" -o sw.js
python3 - <<'PY'
from pathlib import Path
p=Path('assets/index-BusRIYNS.js')
s=p.read_text()
s=s.replace('"https://api-v2.appdeploy.ai/app/605a5f030a6d2cec77"','location.origin')
p.write_text(s)
PY
npm install --omit=dev
