package be.selckin.nexus.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Checksum(String sha1, String md5, String sha256) {
}
