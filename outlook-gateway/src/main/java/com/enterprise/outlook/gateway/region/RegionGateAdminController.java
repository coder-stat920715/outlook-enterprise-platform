package com.enterprise.outlook.gateway.region;

import com.enterprise.outlook.common.model.Region;
import com.enterprise.outlook.gateway.GatewayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Operator API for regional drain / restore. Protected by X-Admin-Key (network-restrict this path in production). */
@RestController
@RequestMapping("/gateway-admin/regions")
public class RegionGateAdminController {
    public record GateRequest(String state, Integer percent) {}
    public record RampRequest(List<Integer> steps, Integer intervalSeconds) {}

    private final RegionGateService gates;
    private final GatewayProperties props;

    public RegionGateAdminController(RegionGateService gates, GatewayProperties props) { this.gates = gates; this.props = props; }

    private void auth(String key) {
        if (!props.adminKey().equals(key)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad admin key");
    }

    @GetMapping
    public Mono<Map<Region, RegionGateService.Gate>> list(@RequestHeader("X-Admin-Key") String key) { auth(key); return gates.all(); }

    @PutMapping("/{region}")
    public Mono<RegionGateService.Gate> set(@RequestHeader("X-Admin-Key") String key, @PathVariable Region region, @RequestBody GateRequest body) {
        auth(key);
        RegionGateService.Gate g = "RAMPING".equals(body.state())
                ? new RegionGateService.Gate("RAMPING", body.percent() == null ? 5 : body.percent())
                : RegionGateService.Gate.parse(body.state());
        return gates.set(region, g);
    }

    /** Starts an asynchronous staged restore e.g. {"steps":[5,25,50,100],"intervalSeconds":60}. */
    @PostMapping("/{region}/ramp")
    public Mono<Map<String, Object>> ramp(@RequestHeader("X-Admin-Key") String key, @PathVariable Region region, @RequestBody RampRequest body) {
        auth(key);
        int[] steps = body.steps().stream().mapToInt(Integer::intValue).toArray();
        gates.ramp(region, steps, Duration.ofSeconds(body.intervalSeconds() == null ? 60 : body.intervalSeconds())).subscribe();
        return Mono.just(Map.of("region", region, "steps", body.steps(), "status", "RAMP_STARTED"));
    }
}
