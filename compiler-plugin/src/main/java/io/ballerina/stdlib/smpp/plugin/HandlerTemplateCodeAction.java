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

import io.ballerina.compiler.syntax.tree.ClassDefinitionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.projects.plugins.codeaction.CodeAction;
import io.ballerina.projects.plugins.codeaction.CodeActionArgument;
import io.ballerina.projects.plugins.codeaction.CodeActionContext;
import io.ballerina.projects.plugins.codeaction.CodeActionExecutionContext;
import io.ballerina.projects.plugins.codeaction.CodeActionInfo;
import io.ballerina.projects.plugins.codeaction.DocumentEdit;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.text.LineRange;
import io.ballerina.tools.text.TextDocumentChange;
import io.ballerina.tools.text.TextEdit;
import io.ballerina.tools.text.TextRange;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.ballerina.stdlib.smpp.plugin.PluginConstants.LS;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.NODE_LOCATION;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_ERROR;

/**
 * One configurable action per handler variant (five registrations — see
 * {@link SmppCompilerPlugin}), anchored on {@code SMPP_101}. Template bodies are
 * derived from the current {@code types.bal} surface: the caller variant demonstrates
 * a {@code TextSms} reply literal, which is how a user discovers both the opt-in
 * parameter and the outbound-message shape from their editor.
 */
public class HandlerTemplateCodeAction implements CodeAction {

    private final String method;
    private final boolean withCaller;

    public HandlerTemplateCodeAction(String method, boolean withCaller) {
        this.method = method;
        this.withCaller = withCaller;
    }

    @Override
    public List<String> supportedDiagnosticCodes() {
        return List.of(SmppDiagnostic.SMPP_101.code());
    }

    @Override
    public Optional<CodeActionInfo> codeActionInfo(CodeActionContext context) {
        Diagnostic diagnostic = context.diagnostic();
        if (diagnostic.location() == null) {
            return Optional.empty();
        }
        CodeActionArgument location =
                CodeActionArgument.from(NODE_LOCATION, diagnostic.location().lineRange());
        String label = ON_ERROR.equals(method)
                ? "Insert onError handler"
                : "Insert " + method + " handler" + (withCaller ? " (with caller, for replies)" : "");
        return Optional.of(CodeActionInfo.from(label, List.of(location)));
    }

    @Override
    public List<DocumentEdit> execute(CodeActionExecutionContext context) {
        LineRange lineRange = null;
        for (CodeActionArgument argument : context.arguments()) {
            if (NODE_LOCATION.equals(argument.key())) {
                lineRange = argument.valueAs(LineRange.class);
            }
        }
        if (lineRange == null) {
            return Collections.emptyList();
        }
        SyntaxTree syntaxTree = context.currentDocument().syntaxTree();
        NonTerminalNode node = PluginUtils.findNode(syntaxTree, lineRange);

        // SMPP_101 is reported on both syntactic forms; insert before whichever close
        // brace applies.
        NodeList<Node> members;
        Token closeBrace;
        Token openBrace;
        if (node instanceof ServiceDeclarationNode service) {
            members = service.members();
            openBrace = service.openBraceToken();
            closeBrace = service.closeBraceToken();
        } else if (node instanceof ClassDefinitionNode clazz) {
            members = clazz.members();
            openBrace = clazz.openBrace();
            closeBrace = clazz.closeBrace();
        } else {
            return Collections.emptyList();
        }

        TextRange insertRange;
        if (members.isEmpty()) {
            insertRange = TextRange.from(openBrace.textRange().endOffset(),
                    closeBrace.textRange().startOffset() - openBrace.textRange().endOffset());
        } else {
            Node last = members.get(members.size() - 1);
            insertRange = TextRange.from(last.textRange().endOffset(),
                    closeBrace.textRange().startOffset() - last.textRange().endOffset());
        }
        TextEdit edit = TextEdit.from(insertRange, template());
        TextDocumentChange change = TextDocumentChange.from(new TextEdit[] {edit});
        return Collections.singletonList(
                new DocumentEdit(context.fileUri(), SyntaxTree.from(syntaxTree, change)));
    }

    private String template() {
        if (ON_ERROR.equals(method)) {
            return LS + "\tremote function onError(smpp:Error smppError) returns error? {" + LS
                    + LS + "\t}" + LS;
        }
        if (withCaller) {
            // The reply text is a non-empty placeholder ON PURPOSE: an empty
            // shortMessage is rejected at submit, so an "" here would teach a pattern
            // that compiles and then fails at runtime. `_ =` avoids handing the user
            // an unused-variable warning along with their new handler.
            return LS + "\tremote function " + method
                    + "(smpp:Sms sms, smpp:Caller caller) returns error? {" + LS
                    + "\t\tsmpp:SubmitResult _ = check caller->submit({" + LS
                    + "\t\t\tdestinationAddress: sms.sourceAddress," + LS
                    + "\t\t\tshortMessage: \"TODO: reply\"" + LS
                    + "\t\t});" + LS
                    + "\t}" + LS;
        }
        return LS + "\tremote function " + method + "(smpp:Sms sms) returns error? {" + LS
                + LS + "\t}" + LS;
    }

    @Override
    public String name() {
        return "ADD_" + method.toUpperCase(Locale.ROOT)
                + (withCaller ? "_WITH_CALLER" : "") + "_CODE_ACTION";
    }
}
