# PC API Contract

Base URL: `http://127.0.0.1:18730`  
WS URL: `ws://127.0.0.1:18731/ws`

## HTTP

- `GET /api/status`
- `POST /api/discovery/start`
- `POST /api/discovery/stop`
- `POST /api/connect` body `{ "targetIp": "192.168.1.23" }`
- `POST /api/disconnect`
- `POST /api/audio/volume/increase`
- `POST /api/audio/volume/decrease`
- `POST /api/audio/mute/toggle`
- `POST /api/audio/local-monitor/toggle`
- `POST /api/settings/save`

## WS Event Types

- `status.updated`
- `discovery.devices`
- `connection.error`
- `audio.level`
- `audio.playback`
