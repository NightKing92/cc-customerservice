package com.bank.cs.service;

import com.bank.cs.model.SlotDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 意图注册中心：定义所有业务意图及其槽位schema
 */
@Component
public class IntentRegistry {

    private final Map<String, IntentConfig> registry = new LinkedHashMap<>();

    public IntentRegistry() {
        // 转账意图
        Map<String, SlotDefinition> transferSlots = new LinkedHashMap<>();
        transferSlots.put("payee", new SlotDefinition(
                "payee", "收款人", "请问您要转给谁？", true));
        transferSlots.put("amount", new SlotDefinition(
                "amount", "转账金额", "请问要转多少钱？", true,
                "^\\d+(\\.\\d{1,2})?$", "金额格式不正确，请输入数字（如 500 或 500.00）"));
        registry.put("transfer", new IntentConfig("transfer", "转账", transferSlots,
                "用户要求转账、汇款、打钱给某人"));

        // 信用卡办理意图
        Map<String, SlotDefinition> creditCardSlots = new LinkedHashMap<>();
        creditCardSlots.put("card_type", new SlotDefinition(
                "card_type", "信用卡类型", "请问您想办理哪种信用卡？我们有：白金卡、金卡、标准卡。", true));
        registry.put("credit_card", new IntentConfig("credit_card", "信用卡办理", creditCardSlots,
                "用户要求办理信用卡、申请信用卡"));
    }

    public IntentConfig getIntent(String intentId) {
        return registry.get(intentId);
    }

    public Map<String, IntentConfig> getAllIntents() {
        return Collections.unmodifiableMap(registry);
    }

    /** 构建意图描述文本，用于LLM prompt */
    public String buildIntentDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("可识别的业务意图列表:\n");
        for (Map.Entry<String, IntentConfig> entry : registry.entrySet()) {
            IntentConfig config = entry.getValue();
            sb.append("- intent=\"").append(entry.getKey()).append("\": ")
              .append(config.getDescription()).append(" (需要槽位: ");
            List<String> slotNames = new ArrayList<>();
            for (SlotDefinition slot : config.getSlotSchema().values()) {
                slotNames.add(slot.getName() + "[" + slot.getDisplayName() + "]");
            }
            sb.append(String.join(", ", slotNames)).append(")\n");
        }
        sb.append("- intent=\"qa\": 用户在问问题、咨询信息、闲聊\n");
        sb.append("- intent=\"none\": 无法识别意图或用户在对当前业务进行确认/补充\n");
        return sb.toString();
    }

    public static class IntentConfig {
        private String intentId;
        private String displayName;
        private Map<String, SlotDefinition> slotSchema;
        private String description;

        public IntentConfig(String intentId, String displayName,
                           Map<String, SlotDefinition> slotSchema, String description) {
            this.intentId = intentId;
            this.displayName = displayName;
            this.slotSchema = slotSchema;
            this.description = description;
        }

        public String getIntentId() { return intentId; }
        public String getDisplayName() { return displayName; }
        public Map<String, SlotDefinition> getSlotSchema() { return slotSchema; }
        public String getDescription() { return description; }
    }
}
