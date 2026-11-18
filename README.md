# RoutineBridge

Experimental Android project for researching Samsung Modes and Routines.

## Goal

Investigate whether third-party applications can reliably inspect and control
Samsung manual routines on real Samsung devices.

Initial target:
- Samsung Galaxy Z Fold7
- One UI 9
- Samsung Modes and Routines

## Research stages

1. Discover package components, providers and permissions.
2. Investigate routine metadata and identifiers.
3. Test supported intents and other interfaces.
4. Investigate Root and Shizuku execution paths.
5. Verify actual routine state and switching behavior.
6. Only then consider a supervisor implementation.

No undocumented API is assumed to be supported.

## Build

Debug APK is built by GitHub Actions.
