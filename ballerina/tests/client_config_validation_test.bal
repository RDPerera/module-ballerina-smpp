// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
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

// Negative + boundary tests for `Client.init`'s config validation (`validateClientConfig`
// in client.bal). Pure-logic coverage: no socket, no mock, no native round trip - `Client`
// init validates before `externInit`.
import ballerina/test;

@test:Config {groups: ["client", "config"]}
function testClientConfigValidationRejectsOutOfBoundsValues() {
    record {|ClientConfig config; string expect;|}[] cases = [
        {config: {host: "h", systemId: "s", password: "p", port: 0}, expect: "port"},
        {config: {host: "h", systemId: "s", password: "p", port: 65536}, expect: "port"},
        {config: {host: "h", systemId: "s", password: "p", bindTimeout: 0.5}, expect: "bindTimeout"},
        {config: {host: "h", systemId: "s", password: "p", bindTimeout: 60000}, expect: "bindTimeout"},
        {config: {host: "h", systemId: "s", password: "p", transactionTimeout: 0.5}, expect: "transactionTimeout"},
        {config: {host: "h", systemId: "s", password: "p", transactionTimeout: 30000}, expect: "transactionTimeout"},
        {config: {host: "h", systemId: "s", password: "p", enquireLinkInterval: 2}, expect: "enquireLinkInterval"},
        {config: {host: "h", systemId: "s", password: "p", enquireLinkInterval: 60000}, expect: "enquireLinkInterval"}
    ];
    foreach var {config, expect} in cases {
        Client|error result = new (config);
        test:assertTrue(result is error, string `config with out-of-bounds ${expect} must fail init`);
        if result is error {
            test:assertTrue(result is Error,
                    string `${expect}: the init error must be the distinct smpp:Error type`);
            test:assertTrue(result.message().includes(expect),
                    string `${expect}: the error should name the field, got: ${result.message()}`);
        }
    }
}

const int CLIENT_CONFIG_BOUNDARY_PORT = 28099;

@test:Config {groups: ["client", "config"]}
function testClientConfigValidationAcceptsBoundaryValues() {
    // Unlike `Listener.init` (which only validates and defers the network attempt to
    // `'start()`), `Client.init` validates AND immediately connects/binds - so a
    // boundary-valid config still has to reach a real connect attempt. Nothing listens on
    // this port, so the connect fails fast (connection refused) instead of validation
    // rejecting the boundary values themselves - which is exactly what this test pins:
    // the failure (if any) must be a CONNECT failure, never a "must be"/"must not" from
    // validateClientConfig.
    Client|error result = new ({
        host: "localhost",
        systemId: "s",
        password: "p",
        port: CLIENT_CONFIG_BOUNDARY_PORT,
        bindTimeout: 1,
        transactionTimeout: 300,
        enquireLinkInterval: 3600
    });
    test:assertTrue(result is error, "nothing listens on this port - connect must fail");
    if result is error {
        test:assertFalse(result.message().includes("must be") || result.message().includes("must not"),
                string `boundary-valid fields must not be rejected by validation: ${result.message()}`);
        test:assertTrue(result.message().includes("failed to"),
                string `must fail at connect, not at validation: ${result.message()}`);
    }
}
