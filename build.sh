#!/bin/sh
set -eu
mkdir -p assets resources
BASE='https://br-empty-star-b2m88i8y.storage.c-6.eu-central-1.aws.neon.tech/sayeh-public'
curl -fsSL "$BASE/assets/index-BusRIYNS.js" -o assets/index-BusRIYNS.js
curl -fsSL "$BASE/assets/index-CaJsiD4p.css" -o assets/index-CaJsiD4p.css
curl -fsSL "$BASE/resources/sayeh-news-logo.png" -o resources/sayeh-news-logo.png
curl -fsSL "$BASE/manifest.webmanifest" -o manifest.webmanifest
curl -fsSL "$BASE/sw.js" -o sw.js
npm install --omit=dev
