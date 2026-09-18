#!/bin/sh
set -eu
BASE='https://br-empty-star-b2m88i8y.storage.c-6.eu-central-1.aws.neon.tech/sayeh-public'
mkdir -p public-static/resources
curl -fsSL "$BASE/resources/sayeh-news-logo.png" -o public-static/resources/sayeh-news-logo.png
curl -fsSL "$BASE/manifest.webmanifest" -o public-static/manifest.webmanifest
curl -fsSL "$BASE/sw.js" -o public-static/sw.js
npx vite build
