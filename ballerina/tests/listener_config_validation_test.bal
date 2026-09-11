// Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Negative + boundary tests for `Listener.init`'s config validation (`validateConfig`/
// `validateRebindPolicy` in listener.bal). Pure-logic coverage: no socket, no mock, no
// native round trip - `Listener` init validates before `externInit`, and unlike `Client`
// never attempts a connect at init time (that happens at `'start()`).
import ballerina/test;

@test:Config {groups: ["listener", "config"]}
function testListenerConfigValidationRejectsOutOfBoundsValues() {
    record {|ListenerConfig config; string expect;|}[] cases = [
        {config: {host: "h", systemId: "s", password: "p", port: 0}, expect: "port"},
        {config: {host: "h", systemId: "s", password: "p", port: 65536}, expect: "port"},
        {config: {host: "h", systemId: "s", password: "p", maxConcurrentDispatch: 0}, expect: "maxConcurrentDispatch"},
        {config: {host: "h", systemId: "s", password: "p", maxConcurrentDispatch: 5000}, expect: "maxConcurrentDispatch"},
        {config: {host: "h", systemId: "s", password: "p", gracefulStopTimeout: -1}, expect: "gracefulStopTimeout"},
        {config: {host: "h", systemId: "s", password: "p", enquireLinkInterval: 2}, expect: "enquireLinkInterval"},
        {config: {host: "h", systemId: "s", password: "p", enquireLinkInterval: 60000}, expect: "enquireLinkInterval"},
        {config: {host: "h", systemId: "s", password: "p", bindTimeout: 0.5}, expect: "bindTimeout"},
        {config: {host: "h", systemId: "s", password: "p", bindTimeout: 60000}, expect: "bindTimeout"},
        {config: {host: "h", systemId: "s", password: "p", transactionTimeout: 0.5}, expect: "transactionTimeout"},
        {config: {host: "h", systemId: "s", password: "p", transactionTimeout: 30000}, expect: "transactionTimeout"},
        {config: {host: "h", systemId: "s", password: "p", rebindPolicy: {initialRebindDelay: -1}}, expect: "initialRebindDelay"},
        {config: {host: "h", systemId: "s", password: "p", rebindPolicy: {initialRebindDelay: 10, maxRebindDelay: 5}}, expect: "maxRebindDelay"},
        {config: {host: "h", systemId: "s", password: "p", rebindPolicy: {backOffMultiplier: 0.5}}, expect: "backOffMultiplier"},
        {config: {host: "h", systemId: "s", password: "p", rebindPolicy: {maxRebindAttempts: -2}}, expect: "maxRebindAttempts"}
    ];
    foreach var {config, expect} in cases {
        Listener|error result = new (config);
        test:assertTrue(result is error, string `config with out-of-bounds ${expect} must fail init`);
        if result is error {
            test:assertTrue(result is Error,
                    string `${expect}: the init error must be the distinct smpp:Error type`);
            test:assertTrue(result.message().includes(expect),
                    string `${expect}: the error should name the field, got: ${result.message()}`);
        }
    }
}

@test:Config {groups: ["listener", "config"]}
function testListenerConfigValidationAcceptsBoundaryValues() {
    // Exactly-at-the-boundary values must pass. `Listener.init` performs no network
    // activity at all (unlike `Client.init`), so a successful init needs no cleanup
    // beyond letting the listener go unused.
    Listener|error ok = new ({
        host: "h",
        systemId: "s",
        password: "p",
        port: 65535,
        maxConcurrentDispatch: 1024,
        gracefulStopTimeout: 0,
        enquireLinkInterval: 5,
        bindTimeout: 300,
        transactionTimeout: 300,
        rebindPolicy: {initialRebindDelay: 0, maxRebindDelay: 0, backOffMultiplier: 1, maxRebindAttempts: -1}
    });
    test:assertTrue(ok !is error,
            ok is error ? ok.message() : "boundary-valid config must init cleanly");
}

@test:Config {groups: ["listener", "config"]}
function testListenerBindTypeExcludesTransmitterAtCompileTime() {
    // `ListenerConfig.bindType` is typed `ListenerBindType` (`RECEIVER|TRANSCEIVER`), not
    // the full `BindType` - a `Listener` cannot even be asked to bind TRANSMITTER, since a
    // transmitter-bound session structurally cannot receive DELIVER_SM/DATA_SM (see
    // types.bal's `ListenerBindType` doc). Both members it DOES allow must be accepted by
    // init (no network activity happens here).
    Listener|error asReceiver = new ({host: "h", systemId: "s", password: "p", bindType: RECEIVER});
    test:assertTrue(asReceiver !is error, "RECEIVER must be a legal Listener bindType");
    Listener|error asTransceiver = new ({host: "h", systemId: "s", password: "p", bindType: TRANSCEIVER});
    test:assertTrue(asTransceiver !is error, "TRANSCEIVER must be a legal Listener bindType");
}
