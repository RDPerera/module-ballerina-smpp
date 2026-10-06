# Change Log
This file contains all the notable changes done to the Ballerina SMPP package through the releases.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `ListenerConfig.enquireLinkTimeout` (default 10s): how long the listener waits for an `enquire_link_resp` before treating the link as dead. Previously a fixed ~2s internal bound, which tore down healthy sessions against SMSCs that answer keepalives slowly.

### Fixed

- `Client.init` and `Listener.start` failures now carry `failureMode` and, for a negative `bind_resp`, `commandStatus` in the error detail (`REJECTED` for bad credentials, `LINK_DOWN` for unreachable/refused/timed-out/TLS failures, `INVALID_REQUEST` for oversized credentials), matching the submit path. Previously the detail record was empty.
- `Client.submit`/`submitMulti`/`submitData` reject an empty `destinationAddress` locally with `INVALID_REQUEST`, as `Caller.submit` already did, instead of forwarding it to the SMSC.

## [1.0.0] - Unreleased

### Added

- Initial release of the `smpp` package, providing an SMPP 3.4 client and listener over [jsmpp](https://github.com/opentelecoms-org/jsmpp).
- `Client`: bind as `TRANSMITTER`, `RECEIVER`, or `TRANSCEIVER` and submit outbound messages.
  - `submit`: submit a single text or binary SMS (`submit_sm`/`submit_sm_resp`).
  - `submitMulti`: submit an SMS to multiple destination addresses in one request (`submit_multi`/`submit_multi_resp`).
  - `queryStatus`: query the delivery status of a previously submitted message (`query_sm`/`query_sm_resp`).
- `Listener`: bind as `RECEIVER` or `TRANSCEIVER` and dispatch inbound SMPP traffic to a service.
  - `onDeliverSm`: invoked for delivery receipts and mobile-originated messages (`deliver_sm`).
  - `onDataSm`: invoked for data-mode messages (`data_sm`).
  - `onError`: invoked when the underlying connection fails or drops.
- `Caller`: injected into `onDeliverSm`/`onDataSm` handlers to submit a reply (e.g. an auto-response) from within the callback.
- TLS support for both `Client` and `Listener` via `SecureSocket`/`InsecureSocket`, including hostname verification.
- Automatic rebind on connection loss, configurable via `RebindPolicy` (initial delay, max delay, backoff multiplier, max attempts).
- A compiler plugin that validates `Listener` service shapes at compile time (recognized remote methods, required `Sms`/`Caller` parameter shapes, `error?` return type, `isolated` recommendation) and offers handler-template code actions.
