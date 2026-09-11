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

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.ServiceDeclarationSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.ClassDefinitionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.diagnostics.Location;

import java.util.List;
import java.util.Optional;

import static io.ballerina.stdlib.smpp.plugin.PluginConstants.CALLER_TYPE;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_DATA_SM;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_DELIVER_SM;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.ON_ERROR;
import static io.ballerina.stdlib.smpp.plugin.PluginConstants.SMS_TYPE;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.diagnostic;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.involvesCaller;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.isErrorOrNilReturn;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.isErrorType;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.isSmppModule;
import static io.ballerina.stdlib.smpp.plugin.PluginUtils.isSmppType;

/**
 * Validates smpp service shapes at compile time, mirroring the Listener's attach-time
 * contract: a service must implement at least one of {@code onDeliverSm}, {@code
 * onDataSm}, or {@code onError} as a {@code remote} method with a recognized parameter
 * shape. Two syntactic forms are analyzed:
 *
 * <ul>
 *   <li>{@code service on smppListener { ... }} — the direct-declaration form used by
 *       every quickstart example;</li>
 *   <li>{@code service class X { *smpp:Service; ... }} — a reusable handler attached
 *       explicitly via {@code listener.attach(new X())}.</li>
 * </ul>
 *
 * <p>Severity discipline: everything the runtime rejects at attach is an ERROR here
 * too (same outcome, earlier). A typo'd method name and a missing {@code remote}
 * qualifier are also ERRORs: since the listener dispatches strictly against {@code
 * getRemoteMethods()}, a non-remote handler is not merely un-validated — it is a
 * program that looks correct but silently never receives a callback, and a
 * compile-time error at the exact line (with a one-word fix) is the honest way to
 * surface that. Isolation is a WARNING: legal, but it silently serializes all dispatch
 * on the runtime's process-wide lock.
 */
public class SmppServiceValidator implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        // Bail out on existing compile errors: symbols may be broken.
        for (Diagnostic diagnostic : context.semanticModel().diagnostics()) {
            if (diagnostic.diagnosticInfo().severity() == DiagnosticSeverity.ERROR) {
                return;
            }
        }
        if (context.node().kind() == SyntaxKind.SERVICE_DECLARATION) {
            validateServiceDeclaration(context);
        } else if (context.node().kind() == SyntaxKind.CLASS_DEFINITION) {
            validateServiceClass(context);
        }
    }

    private void validateServiceDeclaration(SyntaxNodeAnalysisContext context) {
        ServiceDeclarationNode node = (ServiceDeclarationNode) context.node();
        Optional<Symbol> symbol = context.semanticModel().symbol(node);
        if (symbol.isEmpty() || !(symbol.get() instanceof ServiceDeclarationSymbol serviceSymbol)) {
            return;
        }
        if (!attachedToSmppListener(serviceSymbol)) {
            return;
        }
        validateMembers(context, node.members(), node.location(), context.semanticModel());
    }

    private void validateServiceClass(SyntaxNodeAnalysisContext context) {
        ClassDefinitionNode node = (ClassDefinitionNode) context.node();
        Optional<Symbol> symbol = context.semanticModel().symbol(node);
        if (symbol.isEmpty() || !(symbol.get() instanceof ClassSymbol classSymbol)) {
            return;
        }
        // Only classes that opt in via `*smpp:Service` — anything else is not ours to
        // judge, however smpp-ish its method names look. Known fail-open limitation
        // (shared with other connectors' plugins): a TRANSITIVE inclusion
        // (`service class B { *A; }` where A includes *smpp:Service) is not resolved
        // through the included object's own inclusions, so B gets no compile-time
        // validation - the runtime still validates it at attach, so the failure
        // direction is a missing early diagnostic, never a false acceptance.
        boolean includesSmppService = false;
        for (TypeSymbol inclusion : classSymbol.typeInclusions()) {
            if (isSmppType(inclusion, PluginConstants.SERVICE_TYPE)) {
                includesSmppService = true;
                break;
            }
        }
        if (!includesSmppService) {
            return;
        }
        validateMembers(context, node.members(), node.location(), context.semanticModel());
    }

    private boolean attachedToSmppListener(ServiceDeclarationSymbol serviceSymbol) {
        for (TypeSymbol listener : serviceSymbol.listenerTypes()) {
            if (listener.typeKind() == TypeDescKind.UNION) {
                for (TypeSymbol member : ((UnionTypeSymbol) listener).memberTypeDescriptors()) {
                    Optional<ModuleSymbol> module = member.getModule();
                    if (module.isPresent() && isSmppModule(module.get())) {
                        return true;
                    }
                }
            } else {
                Optional<ModuleSymbol> module = listener.getModule();
                if (module.isPresent() && isSmppModule(module.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateMembers(SyntaxNodeAnalysisContext context, Iterable<? extends Node> members,
                                 Location serviceLocation, SemanticModel semanticModel) {
        boolean anyRecognizedRemote = false;
        for (Node member : members) {
            if (!(member instanceof FunctionDefinitionNode function)) {
                continue;
            }
            Optional<Symbol> symbol = semanticModel.symbol(function);
            if (symbol.isEmpty() || !(symbol.get() instanceof MethodSymbol method)) {
                continue;
            }
            String name = PluginUtils.nameOf(method);
            boolean recognizedName = ON_DELIVER_SM.equals(name) || ON_DATA_SM.equals(name)
                    || ON_ERROR.equals(name);

            if (function.kind() == SyntaxKind.RESOURCE_ACCESSOR_DEFINITION) {
                // Load-bearing: resource methods are structurally invisible to attach.
                context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_104,
                        function.location(), name));
                continue;
            }
            boolean isRemote = method.qualifiers().contains(Qualifier.REMOTE);
            if (!isRemote) {
                if (recognizedName) {
                    // Load-bearing: this exact shape compiles but the listener's
                    // attach-time dispatch resolution can never see it, since it
                    // enumerates only remote methods.
                    context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_103,
                            function.location(), name));
                }
                // A non-remote, non-recognized method is an ordinary private helper.
                continue;
            }
            if (!recognizedName) {
                // Load-bearing: the runtime silently ignores it - the typo case.
                context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_102,
                        function.location(), name));
                continue;
            }
            anyRecognizedRemote = true;
            validateSignature(context, function, method, name);
        }
        if (!anyRecognizedRemote) {
            // Mirrors attach's "no remote methods" rule; also the code-action anchor
            // for the handler templates (an empty `service on smppListener {}` lands
            // here).
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_101, serviceLocation));
        }
    }

    private void validateSignature(SyntaxNodeAnalysisContext context, FunctionDefinitionNode function,
                                   MethodSymbol method, String name) {
        FunctionTypeSymbol fnType = method.typeDescriptor();

        // Return type: never inspected at runtime, so this check is load-bearing.
        Optional<TypeSymbol> returnType = fnType.returnTypeDescriptor();
        if (returnType.isPresent() && !isErrorOrNilReturn(returnType.get())) {
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_105, function.location(), name));
        }

        // Rest parameter: rejected for all three methods.
        if (fnType.restParam().isPresent()) {
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_106, function.location(), name));
        }

        // Isolation applies to ALL THREE methods - the runtime derives onError's
        // strand isolation the same way, so a non-isolated onError serializes on the
        // same process-wide lock. Note qualifiers() reflects isolated INFERENCE, so
        // this only fires when the body genuinely defeats it - i.e. when the trap is
        // real.
        if (!method.qualifiers().contains(Qualifier.ISOLATED)) {
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_112, function.location(), name));
        }

        List<ParameterSymbol> params = fnType.params().orElse(List.of());
        if (ON_ERROR.equals(name)) {
            validateOnError(context, function, params);
            return;
        }

        // onDeliverSm / onDataSm: identical rules.
        boolean sawSms = false;
        boolean sawCaller = false;
        for (ParameterSymbol param : params) {
            String paramName = param.getName().orElse("?");
            TypeSymbol type = param.typeDescriptor();
            boolean defaulted = param.paramKind() == ParameterKind.DEFAULTABLE;
            if (isSmppType(type, CALLER_TYPE)) {
                if (defaulted) {
                    // A defaultable Caller would let dispatch silently skip the
                    // user's reply path.
                    context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_108,
                            function.location(), paramName));
                } else if (sawCaller) {
                    context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_109,
                            function.location(), name, "smpp:Caller"));
                } else {
                    sawCaller = true;
                }
            } else if (isSmppType(type, SMS_TYPE)) {
                // A defaultable Sms is accepted - type match precedes the skip.
                if (sawSms) {
                    context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_109,
                            function.location(), name, "smpp:Sms"));
                } else {
                    sawSms = true;
                }
            } else if (involvesCaller(type)) {
                // Checked BEFORE the defaultable skip: `smpp:Caller? c = ()` must be
                // rejected loudly, never silently skipped into nil.
                context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_108,
                        function.location(), paramName));
            } else if (defaulted) {
                // Trailing defaulted params of any other type are SKIPPED, not
                // rejected - `(Sms, string extra = "x")` is a legal, working program.
                // A "too many parameters" rule here would be a breaking regression
                // (pinned by the valid_shapes_class fixture).
                continue;
            } else {
                context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_107,
                        function.location(), paramName));
            }
        }
        if (!sawSms) {
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_110, function.location(), name));
        }
    }

    private void validateOnError(SyntaxNodeAnalysisContext context, FunctionDefinitionNode function,
                                 List<ParameterSymbol> params) {
        int required = 0;
        boolean bad = false;
        String detail = "";
        for (ParameterSymbol param : params) {
            TypeSymbol type = param.typeDescriptor();
            // Anything involving Caller - plain, optional, union, or defaulted - is
            // rejected. onError is 1-arity by design.
            if (involvesCaller(type)) {
                bad = true;
                detail = "it must not declare an smpp:Caller parameter (in any form)";
                break;
            }
            if (param.paramKind() != ParameterKind.DEFAULTABLE) {
                required++;
                if (!isErrorType(type)) {
                    bad = true;
                    detail = "parameter '" + param.getName().orElse("?")
                            + "' must be an error type (note: 'error?' is a union and is not accepted)";
                    break;
                }
            }
        }
        if (!bad && required != 1) {
            bad = true;
            detail = "found " + required + " required parameter(s)";
        }
        if (bad) {
            context.reportDiagnostic(diagnostic(SmppDiagnostic.SMPP_111, function.location(), detail));
        }
    }
}
