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
 *
 */
package io.ballerina.stdlib.smpp.plugin;

import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.tools.diagnostics.Diagnostic;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * End-to-end diagnostic tests for the SMPP compiler plugin: each {@code sample_package_N}
 * is a tiny Ballerina package loaded through the real compiler front-end
 * ({@link BuildProject}), and the {@code SMPP_}-prefixed diagnostics it produces are
 * asserted exactly - both the codes present AND that no other {@code SMPP_} code leaked in.
 * Mirrors the convention used by module-ballerina-email/module-ballerinax-kafka's own
 * {@code compiler-plugin-tests} modules (TestNG + {@code io.ballerina.projects} APIs, no
 * Docker involved).
 */
public class CompilerPluginTest {

    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources", "ballerina_sources")
            .toAbsolutePath();
    private static final Path DISTRIBUTION_PATH = resolveBallerinaHome();

    // sample_package_1  - valid_declaration:     the canonical bare-declaration reply service
    // sample_package_2  - valid_shapes_class:    service-class + explicit-attach idiom
    // sample_package_3  - valid_templates:       the five code-action templates, verbatim
    // sample_package_4  - empty_service:         implements nothing
    // sample_package_5  - inline_new_recognized: inline `new` listener, recognition pin
    // sample_package_6  - missing_remote:        handler missing the `remote` qualifier
    // sample_package_7  - typo_method_name:      onDeliverSM (typo'd casing)
    // sample_package_8  - resource_and_return:   a resource method + a bad return type
    // sample_package_9  - rest_and_bad_param:    a rest parameter + an unsupported parameter type
    // sample_package_10 - caller_shapes_bad:     every rejected smpp:Caller parameter shape
    // sample_package_11 - onerror_bad:           onError declared with a non-error parameter type
    // sample_package_12 - non_isolated:          legal but non-isolated; warns, does not error

    @Test
    public void testValidDeclaration() {
        assertSmppDiagnostics("sample_package_1");
    }

    @Test
    public void testValidShapesClass() {
        assertSmppDiagnostics("sample_package_2");
    }

    @Test
    public void testValidTemplates() {
        assertSmppDiagnostics("sample_package_3");
    }

    @Test
    public void testEmptyService() {
        assertSmppDiagnostics("sample_package_4", "SMPP_101");
    }

    @Test
    public void testInlineNewRecognized() {
        assertSmppDiagnostics("sample_package_5", "SMPP_101");
    }

    @Test
    public void testMissingRemote() {
        assertSmppDiagnostics("sample_package_6", "SMPP_101", "SMPP_103");
    }

    @Test
    public void testTypoMethodName() {
        assertSmppDiagnostics("sample_package_7", "SMPP_101", "SMPP_102");
    }

    @Test
    public void testResourceAndReturn() {
        assertSmppDiagnostics("sample_package_8", "SMPP_104", "SMPP_105");
    }

    @Test
    public void testRestAndBadParam() {
        assertSmppDiagnostics("sample_package_9", "SMPP_106", "SMPP_107");
    }

    @Test
    public void testCallerShapesBad() {
        assertSmppDiagnostics("sample_package_10", "SMPP_108", "SMPP_109", "SMPP_110");
    }

    @Test
    public void testOnErrorBad() {
        assertSmppDiagnostics("sample_package_11", "SMPP_111");
    }

    @Test
    public void testNonIsolated() {
        // Legal - the build must still succeed - but dispatch holds the runtime's
        // process-wide lock for the whole handler, hence a WARNING (not an ERROR).
        assertSmppDiagnostics("sample_package_12", "SMPP_112");
    }

    /**
     * Loads {@code sampleDir}, compiles it, and asserts that the exact set of
     * {@code SMPP_}-prefixed diagnostics produced equals {@code expectedCodes} - no fewer,
     * no extra. An empty {@code expectedCodes} asserts the package produces NO {@code SMPP_}
     * diagnostics at all.
     *
     * @param sampleDir the sample package directory name under {@code ballerina_sources}
     * @param expectedCodes the exact set of expected {@code SMPP_*} diagnostic codes
     */
    private void assertSmppDiagnostics(String sampleDir, String... expectedCodes) {
        Package currentPackage = loadPackage(sampleDir);
        PackageCompilation compilation = currentPackage.getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();

        List<Diagnostic> smppDiagnostics = diagnosticResult.diagnostics().stream()
                .filter(d -> d.diagnosticInfo().code().startsWith("SMPP_"))
                .collect(Collectors.toList());

        Set<String> actualCodes = smppDiagnostics.stream()
                .map(d -> d.diagnosticInfo().code())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = new TreeSet<>(List.of(expectedCodes));

        Assert.assertEquals(actualCodes, expected,
                "SMPP_* diagnostics for " + sampleDir + " did not match. Full diagnostics: "
                        + smppDiagnostics.stream().map(Object::toString).collect(Collectors.joining("; ")));
    }

    private Package loadPackage(String path) {
        Path projectDirPath = RESOURCE_DIRECTORY.resolve(path);
        BuildProject project = BuildProject.load(getEnvironmentBuilder(), projectDirPath);
        return project.currentPackage();
    }

    private ProjectEnvironmentBuilder getEnvironmentBuilder() {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        return ProjectEnvironmentBuilder.getBuilder(environment);
    }

    /**
     * Resolves the local Ballerina distribution root: {@code BALLERINA_HOME} if set,
     * otherwise {@code bal home}'s own answer. Unlike module-ballerina-email (which
     * extracts a {@code jballerina-tools} Maven artifact into {@code target/ballerina-runtime}
     * via the root build), this repository already requires a real {@code bal} on PATH for
     * its examples/CI (see {@code .github/workflows/examples.yml}'s
     * {@code setup-ballerina} step), so reusing that installation avoids a second,
     * redundant distribution-provisioning mechanism.
     *
     * @return the resolved distribution root
     */
    private static Path resolveBallerinaHome() {
        String fromEnv = System.getenv("BALLERINA_HOME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv).toAbsolutePath();
        }
        try {
            Process process = new ProcessBuilder("bal", "home").redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output == null || output.isBlank()) {
                throw new IllegalStateException("'bal home' failed with exit code " + exitCode);
            }
            return Paths.get(output.trim()).toAbsolutePath();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not resolve a Ballerina distribution: set BALLERINA_HOME, "
                    + "or ensure 'bal' is on PATH", e);
        }
    }
}
