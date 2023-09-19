/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.processor.mutateevent;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class DeleteEntryProcessorConfig {
    @NotEmpty
    @NotNull
    @JsonProperty("with_keys")
    private String[] withKeys;
    @JsonProperty("with_keys_except")
    private List<String> withKeysExcept;

    @JsonProperty("delete_when")
    private String deleteWhen;

    public String[] getWithKeys() {
        return withKeys;
    }

    public List<String> getWithKeysExcept() {
        return withKeysExcept;
    }

    public String getDeleteWhen() {
        return deleteWhen;
    }
}
