# Ballerina SMPP Library

  [![Build](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/build-timestamped-master.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/build-timestamped-master.yml)
  [![codecov](https://codecov.io/gh/ballerina-platform/module-ballerina-smpp/branch/main/graph/badge.svg)](https://codecov.io/gh/ballerina-platform/module-ballerina-smpp)
  [![Trivy](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/trivy-scan.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/trivy-scan.yml)
  [![GraalVM Check](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/build-with-bal-test-graalvm.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-smpp/actions/workflows/build-with-bal-test-graalvm.yml)
  [![GitHub Last Commit](https://img.shields.io/github/last-commit/ballerina-platform/module-ballerina-smpp.svg?label=Last%20Commit)](https://github.com/ballerina-platform/module-ballerina-smpp/commits/main)
  [![GitHub issues](https://img.shields.io/github/issues/ballerina-platform/ballerina-standard-library/module/smpp.svg?label=Open%20Issues)](https://github.com/ballerina-platform/ballerina-standard-library/labels/module%2Fsmpp)

## Overview

This module provides a `Client` and a `Listener` for the **Short Message Peer-to-Peer** protocol (SMPP v3.4), letting a Ballerina program send and receive SMS traffic through a Short Message Service Centre (SMSC) — the same role an ESME (External Short Messaging Entity) plays in a telecom messaging gateway. It wraps the Java library [`org.jsmpp:jsmpp`](https://jsmpp.org/) through Ballerina's Java interoperability.

### `smpp:Client`

The `Client` binds to an SMSC as a **transmitter, receiver, or transceiver** ESME and drives the submit-family SMPP operations: `submit_sm`, `submit_multi`, `data_sm`, `query_sm`, `cancel_sm`, and `replace_sm`. Use it wherever a program actively sends (mobile-terminated) messages or manages previously submitted ones.

```ballerina
import ballerina/smpp;

configurable string systemId = ?;
configurable string password = ?;

public function main() returns error? {
    smpp:Client smppClient = check new ("localhost", systemId, password, port = 2775,
            bindType = smpp:TRANSMITTER);

    smpp:SubmitResult result = check smppClient->submit({
        destinationAddress: "94771234567",
        shortMessage: "Hello from Ballerina!"
    });

    check smppClient.close();
}
```

### `smpp:Listener`

The `Listener` binds to an SMSC as a **receiver or transceiver** ESME and dispatches inbound PDUs — mobile-originated (MO) SMS and delivery receipts (DLRs) — to an attached service. A transceiver-bound listener also injects an `smpp:Caller` into a service method, so the same session can reply with `caller->submit(...)`.

```ballerina
import ballerina/smpp;
import ballerina/io;

configurable string systemId = ?;
configurable string password = ?;

listener smpp:Listener smsListener = check new ("localhost", systemId, password, port = 2775,
        bindType = smpp:RECEIVER);

service on smsListener {
    remote function onDeliverSm(smpp:Sms sms) returns error? {
        io:println(string `SMS from ${sms.sourceAddress}: ${sms.shortMessage}`);
    }
}
```

## Documentation

- Package overview, quickstarts, and configuration: [`ballerina/README.md`](ballerina/README.md)
- Full library specification (bind types, the `Client` operations, the `Listener` service contract, TLS, and the error taxonomy): [`docs/spec/spec.md`](docs/spec/spec.md)
- Runnable examples (no carrier account needed — every example runs against a bundled mock SMSC): [`examples/`](examples/)

## Building from the source

### Set up the prerequisites

1. [OpenJDK 21](https://adoptium.net/) or later.
2. Export your GitHub personal access token with the read package permissions as follows.

   ```
   export packageUser=<Username>
   export packagePAT=<Personal access token>
   ```

### Build the source

Execute the commands below to build from the source.

- To build the package:

   ```
   ./gradlew clean build
   ```

- To run the tests:

   ```
   ./gradlew clean test
   ```

- To build the without the tests:

   ```
   ./gradlew clean build -x test
   ```

- To debug the package with a remote debugger:

   ```
   ./gradlew clean build -Pdebug=<port>
   ```

- To debug with Ballerina language:

   ```
   ./gradlew clean build -PbalJavaDebug=<port>
   ```

- Publish the generated artifacts to the local Ballerina central repository:

   ```
   ./gradlew clean build -PpublishToLocalCentral=true
   ```

- Publish the generated artifacts to the Ballerina central repository:

   ```
   ./gradlew clean build -PpublishToCentral=true
   ```

## Contributing to Ballerina

As an open source project, Ballerina welcomes contributions from the community.

For more information, go to the [contribution guidelines](https://github.com/ballerina-platform/ballerina-lang/blob/master/CONTRIBUTING.md).

## Code of conduct

All contributors are encouraged to read the [Ballerina Code of Conduct](https://ballerina.io/code-of-conduct).

## Useful links

- For more information go to the [`smpp` library](https://lib.ballerina.io/ballerina/smpp/latest).
- For example demonstrations of the usage, go to [Ballerina By Examples](https://ballerina.io/learn/by-example/).
- Chat live with us via our [Discord server](https://discord.gg/ballerinalang).
- Post all technical questions on Stack Overflow with the [#ballerina](https://stackoverflow.com/questions/tagged/ballerina) tag.
