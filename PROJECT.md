# ESPHome Controller for Android --- Project Brief

## Goal

Build a native Android application that discovers and controls arbitrary
ESPHome devices on the same local network, without Home Assistant, a
cloud service, MQTT broker, or a required central server.

The application is a general-purpose ESPHome controller. It must
discover entities dynamically from each device rather than relying on
firmware-specific device profiles.

## Product principles

-   Local-first: Android communicates directly with ESPHome devices.
-   No Home Assistant dependency.
-   No cloud account or backend required.
-   Generic: support different ESPHome boards and entity types.
-   Secure: support the ESPHome Native API Noise encryption and store
    secrets securely.
-   Resilient: handle devices appearing/disappearing, network changes,
    reconnects, and mDNS limitations.
-   Be honest about unsupported platforms/features; do not pretend every
    ESPHome feature is exposed through the Native API.

## Recommended technology

-   Kotlin
-   Jetpack Compose
-   Android NSD (`NsdManager`) for DNS-SD/mDNS discovery
-   Kotlin Coroutines and Flow for asynchronous networking/state
-   Protocol Buffers for ESPHome Native API messages
-   ESPHome Native API Noise encryption
-   Room for persistent device metadata and user preferences
-   Android Keystore / encrypted storage for API encryption keys
-   MVVM or a similarly clear presentation architecture

Keep the ESPHome protocol client independent of Android UI code so it
can be tested separately.

## Architecture

``` text
Compose UI
  ├── Device list / device details / entity controls / settings
  └── ViewModels
       ├── DeviceRepository
       │    ├── Discovery service (mDNS / DNS-SD)
       │    ├── Persistent device registry (Room)
       │    └── Connection manager
       │         └── ESPHome Native API client
       │              ├── TCP transport
       │              ├── Protobuf message codec
       │              ├── Noise encryption
       │              ├── Device/entity metadata
       │              ├── State subscriptions
       │              └── Command sending
       └── Entity UI renderer / platform-specific controls
```

Suggested packages:

``` text
app/
  discovery/
  networking/
  esphome/api/
  esphome/protobuf/
  esphome/noise/
  devices/
  entities/
  database/
  security/
  ui/devices/
  ui/device_details/
  ui/entities/
  ui/settings/
```

## Discovery

Discover ESPHome Native API devices using the DNS-SD service
`_esphomelib._tcp.local`.

Requirements: - Browse continuously while the app is in the foreground,
with a manual refresh action. - Resolve service instances to host/IP and
port (normally TCP 6053; use the discovered port). - Read DNS-SD TXT
metadata where available, but do not assume TXT data replaces the Native
API handshake. - Deduplicate devices and handle multiple network
interfaces. - Show discovery state and useful errors. - Support manual
hostname/IP + port entry when mDNS is unavailable. - Persist known
devices and attempt reconnection when appropriate. - Handle Android
local-network permissions and multicast behavior correctly. Investigate
multicast lock requirements and Android-version-specific permission
behavior. - Explain that mDNS generally does not cross VLAN/subnet
boundaries unless the network provides an mDNS reflector/repeater.

Do not use IP scanning as the primary discovery mechanism.

## ESPHome Native API client

Use the upstream ESPHome API protocol definitions and a maintained,
compatible implementation as the source of truth. Inspect the upstream
`aioesphomeapi` project and ESPHome `api.proto` definitions before
implementing message handling.

Important: - The Native API is a Protobuf-based TCP protocol. - Support
the API handshake and client/server negotiation. - Support Noise
encryption when enabled by the device. - Do not silently downgrade or
bypass encryption. - Authentication/encryption key entry must be
possible for each device. - Store secrets using Android Keystore-backed
encryption; never log keys. - Handle API version compatibility and
unknown fields/messages safely. - Implement clean disconnect, timeout,
cancellation, and reconnect behavior. - Do not block the main thread.

Useful upstream references: - https://github.com/esphome/aioesphomeapi -
https://github.com/esphome/esphome - https://esphome.io/components/api/

Do not copy Python runtime code into Android. Reuse protocol definitions
and implement a Kotlin client, or identify and carefully evaluate an
existing Kotlin/JVM implementation. Verify maintenance status, license,
protocol coverage, and Noise support before adopting a dependency.

## Dynamic entity model

After connecting, retrieve device information and the entity list from
the Native API. Build the UI from the reported entity platform and
metadata. Do not hardcode entities or assume all devices have the same
capabilities.

Prioritize Native API entity platforms, including where supported by the
protocol: - Light - Switch - Sensor - Binary sensor - Number - Select -
Text - Button - Climate - Fan - Cover - Lock - Valve - Text sensor -
Date - Time - Date/time - Event - Alarm control panel - Water heater

For each entity, retain identifiers, name, icon/category metadata,
disabled/hidden state, and other metadata exposed by the protocol.
Respect entities disabled or hidden by the device where appropriate;
provide a setting to show hidden entities if feasible.

UI mapping: - Light: on/off, brightness, RGB/white/color
temperature/effects only when the entity advertises those
capabilities. - Switch: toggle. - Sensor: value and unit. - Binary
sensor: state. - Number: numeric control with reported min/max/step. -
Select: options from device metadata. - Text: text input with reported
constraints. - Button: explicit action button. - Climate: target/current
temperatures and supported modes/features. - Fan: supported speed,
preset, oscillation and direction controls. - Cover: open/close/stop and
position/tilt only when supported. - Lock: lock/unlock where
supported. - Other platforms: sensible platform-specific UI; unsupported
platforms should still be listed with a clear unsupported message rather
than crashing.

Subscribe to state updates and update the UI reactively. Commands must
use the correct platform-specific Native API messages. Do not implement
controls based only on entity names.

Note: Camera streaming, OTA updates, and some device-management
functions may use separate protocols/components and are not
automatically provided by generic entity discovery. Treat these as
separate future integrations, not as guaranteed Native API entity
support.

## Main screens

1.  **Devices**
    -   Discovered and saved devices.
    -   Online/offline/connecting/error status.
    -   Device name, board/model if available, hostname and IP.
    -   Search, favorites, refresh, add manually.
2.  **Device details**
    -   Device information and connection status.
    -   Dynamically generated entity controls grouped by platform or
        area/category where metadata allows.
    -   Search/filter entities.
    -   Last-seen and connection diagnostics.
3.  **Settings**
    -   Discovery behavior.
    -   Manual device management.
    -   Encryption key management.
    -   Diagnostics and logs.
    -   About/protocol version information.
4.  **Diagnostics/logs**
    -   Connection errors and reconnect status.
    -   Native API logs subscription if supported.
    -   Never expose secrets in logs.

## Persistence and security

Persist: - Device stable identity (prefer Native API device
identity/credentials metadata where available). - User-assigned display
name and favorite status. - Last known host/port, but refresh discovery
data when possible. - Entity preferences if useful.

Security: - Keep encryption keys out of plaintext preferences and
logs. - Use Android Keystore-backed encryption. - Do not transmit
credentials to any external service. - Warn users that a device without
Native API encryption is communicating without transport encryption. -
Avoid storing transient state unnecessarily.

## Reliability requirements

-   Reconnect with bounded exponential backoff and jitter.
-   Cancel stale connections when a device address changes.
-   Avoid duplicate sessions for the same device.
-   Handle app backgrounding and Android lifecycle constraints.
-   Recover from Wi-Fi changes and network loss.
-   Use timeouts and surface actionable errors.
-   Keep state subscriptions and resources scoped to active connections.
-   Do not claim a device is offline solely because a single request
    failed.

## Development milestones

### Milestone 1 --- Android shell and discovery

-   Create Gradle project and Compose navigation.
-   Implement mDNS browse/resolve for `_esphomelib._tcp`.
-   Show discovered service name, host, IP and port.
-   Add manual host entry.
-   Test on a physical Android phone and real ESPHome device.

Acceptance: app discovers a device on the same Wi-Fi and displays its
resolved endpoint.

### Milestone 2 --- Native API connection

-   Study upstream protocol and select/generate Protobuf definitions.
-   Implement TCP connection, handshake and version negotiation.
-   Implement Noise encryption and per-device key entry.
-   Retrieve device information.
-   Add connection diagnostics.

Acceptance: app securely connects to an ESPHome device with encryption
enabled and displays device information.

### Milestone 3 --- Entity discovery and live states

-   Request entity list.
-   Parse entity metadata.
-   Subscribe to states.
-   Build a generic entity registry and reactive state store.

Acceptance: entities appear dynamically and their states update without
polling.

### Milestone 4 --- Initial controls

-   Implement Light, Switch, Sensor and Binary Sensor.
-   Add capability-aware light controls.
-   Send commands and reflect acknowledged state updates.

Acceptance: control a light and switch, and observe live sensor values
on a real device.

### Milestone 5 --- Remaining entity platforms

-   Add platform-specific models, commands and UI for the other
    supported Native API entity types.
-   Add unknown/unsupported handling and protocol compatibility tests.

Acceptance: supported entities render and operate correctly; unsupported
entities fail gracefully.

### Milestone 6 --- Persistence and hardening

-   Room device registry.
-   Secure credential storage.
-   Reconnect/network-change handling.
-   Favorites, search, manual devices and diagnostics.
-   Automated tests and documentation.

Acceptance: known devices are retained across app restarts and reconnect
reliably.

## Testing strategy

-   Unit tests for Protobuf parsing, entity metadata, command
    construction and state reducers.
-   Protocol tests against fixtures generated from upstream ESPHome
    definitions.
-   Integration tests with actual ESPHome firmware covering encrypted
    and unencrypted API configurations.
-   Test multiple devices, duplicate discovery announcements, device IP
    changes, unavailable devices, reconnects, and Wi-Fi changes.
-   Test on physical Android hardware across supported Android versions.
-   Do not rely exclusively on emulator networking for mDNS validation.

## Codex working instructions

1.  Start by inspecting the repository and reporting its current state.
2.  Before coding, verify current ESPHome Native API protocol
    definitions and Android networking/permission requirements from
    upstream documentation/source.
3.  Propose a short implementation plan and identify protocol/dependency
    risks.
4.  Implement one milestone at a time; keep the project buildable after
    each milestone.
5.  Add tests alongside protocol and entity functionality.
6.  Run Gradle tests and build tasks after changes; report exact
    commands and results.
7.  Do not invent protocol messages or claim unsupported features work.
8.  Do not add a backend, cloud service, Home Assistant, MQTT, or
    unrelated dependencies.
9.  Ask before adding paid services, external accounts, or dependencies
    that require credentials.
10. Keep README and this project brief updated as implementation
    decisions change.

## Initial task for Codex

Inspect the current working directory. If it is empty, scaffold a Kotlin
Android application using Jetpack Compose. Then investigate the current
ESPHome Native API protocol and Android mDNS requirements. Implement
**Milestone 1 only**: foreground DNS-SD discovery and a simple device
list with manual refresh and manual host entry. Add tests where
practical, build the app, and report how to run it on a physical Android
phone. Do not begin the Native API protocol implementation until
discovery works and the design is reviewed.

## Current implementation status

The app supports local homes, foreground discovery, device setup and editing, saved Native API and web-server connection preferences, automatic foreground connections with retry, and live controls for lights, switches, selects, numbers and buttons plus sensor readings; other platforms are listed as unsupported. Sessions are managed per device by a ViewModel with independent backoff and network-change reconnects. The dashboard uses entity tiles, the Devices tab provides a per-device detailed view, and the app follows the system theme by default with a user override. ESPHome entity coverage and resilience remain incomplete. Real device tests and broader network and Android-version testing are required; see `AGENTS.md` and `README.md` for the current status.
