package xy.mailsenders.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xy.mailsenders.service.MetricsService;

import java.util.List;
import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        return metricsService.getSummary();
    }

    @GetMapping("/campaigns")
    public List<Map<String, Object>> getCampaigns() {
        return metricsService.getCampaigns();
    }
}
