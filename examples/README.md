# Ballerina SMPP Examples

This directory contains a set of examples to demonstrate how to use the `ballerina/smpp` connector.

## Examples

- [**Send an SMS**](<send-sms/A sample SMS sender using Ballerina SMPP.md>): submit a single SMS using `smpp:Client`.
- [**Receive SMS and delivery receipts**](<receive-sms/A sample SMS receiver using Ballerina SMPP.md>): bind `smpp:Listener` as a receiver and log inbound traffic.
- [**Two-way SMS: a balance-enquiry short code**](<two-way-sms/A sample two-way SMS short code using Ballerina SMPP.md>): reply on the same session using `smpp:Caller`.

## Running the Examples

Each example is a self-contained Ballerina package. From inside an example's directory:

```shell
$ bal run
```

The examples are also built as part of the repository build (`./gradlew build` from the repository root), against the `ballerina/smpp` package from the same checkout. Until `ballerina/smpp` is published to Ballerina Central, run an example the same way, with the distribution that build unpacks:

```shell
$ ../../target/ballerina-runtime/bin/bal run
```

All examples need a real SMPP v3.4 SMSC to connect to; edit each example's `Config.toml` with your SMSC's connection details before running it.
