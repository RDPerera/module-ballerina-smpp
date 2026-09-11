# `ballerina/smpp` examples

Runnable examples for the `ballerina/smpp` library: an `smpp:Client` (transmitter/
receiver/transceiver) for submitting and managing SMS traffic, and an `smpp:Listener`
(receiver/transceiver) that dispatches inbound mobile-originated (MO) messages and
delivery receipts (DLRs) to a service — bidirectionally, via an `smpp:Caller`
injected into a transceiver-bound service method.

Each example is a self-contained Ballerina package. They run end to end against the
bundled [mock SMSC](mock-smsc/), so you need no carrier account.

## Prerequisites

- **Ballerina** Swan Lake 2201.13.x (`bal version`)
- **Java 21** — only to run the mock SMSC (a small Gradle app)

## Running an example

Every example needs an SMSC to talk to. Use two terminals:

```bash
# terminal 1 — start the mock SMSC
cd examples/mock-smsc
./gradlew run --args="steady 2775"

# terminal 2 — run the example
cd examples/send-sms   # or receive-sms, two-way-sms
bal run
```

Stop each with `Ctrl-C`. Connection settings are `configurable` and default to the
mock; override them for a real SMSC with a `Config.toml` or
`bal run -- -Chost=... -Cport=... -CsystemId=... -Cpassword=...`.

## The examples

| Example | What it demonstrates | Use case |
|---------|----------------------|----------|
| [send-sms](send-sms/) | The minimal `Client`: connect, `submit`, `close`. | OTPs, alerts, notifications — any pure send-side integration. |
| [receive-sms](receive-sms/) | The minimal `Listener`: bind and log every inbound message and delivery receipt via `onDeliverSm`. | Inbound SMS ingestion; the starting point for any receive-side flow. |
| [two-way-sms](two-way-sms/) | Reply on the same session via `smpp:Caller` (`TRANSCEIVER` + `ASYNC`): a balance-enquiry short code that answers a `BAL` keyword inline. | Self-care short codes, keyword-driven campaigns, chatbot-style flows. |

The [mock SMSC](mock-smsc/) harness and its scenarios are documented in
[mock-smsc/README.md](mock-smsc/README.md). Every scenario answers `submit_sm`, and a
submit that requests a receipt on success-or-failure gets a correlated delivery
receipt pushed back — so the reply path is exercised end to end, mock included.

## Note on the library dependency

Each example's `Ballerina.toml` overrides `[[dependency]]` with `repository = "local"`
to resolve `ballerina/smpp` from your local Ballerina repository (populated by running
`./gradlew build` in the repo root, which builds and `bal push`es the module there)
rather than from Ballerina Central, since these examples are versioned inside the
`module-ballerina-smpp` repository ahead of the module's first publish. Once the module
is published, that override is no longer required — a plain `import ballerina/smpp;`
resolves from Central like any other import.
