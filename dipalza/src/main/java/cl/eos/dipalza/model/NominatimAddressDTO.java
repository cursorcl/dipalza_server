package cl.eos.dipalza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimAddressDTO(String road, String pedestrian, String footway, String cycleway) {
}
