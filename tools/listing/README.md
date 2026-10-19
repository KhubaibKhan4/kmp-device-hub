# Listing screenshots and preview video

1. Panel renders (and the plugin icon): `./gradlew :idea-plugin:test --tests '*ListingRender*'` → `idea-plugin/build/listing/`.
2. Device footage: real emulator/simulator screenshots and screen recordings (dark mode, text size, Arabic RTL, push,
   clean 9:41 status bar), captured with the plugin's own `DeviceSettings` / `DeviceSimulation` calls.
3. `python3 tools/listing/compose_screenshots.py idea-plugin/build/listing <footage> idea-plugin/build/listing/plugin-icon.png dist/listing`
   → seven 2560×1600 Marketplace screenshots.
4. `python3 tools/listing/make_preview_video.py idea-plugin/build/listing <footage> dist/listing <out>.mp4`
   → a ~64 s 1920×1080 preview video. Needs Pillow and ffmpeg.
