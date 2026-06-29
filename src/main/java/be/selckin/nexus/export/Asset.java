package be.selckin.nexus.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Asset(String id, String path, String downloadUrl, Checksum checksum, long fileSize) {
}
