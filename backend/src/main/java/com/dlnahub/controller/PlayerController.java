package com.dlnahub.controller;

import com.dlnahub.dlna.Renderer;
import com.dlnahub.dlna.RendererDiscoveryManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final RendererDiscoveryManager rendererDiscoveryManager;

    @Autowired
    public PlayerController(RendererDiscoveryManager rendererDiscoveryManager) {
        this.rendererDiscoveryManager = rendererDiscoveryManager;
    }

    @GetMapping
    public ResponseEntity<List<Renderer>> getPlayers() {
        Set<Renderer> renderers = rendererDiscoveryManager.getDiscoveredRenderers();
        return ResponseEntity.ok(List.copyOf(renderers));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Renderer> getPlayer(@PathVariable String id) {
        Renderer renderer = rendererDiscoveryManager.getRenderer(id);
        if (renderer == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(renderer);
    }
}
