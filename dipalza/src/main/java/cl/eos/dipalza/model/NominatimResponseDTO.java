package cl.eos.dipalza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimResponseDTO(
        NominatimAddressDTO address,
        @JsonProperty("display_name") String displayName) {
}
