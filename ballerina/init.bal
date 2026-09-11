// Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
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
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
// either express or implied. See the License for the specific
// language governing permissions and limitations under the License.

// Captures the module reference at module-init time, before any Client or Listener can be
// constructed, so the native layer can create module-qualified `smpp:Sms`/`smpp:Error`
// values. Without this, ModuleUtils.getModule() returns null and every native call that
// needs it (Listener bind, Client bind, error creation) fails with a NullPointerException.
import ballerina/jballerina.java;

function setModule() = @java:Method {
    'class: "io.ballerina.stdlib.smpp.ModuleUtils",
    name: "setModule"
} external;

function init() {
    setModule();
}
