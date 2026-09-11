## Overview

This module provides a `Client` and a `Listener` for **SMPP v3.4** (Short Message Peer-to-Peer), the protocol used by an ESME (External Short Messaging Entity) to exchange SMS traffic with a Short Message Service Centre (SMSC). It wraps the Java library [`org.jsmpp:jsmpp`](https://jsmpp.org/) through Ballerina's Java interoperability.

## Key features

- `smpp:Client` — a transmitter/receiver/transceiver ESME for `submit_sm`, `submit_multi`, `data_sm`, `query_sm`, `cancel_sm`, and `replace_sm`.
- `smpp:Listener` — a receiver/transceiver ESME that dispatches inbound `deliver_sm`/`data_sm` (MO messages and delivery receipts) to a service.
- `smpp:Caller` — injected into a transceiver-bound `Listener` service so a handler can reply on the same session (`submit_sm`).
- TLS (`SecureSocket`) for both the `Client` and the `Listener`, including mutual TLS.
- A single `smpp:Error` type carrying a `FailureMode` so retry logic can branch on *why* an operation failed, and whether a retry can duplicate the message.

## `smpp:Client`

The `Client` binds once at `init` and stays bound for its lifetime; `close()` unbinds and releases the underlying session. It accepts any `BindType` — `TRANSMITTER` for a pure sender, `RECEIVER` if you only need `query_sm`/`cancel_sm` against a receive-only credential, or `TRANSCEIVER` for both.

### Send a text message

```ballerina
import ballerina/smpp;

configurable string systemId = ?;
configurable string password = ?;

public function main() returns error? {
    smpp:Client smppClient = check new ({
        host: "localhost",
        port: 2775,
        systemId,
        password,
        bindType: smpp:TRANSMITTER
    });

    smpp:SubmitResult|smpp:Error result = smppClient->submit({
        destinationAddress: "94771234567",
        shortMessage: "Hello from Ballerina!"
    });
    if result is smpp:SubmitResult {
        // Correlate later delivery receipts against result.messageId.
    }

    check smppClient.close();
}
```

### Send to multiple destinations

```ballerina
smpp:MultiSubmitResult result = check smppClient->submitMulti(
    {destinationAddress: "", shortMessage: "Flash sale ends tonight!"},
    ["94771234567", "94777654321"]
);
```

### Query, cancel, and replace

```ballerina
smpp:QueryResult status = check smppClient->queryStatus(messageId, "94771234567");
check smppClient->cancel(messageId, "94771234567", "94771234567");
check smppClient->replace(messageId, "94771234567", {destinationAddress: "94771234567", shortMessage: "Updated text"});
```

`data_sm` (`submitData`) follows the same shape as `submit` and is the alternative MT transfer PDU some SMSCs prefer for binary/WAP-push payloads.

## `smpp:Listener`

The `Listener` binds as `RECEIVER` (inbound only) or `TRANSCEIVER` (inbound + reply via `Caller`), and dispatches every inbound `deliver_sm`/`data_sm` to whichever of these remote methods your attached service implements:

- `remote function onDeliverSm(smpp:Sms sms) returns error?`
- `remote function onDataSm(smpp:Sms sms) returns error?`
- `remote function onError(error err) returns error?` — an unexpected session drop (see **Resilience** in the [specification](https://github.com/ballerina-platform/module-ballerina-smpp/blob/main/docs/spec/spec.md)).

```ballerina
import ballerina/smpp;
import ballerina/io;

configurable string systemId = ?;
configurable string password = ?;

listener smpp:Listener smsListener = check new ({
    host: "localhost",
    port: 2775,
    systemId,
    password,
    bindType: smpp:RECEIVER
});

service on smsListener {
    remote function onDeliverSm(smpp:Sms sms) returns error? {
        if sms.deliveryReceipt {
            io:println(string `Delivery receipt: ${sms.receipt?.finalStatus ?: "unparsed"}`);
        } else {
            io:println(string `SMS from ${sms.sourceAddress}: ${sms.shortMessage}`);
        }
    }
}
```

### Replying with `Caller`

A `TRANSCEIVER`-bound listener can declare an `smpp:Caller` parameter (matched by type, in either parameter order) and reply on the same session:

```ballerina
service on smsListener {
    remote function onDeliverSm(smpp:Sms sms, smpp:Caller caller) returns error? {
        smpp:SubmitResult result = check caller->submit({
            destinationAddress: sms.sourceAddress,
            shortMessage: "Thanks for your message!"
        });
    }
}
```

### Delivery receipts

When `sms.deliveryReceipt` is `true`, jsmpp's Appendix-B receipt parser fills `sms.receipt` (`id`, `finalStatus`, `submitDate`/`doneDate`, `errorCode`, `text`) whenever the body conforms to the format; the raw body is always on `sms.shortMessage`. Correlate a receipt against a submit's `SubmitResult.messageId` using **`sms.receiptedMessageId`** (the `receipted_message_id` TLV, §5.3.2.12) — the only field SMPP guarantees for this; the Appendix-B body's `receipt.id` is vendor specific.

## TLS

Both the `Client` and the `Listener` accept `secureSocket: SecureSocket|InsecureSocket` to wrap the SMPP session in TLS — SMPP binds otherwise send `systemId`/`password` in cleartext.

```ballerina
smpp:Client smppClient = check new ({
    host: "smsc.example.com",
    port: 3550,
    systemId,
    password,
    secureSocket: {
        cert: {path: "./truststore.p12", password: trustStorePass}
    }
});
```

`cert` (required) verifies the server against a PKCS12/JKS truststore or a PEM CA certificate path; hostname verification is on by default; only TLS 1.2/1.3 are negotiated; set `key` (a `crypto:KeyStore`) for mutual TLS. `InsecureSocket` (development/testing only, disables server-certificate verification) is documented on the type itself, along with a loud warning logged whenever it is in effect.

## Error handling

Every failing operation returns a distinct `smpp:Error` whose `ErrorDetail` carries a `FailureMode` — `REJECTED`, `TIMEOUT_DELIVERY_UNKNOWN`, `LINK_DOWN`, `LINK_ABANDONED`, `INVALID_REQUEST`, or `PROTOCOL_ERROR` — plus, for submit-family operations, `possiblySubmitted`: the single bit to branch retry logic on (`false` means a retry cannot duplicate the message). See [`FailureMode`](https://github.com/ballerina-platform/module-ballerina-smpp/blob/main/docs/spec/spec.md#6-error-handling) in the specification for the full retry guidance.

## Examples

Runnable, end-to-end examples live in [`examples/`](https://github.com/ballerina-platform/module-ballerina-smpp/tree/main/examples) in the source repository. Each runs against a bundled mock SMSC, so no carrier account is needed:

- [send-sms](https://github.com/ballerina-platform/module-ballerina-smpp/tree/main/examples/send-sms) — `Client`-only: connect, `submit`, `close`.
- [receive-sms](https://github.com/ballerina-platform/module-ballerina-smpp/tree/main/examples/receive-sms) — `Listener`-only: `onDeliverSm` handling MO messages and delivery receipts.
- [two-way-sms](https://github.com/ballerina-platform/module-ballerina-smpp/tree/main/examples/two-way-sms) — a `Listener` + `Caller` balance-enquiry short code: reply on the same session to an inbound keyword.

## Report issues

To report bugs, request new features, start new discussions, view project boards, etc., go to the [Ballerina standard library parent repository](https://github.com/ballerina-platform/ballerina-standard-library).

## Useful links

- Chat live with us via our [Discord server](https://discord.gg/ballerinalang).
- Post all technical questions on Stack Overflow with the [#ballerina](https://stackoverflow.com/questions/tagged/ballerina) tag.
