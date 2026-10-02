/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.smpp;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;

import java.util.Map;

/**
 * Captures the {@code ballerina/smpp} module reference at module init time so the native
 * client and listener glue can create {@code smpp:Sms}, {@code smpp:SubmitResult}, and
 * other module-qualified record values, and {@code smpp:Error} values.
 *
 * <p>Shared by both the {@code client} and {@code listener} native packages — neither owns
 * this on its own, since both need to create the same module-qualified values.
 */
public final class ModuleUtils {

    // Must match the distinct error type name declared in types.bal
    // (`public type Error distinct error<ErrorDetail>;`).
    private static final String ERROR_TYPE_NAME = "Error";

    // volatile: written once from the module-init strand, read from jsmpp reader/pool
    // threads and the rebind executor. The happens-before edges exist incidentally today;
    // volatile makes the publication explicit and free (concurrency review, minor).
    private static volatile Module smppModule;

    private ModuleUtils() {
    }

    /** Invoked from the module {@code init()} function via Java interop. */
    public static void setModule(Environment env) {
        smppModule = env.getCurrentModule();
    }

    public static Module getModule() {
        return smppModule;
    }

    /**
     * Creates a module-qualified {@code smpp:Error} carrying {@code message}, so every
     * error this connector's native layer returns is typed - callers can match it with
     * {@code err is smpp:Error} instead of getting a generic, unqualified {@code error}.
     *
     * @param message the error message
     * @return a {@code smpp:Error} value
     */
    public static BError createError(String message) {
        return ErrorCreator.createDistinctError(ERROR_TYPE_NAME, smppModule, StringUtils.fromString(message));
    }

    /**
     * As {@link #createError(String)}, additionally carrying an {@code ErrorDetail}
     * record (failureMode / commandStatus / possiblySubmitted - all-optional fields of
     * the open detail record in types.bal). The submit-family operations use this so
     * callers can branch retry logic on {@code e.detail().failureMode}; every other error
     * site keeps the no-detail overload.
     *
     * @param message the error message
     * @param detail detail entries; {@code String} values become {@code BString},
     *     {@code null} values are skipped
     * @return a {@code smpp:Error} value with a populated detail record
     */
    public static BError createError(String message, Map<String, Object> detail) {
        BMap<BString, Object> d = ValueCreator.createRecordValue(smppModule, "ErrorDetail");
        for (Map.Entry<String, Object> e : detail.entrySet()) {
            if (e.getValue() != null) {
                Object v = e.getValue() instanceof String s ? StringUtils.fromString(s) : e.getValue();
                d.put(StringUtils.fromString(e.getKey()), v);
            }
        }
        return ErrorCreator.createDistinctError(ERROR_TYPE_NAME, smppModule,
                StringUtils.fromString(message), d);
    }
}
