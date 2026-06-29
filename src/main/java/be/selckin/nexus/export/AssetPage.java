package be.selckin.nexus.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssetPage(List<Asset> items, String continuationToken) {
}
