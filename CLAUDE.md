# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All commands must be run from the project root via Gradle wrapper. There is no standalone test runner — tests require an Android emulator or device.

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests (JVM only)
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a single unit test class
./gradlew test --tests "com.punchthrough.blestarterappandroid.ExampleUnitTest"

# Lint Kotlin code style (ktlint)
./gradlew ktlint

# Auto-fix Kotlin code style
./gradlew ktlintFormat

# Full check (lint + tests)
./gradlew check
```

Build outputs land in `app/build/outputs/apk/`.

## Project Status

This project is undergoing a UI refactor to transform the original BLE starter app into a Telegram-style messenger. Steps 1–7 are complete. **Step 8 (polish) is the only remaining step.**

### Completed steps
- **Step 1** — Dependencies: Navigation Component, Material3, ViewModel/LiveData, Room with KSP
- **Step 2** — Room Database: `Contact` and `Message` entities, DAOs, `AppDatabase` singleton
- **Step 3** — `BleViewModel`: shared ViewModel scoped to `MainActivity`, bridging BLE layer and UI
- **Step 4** — `MainActivity` refactored to host a floating pill-shaped bottom nav bar with 4 tabs (Navigation Component)
- **Step 5** — `ChatsFragment`: conversation list with `ChatsAdapter` (DiffUtil), empty state, Room LiveData
- **Step 6** — `ChatActivity`: bubble UI (incoming/outgoing), `MessagesAdapter` with two ViewHolder types, Room persistence, BLE send via `ConnectionManager`
- **Step 7** — `ContactsFragment`, `DeviceFragment`, `SettingsFragment` fully implemented

### Pending: Step 8 — Polish
Tasks remaining for Step 8:
1. **Avatar colors**: assign a deterministic color per contact address instead of always blue (`avatar_background.xml` is currently a fixed `#2196F3` oval)
2. **Dark theme**: add `res/values-night/themes.xml` with `Theme.Material3.Dark.NoActionBar`
3. **BleOperationsActivity cleanup**: this activity still exists and is still registered in the Manifest but is no longer the entry point. Decide whether to keep it (as a debug/raw view), repurpose it, or remove it. Currently `ConnectionManager.connect()` in `DeviceFragment` triggers `connectionEventListener` in the old `MainActivity` code — this listener no longer exists after the refactor. The `onConnectionSetupComplete` callback that used to launch `BleOperationsActivity` must be re-wired so that connecting a device from `DeviceFragment` updates `BleViewModel.connectedDevice` and navigates to the Chats tab instead.
4. **Incoming message routing**: `BleOperationsActivity.onCharacteristicChanged` still contains the `+RCV` parsing and the `bleViewModel.onMessageReceived()` call. If `BleOperationsActivity` is removed or bypassed, this parsing logic must move to a foreground `Service` or remain in `BleOperationsActivity` while keeping it invisible/background.
5. **Known architectural gap**: `ChatActivity` uses `by viewModels()` (its own ViewModel instance) instead of `by activityViewModels()`. This means `connectedDevice` is always null in `ChatActivity` and BLE send silently fails. Fix: change to `by activityViewModels()` — but note that `ChatActivity` is not a fragment, so it needs to share the ViewModel with `MainActivity` via the same process. The correct fix is to scope `BleViewModel` to the `Application` level using a custom `ViewModelProvider.Factory`, or to pass the connected device address via Intent and look it up inside `ChatActivity`.

## Architecture

This is a BLE messenger Android app that pairs with REYAX RYLR999 LoRa modules using AT-command protocol. The app lets users send and receive messages over BLE when no network infrastructure is available.

### BLE Communication Layer (`ble/`)

`ConnectionManager` is a singleton object that owns the GATT connection lifecycle. It serializes all BLE operations (connect, read, write, notifications, MTU negotiation) through a `ConcurrentLinkedQueue` — only one operation runs at a time, and `signalEndOfOperation()` must be called after every async callback to advance the queue. Consumers register a `ConnectionEventListener` (with WeakReference storage) to receive callbacks.

**Hardware UUIDs (RYLR999 module):**
- Service: `4880c12c-fdcb-4077-8920-a450d7f9b907`
- Write characteristic: `f000c0c1-0451-4000-b000-000000000000`
- Notify characteristic: `f000c0c2-0451-4000-b000-000000000000`
- NUS TX (message write used in `ChatActivity`): `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- NUS RX (message notifications): `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

**AT command protocol:**
- Outgoing: `AT+SEND=<address>,<message>\r\n`
- Incoming notification: `+RCV=<sender>,<node_id>,<message>` (3-part format used in this app)
- Config commands: `AT+ADDRESS=<n>\r\n`, `AT+CHANNEL=<n>\r\n`, `AT+CRFOP=<n>\r\n`

### ViewModel / State (`BleViewModel`)

`BleViewModel` (scoped to `MainActivity`) is the single source of truth shared across all fragments via `activityViewModels()`. It:
- Holds `connectedDevice` and `connectionStatus` as `LiveData`
- Exposes `incomingMessage` as a one-shot event for new BLE messages
- Owns all Room DB interactions (`contactDao`, `messageDao`)
- Bridges between the BLE layer and the UI — callers invoke `onDeviceConnected`, `onDeviceDisconnected`, `onMessageReceived`, `onMessageSent`

**Known gap:** `ChatActivity` uses `by viewModels()` instead of `by activityViewModels()`, so `connectedDevice` is always null there. See Step 8 tasks above for the fix.

### Navigation

`MainActivity` hosts a floating pill-shaped `BottomNavigationView` (Material3, `nav_pill_background.xml` drawable with 28dp corner radius) with four destinations managed by the Android Navigation Component (`nav_graph.xml`):
- **Chats** (`ChatsFragment`) — start destination, list of conversations from `lastMessages` LiveData
- **Contacts** (`ContactsFragment`) — manage named LoRa node contacts, add/edit/delete via dialogs
- **Device** (`DeviceFragment`) — BLE scanning (moved from old `MainActivity`) and connection status
- **Settings** (`SettingsFragment`) — AT config params, persisted in `SharedPreferences`, sent to module on save

`ChatActivity` is a separate `Activity` (not a fragment destination) launched from `ChatsFragment` via explicit Intent with `EXTRA_CONTACT_ADDRESS` and `EXTRA_CONTACT_NAME`.

The navbar floats over content with `android:layout_gravity="bottom|center_horizontal"` and `android:layout_marginBottom="24dp"`. All fragment RecyclerViews have `android:paddingBottom="80dp"` to prevent content being hidden behind the navbar.

### Data Layer (`data/`)

Room database (`messenger_db`, version 1) with KSP annotation processing:
- `Contact(address: Int PK, name: String, lastSeen: Long)` — `address` is the integer LoRa node number
- `Message(id: Long autoGenerate PK, contactAddress: Int, content: String, timestamp: Long, isOutgoing: Boolean)`

`MessageDao`: `getLastMessagePerContact()` (chats list), `getMessagesForContact(address)` (chat detail), `insert()`, `deleteConversation()`. Both query methods return `LiveData`.
`ContactDao`: `getAllContacts()` (LiveData), `insertOrUpdate()`, `delete()`, `getByAddress()`.

### UI Layer (`ui/`)

All fragments use ViewBinding with the null-safety pattern (`_binding`/`binding` pair, nulled in `onDestroyView`).

RecyclerView adapters:
- `ChatsAdapter` — DiffUtil, `ChatItem(contact, lastMessage)` data class, formats time as HH:mm (<24h) or dd/MM (older)
- `ContactsAdapter` — DiffUtil, long-press for edit/delete dialog
- `MessagesAdapter` — two view types (`VIEW_TYPE_INCOMING = 0`, `VIEW_TYPE_OUTGOING = 1`), separate ViewHolders and layouts

Drawables:
- `bubble_outgoing.xml` — `#2196F3` fill, 18dp corners except bottom-right (4dp)
- `bubble_incoming.xml` — `?attr/colorSurfaceVariant` fill, 18dp corners except top-left (4dp)
- `avatar_background.xml` — fixed `#2196F3` oval (to be made dynamic in Step 8)
- `nav_pill_background.xml` — `?attr/colorSurface` rectangle with 28dp corners and 1dp outline stroke
- `input_background.xml` — `?attr/colorSurfaceVariant` rectangle with 24dp corners

Theme: `Theme.Material3.Light.NoActionBar` (upgraded from original `Theme.AppCompat.Light.DarkActionBar` to support Material3 attributes like `colorSurfaceVariant`, `colorOutlineVariant`).

## Key Constraints

- `compileSdk 34`, `minSdk 21`, JVM target 17, Kotlin 1.9.0
- KSP `1.9.0-1.0.13` (not kapt) for Room annotation processing
- Build system: legacy `apply plugin:` style (not `plugins {}` block in root), KSP classpath in root `build.gradle` `buildscript.dependencies`
- ViewBinding enabled; DataBinding not used
- All BLE operations require `@SuppressLint("MissingPermission")` — permission checks delegated to UI layer
- Timber used for all logging (no direct `Log.*` calls in new code; `BleOperationsActivity` still uses `Log.*`)
- Package name: `com.punchthrough.blestarterappandroid`
- UI fragments in subpackage `ui/`, data layer in `data/` (with `model/`, `dao/`, `database/` subpackages)