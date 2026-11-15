package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.mail.config.MailProperties;
import com.enterprise.outlook.mail.dto.Dtos.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full-text search over OpenSearch. Tenant isolation: documents are routed by tenantId AND every query carries
 * mandatory tenant_id + mailbox_id filters (never user-controlled).
 */
@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX = "mail-messages";
    private final RestClient http;
    private volatile boolean indexReady;

    public SearchService(MailProperties props) { this.http = RestClient.builder().baseUrl(props.opensearchUrl()).build(); }

    private void ensureIndex() {
        if (indexReady) return;
        try {
            http.put().uri("/" + INDEX).contentType(MediaType.APPLICATION_JSON).body("""
                    {"settings":{"number_of_shards":3,"number_of_replicas":0},
                     "mappings":{"properties":{"tenantId":{"type":"keyword"},"mailboxId":{"type":"keyword"},"messageId":{"type":"keyword"},
                       "folderId":{"type":"keyword"},"subject":{"type":"text"},"sender":{"type":"keyword"},"snippet":{"type":"text"},
                       "body":{"type":"text"},"attachments":{"type":"text"},"receivedAt":{"type":"date"}}}}""").retrieve().toBodilessEntity();
        } catch (Exception e) { /* already exists */ }
        indexReady = true;
    }

    public void index(String docJson) {
        ensureIndex();
        JsonNode d = Json.read(docJson, JsonNode.class);
        String id = d.path("mailboxId").asText() + "_" + d.path("messageId").asText();
        http.put().uri("/" + INDEX + "/_doc/" + id + "?routing=" + d.path("tenantId").asText())
                .contentType(MediaType.APPLICATION_JSON).body(docJson).retrieve().toBodilessEntity();
    }

    public List<SearchHit> search(UUID tenant, UUID mailbox, String q, int limit) {
        ensureIndex();
        Map<String, Object> query = Map.of("size", Math.min(limit, 50), "sort", List.of(Map.of("receivedAt", "desc")),
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("tenantId", tenant.toString())), Map.of("term", Map.of("mailboxId", mailbox.toString()))),
                        "must", List.of(Map.of("multi_match", Map.of("query", q, "fields", List.of("subject^3", "body", "snippet", "attachments", "sender")))))));
        JsonNode res = http.post().uri("/" + INDEX + "/_search?routing=" + tenant).contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(query)).retrieve().body(JsonNode.class);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode h : res.path("hits").path("hits")) {
            JsonNode s = h.path("_source");
            hits.add(new SearchHit(UUID.fromString(s.path("messageId").asText()), s.path("subject").asText(), s.path("sender").asText(),
                    s.path("snippet").asText(), Instant.parse(s.path("receivedAt").asText())));
        }
        return hits;
    }
}
