package be.selckin.nexus.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Repository(String name, String format, String type, String url) {
}
