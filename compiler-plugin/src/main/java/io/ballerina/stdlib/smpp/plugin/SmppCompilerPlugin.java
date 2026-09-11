/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

import io.ballerina.projects.plugins.CompilerPlugin;
import io.ballerina.projects.plugins.CompilerPluginContext;

import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_DATA_SM;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_DELIVER_SM;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_ERROR;

/**
 * The smpp compiler plugin: compile-time service-shape validation plus handler code
 * actions. Runs in every consumer project that imports {@code ballerina/smpp} (the jar
 * ships inside the bala); it never runs while building this package itself.
 *
 * <p>The listener recognizes three handler methods, so the action set is
 * {@code onDeliverSm} ± caller, {@code onDataSm} ± caller, and {@code onError}. All
 * anchor on {@code SMPP_101} — the "service implements no smpp handler" diagnostic an
 * empty {@code service on smppListener {}} lands on, which is exactly the moment a user
 * discovers the opt-in Caller parameter through their editor rather than the docs.
 */
public class SmppCompilerPlugin extends CompilerPlugin {

    @Override
    public void init(CompilerPluginContext context) {
        context.addCodeAnalyzer(new SmppServiceAnalyzer());
        context.addCodeAction(new HandlerTemplateCodeAction(ON_DELIVER_SM, false));
        context.addCodeAction(new HandlerTemplateCodeAction(ON_DELIVER_SM, true));
        context.addCodeAction(new HandlerTemplateCodeAction(ON_DATA_SM, false));
        context.addCodeAction(new HandlerTemplateCodeAction(ON_DATA_SM, true));
        context.addCodeAction(new HandlerTemplateCodeAction(ON_ERROR, false));
    }
}
