package com.worldmind.dispatch.api;

import com.worldmind.core.model.StargateInfo;
import com.worldmind.stargate.StargateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for Stargate container information.
 */
@RestController
@RequestMapping("/api/v1/stargates")
public class StargateController {

    private static final Logger log = LoggerFactory.getLogger(StargateController.class);

    private final StargateManager stargateManager;

    public StargateController(StargateManager stargateManager) {
        this.stargateManager = stargateManager;
    }

    /**
     * GET /api/v1/stargates — List active stargates.
     * Returns an empty list since StargateManager does not currently track active containers.
     * This endpoint will be enriched when container lifecycle tracking is added.
     */
    @GetMapping
    public List<StargateInfo> listStargates() {
        log.debug("Listing active stargates");
        return List.of();
    }
}
