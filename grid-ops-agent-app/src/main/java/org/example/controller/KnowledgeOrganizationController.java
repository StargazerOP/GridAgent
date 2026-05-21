package org.example.controller;

import org.example.agent.skill.service.SkillRegistry;
import org.example.knowledge.KnowledgeOrgMatch;
import org.example.knowledge.KnowledgeOrganizationService;
import org.example.service.KnowledgeBaseService;
import org.example.tool.ToolRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-org")
public class KnowledgeOrganizationController {

    private final KnowledgeOrganizationService knowledgeOrganizationService;

    @Autowired(required = false)
    private SkillRegistry skillRegistry;

    @Autowired(required = false)
    private ToolRegistryService toolRegistryService;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    public KnowledgeOrganizationController(KnowledgeOrganizationService knowledgeOrganizationService) {
        this.knowledgeOrganizationService = knowledgeOrganizationService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> response = new LinkedHashMap<>(knowledgeOrganizationService.overview());
        if (skillRegistry != null) {
            response.put("skills", skillRegistry.getEnabledSkills().size());
        }
        if (toolRegistryService != null) {
            response.put("tools", toolRegistryService.getAllTools().size());
        }
        if (knowledgeBaseService != null) {
            try {
                response.put("documents", knowledgeBaseService.listDocuments(null, 1, 1000).size());
            } catch (Exception e) {
                response.put("documents", "unavailable");
            }
        }
        return response;
    }

    @GetMapping("/templates")
    public Map<String, Object> templates() {
        return Map.of(
                "templates", knowledgeOrganizationService.templates(),
                "total", knowledgeOrganizationService.templates().size()
        );
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam(value = "category", required = false) String category,
                                     @RequestParam(value = "q", required = false) String query) {
        return knowledgeOrganizationService.graph(category, query);
    }

    @PostMapping("/match")
    public KnowledgeOrgMatch match(@RequestBody Map<String, Object> request) {
        return knowledgeOrganizationService.match(String.valueOf(request.getOrDefault("query", "")));
    }

    @PostMapping("/instant-plan")
    public Map<String, Object> instantPlan(@RequestBody Map<String, Object> request) {
        return knowledgeOrganizationService.instantPlan(String.valueOf(request.getOrDefault("query", "")));
    }
}
