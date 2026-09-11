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

import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.CodeAnalysisContext;
import io.ballerina.projects.plugins.CodeAnalyzer;

import java.util.List;

/**
 * Registers {@link SmppServiceValidator} for BOTH syntactic forms an smpp service can
 * take. Registering only {@code SERVICE_DECLARATION} (as some other connectors' plugins
 * do) would leave the {@code service class X { *smpp:Service; }} + explicit-{@code
 * attach} idiom — the reusable-handler form this module's own test suite is expected to
 * use — completely unvalidated.
 */
public class SmppServiceAnalyzer extends CodeAnalyzer {

    @Override
    public void init(CodeAnalysisContext codeAnalysisContext) {
        codeAnalysisContext.addSyntaxNodeAnalysisTask(new SmppServiceValidator(),
                List.of(SyntaxKind.SERVICE_DECLARATION, SyntaxKind.CLASS_DEFINITION));
    }
}
