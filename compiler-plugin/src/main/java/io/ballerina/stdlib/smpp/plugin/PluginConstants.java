/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ballerina.stdlib.smpp.plugin;

/**
 * Names the plugin validates against. The service-shape contract itself lives in
 * {@link SmppServiceValidator}; it MUST mirror the runtime's attach-time validation in the
 * native {@code Dispatcher} (the {@code listener} native subpackage) — any change there
 * needs a matching change here (and vice versa; the fixture tests pin the parity).
 */
public final class PluginConstants {

    private PluginConstants() {
    }

    public static final String PACKAGE_ORG = "ballerina";
    public static final String PACKAGE_NAME = "smpp";

    public static final String ON_DELIVER_SM = "onDeliverSm";
    public static final String ON_DATA_SM = "onDataSm";
    public static final String ON_ERROR = "onError";

    public static final String SMS_TYPE = "Sms";
    public static final String CALLER_TYPE = "Caller";
    public static final String LISTENER_TYPE = "Listener";
    public static final String SERVICE_TYPE = "Service";

    public static final String NODE_LOCATION = "node.location";
    public static final String LS = System.lineSeparator();
}
