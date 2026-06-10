package org.example.agent.skill.service;

import jakarta.annotation.PostConstruct;
import org.example.agent.skill.model.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        registerBuiltinSkills();
        logger.info("Skill Registry 初始化完成，已注册 {} 个 Skill", skills.size());
    }

    public void register(Skill skill) {
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skills.put(skill.getSkillId(), skill);
        logger.info("注册Skill: skillId={}, name={}", skill.getSkillId(), skill.getName());
    }

    public Optional<Skill> getSkill(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }

    public List<Skill> getSkillsByCategory(String category) {
        return skills.values().stream()
                .filter(skill -> category.equals(skill.getCategory()))
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }

    public void unregister(String skillId) {
        skills.remove(skillId);
    }

    private void registerBuiltinSkills() {
        register(Skill.builder()
                .skillId("transformer-oil-temp-diagnosis")
                .name("主变油温异常诊断")
                .version("1.0")
                .description("针对主变压器油温异常告警的诊断流程，包括冷却系统检查、负荷分析、历史缺陷关联等")
                .category("fault_diagnosis")
                .priority(80)
                .applicableScenarios(List.of("油温异常", "油温升高", "冷却器异常", "变压器过热"))
                .recommendedTools(List.of("getDeviceStatus", "getAlarmHistory", "getDeviceLogs", "getDefectTickets", "searchSafetyRules", "getDeviceProfile"))
                .diagnosisWorkflow(List.of("查设备状态→查冷却器状态→查环境温度→查负荷→查历史告警→查缺陷工单→查安规→生成诊断报告"))
                .promptTemplate("你正在执行主变油温异常诊断流程。请按步骤排查：1.确认油温读数 2.检查冷却器运行状态 3.检查负荷情况 4.检查环境温度 5.查看历史告警 6.查看缺陷工单 7.查询相关安规 8.生成诊断报告")
                .examples(List.of(
                        Map.of("input", "1号主变油温86℃超过阈值80℃", "output", "执行油温异常诊断流程，检查冷却器#2风机启动失败..."),
                        Map.of("input", "主变TR-110KV-001油温持续升高", "output", "检查冷却系统、负荷情况、历史缺陷...")
                ))
                .build());

        register(Skill.builder()
                .skillId("switchgear-pd-diagnosis")
                .name("开关柜局放异常诊断")
                .version("1.0")
                .description("针对开关柜局部放电异常告警的诊断流程")
                .category("fault_diagnosis")
                .priority(80)
                .applicableScenarios(List.of("局放异常", "局部放电", "开关柜放电", "绝缘异常"))
                .recommendedTools(List.of("getDeviceStatus", "getAlarmHistory", "getDefectTickets", "getDeviceProfile"))
                .diagnosisWorkflow(List.of("查设备状态→查局放值→查历史告警→查缺陷工单→查设备台账→生成诊断报告"))
                .promptTemplate("你正在执行开关柜局放异常诊断流程。请按步骤排查：1.确认局放数值 2.查看局放趋势 3.检查历史告警 4.检查缺陷工单 5.查看设备台账 6.生成诊断报告")
                .build());

        register(Skill.builder()
                .skillId("safety-regulation-qa")
                .name("安规条款查询")
                .version("1.0")
                .description("电力安全工作规程查询和解读")
                .category("knowledge_qa")
                .priority(70)
                .applicableScenarios(List.of("安规", "安全规程", "操作规程", "安全措施", "高压室", "倒闸操作"))
                .recommendedTools(List.of("searchSafetyRules", "queryInternalDocs"))
                .promptTemplate("请查询相关安规条款，给出准确引用和安全建议。")
                .build());

        register(Skill.builder()
                .skillId("defect-ticket-check")
                .name("缺陷工单检查")
                .version("1.0")
                .description("历史缺陷工单查询和重复缺陷判断")
                .category("ticket_analysis")
                .priority(60)
                .applicableScenarios(List.of("缺陷工单", "历史缺陷", "重复缺陷", "维修记录"))
                .recommendedTools(List.of("getDefectTickets", "getAlarmHistory"))
                .promptTemplate("请查询设备历史缺陷工单，分析与当前告警的关联性，判断是否为重复缺陷。")
                .build());

        register(Skill.builder()
                .skillId("line-trip-repair")
                .name("配网线路跳闸抢修")
                .version("1.0")
                .description("配网线路跳闸告警的抢修指导流程")
                .category("fault_diagnosis")
                .priority(75)
                .applicableScenarios(List.of("线路跳闸", "配网故障", "停电", "线路故障"))
                .recommendedTools(List.of("getDeviceStatus", "getAlarmHistory", "getDeviceLogs", "searchSafetyRules"))
                .promptTemplate("请执行配网线路跳闸抢修流程：1.确认跳闸线路和范围 2.查询保护动作信息 3.查询历史告警 4.制定抢修方案 5.安全措施提示")
                .build());

        register(Skill.builder()
                .skillId("knowledge-graph-topology-analysis")
                .name("知识图谱与拓扑分析")
                .version("1.0")
                .description("面向设备、断面、线路和流程关系的图谱查询与拓扑影响分析能力")
                .category("graph_rag")
                .priority(72)
                .applicableScenarios(List.of("拓扑分析", "断面关系", "关联设备", "知识图谱", "影响范围"))
                .recommendedTools(List.of("queryKnowledgeGraph", "analyzeTopology"))
                .diagnosisWorkflow(List.of("查询图谱节点→展开邻接关系→识别关联设备→生成影响范围摘要"))
                .promptTemplate("请基于知识组织图谱和设备拓扑关系，分析当前任务涉及的设备、流程、工具和知识约束。")
                .build());

        register(Skill.builder()
                .skillId("device-profile-query")
                .name("设备台账查询")
                .version("1.0")
                .description("查询设备基础档案、额定参数、所属站线、投运信息和运行边界，用于确认诊断对象。")
                .category("asset_query")
                .priority(76)
                .applicableScenarios(List.of("设备档案", "台账", "主变参数", "额定容量", "冷却方式"))
                .recommendedTools(List.of("getDeviceProfile"))
                .outputSchema(Map.of("profile", "设备台账结构化信息", "dataSource", "PMS/mock"))
                .promptTemplate("请查询设备台账并抽取额定容量、冷却方式、告警阈值和所属站线等关键字段。")
                .build());

        register(Skill.builder()
                .skillId("device-status-query")
                .name("设备状态查询")
                .version("1.0")
                .description("查询设备实时或近实时状态量，包括油温、负荷率、冷却器、环境温度和告警标志。")
                .category("state_query")
                .priority(82)
                .applicableScenarios(List.of("实时状态", "油温", "负荷率", "冷却器状态", "环境温度"))
                .recommendedTools(List.of("getDeviceStatus"))
                .outputSchema(Map.of("status", "设备状态快照", "metrics", "关键测点"))
                .promptTemplate("请优先确认当前测点是否越限，并记录数据来源、时间范围和缺失字段。")
                .build());

        register(Skill.builder()
                .skillId("alarm-history-retrieval")
                .name("历史告警检索")
                .version("1.0")
                .description("检索设备近期告警、重复告警和关联告警，用于判断异常演化顺序。")
                .category("alarm_retrieval")
                .priority(78)
                .applicableScenarios(List.of("历史告警", "重复告警", "冷却器告警", "油温告警"))
                .recommendedTools(List.of("getAlarmHistory"))
                .outputSchema(Map.of("alarms", "告警列表", "openCount", "未闭环数量"))
                .promptTemplate("请按时间顺序整理告警，并标注未确认或未闭环告警。")
                .build());

        register(Skill.builder()
                .skillId("operation-log-retrieval")
                .name("运行日志检索")
                .version("1.0")
                .description("检索设备运行日志、动作记录和控制回路事件，用于解释状态变化。")
                .category("log_retrieval")
                .priority(70)
                .applicableScenarios(List.of("运行日志", "冷却器动作", "风机启动", "油泵动作"))
                .recommendedTools(List.of("getDeviceLogs"))
                .outputSchema(Map.of("logs", "运行日志列表", "abnormalEvents", "异常事件"))
                .promptTemplate("请提取与当前告警相关的动作记录和异常日志。")
                .build());

        register(Skill.builder()
                .skillId("transformer-oil-temperature-risk-check")
                .name("主变油温规则校核")
                .version("1.0")
                .description("基于油温阈值、负荷率、冷却器状态、告警历史和缺陷工单进行主变油温风险校核。")
                .category("mechanism_check")
                .priority(86)
                .applicableScenarios(List.of("主变油温异常", "冷却器异常", "高负荷", "油温越限", "规则校核"))
                .recommendedTools(List.of("assessTransformerOilTempRisk", "checkOperationRisk"))
                .outputSchema(Map.of("riskLevel", "规则风险等级", "findings", "校核发现项", "humanConfirmationItems", "人工确认项"))
                .promptTemplate("请用规则校核结果支撑诊断结论，明确其为演示状态和规则阈值推演。")
                .build());

        register(Skill.builder()
                .skillId("diagnosis-report-generation")
                .name("诊断报告生成")
                .version("1.0")
                .description("基于 Workflow、Skill 调用、中间证据、风险校核和数据缺口生成结构化诊断报告。")
                .category("report_generation")
                .priority(84)
                .applicableScenarios(List.of("诊断报告", "处置建议", "人工确认", "证据链整理"))
                .recommendedTools(List.of())
                .outputSchema(Map.of("report", "结构化诊断报告", "evidenceRefs", "证据引用"))
                .promptTemplate("请仅基于执行过程中的证据、中间产物和风险校核结果组织报告，不要写成真实设备自动控制指令。")
                .build());

        register(Skill.builder()
                .skillId("rag-evidence-retrieval")
                .name("规程案例检索")
                .version("1.0")
                .description("面向规程、案例、手册和专家经验的 RAG 证据召回能力")
                .category("knowledge_retrieval")
                .priority(74)
                .applicableScenarios(List.of("规程检索", "案例检索", "知识库", "处理依据", "安全规则"))
                .recommendedTools(List.of("queryInternalDocs", "searchSafetyRules"))
                .diagnosisWorkflow(List.of("生成检索问题→召回文档片段→提取依据要点→记录证据来源"))
                .promptTemplate("请优先召回本地知识库和安全规程，给出可追溯的依据摘要。")
                .build());

        register(Skill.builder()
                .skillId("operation-risk-simulation")
                .name("运行风险推演")
                .version("1.0")
                .description("面向 N-1、负荷转供、方式调整和故障场景的风险推演能力，结果需标注估算性质")
                .category("risk_simulation")
                .priority(68)
                .applicableScenarios(List.of("N-1", "风险校核", "负荷转供", "方式调整", "故障场景"))
                .recommendedTools(List.of("generateFaultScenario", "calculatePowerFlowEstimate", "checkOperationRisk"))
                .diagnosisWorkflow(List.of("生成候选场景→估算潮流变化→校核风险项→给出人工复核要求"))
                .promptTemplate("请将推演结果明确标注为估算或模拟证据，不得作为真实调度计算结论。")
                .build());
    }
}
