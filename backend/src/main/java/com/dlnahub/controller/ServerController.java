package com.dlnahub.controller;

import com.dlnahub.dlna.DiscoveryManager;
import com.dlnahub.dlna.MediaServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final DiscoveryManager discoveryManager;

    @Autowired
    public ServerController(DiscoveryManager discoveryManager) {
        this.discoveryManager = discoveryManager;
    }

    @GetMapping
    public ResponseEntity<List<MediaServer>> getServers() {
        Set<MediaServer> servers = discoveryManager.getDiscoveredServers();
        return ResponseEntity.ok(List.copyOf(servers));
    }

    @PostMapping("/{id}/subscribe")
    public ResponseEntity<Map<String, Boolean>> subscribe(@PathVariable String id) {
        discoveryManager.subscribe(id);
        return ResponseEntity.ok(Map.of("subscribed", true));
    }

    @DeleteMapping("/{id}/unsubscribe")
    public ResponseEntity<Map<String, Boolean>> unsubscribe(@PathVariable String id) {
        discoveryManager.unsubscribe(id);
        return ResponseEntity.ok(Map.of("unsubscribed", true));
    }
}
